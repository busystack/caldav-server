"""Observe a bounded independent-client workload on this driver's disposable application."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import threading
import selectors
from uuid import uuid4
import time


def database(operations, environment):
    return json.loads(operations.sql(environment, """SELECT json_build_object(
'databaseBytes',pg_database_size(current_database()),
'resourceTableBytes',pg_total_relation_size('calendar_resources'),
'commits',xact_commit,'rollbacks',xact_rollback,'blocksRead',blks_read,'blocksHit',blks_hit,
'rowsReturned',tup_returned,'rowsFetched',tup_fetched,'rowsInserted',tup_inserted,
'rowsUpdated',tup_updated,'rowsDeleted',tup_deleted,'tempFiles',temp_files,'tempBytes',temp_bytes,
'deadlocks',deadlocks,'checksumFailures',checksum_failures)
FROM pg_stat_database WHERE datname=current_database();"""))


def heap_configuration(application, inspected, environment):
    labels=inspected['Config'].get('Labels') or {}
    assert labels.get('com.docker.compose.project')==environment['COMPOSE_PROJECT_NAME']
    assert labels.get('com.docker.compose.service')=='application'
    command=[*inspected['Config'].get('Entrypoint',[]),*(inspected['Config'].get('Cmd') or [])]
    percentage=next((value.split('=',1)[1] for value in command if value.startswith('-XX:MaxRAMPercentage=')),None)
    record={'containerMemoryLimitBytes':inspected['HostConfig']['Memory'],
        'applicationJvmCommand':command,'configuredMaxRAMPercentage':percentage}
    try:
        # Attach only to PID1 in this explicitly owned synthetic fixture. Clear tool-only Java
        # options so the diagnostic process neither inherits nor echoes test trust-store secrets.
        result=subprocess.run(['docker','exec','--env','JAVA_TOOL_OPTIONS=','--env','JDK_JAVA_OPTIONS=',
            application,'/opt/java/openjdk/bin/jcmd','1','VM.flags'],env=environment,
            text=True,capture_output=True,timeout=15,check=True)
        if len(result.stdout)>65536: raise ValueError('VM flags exceed diagnostic output bound')
        maximum=re.search(r'(?:^|\s)-XX:MaxHeapSize=(\d+)(?=\s|$)',result.stdout)
        if maximum is None: raise ValueError('VM flags lack actual MaxHeapSize')
        record.update(source='actual owned-container jcmd PID1 VM.flags',maxHeapBytes=int(maximum.group(1)),
            vmFlags=result.stdout.strip())
    except (OSError,ValueError,subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
        # An unavailable attach mechanism is explicit. This fallback records the actual launched
        # command and configured percentage, rather than inventing an observed heap size.
        assert percentage is not None and record['containerMemoryLimitBytes']>0
        record.update(source='actual launched command; attach diagnostic unavailable',maxHeapBytes=None,
            attachFailure=type(error).__name__)
    return record


def observe(checkpoint, environment, python, output):
    operations = checkpoint.operations
    application = operations.compose(environment, 'ps', '--quiet', 'application')
    inspected = json.loads(operations.execute(['docker', 'inspect', application], environment))[0]
    secret = checkpoint.directory / 'workload-secret'
    secret.write_text(checkpoint.saved['credential'])
    secret.chmod(0o600)
    variables = environment.copy()
    variables.update(BUSYSTACK_CLIENT_URL=checkpoint.base + '/dav/',
        BUSYSTACK_CLIENT_USER=checkpoint.saved['username'], BUSYSTACK_CLIENT_SECRET_FILE=str(secret),
        BUSYSTACK_CLIENT_CA=str(checkpoint.ca),
        BUSYSTACK_CLIENT_SYNC_DEPTH_INPUT='setting absent; Compose/application default')
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata = {'status': 'running', 'startedAt': datetime.now(timezone.utc).isoformat(),
        'applicationImage': inspected['Image'], 'applicationMemoryLimitBytes': inspected['HostConfig']['Memory'],
        'applicationNanoCpuLimit': inspected['HostConfig']['NanoCpus'],
        'jvmHeap':heap_configuration(application,inspected,environment),
        'host': {'system': platform.system(), 'architecture': platform.machine(), 'kernel': platform.release(),
            'logicalCpus': os.cpu_count(), 'loadAverage': list(os.getloadavg())},
        'postgresVersion': operations.sql(environment, 'SHOW server_version;'),
        'dockerVersion': operations.execute(['docker', 'version', '--format', '{{.Server.Version}}'], environment),
        'databaseBefore': database(operations, environment), 'containerSamples': [],
        'interpretation': 'One warmed application and one sequential independent client on a shared host. '
            'Docker samples include observer overhead; PostgreSQL cumulative statistics update asynchronously. '
            'Calendar/task data is synthetic. No capacity, scalability or high-availability claim.'}
    if hasattr(os, 'sched_getaffinity'):
        metadata['host']['affinityCpuCount'] = len(os.sched_getaffinity(0))
    quota = Path('/sys/fs/cgroup/cpu.max')
    if quota.exists(): metadata['host']['cgroupCpuMax'] = quota.read_text().strip()
    cpuinfo = Path('/proc/cpuinfo')
    if cpuinfo.exists():
        for line in cpuinfo.read_text().splitlines():
            if line.startswith('model name'):
                metadata['host']['cpuModel'] = line.split(':', 1)[1].strip()
                break
    meminfo = Path('/proc/meminfo')
    if meminfo.exists():
        value = next(line for line in meminfo.read_text().splitlines() if line.startswith('MemTotal:'))
        metadata['host']['memoryBytes'] = int(value.split()[1]) * 1024
    output.write_text(json.dumps(metadata, indent=2) + '\n')
    stop = threading.Event()
    restarting = threading.Event()
    observations, errors = [], []
    started = time.monotonic()

    def sample():
        if restarting.is_set():
            return
        try:
            measured = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', application],
                env=environment, text=True, capture_output=True, timeout=10, check=True)
            values = json.loads(measured.stdout)
            observations.append({'elapsedSeconds': round(time.monotonic() - started, 2),
                'cpuPercent': values['CPUPerc'], 'memoryUsage': values['MemUsage'],
                'memoryPercent': values['MemPerc'], 'networkIo': values['NetIO'], 'blockIo': values['BlockIO'], 'pids': values['PIDs']})
            if len(observations) % 10 == 0:
                print('WORKLOAD_OBSERVATION elapsedSeconds=' + str(observations[-1]['elapsedSeconds'])
                    + ' memory=' + values['MemUsage'] + ' cpu=' + values['CPUPerc'], flush=True)
        except (RuntimeError, ValueError, subprocess.TimeoutExpired, subprocess.CalledProcessError, OSError, KeyError) as error:
            if not restarting.is_set():
                errors.append(type(error).__name__)

    def sampling():
        while not stop.is_set():
            sample()
            # This is a measurement interval, not synchronization of application mutations.
            stop.wait(2)

    sample()
    thread = threading.Thread(target=sampling, daemon=True)
    thread.start()
    try:
        result = subprocess.run([python, str(checkpoint.root / 'qualification/clients/workload.py')],
            cwd=checkpoint.root, env=variables, text=True, capture_output=True, timeout=900)
        if result.returncode != 0:
            raise RuntimeError('Independent measured workload failed; no performance pass is recorded')
        measured = json.loads(result.stdout)
        assert measured['status'] == 'passed'
        metadata['workload'] = measured
        metadata['packagedIndependentClients'] = {}
        for client_script in ('python_caldav.py', 'thunderbird.py'):
            client_variables=variables.copy()
            client_variables['BUSYSTACK_THUNDERBIRD_BINARY']=checkpoint.workload['thunderbird']
            client_result=subprocess.run([python,str(checkpoint.root/'qualification/clients'/client_script)],
                cwd=checkpoint.root,env=client_variables,text=True,capture_output=True,timeout=360)
            if client_result.returncode!=0:
                raise RuntimeError('Required independent packaged-client qualification failed: '+client_script+' '+client_result.stdout[-2000:])
            client_record=json.loads(client_result.stdout);assert client_record['status']=='passed'
            metadata['packagedIndependentClients'][client_script]=client_record
        print('FINAL_PACKAGED_PYTHON_AND_THUNDERBIRD_CLIENTS_PASSED',flush=True)
        metadata['capacity'] = capacity(checkpoint, environment, python, variables, restarting)
        metadata['databaseAfter'] = database(operations, environment)
        metadata['status'] = 'passed'
    except Exception:
        metadata['status'] = 'failed'
        raise
    finally:
        stop.set()
        thread.join(timeout=12)
        sample()
        metadata['finishedAt'] = datetime.now(timezone.utc).isoformat()
        metadata['elapsedSeconds'] = round(time.monotonic() - started, 2)
        metadata['containerSamples'] = observations
        metadata['observerErrors'] = errors
        if thread.is_alive() or errors or not observations:
            metadata['status'] = 'failed'
        output.write_text(json.dumps(metadata, indent=2) + '\n')
        secret.unlink(missing_ok=True)
    if metadata['status'] != 'passed':
        raise RuntimeError('Required container measurements did not complete')
    print('MEASURED_INDEPENDENT_CLIENT_WORKLOAD_PASSED artifact=' + str(output), flush=True)


def capacity(checkpoint, environment, python, variables, restarting):
    """Owned disposable SQL population; every sync/query/property assertion uses real HTTPS."""
    operations=checkpoint.operations
    identifier=str(uuid4())
    owner=checkpoint.owner
    operations.sql(environment, f"""BEGIN;
