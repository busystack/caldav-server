package org.busystack.caldav.calendar.query;

import static net.fortuna.ical4j.model.Property.*;

import java.time.*;
import net.fortuna.ical4j.model.Component;
import org.busystack.caldav.calendar.recurrence.Occurrence;

/** RFC 4791 section 9.9 component tables, including their intentionally different boundaries. */
public final class TimeRangeMatcher {
  private TimeRangeMatcher() {}

  public static boolean matches(Occurrence occurrence, TimeRange range, ZoneId zone) {
    Instant start = occurrence.instant(DTSTART, zone), end = occurrence.instant(DTEND, zone);
    if (Component.VEVENT.equals(occurrence.component().getName()))
      return start != null && range.overlaps(start, end == null ? start : end);
    if (!Component.VTODO.equals(occurrence.component().getName()))
      throw new QueryException(
          QueryException.Kind.UNSUPPORTED_FILTER, "Time range is undefined for this component");
    Instant due = occurrence.instant(DUE, zone),
        completed = occurrence.instant(COMPLETED, zone),
        created = occurrence.instant(CREATED, zone);
    if (start != null && occurrence.hasDuration())
      return range.startsAtOrBefore(due) && (range.endsAfter(start) || range.endsAtOrAfter(due));
    if (start != null && due != null)
      return (range.startsBefore(due) || range.startsAtOrBefore(start))
          && (range.endsAfter(start) || range.endsAtOrAfter(due));
    if (start != null) return range.contains(start);
    if (due != null) return range.startsBefore(due) && range.endsAtOrAfter(due);
    if (completed != null && created != null)
      return (range.startsAtOrBefore(created) || range.startsAtOrBefore(completed))
          && (range.endsAtOrAfter(created) || range.endsAtOrAfter(completed));
    if (completed != null)
      return range.startsAtOrBefore(completed) && range.endsAtOrAfter(completed);
    if (created != null) return range.endsAfter(created);
    return true;
  }
}
