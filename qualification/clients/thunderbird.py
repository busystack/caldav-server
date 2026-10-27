#!/usr/bin/env python3
"""Drive an isolated Thunderbird's native CalDAV provider through Mozilla Marionette.

No existing profile is opened. Test CA trust is added to this disposable profile;
certificate validation remains enabled. This qualifies the desktop client's native
provider, not a person manually completing its graphical account wizard.
"""

import base64
import importlib.metadata
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time

import caldav
from marionette_driver.marionette import Marionette


SETUP = r"""
const [eventUrl,taskUrl,username,password,ca] = arguments;
const done=arguments[arguments.length-1];
(async()=>{
  const {cal}=ChromeUtils.importESModule('resource:///modules/calendar/calUtils.sys.mjs');
  Cc['@mozilla.org/security/x509certdb;1'].getService(Ci.nsIX509CertDB).addCertFromBase64(ca,'CT,C,C');
  Services.prefs.setBoolPref('signon.rememberSignons',true);
  Services.logins.setLoginSavingEnabled(new URL(eventUrl).origin,true);
  await cal.auth.passwordManagerSave(username,password,new URL(eventUrl).origin,'BusyStack DAV');
  globalThis.busystackCalendars=[];
  globalThis.busystackMetadata=[];
  Services.obs.addObserver({observe(subject){
    const channel=subject.QueryInterface(Ci.nsIHttpChannel);
    if(channel.URI.prePath!==new URL(eventUrl).origin)return;
    let origin='missing',site='missing';
    try{const value=channel.getRequestHeader('Origin');origin=value==='null'?'null':value===new URL(eventUrl).origin?'same-origin':'foreign';}catch{}
    try{const value=channel.getRequestHeader('Sec-Fetch-Site');site=['none','same-origin','same-site','cross-site'].includes(value)?value:'other';}catch{}
    if(globalThis.busystackMetadata.length<10)globalThis.busystackMetadata.push({method:channel.requestMethod,status:channel.responseStatus,origin,site});
  }},'http-on-examine-response');
  globalThis.busystackRefresh = (calendar,register=false) => new Promise((resolve,reject)=>{
    let settled=false;
    const deadline=setTimeout(()=>{if(!settled){settled=true;calendar.removeObserver(observer);reject(new Error("Native refresh timeout status="+calendar.getProperty("currentStatus")+" checked="+calendar.wrappedJSObject.checkedServerInfo+" disabled="+calendar.getProperty("disabled")+" metadata="+JSON.stringify(globalThis.busystackMetadata)));}},45000);
    const observer={
      QueryInterface:ChromeUtils.generateQI(['calIObserver']),
      onStartBatch(){},onEndBatch(){},onAddItem(){},onModifyItem(){},onDeleteItem(){},onPropertyChanged(){},onPropertyDeleting(){},
      onLoad(){if(!settled){settled=true;clearTimeout(deadline);calendar.removeObserver(observer);resolve();}},
      onError(_calendar,code){if(!settled){settled=true;clearTimeout(deadline);calendar.removeObserver(observer);reject(new Error('Native CalDAV load failed: '+code));}}
    };
    calendar.addObserver(observer);if(register)cal.manager.registerCalendar(calendar);else calendar.refresh();
  });
  for(const [url,name] of [[eventUrl,'BusyStack qualification events'],[taskUrl,'BusyStack qualification tasks']]){
    const calendar=cal.manager.createCalendar('caldav',Services.io.newURI(url));
    calendar.setProperty('username',username);calendar.name=name;
    calendar.setProperty('cache.enabled',false);calendar.setProperty('suppressAlarms',true);
    calendar.id=cal.getUUID();
    globalThis.busystackCalendars.push(calendar);
    await globalThis.busystackRefresh(calendar,true);
  }
  return {app:Services.appinfo.name,version:Services.appinfo.version};
})().then(done,error=>done({error:String(error.message)}));
"""

OPERATIONS = r"""
const done=arguments[arguments.length-1];
(async()=>{
  const [events,tasks]=globalThis.busystackCalendars;
  const event=Cc['@mozilla.org/calendar/event;1'].createInstance(Ci.calIEvent);
  event.icalString='BEGIN:VEVENT\r\nUID:thunderbird-event\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261027T100000Z\r\nDTEND:20261027T110000Z\r\nSUMMARY:Thunderbird café 雪\r\nRRULE:FREQ=DAILY;COUNT=3\r\nX-NATIVE-CLIENT:preserved\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Stored alarm\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT\r\n';
  let stored=await events.addItem(event);
  if(stored.title!=='Thunderbird café 雪')throw new Error('Event title changed');
  const old=stored;stored=stored.clone();stored.title='Thunderbird changed';
  stored=await events.modifyItem(stored,old);
  await globalThis.busystackRefresh(events);
  const reloaded=await events.getItem('thunderbird-event');
  if(reloaded.title!=='Thunderbird changed'||reloaded.getProperty('X-NATIVE-CLIENT')!=='preserved'||!reloaded.recurrenceInfo||reloaded.getAlarms().length!==1)throw new Error('Event round trip lost data');
  const task=Cc['@mozilla.org/calendar/todo;1'].createInstance(Ci.calITodo);
  task.icalString='BEGIN:VTODO\r\nUID:thunderbird-task\r\nDTSTAMP:20261027T000000Z\r\nSUMMARY:Thunderbird undated task\r\nPRIORITY:2\r\nPERCENT-COMPLETE:25\r\nEND:VTODO\r\n';
  let savedTask=await tasks.addItem(task);
  if(savedTask.entryDate||savedTask.dueDate)throw new Error('Task dates were invented');
  const before=savedTask;savedTask=savedTask.clone();savedTask.isCompleted=true;
  savedTask=await tasks.modifyItem(savedTask,before);
  await globalThis.busystackRefresh(tasks);
  const reloadedTask=await tasks.getItem('thunderbird-task');
  if(!reloadedTask.isCompleted||reloadedTask.percentComplete!==100)throw new Error('Task completion changed');
  return {event:reloaded.icalString,task:reloadedTask.icalString};
})().then(done,error=>done({error:String(error.message)}));
"""

