#!/usr/bin/env python3
"""Independent python-caldav operations. Called by IndependentClientIT over test-CA HTTPS.

The fixture provisions its account/credential through application services before this
process starts. All calendar/task discovery, creation, queries and mutations use
python-caldav's own HTTP/XML and Python's independent iCalendar implementation.
"""

import importlib.metadata
import json
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import caldav


def main():
    assert importlib.metadata.version("caldav") == "3.6.0", "Install qualification/clients/requirements.txt"
    password = Path(os.environ["BUSYSTACK_CLIENT_SECRET_FILE"]).read_text().strip()
    with caldav.DAVClient(
        url=os.environ["BUSYSTACK_CLIENT_URL"],
        username=os.environ["BUSYSTACK_CLIENT_USER"],
        password=password,
        auth_type="basic",
        ssl_verify_cert=os.environ["BUSYSTACK_CLIENT_CA"],
        timeout=30,
    ) as client:
        principal = client.principal()
        events = principal.make_calendar(name="Python events 雪", cal_id="python-events", supported_calendar_component_set=["VEVENT"])
        tasks = principal.make_calendar(name="Python tasks", cal_id="python-tasks", supported_calendar_component_set=["VTODO"])
        assert {str(calendar.url) for calendar in principal.calendars()} >= {str(events.url), str(tasks.url)}
        start = datetime(2026, 10, 27, 10, tzinfo=timezone.utc)
        event = events.save_event(dtstart=start, dtend=start + timedelta(hours=1), summary="Independent café 雪", uid=str(uuid4()))
        event.load()
        assert str(event.icalendar_component["SUMMARY"]) == "Independent café 雪"
        event.icalendar_component.add("X-INDEPENDENT-PROPERTY", "Preserved by BusyStack")
        event.save()
        event.load()
        assert str(event.icalendar_component["X-INDEPENDENT-PROPERTY"]) == "Preserved by BusyStack"
        assert len(events.search(start=start, end=start + timedelta(days=1), event=True)) == 1
        recurring = events.save_event(dtstart=start + timedelta(days=1), dtend=start + timedelta(days=1, hours=1), summary="Independent recurring event", rrule={"FREQ": "DAILY", "COUNT": 3}, uid=str(uuid4()))
        found = events.search(start=start + timedelta(days=1), end=start + timedelta(days=4), event=True, expand=True, server_expand=True)
        assert len(found) == 3
        task = tasks.save_todo(summary="Independent undated task", uid=str(uuid4()), priority=2, **{"percent-complete": 25})
        task.load()
        assert "DTSTART" not in task.icalendar_component and "DUE" not in task.icalendar_component
        assert len(tasks.todos(include_completed=True)) == 1
        task.complete(completion_timestamp=datetime(2026, 10, 27, 12, tzinfo=timezone.utc))
        task.load()
        assert str(task.icalendar_component["STATUS"]) == "COMPLETED"
        initial = events.get_objects_by_sync_token(disable_fallback=True)
        assert str(initial.sync_token).startswith("urn:busystack:sync:")
        assert len(list(initial)) == 2
        event.delete()
        updated = events.get_objects_by_sync_token(sync_token=initial.sync_token, disable_fallback=True)
        assert str(updated.sync_token) != str(initial.sync_token)
        assert event.url.canonical() in updated.deleted_urls, "Incremental sync must report the deleted resource"
        assert len(events.events()) == 1
        recurring.delete()
        task.delete()
        assert len(events.events()) == 0
        assert len(tasks.todos(include_completed=True)) == 0
        events.delete()
        tasks.delete()
        print(json.dumps({"client": "python-caldav", "version": caldav.__version__, "icalendar": importlib.metadata.version("icalendar"), "status": "passed", "allowSyncDepthOne": True, "syncFallbackDisabled": True, "operations": ["principal and home discovery", "calendar and VTODO collection creation", "Unicode event CRUD", "unknown-property round trip", "event time-range query", "server recurrence expansion", "undated task CRUD and completion", "initial and incremental synchronization", "resource and collection deletion"]}, sort_keys=True))


if __name__ == "__main__":
    main()
