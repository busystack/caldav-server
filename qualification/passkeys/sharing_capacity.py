"""Observe real-browser management of a large, owned disposable grant fixture."""
from datetime import datetime, timezone
import json
from pathlib import Path
import threading
import time
from uuid import uuid4


class SharingCapacity:
    def __init__(self, checkpoint, environment):
        self.checkpoint=checkpoint;self.environment=environment;self.operations=checkpoint.operations
        self.groups=[str(uuid4()) for _ in range(500)];self.collections=[str(uuid4()) for _ in range(25)]
        self.stop=threading.Event();self.samples=[];self.errors=[];self.started=time.monotonic()

    def seed(self):
        current=json.loads(self.checkpoint.exchange.read_text())
        user=current['username'].replace("'","''")
        owner=self.operations.sql(self.environment,"SELECT id FROM accounts WHERE username='"+user+"';")
        member=self.operations.sql(self.environment,"SELECT id FROM accounts WHERE username='"+current['readerUsername']+"';")
        groups=','.join("('"+i+"','Capacity group "+str(n)+"','"+owner+"')" for n,i in enumerate(self.groups))
        collections=','.join("('"+i+"','"+owner+"','Capacity sharing "+str(n)+"','VEVENT')" for n,i in enumerate(self.collections))
        group_ids=','.join("'"+i+"'::uuid" for i in self.groups);collection_ids=','.join("'"+i+"'::uuid" for i in self.collections)
        self.operations.sql(self.environment,f"""BEGIN;
SELECT pg_advisory_xact_lock(4779818222654407499);
SELECT set_config('busystack.defer_dav_bindings','on',true);
INSERT INTO sharing_groups(id,name,created_by) VALUES {groups};
INSERT INTO collections(id,owner_id,name,component) VALUES {collections};
INSERT INTO sharing_group_members(group_id,account_id) SELECT id,'{member}' FROM sharing_groups WHERE id IN ({group_ids});
INSERT INTO collection_group_members(collection_id,group_id,permission)
SELECT c.id,g.id,'READ' FROM collections c CROSS JOIN sharing_groups g WHERE c.id IN ({collection_ids}) AND g.id IN ({group_ids});
SELECT set_config('busystack.defer_dav_bindings','off',true);
SELECT dav_reconcile_memberships(id) FROM collections WHERE id IN ({collection_ids});
COMMIT;""")
        self.started=time.monotonic()
        current['sharingCapacity']={'firstGroup':self.groups[0],'groupName':'Capacity group 0','initialGrants':12500}
        self.checkpoint.exchange.write_text(json.dumps(current))
        self.application=self.operations.compose(self.environment,'ps','--quiet','application')
        inspected=json.loads(self.operations.execute(['docker','inspect',self.application],self.environment))[0]
        self.record={'status':'running','recordedAt':datetime.now(timezone.utc).isoformat(),'applicationImage':inspected['Image'],
            'memoryLimitBytes':inspected['HostConfig']['Memory'],'groups':500,'collections':25,'initialGrants':12500,
            'population':'Owned isolated SQL fixture; assertions are actual CSRF-protected browser forms',
            'databaseBefore':self.operations.sql(self.environment,'SELECT pg_database_size(current_database());')}
        def sample():
            while not self.stop.is_set():
                try:
                    value=json.loads(self.operations.execute(['docker','stats','--no-stream','--format','{{json .}}',self.application],self.environment))
                    self.samples.append({'elapsedSeconds':round(time.monotonic()-self.started,2),'memoryUsage':value['MemUsage'],'cpuPercent':value['CPUPerc']})
                except (ValueError,RuntimeError,KeyError,OSError) as e:self.errors.append(type(e).__name__)
                self.stop.wait(2)
        self.thread=threading.Thread(target=sample,daemon=True);self.thread.start()

    def finish(self):
        current=json.loads(self.checkpoint.exchange.read_text())
        result=current['sharingCapacity'];assert result['browserPassed']
        group_ids=','.join("'"+i+"'::uuid" for i in self.groups);collection_ids=','.join("'"+i+"'::uuid" for i in self.collections)
        count=int(self.operations.sql(self.environment,f'SELECT count(*) FROM collection_group_members WHERE collection_id IN ({collection_ids});'))
        expected=12475 if result['removedGroup']==self.groups[0] else 12474
        assert count==expected,(count,expected)
        assert self.operations.sql(self.environment,"SELECT count(*) FROM sharing_groups WHERE id='"+self.groups[0]+"';")=='0'
        assert self.operations.sql(self.environment,f"SELECT count(*) FROM dav_bindings b JOIN accounts a ON a.id=b.home_id WHERE a.username='restorewriter' AND b.collection_id IN ({collection_ids});")=='0'
        assert self.operations.sql(self.environment,f"SELECT count(*) FROM dav_bindings b JOIN accounts a ON a.id=b.home_id WHERE a.username='restorereader' AND b.collection_id IN ({collection_ids});")=='25'
        self.stop.set();self.thread.join(timeout=12)
        self.record.update(status='passed',remainingGrantsBeforeCleanup=count,browser=result,elapsedSeconds=round(time.monotonic()-self.started,2),containerSamples=self.samples,observerErrors=self.errors,
            databaseAfter=self.operations.sql(self.environment,'SELECT pg_database_size(current_database());'))
        assert not self.thread.is_alive() and self.samples and not self.errors
        output=Path(self.checkpoint.root/'target/qualification/sharing-capacity.json');output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(self.record,indent=2)+'\n')
        # Generated fixture identifiers only; retirement records intentionally remain non-reusable.
        self.operations.sql(self.environment,f"BEGIN; SELECT set_config('busystack.defer_dav_bindings','on',true); DELETE FROM collections WHERE id IN ({collection_ids}); DELETE FROM sharing_groups WHERE id IN ({group_ids}); SELECT set_config('busystack.defer_dav_bindings','off',true); COMMIT;")
        print('PACKAGED_SHARING_CAPACITY_BROWSER_MANAGEMENT_PASSED artifact='+str(output),flush=True)
