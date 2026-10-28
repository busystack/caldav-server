package org.busystack.caldav.calendar;

import java.time.*;
import java.time.temporal.Temporal;
import java.util.*;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.property.*;
import net.fortuna.ical4j.transform.recurrence.Frequency;
import org.busystack.caldav.calendar.recurrence.TemporalSupport;

/**
 * Application write-boundary checks for RFC 5545 cross-property constraints. Parsing, typed values
 * and their ordinary validators remain iCal4j responsibilities; no recurrence is expanded here.
 */
public final class CalendarConstraints {
  private CalendarConstraints() {}

  /** Apply the same observance constraints to collection properties and request timezones. */
  public static void validateTimezone(net.fortuna.ical4j.model.component.VTimeZone timezone) {
    validate(timezone, null, Map.of());
  }

  static void validate(net.fortuna.ical4j.model.Calendar calendar, Map<Property, String> lexical) {
    for (Component component : calendar.getComponents()) validate(component, null, lexical);
  }

  private static void validate(
      Component component, Component parent, Map<Property, String> lexical) {
    Temporal start = TemporalSupport.date(component, Property.DTSTART);
    boolean event = component.getName().equals(Component.VEVENT);
    boolean task = component.getName().equals(Component.VTODO);
    if (event && start == null) invalid(); // METHOD is prohibited for stored calendar objects.
    if (event || task) {
      Temporal end = TemporalSupport.date(component, event ? Property.DTEND : Property.DUE);
      Property duration = component.getProperty(Property.DURATION).orElse(null);
      if (duration != null && (end != null || start == null)) invalid();
      if (end != null && start != null) {
        sameType(start, end);
        if (compare(start, end) >= 0) invalid();
      }
      if (duration != null) {
        String value = lexical.getOrDefault(duration, duration.getValue());
        positiveDuration(value);
        if (start instanceof LocalDate && value.toUpperCase(Locale.ROOT).contains("T")) invalid();
      }
      Temporal id = TemporalSupport.date(component, Property.RECURRENCE_ID);
      if (id != null && start != null) sameType(start, id);
      for (String name : List.of(Property.RDATE, Property.EXDATE))
        for (var property : component.getProperties(name)) {
          if (property instanceof RDate<?> date && date.getPeriods().isPresent()) {
            if (start instanceof LocalDate) invalid();
            for (var period : date.getPeriods().get()) {
              if (period.getEnd() != null && compare(period.getStart(), period.getEnd()) >= 0)
                invalid();
            }
          } else if (property instanceof DateListProperty<?> dates && start != null)
            for (Temporal date : dates.getDates()) sameType(start, date);
        }
    }
    for (var property : component.getProperties()) {
      String name = property.getName();
      if (Set.of(Property.COMPLETED, Property.PERCENT_COMPLETE).contains(name) && !task) invalid();
      if (Set.of(Property.ACTION, Property.TRIGGER, Property.REPEAT).contains(name)
          && !component.getName().equals(Component.VALARM)) invalid();
      if (property instanceof Geo geo
          && (geo.getLatitude().abs().compareTo(java.math.BigDecimal.valueOf(90)) > 0
              || geo.getLongitude().abs().compareTo(java.math.BigDecimal.valueOf(180)) > 0))
        invalid();
      if (property instanceof RRule<?> rule) rule(rule, start, component.getName());
      if (property instanceof DateProperty<?> date) {
        Temporal value = date.getDate();
        if (Set.of(Property.DTSTAMP, Property.CREATED, Property.LAST_MODIFIED, Property.COMPLETED)
                .contains(property.getName())
            && !utc(value)) invalid();
        if (property.getParameter(Parameter.TZID).isPresent()
            && (value instanceof LocalDate || utc(value))) invalid();
      }
    }
    if (component.getName().equals(Component.VALARM)) alarm(component, parent, lexical);
    if (component instanceof ComponentContainer<?> children)
      for (Component child : children.getComponents()) validate(child, component, lexical);
  }

