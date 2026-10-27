package org.busystack.caldav.calendar.query;

import static net.fortuna.ical4j.model.Property.*;

import java.time.*;
import java.util.*;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.property.FreeBusy;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.recurrence.*;

/**
 * Computes busy intervals from authorized input resources; does not deliver invitations or alarms.
 */
public final class CalendarFreeBusy {
  public enum BusyType {
    BUSY("BUSY"),
    BUSY_TENTATIVE("BUSY-TENTATIVE"),
    BUSY_UNAVAILABLE("BUSY-UNAVAILABLE");
    private final String value;

    BusyType(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public record BusyPeriod(Instant start, Instant end, BusyType type) {}

  public record Source(CalendarContent.Parsed calendar, FloatingTimezone timezone) {
    public Source {
      Objects.requireNonNull(calendar);
      Objects.requireNonNull(timezone);
    }
  }

  private final WorkBudget budget;
  private final RecurrenceExpander recurrence;

  public CalendarFreeBusy(QueryLimits limits) {
    budget = new WorkBudget(limits);
    recurrence = new RecurrenceExpander(budget);
  }

  public List<BusyPeriod> query(
      List<CalendarContent.Parsed> calendars, TimeRange range, ZoneId zone) {
    return query(calendars, range, FloatingTimezone.of(zone));
  }

  public List<BusyPeriod> query(
      List<CalendarContent.Parsed> calendars, TimeRange range, FloatingTimezone timezone) {
    return querySources(
        calendars.stream().map(calendar -> new Source(calendar, timezone)).toList(), range);
  }

  public List<BusyPeriod> querySources(List<Source> sources, TimeRange range) {
    if (range.start() == null || range.end() == null)
      throw QueryException.invalid("Free/busy requires a finite time range");
    var periods = new ArrayList<BusyPeriod>();
    for (var source : sources) {
      var parsed = source.calendar();
      var timezone = source.timezone();
      budget.charge(1);
      if (parsed.calendar().getComponents(Component.VEVENT).isEmpty()
          && parsed.calendar().getComponents(Component.VFREEBUSY).isEmpty()) continue;
      for (var occurrence : recurrence.occurrences(parsed, range, timezone)) {
        var c = occurrence.component();
        budget.charge(1);
        if (Component.VEVENT.equals(c.getName())) {
          if (c.getProperty(TRANSP).map(p -> p.getValue().equals("TRANSPARENT")).orElse(false))
            continue;
          String status = c.getProperty(STATUS).map(p -> p.getValue()).orElse("CONFIRMED");
          if (status.equals("CANCELLED")) continue;
          var start = occurrence.instant(DTSTART, timezone.zoneId());
          var end = occurrence.instant(DTEND, timezone.zoneId());
          if (start != null && end != null)
            add(
                periods,
                range,
                start,
                end,
                status.equals("TENTATIVE") ? BusyType.BUSY_TENTATIVE : BusyType.BUSY);
        } else if (Component.VFREEBUSY.equals(c.getName())) {
          for (var p : c.getProperties(FREEBUSY)) {
            String type =
                p.getParameter(Parameter.FBTYPE).map(value -> value.getValue()).orElse("BUSY");
            if (type.equals("FREE")) continue;
            BusyType busy =
                type.equals("BUSY-TENTATIVE")
                    ? BusyType.BUSY_TENTATIVE
                    : type.equals("BUSY-UNAVAILABLE") ? BusyType.BUSY_UNAVAILABLE : BusyType.BUSY;
            for (var interval : ((FreeBusy) p).getIntervals())
              add(periods, range, interval.getStart(), interval.getEnd(), busy);
          }
        }
      }
    }
    var result = new ArrayList<BusyPeriod>();
    for (BusyType type : BusyType.values()) {
      var sorted =
          periods.stream()
              .filter(p -> p.type() == type)
              .sorted(Comparator.comparing(BusyPeriod::start).thenComparing(BusyPeriod::end))
              .toList();
      BusyPeriod current = null;
      for (var next : sorted) {
        if (current == null) current = next;
        else if (!next.start().isAfter(current.end()))
          current =
              new BusyPeriod(
                  current.start(),
                  current.end().isAfter(next.end()) ? current.end() : next.end(),
                  type);
        else {
          result.add(current);
          current = next;
        }
      }
      if (current != null) result.add(current);
    }
    result.sort(Comparator.comparing(BusyPeriod::start).thenComparing(p -> p.type().value()));
    java.lang.ref.Reference.reachabilityFence(sources);
    return List.copyOf(result);
  }

  private void add(
      List<BusyPeriod> result, TimeRange range, Instant start, Instant end, BusyType type) {
    budget.charge(1);
    if (!start.isBefore(end) || !range.overlaps(start, end)) return;
    budget.instance();
    result.add(
        new BusyPeriod(
            start.isBefore(range.start()) ? range.start() : start,
            end.isAfter(range.end()) ? range.end() : end,
            type));
  }
}
