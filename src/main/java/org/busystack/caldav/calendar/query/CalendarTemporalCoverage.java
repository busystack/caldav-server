package org.busystack.caldav.calendar.query;

import java.time.*;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.property.*;
import org.busystack.caldav.calendar.CalendarContent;

/**
 * Coverage is recomputed from authoritative VTIMEZONE data on every parse, never trusted from a
 * cache.
 */
public final class CalendarTemporalCoverage {
  private CalendarTemporalCoverage() {}

  public static void check(
      CalendarContent.Parsed parsed, TimeRange range, FloatingTimezone floating) {
    checkEnd(parsed, range.end(), floating);
    for (var component : parsed.calendar().getComponents()) checkComponent(parsed, component);
  }

  public static void checkEnd(
      CalendarContent.Parsed parsed, Instant end, FloatingTimezone floating) {
    checkEnd(floating.coverageEnd(), end);
    for (Instant bound : parsed.zoneCoverage().values()) checkEnd(bound, end);
  }

  private static void checkEnd(Instant bound, Instant end) {
    if (bound != null && (end == null || end.isAfter(bound))) throw QueryException.limit();
  }

  private static void checkComponent(CalendarContent.Parsed parsed, Component component) {
    if (component.getName().equals(Component.VTIMEZONE)) return;
    for (Property property : component.getProperties()) {
      if (property instanceof DateProperty<?> date) checkValue(parsed, date.getDate());
      else if (property instanceof RDate<?> rdate && rdate.getPeriods().isPresent()) {
        Instant bound =
            property
                .getParameter(Parameter.TZID)
                .map(p -> parsed.zoneCoverage().get(p.getValue()))
                .orElse(null);
        for (var period : rdate.getPeriods().get()) {
          checkPeriodValue(parsed, bound, period.getStart());
          if (period.getEnd() != null) checkPeriodValue(parsed, bound, period.getEnd());
        }
      } else if (property instanceof DateListProperty<?> dates) {
        for (var value : dates.getDates()) checkValue(parsed, value);
      }
    }
    if (component instanceof ComponentContainer<?> nested)
      for (var child : nested.getComponents()) checkComponent(parsed, child);
  }

  private static void checkPeriodValue(
      CalendarContent.Parsed parsed, Instant bound, java.time.temporal.Temporal value) {
    checkValue(parsed, value);
    if (bound != null
        && value instanceof LocalDateTime local
        && !local.toInstant(ZoneOffset.MIN).isBefore(bound)) throw QueryException.limit();
  }

  private static void checkValue(CalendarContent.Parsed parsed, java.time.temporal.Temporal value) {
    checkTemporal(
        value,
        value instanceof ZonedDateTime date
            ? parsed.zoneCoverage().get(date.getZone().getId())
            : null);
  }

  public static void checkTemporal(java.time.temporal.Temporal value, Instant bound) {
    if (value instanceof ZonedDateTime date) {
      // Use the largest legal offset so an unresolved value beyond the horizon cannot
      // acquire a misleading instant from ZoneRules' final fixed-offset tail.
      if (bound != null
          && (!date.toLocalDateTime().toInstant(ZoneOffset.MAX).isBefore(bound)
              || !date.toInstant().isBefore(bound))) throw QueryException.limit();
    }
  }
}