  private static void rule(RRule<?> property, Temporal start, String component) {
    var rule = property.getRecur();
    Temporal until = rule.getUntil();
    if (start != null && until != null) {
      sameType(start, until);
      if (component.equals("STANDARD") || component.equals("DAYLIGHT")) {
        if (!utc(until)) invalid();
      } else if (start instanceof LocalDateTime) {
        if (!(until instanceof LocalDateTime)) invalid();
      } else if (!(start instanceof LocalDate) && !utc(until)) invalid();
    }
    Frequency frequency = rule.getFrequency();
    for (var day : rule.getDayList()) {
      int ordinal = day.getOffset();
      if (Math.abs(ordinal) > 53
          || ordinal != 0 && frequency != Frequency.MONTHLY && frequency != Frequency.YEARLY
          || ordinal != 0 && frequency == Frequency.YEARLY && !rule.getWeekNoList().isEmpty())
        invalid();
    }
    if (!rule.getMonthDayList().isEmpty() && frequency == Frequency.WEEKLY
        || !rule.getYearDayList().isEmpty()
            && Set.of(Frequency.DAILY, Frequency.WEEKLY, Frequency.MONTHLY).contains(frequency)
        || !rule.getWeekNoList().isEmpty() && frequency != Frequency.YEARLY) invalid();
    for (var values :
        List.of(
            rule.getMonthDayList(),
            rule.getYearDayList(),
            rule.getWeekNoList(),
            rule.getSetPosList())) if (values.contains(0)) invalid();
    if (!rule.getSetPosList().isEmpty()
        && rule.getDayList().isEmpty()
        && rule.getMonthDayList().isEmpty()
        && rule.getYearDayList().isEmpty()
        && rule.getWeekNoList().isEmpty()
        && rule.getMonthList().isEmpty()
        && rule.getHourList().isEmpty()
        && rule.getMinuteList().isEmpty()
        && rule.getSecondList().isEmpty()) invalid();
    // DATE BYHOUR/BYMINUTE/BYSECOND are intentionally not rejected: §3.3.10 requires ignoring them.
  }

  private static void alarm(Component alarm, Component parent, Map<Property, String> lexical) {
    if (alarm.getProperties(Property.ACTION).size() != 1
        || alarm.getProperties(Property.TRIGGER).size() != 1) invalid();
    String action = alarm.getRequiredProperty(Property.ACTION).getValue().toUpperCase(Locale.ROOT);
    if (!action.matches("[A-Z0-9-]+")) invalid();
    if (action.equals("DISPLAY") || action.equals("EMAIL"))
      if (alarm.getProperties(Property.DESCRIPTION).size() != 1) invalid();
    if (action.equals("EMAIL")
        && (alarm.getProperties(Property.SUMMARY).size() != 1
            || alarm.getProperties(Property.ATTENDEE).isEmpty())) invalid();
    if (action.equals("AUDIO") && alarm.getProperties(Property.ATTACH).size() > 1) invalid();
    Property duration = alarm.getProperty(Property.DURATION).orElse(null);
    Property repeat = alarm.getProperty(Property.REPEAT).orElse(null);
    if (alarm.getProperties(Property.DURATION).size() > 1
        || alarm.getProperties(Property.REPEAT).size() > 1) invalid();
    if ((duration == null) != (repeat == null)) invalid();
    if (duration != null) positiveDuration(lexical.getOrDefault(duration, duration.getValue()));
    var trigger = alarm.getProperty(Property.TRIGGER).orElse(null);
    if (!(trigger instanceof Trigger value))
      return; // Ordinary mandatory-property validation runs first.
    if (value.getDate() != null) {
      if (!utc(value.getDate()) || trigger.getParameter(Parameter.RELATED).isPresent()) invalid();
    } else if (parent != null) {
      boolean end =
          trigger
              .getParameter(Parameter.RELATED)
              .map(p -> p.getValue().equalsIgnoreCase("END"))
              .orElse(false);
      boolean start = parent.getProperty(Property.DTSTART).isPresent();
      if (!end && !start) invalid();
      if (end) {
        boolean explicit =
            parent
                .getProperty(
                    parent.getName().equals(Component.VTODO) ? Property.DUE : Property.DTEND)
                .isPresent();
        if (!explicit && !(start && parent.getProperty(Property.DURATION).isPresent())) invalid();
      }
    }
  }

  private static void positiveDuration(String value) {
    var duration = TemporalSupport.duration(value);
    if (duration.days() < 0
        || duration.clock().isNegative()
        || duration.days() == 0 && duration.clock().isZero()) invalid();
  }

  private static void sameType(Temporal first, Temporal second) {
    if ((first instanceof LocalDate) != (second instanceof LocalDate)) invalid();
  }

  /** Floating and absolute DATE-TIMEs cannot be ordered without collection timezone context. */
  private static int compare(Temporal first, Temporal second) {
    if (first instanceof LocalDate a && second instanceof LocalDate b) return a.compareTo(b);
    if (first instanceof LocalDateTime a && second instanceof LocalDateTime b)
      return a.compareTo(b);
    if (!(first instanceof LocalDateTime) && !(second instanceof LocalDateTime))
      return TemporalSupport.instant(first, ZoneOffset.UTC)
          .compareTo(TemporalSupport.instant(second, ZoneOffset.UTC));
    return -1;
  }

  private static boolean utc(Temporal value) {
    return value instanceof Instant
        || value instanceof OffsetDateTime offset && offset.getOffset().equals(ZoneOffset.UTC)
        || value instanceof ZonedDateTime zoned
            && zoned.getOffset().equals(ZoneOffset.UTC)
            && zoned.getZone() instanceof ZoneOffset;
  }

  private static void invalid() {
    throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
  }
}
