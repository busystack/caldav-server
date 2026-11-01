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
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time

import caldav
from sync_profile import verify_sync_profile
import icalendar
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
  event.icalString='BEGIN:VEVENT\r\nUID:thunderbird-event\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261027T100000Z\r\nDTEND:20261027T110000Z\r\nSUMMARY:Thunderbird café 雪\r\nRRULE:FREQ=DAILY;COUNT=4\r\nEXDATE:20261030T100000Z\r\nX-NATIVE-CLIENT:preserved\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Stored alarm\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT\r\n';
  let stored=await events.addItem(event);
  if(stored.title!=='Thunderbird café 雪')throw new Error('Event title changed');
  const old=stored;stored=stored.clone();stored.title='Thunderbird changed';
  const recurrenceId=Cc['@mozilla.org/calendar/datetime;1'].createInstance(Ci.calIDateTime);
  recurrenceId.icalString='20261028T100000Z';
  const detached=stored.recurrenceInfo.getOccurrenceFor(recurrenceId).clone();
  detached.title='Thunderbird moved occurrence 雪';
  detached.startDate=recurrenceId.clone();detached.startDate.hour=12;
  detached.endDate=recurrenceId.clone();detached.endDate.hour=13;
  detached.setProperty('X-NATIVE-EXCEPTION','preserved');
  detached.clearAlarms();detached.addAlarm(stored.getAlarms()[0].clone());
  stored.recurrenceInfo.modifyException(detached,true);
  stored=await events.modifyItem(stored,old);
  await globalThis.busystackRefresh(events);
  const reloaded=await events.getItem('thunderbird-event');
  if(reloaded.title!=='Thunderbird changed'||reloaded.getProperty('X-NATIVE-CLIENT')!=='preserved'||!reloaded.recurrenceInfo||reloaded.getAlarms().length!==1)throw new Error('Event round trip lost data');
  const reloadedException=reloaded.recurrenceInfo.getExceptionFor(recurrenceId);
  if(reloaded.recurrenceInfo.getExceptionIds().length!==1||!reloadedException||reloadedException.recurrenceId.icalString!=='20261028T100000Z'||reloadedException.startDate.icalString!=='20261028T120000Z'||reloadedException.endDate.icalString!=='20261028T130000Z'||reloadedException.title!=='Thunderbird moved occurrence 雪'||reloadedException.getProperty('X-NATIVE-EXCEPTION')!=='preserved'||reloadedException.getAlarms().length!==1)throw new Error('Native detached exception lost its recurrence identity, time, title, extension or alarm');
  const task=Cc['@mozilla.org/calendar/todo;1'].createInstance(Ci.calITodo);
  task.icalString='BEGIN:VTODO\r\nUID:thunderbird-task\r\nDTSTAMP:20261027T000000Z\r\nSUMMARY:Thunderbird undated task\r\nPRIORITY:2\r\nPERCENT-COMPLETE:25\r\nX-NATIVE-TASK:preserved\r\nEND:VTODO\r\n';
  let savedTask=await tasks.addItem(task);
  if(savedTask.entryDate||savedTask.dueDate)throw new Error('Task dates were invented');
  const before=savedTask;savedTask=savedTask.clone();savedTask.isCompleted=true;
  savedTask=await tasks.modifyItem(savedTask,before);
  await globalThis.busystackRefresh(tasks);
  const reloadedTask=await tasks.getItem('thunderbird-task');
  if(!reloadedTask.isCompleted||reloadedTask.percentComplete!==100||reloadedTask.getProperty('X-NATIVE-TASK')!=='preserved')throw new Error('Task completion changed');
  const allDay=Cc['@mozilla.org/calendar/event;1'].createInstance(Ci.calIEvent);
  allDay.icalString='BEGIN:VEVENT\r\nUID:thunderbird-all-day\r\nDTSTAMP:20261027T000000Z\r\nDTSTART;VALUE=DATE:20261028\r\nDTEND;VALUE=DATE:20261030\r\nSUMMARY:Thunderbird all-day event\r\nX-NATIVE-SHAPE:all-day\r\nEND:VEVENT\r\n';
  await events.addItem(allDay);
  const floating=Cc['@mozilla.org/calendar/event;1'].createInstance(Ci.calIEvent);
  floating.icalString='BEGIN:VEVENT\r\nUID:thunderbird-floating\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261027T133000\r\nDTEND:20261027T141500\r\nSUMMARY:Thunderbird floating event\r\nX-NATIVE-SHAPE:floating\r\nEND:VEVENT\r\n';
  await events.addItem(floating);
  await globalThis.busystackRefresh(events);
  const reloadedDay=await events.getItem('thunderbird-all-day');
  const reloadedFloating=await events.getItem('thunderbird-floating');
  if(!reloadedDay.startDate.isDate||!reloadedDay.endDate.isDate||reloadedDay.startDate.icalString!=='20261028'||reloadedDay.endDate.icalString!=='20261030')throw new Error('Native all-day value or exclusive end changed');
  if(!reloadedFloating.startDate.timezone.isFloating||!reloadedFloating.endDate.timezone.isFloating||reloadedFloating.startDate.icalString!=='20261027T133000'||reloadedFloating.endDate.icalString!=='20261027T141500')throw new Error('Native floating date became zoned');
  return {event:reloaded.icalString,exception:reloadedException.icalString,task:reloadedTask.icalString};
})().then(done,error=>done({error:String(error.message)}));
"""

CROSS_CLIENT = r"""
const done=arguments[arguments.length-1];
(async()=>{
  const [events,tasks]=globalThis.busystackCalendars;
  await globalThis.busystackRefresh(events);await globalThis.busystackRefresh(tasks);
  const event=await events.getItem('thunderbird-event');
  if(event.title!=='Python changed native recurring event 雪'||event.getProperty('X-PYTHON-ROUNDTRIP')!=='event'||event.getProperty('X-NATIVE-CLIENT')!=='preserved'||!event.recurrenceInfo||event.getAlarms().length!==1)throw new Error('Native refresh lost Python event edit, recurrence, alarm or extension');
  const rangeStart=Cc['@mozilla.org/calendar/datetime;1'].createInstance(Ci.calIDateTime);
  rangeStart.icalString='20261027T000000Z';
  const rangeEnd=rangeStart.clone();rangeEnd.day+=4;
  const occurrences=event.recurrenceInfo.getOccurrences(rangeStart,rangeEnd,0);
  if(occurrences.length!==3||occurrences.some(item=>item.getAlarms().length!==1))throw new Error('Native recurrence instances or alarms changed after Python edit');
  const recurrenceId=Cc['@mozilla.org/calendar/datetime;1'].createInstance(Ci.calIDateTime);
  recurrenceId.icalString='20261028T100000Z';
  const exception=event.recurrenceInfo.getExceptionFor(recurrenceId);
  if(event.recurrenceInfo.getExceptionIds().length!==1||!exception||exception.recurrenceId.icalString!=='20261028T100000Z'||exception.startDate.icalString!=='20261028T140000Z'||exception.endDate.icalString!=='20261028T150000Z'||exception.title!=='Python moved native occurrence 雪'||exception.getProperty('X-NATIVE-EXCEPTION')!=='preserved'||exception.getProperty('X-PYTHON-EXCEPTION')!=='preserved')throw new Error('Native refresh lost Python detached-exception edit');
  const starts=occurrences.map(item=>item.startDate.icalString);
  if(JSON.stringify(starts)!==JSON.stringify(['20261027T100000Z','20261028T140000Z','20261029T100000Z']))throw new Error('Native occurrence expansion ignored the detached override or EXDATE');
  const task=await tasks.getItem('thunderbird-task');
  if(task.title!=='Python changed native undated task 雪'||task.getProperty('X-PYTHON-ROUNDTRIP')!=='task'||task.getProperty('X-NATIVE-TASK')!=='preserved'||!task.isCompleted||task.percentComplete!==100||task.priority!==5||task.entryDate||task.dueDate)throw new Error('Native refresh lost Python task edit or invented dates');
  const allDay=await events.getItem('thunderbird-all-day');
  if(allDay.title!=='Python changed native all-day event'||allDay.getProperty('X-PYTHON-ROUNDTRIP')!=='all-day'||allDay.getProperty('X-NATIVE-SHAPE')!=='all-day'||!allDay.startDate.isDate||!allDay.endDate.isDate||allDay.startDate.icalString!=='20261028'||allDay.endDate.icalString!=='20261030')throw new Error('Cross-client all-day end or value type changed');
  const floating=await events.getItem('thunderbird-floating');
  if(floating.title!=='Python changed native floating event'||floating.getProperty('X-PYTHON-ROUNDTRIP')!=='floating'||floating.getProperty('X-NATIVE-SHAPE')!=='floating'||!floating.startDate.timezone.isFloating||!floating.endDate.timezone.isFloating||floating.startDate.icalString!=='20261027T133000'||floating.endDate.icalString!=='20261027T141500')throw new Error('Cross-client floating value became zoned');
  for(const [calendar,item,kind] of [[events,event,'event'],[tasks,task,'task'],[events,allDay,'all-day'],[events,floating,'floating']]) {
    const changed=item.clone();changed.title='Thunderbird final '+kind;changed.setProperty('X-NATIVE-RETURN',kind);
    if(kind==='event'){
      const finalException=changed.recurrenceInfo.getExceptionFor(recurrenceId).clone();
      finalException.title='Thunderbird final exception';
      finalException.startDate=recurrenceId.clone();finalException.startDate.hour=16;
      finalException.endDate=recurrenceId.clone();finalException.endDate.hour=17;
      finalException.setProperty('X-NATIVE-RETURN-EXCEPTION','preserved');
      changed.recurrenceInfo.modifyException(finalException,true);
    }
    await calendar.modifyItem(changed,item);
  }
  await globalThis.busystackRefresh(events);await globalThis.busystackRefresh(tasks);
  return {crossClient:true,nativeOccurrenceCount:occurrences.length,nativeDetachedExceptions:event.recurrenceInfo.getExceptionIds().length};
})().then(done,error=>done({error:String(error.message)}));
"""

DELETE = r"""
const done=arguments[arguments.length-1];
(async()=>{
  const [events,tasks]=globalThis.busystackCalendars;
  await events.deleteItem(await events.getItem('thunderbird-event'));
  await events.deleteItem(await events.getItem('thunderbird-all-day'));
  await events.deleteItem(await events.getItem('thunderbird-floating'));
  await tasks.deleteItem(await tasks.getItem('thunderbird-task'));
  await globalThis.busystackRefresh(events);await globalThis.busystackRefresh(tasks);
  if(await events.getItem('thunderbird-event')||await events.getItem('thunderbird-all-day')||await events.getItem('thunderbird-floating')||await tasks.getItem('thunderbird-task'))throw new Error('Deleted item remained');
  return {deleted:true};
})().then(done,error=>done({error:String(error.message)}));
"""


def independent_items(events, tasks):
    result = {}
    for item in [*events.events(), *tasks.todos(include_completed=True)]:
        item.load()
        model = icalendar.Calendar.from_ical(item.data)
        primary = [part for part in model.subcomponents if part.name in {"VEVENT", "VTODO"}]
        masters = [part for part in primary if "RECURRENCE-ID" not in part]
        assert len(masters) == 1, "One authoritative resource must retain one recurring master"
        uid = str(masters[0]["UID"])
        assert {str(part["UID"]) for part in primary} == {uid}, "Detached exceptions must share the master's UID"
        assert len(primary) == (2 if uid == "thunderbird-event" else 1), "An exception must remain inside its master's resource"
        assert uid not in result, "Cross-client operation created a duplicate UID resource"
        result[uid] = (item, model, masters[0])
    assert set(result) == {"thunderbird-event", "thunderbird-task", "thunderbird-all-day", "thunderbird-floating"}
    return result


def assert_independent_data(items, final=False, completed=None, exception_phase="native"):
    event = items["thunderbird-event"][2]
    assert event.name == "VEVENT"
    assert event.decoded("DTSTART") == datetime(2026, 10, 27, 10, tzinfo=timezone.utc)
    assert event.decoded("DTEND") == datetime(2026, 10, 27, 11, tzinfo=timezone.utc)
    assert event["RRULE"]["FREQ"] == ["DAILY"] and event["RRULE"]["COUNT"] == [4]
    excluded = event["EXDATE"] if isinstance(event["EXDATE"], list) else [event["EXDATE"]]
    assert [entry.dt for values in excluded for entry in values.dts] == [datetime(2026, 10, 30, 10, tzinfo=timezone.utc)]
    detached = [part for part in items["thunderbird-event"][1].subcomponents if part.name == "VEVENT" and "RECURRENCE-ID" in part]
    assert len(detached) == 1
    exception = detached[0]
    assert exception.decoded("RECURRENCE-ID") == datetime(2026, 10, 28, 10, tzinfo=timezone.utc)
    assert "RRULE" not in exception and "EXDATE" not in exception
    hour = 16 if final else 14 if exception_phase == "python" else 12
    title = "Thunderbird final exception" if final else "Python moved native occurrence 雪" if exception_phase == "python" else "Thunderbird moved occurrence 雪"
    assert exception.decoded("DTSTART") == datetime(2026, 10, 28, hour, tzinfo=timezone.utc)
    assert exception.decoded("DTEND") == datetime(2026, 10, 28, hour + 1, tzinfo=timezone.utc)
    assert str(exception["SUMMARY"]) == title and str(exception["X-NATIVE-EXCEPTION"]) == "preserved"
    exception_alarms = [part for part in exception.subcomponents if part.name == "VALARM"]
    assert len(exception_alarms) == 1 and exception_alarms[0].decoded("TRIGGER") == timedelta(minutes=-15)
    if final or exception_phase == "python":
        assert str(exception["X-PYTHON-EXCEPTION"]) == "preserved"
    if final:
        assert str(exception["X-NATIVE-RETURN-EXCEPTION"]) == "preserved"
    assert str(event["X-NATIVE-CLIENT"]) == "preserved"
    alarms = [part for part in event.subcomponents if part.name == "VALARM"]
    assert len(alarms) == 1 and alarms[0].decoded("TRIGGER") == timedelta(minutes=-15)
    assert str(alarms[0]["ACTION"]) == "DISPLAY" and str(alarms[0]["DESCRIPTION"]) == "Stored alarm"
    task = items["thunderbird-task"][2]
    assert task.name == "VTODO" and "DTSTART" not in task and "DUE" not in task
    assert str(task["STATUS"]) == "COMPLETED" and int(task["PERCENT-COMPLETE"]) == 100
    assert str(task["X-NATIVE-TASK"]) == "preserved"
    assert task.decoded("COMPLETED").tzinfo is not None
    if completed is not None:
        assert task.decoded("COMPLETED") == completed, "Summary/priority edits changed task completion timestamp"
    all_day = items["thunderbird-all-day"][2]
    assert all_day.decoded("DTSTART") == date(2026, 10, 28) and all_day.decoded("DTEND") == date(2026, 10, 30)
    assert str(all_day["DTSTART"].params["VALUE"]) == "DATE" and str(all_day["DTEND"].params["VALUE"]) == "DATE"
    assert str(all_day["X-NATIVE-SHAPE"]) == "all-day"
    floating = items["thunderbird-floating"][2]
    assert floating.decoded("DTSTART") == datetime(2026, 10, 27, 13, 30)
    assert floating.decoded("DTEND") == datetime(2026, 10, 27, 14, 15)
    assert floating.decoded("DTSTART").tzinfo is None and floating.decoded("DTEND").tzinfo is None
    assert "TZID" not in floating["DTSTART"].params and "TZID" not in floating["DTEND"].params
    assert str(floating["X-NATIVE-SHAPE"]) == "floating"
    if final:
        for uid, kind in [("thunderbird-event", "event"), ("thunderbird-task", "task"), ("thunderbird-all-day", "all-day"), ("thunderbird-floating", "floating")]:
            component = items[uid][2]
            assert str(component["SUMMARY"]) == "Thunderbird final " + kind
            assert str(component["X-PYTHON-ROUNDTRIP"]) == kind and str(component["X-NATIVE-RETURN"]) == kind
        assert int(task["PRIORITY"]) == 5


def python_edits_native_items(events, tasks):
    items = independent_items(events, tasks)
    assert_independent_data(items)
    completed = items["thunderbird-task"][2].decoded("COMPLETED")
    urls = {uid: str(item.url) for uid, (item, _, _) in items.items()}
    for uid, kind, summary in [
        ("thunderbird-event", "event", "Python changed native recurring event 雪"),
        ("thunderbird-task", "task", "Python changed native undated task 雪"),
        ("thunderbird-all-day", "all-day", "Python changed native all-day event"),
        ("thunderbird-floating", "floating", "Python changed native floating event"),
    ]:
        item, model, component = items[uid]
        component.pop("SUMMARY", None)
        component.add("SUMMARY", summary)
        component.add("X-PYTHON-ROUNDTRIP", kind)
        if kind == "task":
            component.pop("PRIORITY", None)
            component.add("PRIORITY", 5)
        if kind == "event":
            exception = next(part for part in model.subcomponents if part.name == "VEVENT" and "RECURRENCE-ID" in part)
            for property_name in ("DTSTART", "DTEND", "SUMMARY"):
                exception.pop(property_name, None)
            exception.add("DTSTART", datetime(2026, 10, 28, 14, tzinfo=timezone.utc))
            exception.add("DTEND", datetime(2026, 10, 28, 15, tzinfo=timezone.utc))
            exception.add("SUMMARY", "Python moved native occurrence 雪")
            exception.add("X-PYTHON-EXCEPTION", "preserved")
        item.data = model.to_ical()
        item.save()
    reloaded = independent_items(events, tasks)
    assert_independent_data(reloaded, completed=completed, exception_phase="python")
    assert {uid: str(item.url) for uid, (item, _, _) in reloaded.items()} == urls
    return urls, completed


def main():
    assert importlib.metadata.version("caldav") == "3.6.0" and importlib.metadata.version("icalendar") == "7.3.0", "Install pinned independent-client dependencies"
    binary = os.environ["BUSYSTACK_THUNDERBIRD_BINARY"]
    password = Path(os.environ["BUSYSTACK_CLIENT_SECRET_FILE"]).read_text().strip()
    with caldav.DAVClient(url=os.environ["BUSYSTACK_CLIENT_URL"], username=os.environ["BUSYSTACK_CLIENT_USER"], password=password, auth_type="basic", ssl_verify_cert=os.environ["BUSYSTACK_CLIENT_CA"], timeout=30) as client:
        principal = client.principal()
        events = principal.make_calendar(name="Thunderbird events", cal_id="thunderbird-events", supported_calendar_component_set=["VEVENT"])
        tasks = principal.make_calendar(name="Thunderbird tasks", cal_id="thunderbird-tasks", supported_calendar_component_set=["VTODO"])
        sync_profile = verify_sync_profile(client, events)
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
                    for kind in ("event", "exception", "task"):
                        (artifacts / ("thunderbird-" + kind + ".ics")).write_bytes(result[kind].encode("utf-8"))
                    assert len(events.events()) == 3 and len(tasks.todos(include_completed=True)) == 1
                    urls, completed = python_edits_native_items(events, tasks)
                    cross = driver.execute_async_script(CROSS_CLIENT, new_sandbox=False)
                    assert cross == {"crossClient": True, "nativeOccurrenceCount": 3, "nativeDetachedExceptions": 1}, cross
                    final = independent_items(events, tasks)
                    assert_independent_data(final, final=True, completed=completed)
                    assert {uid: str(item.url) for uid, (item, _, _) in final.items()} == urls
                    for uid, (_, model, _) in final.items():
                        (artifacts / (uid + "-cross-client.ics")).write_bytes(model.to_ical())
                    deleted = driver.execute_async_script(DELETE, new_sandbox=False)
                    assert deleted == {"deleted": True}, deleted
                    assert len(events.events()) == 0 and len(tasks.todos(include_completed=True)) == 0
                    print(json.dumps({"client": info["app"], "version": info["version"], "driver": importlib.metadata.version("marionette_driver"), "crossClient": {"python-caldav": importlib.metadata.version("caldav"), "icalendar": importlib.metadata.version("icalendar"), "sameResources": 4, "nativeRecurrenceInstances": cross["nativeOccurrenceCount"], "nativeDetachedExceptions": cross["nativeDetachedExceptions"], "excludedOccurrences": 1}, "mode": "isolated headless native desktop CalDAV provider", "syncConfiguration": sync_profile, "status": "passed", "operations": ["server discovery by native provider", "recurring event CRUD", "Unicode and unknown-property round trip", "alarm preservation without execution", "undated VTODO CRUD and completion", "same-object Thunderbird to Python to Thunderbird to Python edits", "cross-client recurrence, alarm, extension and completion timestamp preservation", "same-resource detached exception identity/time/title edits in both clients", "EXDATE exclusion verified by native occurrence expansion", "all-day DATE and exclusive end preservation across both clients", "floating DATE-TIME preservation across both clients", "native refresh and deletion synchronization"]}, sort_keys=True))
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
                        # Keep startup failures reviewable after TemporaryDirectory cleanup.
                        # Never retain the disposable DAV secret or its Basic authorization value.
                        artifacts = Path("target/qualification")
                        artifacts.mkdir(parents=True, exist_ok=True)
                        diagnostic = (profile / "browser.log").read_text(errors="replace")[-262144:]
                        diagnostic = diagnostic.replace(password, "[REDACTED]")
                        authorization = base64.b64encode(
                            (os.environ["BUSYSTACK_CLIENT_USER"] + ":" + password).encode()).decode()
                        diagnostic = diagnostic.replace(authorization, "[REDACTED]")
                        (artifacts / "thunderbird-process.log").write_text(diagnostic)
        events.delete()
        tasks.delete()


if __name__ == "__main__":
    main()
