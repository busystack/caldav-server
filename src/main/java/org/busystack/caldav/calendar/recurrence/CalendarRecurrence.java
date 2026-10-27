package org.busystack.caldav.calendar.recurrence;

import static net.fortuna.ical4j.model.Property.*;

import java.time.*;
import java.time.temporal.Temporal;
import java.util.*;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.property.*;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.query.*;

/** Report-only copies; cumulative work belongs to one projection/REPORT instance. */
public final class CalendarRecurrence {
  private final WorkBudget budget;
  private final RecurrenceExpander recurrence;

  public CalendarRecurrence(QueryLimits limits) {
    budget = new WorkBudget(limits);
    recurrence = new RecurrenceExpander(budget);
  }

  public net.fortuna.ical4j.model.Calendar expand(
      CalendarContent.Parsed parsed, TimeRange range, FloatingTimezone timezone) {
    finite(range);
    var result = parsed.calendar().copy();
    var components = new ArrayList<net.fortuna.ical4j.model.component.CalendarComponent>();
    for (var occurrence : recurrence.occurrences(parsed, range, timezone)) {
      if (!TimeRangeMatcher.matches(occurrence, range, timezone.zoneId())) continue;
      var copy = occurrence.component().copy();
      copy.removeAll(RRULE, RDATE, EXDATE, EXRULE, RECURRENCE_ID, DURATION);
      for (var field : occurrence.dates().entrySet()) {
        if (field.getKey().equals(RECURRENCE_ID)) continue;
        Temporal value = reportValue(field.getValue());
        Property property =
            switch (field.getKey()) {
              case DTSTART -> new DtStart<>(value);
              case DTEND -> new DtEnd<>(value);
              case DUE -> new Due<>(value);
              default -> null;
            };
        if (property != null) copy.replace(property);
      }
      if (occurrence.recurrenceId() != null)
        copy.add(new RecurrenceId<>(reportValue(occurrence.recurrenceId())));
      components.add((net.fortuna.ical4j.model.component.CalendarComponent) copy);
    }
    result.setComponentList(new ComponentList<>(components));
    java.lang.ref.Reference.reachabilityFence(timezone);
    return result;
  }

  public net.fortuna.ical4j.model.Calendar limitRecurrence(
      CalendarContent.Parsed parsed, TimeRange range, FloatingTimezone timezone) {
    finite(range);
    Component master =
        parsed.calendar().getComponents().stream()
            .filter(
                c ->
                    !c.getName().equals(Component.VTIMEZONE)
                        && c.getProperty(RECURRENCE_ID).isEmpty())
            .findFirst()
            .orElse(null);
    Set<Component> keep = Collections.newSetFromMap(new IdentityHashMap<>());
    for (var occurrence : recurrence.occurrences(parsed, range, timezone)) {
      var c = occurrence.component();
      if (c.getProperty(RECURRENCE_ID).isEmpty()) continue;
      boolean current = TimeRangeMatcher.matches(occurrence, range, timezone.zoneId());
      boolean original = false;
      if (master != null && occurrence.recurrenceId() != null)
        original =
            TimeRangeMatcher.matches(
                RecurrenceExpander.single(
                    parsed,
                    master,
                    occurrence.recurrenceId(),
                    occurrence.recurrenceId(),
                    timezone.zoneId()),
                range,
                timezone.zoneId());
      if (current || original) keep.add(c);
    }
    var result = parsed.calendar().copy();
    var retained = new ArrayList<net.fortuna.ical4j.model.component.CalendarComponent>();
    var originals = parsed.calendar().getComponents();
    for (int i = 0; i < originals.size(); i++) {
      var original = originals.get(i);
      if (original.getName().equals(Component.VTIMEZONE)
          || original.getProperty(RECURRENCE_ID).isEmpty()
          || keep.contains(original)) retained.add(result.getComponents().get(i));
    }
    result.setComponentList(new ComponentList<>(retained));
    java.lang.ref.Reference.reachabilityFence(timezone);
    return result;
  }

  private static Temporal reportValue(Temporal value) {
    return value instanceof ZonedDateTime z
        ? z.toInstant()
        : value instanceof OffsetDateTime o ? o.toInstant() : value;
  }

  private static void finite(TimeRange range) {
    if (range.start() == null || range.end() == null)
      throw QueryException.invalid("Calendar-data selectors require both time boundaries");
  }
}