INSERT INTO collections(id,owner_id,name,component) VALUES ('{identifier}','{owner}','Disposable measured capacity','VEVENT');
WITH fixture AS (
 SELECT i,convert_to('BEGIN:VCALENDAR' || E'\r\n' || 'VERSION:2.0' || E'\r\n'
 || 'PRODID:-//BusyStack capacity fixture//EN' || E'\r\n' || 'BEGIN:VEVENT' || E'\r\n'
 || 'UID:capacity-' || i || E'\r\n' || 'DTSTAMP:20261027T000000Z' || E'\r\n'
 || 'DTSTART:' || CASE WHEN i=5000 THEN '20261027' ELSE '20000101' END || 'T000000Z' || E'\r\n'
 || 'DTEND:' || CASE WHEN i=5000 THEN '20261027' ELSE '20000101' END || 'T010000Z' || E'\r\n'
 || 'SUMMARY:Capacity fixture ' || i || E'\r\n' || 'DESCRIPTION:Independent synthetic data' || E'\r\n'
 || repeat(' ' || repeat('x',69) || E'\r\n',60) || 'END:VEVENT' || E'\r\n' || 'END:VCALENDAR' || E'\r\n','UTF8') AS body
 FROM generate_series(1,10037) i
)
INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision)
 SELECT '{identifier}','capacity-' || lpad(i::text,5,'0') || '.ics','capacity-' || i,body,'"' || encode(sha256(body),'hex') || '"',i FROM fixture;
