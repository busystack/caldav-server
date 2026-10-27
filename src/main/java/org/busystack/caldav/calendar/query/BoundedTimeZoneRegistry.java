package org.busystack.caldav.calendar.query;

import java.time.*;
import java.time.temporal.*;
import java.time.temporal.Temporal;
import java.time.zone.ZoneRules;
import java.util.*;
import java.util.Map;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryImpl;
import net.fortuna.ical4j.model.component.Observance;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.RRule;
import org.busystack.caldav.calendar.recurrence.TemporalSupport;
import org.busystack.caldav.calendar.recurrence.WorkBudget;

/** Checks observance work before the library eagerly constructs timezone transition rules. */
public final class BoundedTimeZoneRegistry implements TimeZoneRegistry {
  private final TimeZoneRegistryImpl delegate = new TimeZoneRegistryImpl();
  private final WorkBudget budget = new WorkBudget(QueryLimits.defaults());
  private int zones;
  private final Map<String, Instant> coverage = new HashMap<>();

  public Map<String, Instant> coverage() {
    return Map.copyOf(coverage);
  }

  @Override
  public void register(TimeZone timezone) {
    register(timezone, false);
  }

  @Override
  public void register(TimeZone timezone, boolean update) {
    if (update) throw new IllegalArgumentException("External timezone updates are disabled");
    if (++zones > 32 || timezone.getVTimeZone().getComponents().size() > 128)
      throw QueryException.limit();
    boolean bounded =
        timezone.getVTimeZone().getComponents().stream()
            .anyMatch(o -> !AnnualTimezoneRules.eligible(o));
    LocalDateTime horizon =
        LocalDateTime.now(ZoneOffset.UTC).plusDays(budget.limits().maximumRangeDays());
    LocalDateTime explicitEnd = LocalDateTime.now(ZoneOffset.UTC);
    for (var observance : timezone.getVTimeZone().getComponents()) {
      var onset = ((DtStart<?>) observance.getRequiredProperty(Property.DTSTART)).getDate();
      if (!(onset instanceof LocalDateTime start))
        throw new QueryException(QueryException.Kind.INVALID_DATA, "Timezone onset must be local");
      if (start.isAfter(horizon)) horizon = start.plusYears(5);
      for (var property : observance.getProperties(Property.RDATE)) {
        var dates = (net.fortuna.ical4j.model.property.RDate<?>) property;
        if (dates.getPeriods().isPresent())
          throw new QueryException(
              QueryException.Kind.INVALID_DATA, "Timezone onsets must be local date-times");
        for (var value : dates.getDates()) {
          if (!(value instanceof LocalDateTime date))
            throw new QueryException(
                QueryException.Kind.INVALID_DATA, "Timezone onsets must be local date-times");
          if (date.isAfter(explicitEnd)) explicitEnd = date;
        }
      }
    }
    var ends = new IdentityHashMap<Observance, LocalDateTime>();
    for (var observance : timezone.getVTimeZone().getComponents()) {
      DtStart<Temporal> property = observance.getRequiredProperty(Property.DTSTART);
      Temporal start = property.getDate();
      if (!(start instanceof LocalDateTime local))
        throw new QueryException(QueryException.Kind.INVALID_DATA, "Timezone onset must be local");
      LocalDateTime end = LocalDateTime.now();
      if (end.isBefore(local)) end = local.plusYears(5);
      if (bounded) end = horizon;
      else if (explicitEnd.isAfter(end)) end = explicitEnd.plusNanos(1);
      for (var propertyRule : observance.getProperties(Property.RRULE)) {
        var rule = ((RRule<?>) propertyRule).getRecur();
        var offset =
            ((net.fortuna.ical4j.model.property.TzOffsetFrom)
                    observance.getRequiredProperty(Property.TZOFFSETFROM))
                .getOffset();
        if (rule.getUntil() != null) {
          var until =
              TemporalSupport.instant(rule.getUntil(), ZoneOffset.UTC)
                  .atOffset(offset)
                  .toLocalDateTime();
          end = until.plusNanos(1);
        } else if (rule.getCount() > 0) {
          var counted = countedEnd(rule, local, offset);
          end = counted;
        }
        budget.rule(rule, local, local, end);
      }
      ends.put(observance, end);
      for (var dates : observance.getProperties(Property.RDATE))
        budget.charge(dates.getValue().length());
    }
    delegate.register(timezone, false);
    var corrected =
        new org.busystack.caldav.calendar.timezone.Ical4jZoneRulesBuilder()
            .vTimeZone(timezone.getVTimeZone())
            .periodEnds(ends)
            .perpetualAnnual(!bounded)
            .build();
    for (var id : delegate.getZoneRules().keySet())
      if (timezone.getID().equals(delegate.getTzId(id))) {
        delegate.getZoneRules().put(id, corrected);
        if (bounded) {
          coverage.put(id, horizon.toInstant(ZoneOffset.MAX));
          coverage.put(timezone.getID(), horizon.toInstant(ZoneOffset.MAX));
        }
      }
  }

  private LocalDateTime countedEnd(
      net.fortuna.ical4j.model.Recur<?> rule, LocalDateTime start, ZoneOffset offset) {
    int count = rule.getCount();
    if (count > budget.limits().maximumInstances()) throw QueryException.limit();
    var unit =
        switch (rule.getFrequency()) {
          case SECONDLY -> ChronoUnit.SECONDS;
          case MINUTELY -> ChronoUnit.MINUTES;
          case HOURLY -> ChronoUnit.HOURS;
          case DAILY -> ChronoUnit.DAYS;
          case WEEKLY -> ChronoUnit.WEEKS;
          case MONTHLY -> ChronoUnit.MONTHS;
          case YEARLY -> ChronoUnit.YEARS;
        };
    int maximum = budget.limits().maximumRuleIncrements() - 5;
    int increments = Math.min(maximum, Math.max(2, Math.min(32, count + 1)));
    @SuppressWarnings("unchecked")
    var typed =
        (net.fortuna.ical4j.model.Recur<OffsetDateTime>) (net.fortuna.ical4j.model.Recur<?>) rule;
    while (true) {
      LocalDateTime end;
      try {
        end =
            start
                .plus(Math.multiplyExact((long) Math.max(1, rule.getInterval()), increments), unit)
                .plusNanos(1);
      } catch (DateTimeException | ArithmeticException ex) {
        throw QueryException.limit();
      }
      budget.rule(rule, start, start, end);
      List<OffsetDateTime> dates;
      try (var stream =
          typed.getDatesAsStream(
              start.atOffset(offset), start.atOffset(offset), end.atOffset(offset), count)) {
        dates = stream.toList();
      }
      if (dates.size() >= count) return dates.getLast().toLocalDateTime().plusNanos(1);
      if (increments == maximum) throw QueryException.limit();
      increments = Math.min(maximum, increments * 2);
    }
  }

  @Override
  public void clear() {
    delegate.clear();
    coverage.clear();
  }

  @Override
  public TimeZone getTimeZone(String id) {
    return delegate.getTimeZone(id);
  }

  @Override
  public Map<String, ZoneRules> getZoneRules() {
    return delegate.getZoneRules();
  }

  @Override
  public java.time.ZoneId getZoneId(String id) {
    return delegate.getZoneId(id);
  }

  @Override
  public String getTzId(String id) {
    return delegate.getTzId(id);
  }
}
