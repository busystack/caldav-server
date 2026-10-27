package org.busystack.caldav.calendar.recurrence;

import static net.fortuna.ical4j.model.Property.*;

import java.time.*;
import java.time.temporal.Temporal;
import java.util.*;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.property.Repeat;
import net.fortuna.ical4j.model.property.Trigger;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.query.*;

/** Calculates stored alarm trigger times only; no notifications or reminder execution. */
public final class AlarmCalculator {
  private AlarmCalculator() {}

  public static List<Instant> triggers(
      CalendarContent.Parsed parsed,
      Component alarm,
      Occurrence owner,
      ZoneId zone,
      WorkBudget budget) {
    Trigger trigger = alarm.getRequiredProperty(TRIGGER);
    Temporal first;
    if (trigger.isAbsolute()) first = trigger.getDate();
    else {
      boolean endRelated =
          trigger
              .getParameter(Parameter.RELATED)
              .map(p -> p.getValue().equals("END"))
              .orElse(false);
      first =
          owner
              .dates()
              .get(
                  endRelated
                      ? (Component.VTODO.equals(owner.component().getName()) ? DUE : DTEND)
                      : DTSTART);
      if (first == null)
        throw new QueryException(
            QueryException.Kind.INVALID_DATA, "Relative alarm has no effective anchor");
      first = TemporalSupport.duration(parsed, trigger).addTo(first, zone);
    }
    int repeat = alarm.getProperty(REPEAT).map(p -> ((Repeat) p).getCount()).orElse(0);
    if (repeat < 0 || repeat > budget.limits().maximumInstances()) throw QueryException.limit();
    var result = new ArrayList<Instant>();
    for (int index = 0; index <= repeat; index++) {
      budget.charge(1);
      result.add(TemporalSupport.instant(first, zone));
      if (index < repeat) {
        var duration =
            alarm
                .getProperty(DURATION)
                .orElseThrow(
                    () ->
                        new QueryException(
                            QueryException.Kind.INVALID_DATA, "Repeated alarm lacks DURATION"));
        first = TemporalSupport.duration(parsed, duration).addTo(first, zone);
      }
    }
    return result;
  }
}
