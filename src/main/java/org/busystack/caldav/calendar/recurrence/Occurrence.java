package org.busystack.caldav.calendar.recurrence;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.Temporal;
import java.util.Map;
import net.fortuna.ical4j.model.Component;

/** Read-only view of one effective recurrence; the authoritative component is never modified. */
public record Occurrence(
    Component component,
    Temporal recurrenceId,
    Map<String, Temporal> dates,
    boolean hasDuration,
    Temporal originalStart,
    Temporal originalEnd) {
  public Occurrence {
    dates = Map.copyOf(dates);
  }

  public Instant instant(String name, ZoneId floating) {
    Temporal value = dates.get(name);
    return value == null ? null : TemporalSupport.instant(value, floating);
  }
}
