"""Observe a bounded independent-client workload on this driver's disposable application."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import subprocess
import threading
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
        BUSYSTACK_CLIENT_CA=str(checkpoint.ca))
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata = {'status': 'running', 'startedAt': datetime.now(timezone.utc).isoformat(),
        'applicationImage': inspected['Image'], 'applicationMemoryLimitBytes': inspected['HostConfig']['Memory'],
        'applicationNanoCpuLimit': inspected['HostConfig']['NanoCpus'],
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
    observations, errors = [], []
    started = time.monotonic()

    def sample():
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
