package org.busystack.caldav.calendar.query;

import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.time.zone.ZoneRules;
import java.util.Map;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryImpl;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.RRule;
import org.busystack.caldav.calendar.recurrence.WorkBudget;

/** Checks observance work before the library eagerly constructs timezone transition rules. */
public final class BoundedTimeZoneRegistry implements TimeZoneRegistry {
  private final TimeZoneRegistryImpl delegate = new TimeZoneRegistryImpl();
  private final WorkBudget budget = new WorkBudget(QueryLimits.defaults());
  private int zones;

  @Override
  public void register(TimeZone timezone) {
    register(timezone, false);
  }

  @Override
  public void register(TimeZone timezone, boolean update) {
    if (update) throw new IllegalArgumentException("External timezone updates are disabled");
    if (++zones > 32 || timezone.getVTimeZone().getComponents().size() > 128)
      throw QueryException.limit();
    for (var observance : timezone.getVTimeZone().getComponents()) {
      DtStart<Temporal> property = observance.getRequiredProperty(Property.DTSTART);
      Temporal start = property.getDate();
      if (!(start instanceof LocalDateTime local))
        throw new QueryException(QueryException.Kind.INVALID_DATA, "Timezone onset must be local");
      LocalDateTime end = LocalDateTime.now();
      if (end.isBefore(local)) end = local.plusYears(5);
      for (var rule : observance.getProperties(Property.RRULE))
        budget.rule(((RRule<?>) rule).getRecur(), local, local, end);
      for (var dates : observance.getProperties(Property.RDATE))
        budget.charge(dates.getValue().length());
    }
    delegate.register(timezone, false);
  }

  @Override
  public void clear() {
    delegate.clear();
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
