#!/usr/bin/env python3
"""Measured small-installation workload through independent python-caldav, not a capacity claim."""

import importlib.metadata
import json
import os
from pathlib import Path
import platform
import statistics
import time
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import caldav
from sync_profile import verify_sync_profile


def summary(values):
    ordered = sorted(values)
    return {"count": len(values), "meanMs": round(statistics.mean(values), 2), "medianMs": round(statistics.median(values), 2), "p95Ms": round(ordered[max(0, (95 * len(ordered) + 99) // 100 - 1)], 2), "maxMs": round(max(values), 2)}


def main():
    assert caldav.__version__ == "3.6.0"
    timings = {}

    def measure(kind, operation):
        start = time.perf_counter_ns()
        result = operation()
        timings.setdefault(kind, []).append((time.perf_counter_ns() - start) / 1_000_000)
        return result

    password = Path(os.environ["BUSYSTACK_CLIENT_SECRET_FILE"]).read_text().strip()
    with caldav.DAVClient(url=os.environ["BUSYSTACK_CLIENT_URL"], username=os.environ["BUSYSTACK_CLIENT_USER"], password=password, auth_type="basic", ssl_verify_cert=os.environ["BUSYSTACK_CLIENT_CA"], timeout=60) as client:
        principal = client.principal()
        nonce = uuid4().hex
        events = principal.make_calendar(name="Disposable measured events", cal_id="workload-events-" + nonce, supported_calendar_component_set=["VEVENT"])
        tasks = principal.make_calendar(name="Disposable measured tasks", cal_id="workload-tasks-" + nonce, supported_calendar_component_set=["VTODO"])
        sync_profile = verify_sync_profile(client, events)
        objects = []
        anchor = datetime(2026, 11, 1, 10, tzinfo=timezone.utc)
        stored_bytes = 0
        try:
            for index in range(200):
                start = anchor + timedelta(days=index % 30, hours=index % 8)
                item = measure("eventCreate", lambda: events.save_event(uid="workload-" + str(uuid4()), dtstart=start, dtend=start + timedelta(minutes=45), summary="Measured event " + str(index), description="Disposable synthetic workload data. " * 40))
                stored_bytes += len(item.data.encode("utf-8"))
                objects.append(item)
            for index in range(20):
                item = measure("recurringEventCreate", lambda: events.save_event(uid="workload-" + str(uuid4()), dtstart=anchor + timedelta(days=index % 5), dtend=anchor + timedelta(days=index % 5, hours=1), summary="Measured weekly recurrence", rrule={"FREQ": "WEEKLY", "COUNT": 12}))
                stored_bytes += len(item.data.encode("utf-8"))
                objects.append(item)
            for index in range(50):
                properties = {} if index % 2 == 0 else {"due": anchor + timedelta(days=index % 30)}
                item = measure("taskCreate", lambda: tasks.save_todo(uid="workload-" + str(uuid4()), summary="Measured task " + str(index), priority=5, **properties))
                stored_bytes += len(item.data.encode("utf-8"))
            for item in objects[:20]:
                measure("eventGet", item.load)
            for _ in range(10):
                found = measure("monthCalendarQuery", lambda: events.search(start=anchor - timedelta(days=1), end=anchor + timedelta(days=31), event=True, expand=False))
                assert len(found) == 220
            first = measure("initialSync", lambda: events.get_objects_by_sync_token(disable_fallback=True))
            assert len(list(first)) == 220 and str(first.sync_token).startswith("urn:busystack:sync:")
            for item in objects[:10]:
                item.icalendar_component["SUMMARY"] = "Measured updated event"
                measure("eventUpdate", item.save)
            for item in objects[10:15]:
                measure("eventDelete", item.delete)
            delta = measure("incrementalSync", lambda: events.get_objects_by_sync_token(sync_token=first.sync_token, disable_fallback=True))
            assert {item.url.canonical() for item in objects[10:15]} <= delta.deleted_urls
            assert len(events.events()) == 215 and len(tasks.todos(include_completed=True)) == 50
            print(json.dumps({"status": "passed", "client": "python-caldav", "version": caldav.__version__, "icalendar": importlib.metadata.version("icalendar"), "python": platform.python_version(), "platform": platform.system() + " " + platform.machine(), "transport": "HTTPS with isolated test-CA validation", "syncConfiguration": sync_profile, "syncFallbackDisabled": True, "calendarQueryRepresentation": "stored resources; expand=False", "latencyScope": "End-to-end independent client calls, including serialization, TLS/network, server/database and response parsing", "dataset": {"collections": 2, "nonRecurringEvents": 200, "recurringResources": 20, "weeklyInstancesPerRecurringResource": 12, "tasks": 50, "undatedTasks": 25, "acceptedResourceBytes": stored_bytes}, "operations": {name: summary(values) for name, values in timings.items()}, "interpretation": "One bounded single-client workload; no scalability, high-availability or production sizing claim."}, sort_keys=True))
        finally:
            # Both namespaces are created by this process; never remove another user's data.
            events.delete()
            tasks.delete()


if __name__ == "__main__":
    main()