INSERT INTO collection_changes(collection_id,revision,name,deleted)
 SELECT collection_id,revision,name,false FROM calendar_resources WHERE collection_id='{identifier}';
UPDATE collections SET revision=10037 WHERE id='{identifier}';
COMMIT;""")
    configured=variables.copy()
    configured['BUSYSTACK_CAPACITY_URL']=checkpoint.base+'/dav/calendars/'+owner+'/'+identifier+'/'
    process=None
    try:
        process=subprocess.Popen([python,str(checkpoint.root/'qualification/clients/capacity.py')],cwd=checkpoint.root,env=configured,
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
        selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
        deadline=time.monotonic()+900;result=None
        while time.monotonic()<deadline:
            events=selector.select(timeout=1)
            if not events:
                if process.poll() is not None:break
                continue
            line=process.stdout.readline().strip()
            if line=='CAPACITY_READY_RESTART':
                restarting.set()
                operations.compose(environment,'restart','application')
                ready_deadline=time.monotonic()+120
                while time.monotonic()<ready_deadline:
                    try:
                        if checkpoint.request(checkpoint.resource_path,headers={'Authorization':'Basic '+checkpoint.saved['encoded']})[0]==200:break
                    except (OSError,TimeoutError):pass
                    time.sleep(0.25)
                else:raise RuntimeError('Capacity restart never restored authenticated access')
                restarting.clear()
                checkpoint.verify_language(checkpoint.saved['encoded'])
                process.stdin.write('CONTINUE_AFTER_RESTART\n');process.stdin.flush()
            elif line:
                result=json.loads(line)
                assert result['status']=='passed'
            if process.poll() is not None:break
        if process.poll() is None:process.wait(timeout=max(1,deadline-time.monotonic()))
        if process.returncode!=0 or result is None:
            error=process.stderr.read()[-3000:]
            raise RuntimeError('Required independent capacity traversal failed: '+error)
        result['observationGap']='Docker samples pause during the explicitly controlled JVM restart, then resume after authenticated readiness'
        print('INDEPENDENT_CAPACITY_SYNC_QUERY_PROPERTY_AND_RESTART_PASSED',flush=True)
        return result
    finally:
        restarting.clear()
        if process is not None and process.poll() is None:
            process.kill();process.wait(timeout=10)
        # Only this generated fixture collection is removed, through the authenticated DAV boundary.
        target='/dav/calendars/'+owner+'/'+identifier+'/'
        status,_,_=checkpoint.request(target,'DELETE',{'Authorization':'Basic '+checkpoint.saved['encoded']})
        if status not in (204,404):raise RuntimeError('Owned capacity fixture cleanup failed')