DELETE = r"""
const done=arguments[arguments.length-1];
(async()=>{
  const [events,tasks]=globalThis.busystackCalendars;
  await events.deleteItem(await events.getItem('thunderbird-event'));
  await tasks.deleteItem(await tasks.getItem('thunderbird-task'));
  await globalThis.busystackRefresh(events);await globalThis.busystackRefresh(tasks);
  if(await events.getItem('thunderbird-event')||await tasks.getItem('thunderbird-task'))throw new Error('Deleted item remained');
  return {deleted:true};
})().then(done,error=>done({error:String(error.message)}));
"""


def main():
    binary = os.environ["BUSYSTACK_THUNDERBIRD_BINARY"]
    password = Path(os.environ["BUSYSTACK_CLIENT_SECRET_FILE"]).read_text().strip()
    with caldav.DAVClient(url=os.environ["BUSYSTACK_CLIENT_URL"], username=os.environ["BUSYSTACK_CLIENT_USER"], password=password, auth_type="basic", ssl_verify_cert=os.environ["BUSYSTACK_CLIENT_CA"], timeout=30) as client:
        principal = client.principal()
        events = principal.make_calendar(name="Thunderbird events", cal_id="thunderbird-events", supported_calendar_component_set=["VEVENT"])
        tasks = principal.make_calendar(name="Thunderbird tasks", cal_id="thunderbird-tasks", supported_calendar_component_set=["VTODO"])
        with tempfile.TemporaryDirectory(prefix="busystack-thunderbird-") as temporary:
            profile = Path(temporary)
            profile.chmod(0o700)
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                port = listener.getsockname()[1]
            prefs = {"marionette.port": port, "marionette.host": "127.0.0.1", "marionette.enabled": True, "mail.provider.enabled": False, "mailnews.start_page.enabled": False, "datareporting.policy.dataSubmissionEnabled": False, "datareporting.healthreport.uploadEnabled": False, "toolkit.telemetry.enabled": False, "app.update.enabled": False, "calendar.timezone.local": "UTC", "network.proxy.type": 0, "mail.shell.checkDefaultClient": False, "mailnews.database.global.indexer.enabled": False}
            (profile / "user.js").write_text("\n".join("user_pref(" + json.dumps(key) + "," + json.dumps(value) + ");" for key, value in prefs.items()))
            env = os.environ.copy()
            env.update(MOZ_HEADLESS="1", MOZ_NO_REMOTE="1")
            with (profile / "browser.log").open("w") as log:
                process = subprocess.Popen([binary, "--headless", "--no-remote", "--profile", str(profile), "--marionette", "--remote-allow-system-access"], env=env, stdout=log, stderr=subprocess.STDOUT)
                driver = None
                try:
                    deadline = time.monotonic() + 120
                    while time.monotonic() < deadline:
                        if process.poll() is not None:
                            raise RuntimeError("Isolated Thunderbird exited before Marionette was ready")
                        try:
                            with socket.create_connection(("127.0.0.1", port), timeout=1):
                                break
                        except OSError:
                            threading.Event().wait(0.1)
                    else:
                        raise RuntimeError("Isolated Thunderbird did not become ready")
                    driver = Marionette(host="127.0.0.1", port=port, socket_timeout=120)
                    driver.start_session()
                    driver.set_context("chrome")
                    driver.timeout.script = 120
                    pem = Path(os.environ["BUSYSTACK_CLIENT_CA"]).read_text()
                    ca = "".join(line for line in pem.splitlines() if not line.startswith("---"))
                    info = driver.execute_async_script(SETUP, new_sandbox=False, script_args=[str(events.url), str(tasks.url), os.environ["BUSYSTACK_CLIENT_USER"], password, ca])
                    assert "error" not in info, info
                    result = driver.execute_async_script(OPERATIONS, new_sandbox=False)
                    assert "error" not in result, result
                    artifacts = Path("target/qualification")
                    artifacts.mkdir(parents=True, exist_ok=True)
                    for kind in ("event", "task"):
                        (artifacts / ("thunderbird-" + kind + ".ics")).write_text(result[kind])
                    assert len(events.events()) == 1 and len(tasks.todos(include_completed=True)) == 1
                    deleted = driver.execute_async_script(DELETE, new_sandbox=False)
                    assert deleted == {"deleted": True}, deleted
                    assert len(events.events()) == 0 and len(tasks.todos(include_completed=True)) == 0
                    print(json.dumps({"client": info["app"], "version": info["version"], "driver": importlib.metadata.version("marionette_driver"), "mode": "isolated headless native desktop CalDAV provider", "allowSyncDepthOne": True, "status": "passed", "operations": ["server discovery by native provider", "recurring event CRUD", "Unicode and unknown-property round trip", "alarm preservation without execution", "undated VTODO CRUD and completion", "native refresh and deletion synchronization"]}, sort_keys=True))
                finally:
                    try:
                        if driver is not None:
                            driver.delete_session()
                    finally:
                        process.terminate()
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=15)
        events.delete()
        tasks.delete()


if __name__ == "__main__":
    main()
