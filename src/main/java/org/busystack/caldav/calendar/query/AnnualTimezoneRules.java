package org.busystack.caldav.calendar.query;

import java.time.*;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.Observance;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.transform.recurrence.Frequency;

/** Conservative shapes that Java's one-transition-per-year lastRules can represent exactly. */
final class AnnualTimezoneRules {
  private AnnualTimezoneRules() {}

  static boolean eligible(Observance observance) {
    for (var property : observance.getProperties(Property.RRULE)) {
      var rule = ((RRule<?>) property).getRecur();
      if (rule.getUntil() != null || rule.getCount() > 0) continue;
      if (rule.getFrequency() != Frequency.YEARLY
          || Math.max(1, rule.getInterval()) != 1
          || rule.getMonthList().size() > 1
          || rule.getDayList().size() > 1
          || rule.getMonthDayList().size() > 1
          || !rule.getSetPosList().isEmpty()
          || !rule.getYearDayList().isEmpty()
          || !rule.getWeekNoList().isEmpty()) return false;
      var onset = ((DtStart<?>) observance.getRequiredProperty(Property.DTSTART)).getDate();
      if (!(onset instanceof LocalDateTime date)) return false;
      if (!same(rule.getHourList(), date.getHour())
          || !same(rule.getMinuteList(), date.getMinute())
          || !same(rule.getSecondList(), date.getSecond())) return false;
      Month month =
          rule.getMonthList().isEmpty()
              ? date.getMonth()
              : Month.of(rule.getMonthList().getFirst().getMonthOfYear());
      if (!rule.getDayList().isEmpty()) {
        int ordinal = rule.getDayList().getFirst().getOffset();
        if (ordinal == 0 || Math.abs(ordinal) > 4 || !rule.getMonthDayList().isEmpty())
          return false;
        if (rule.getMonthList().isEmpty()
            && month != (ordinal > 0 ? Month.JANUARY : Month.DECEMBER)) return false;
      } else {
        int day =
            rule.getMonthDayList().isEmpty()
                ? date.getDayOfMonth()
                : rule.getMonthDayList().getFirst();
        if (day > month.minLength() || day < -month.minLength()) return false;
      }
    }
    return true;
  }

  private static boolean same(java.util.List<Integer> values, int expected) {
    return values.isEmpty() || values.size() == 1 && values.getFirst() == expected;
  }
}
