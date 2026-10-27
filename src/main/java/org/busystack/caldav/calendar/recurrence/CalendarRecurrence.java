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
  private static final long MAX_REPORT_BYTES = CalendarReportSize.MAX_REPORT_BYTES;
  private long reportBytes;
  private final Map<Component, Long> outputEstimates = new IdentityHashMap<>();

  public CalendarRecurrence(QueryLimits limits) {
    budget = new WorkBudget(limits);
    recurrence = new RecurrenceExpander(budget);
  }

  public net.fortuna.ical4j.model.Calendar expand(
      CalendarContent.Parsed parsed, TimeRange range, FloatingTimezone timezone) {
    finite(range);
    var result = CalendarReports.copy(parsed);
    var components = new ArrayList<net.fortuna.ical4j.model.component.CalendarComponent>();
    var zones = new HashMap<String, FloatingTimezone>();
    for (var occurrence : recurrence.occurrences(parsed, range, timezone)) {
      if (!TimeRangeMatcher.matches(occurrence, range, timezone.zoneId())) continue;
      reserveOutput(parsed, occurrence.component());
      var copy = CalendarReports.componentCopy(parsed, occurrence.component());
      copy.removeAll(RRULE, RDATE, EXDATE, EXRULE, RECURRENCE_ID, DURATION);
      for (var field : occurrence.dates().entrySet()) {
        if (field.getKey().equals(RECURRENCE_ID)) continue;
        Temporal value = reportValue(field.getValue());
        Property property =
            switch (field.getKey()) {
              case DTSTART -> new DtStart<>(parameters(occurrence.component(), DTSTART), value);
              case DTEND -> new DtEnd<>(parameters(occurrence.component(), DTEND), value);
              case DUE -> new Due<>(parameters(occurrence.component(), DUE), value);
              default -> null;
            };
        if (property != null) copy.replace(property);
      }
      if (occurrence.recurrenceId() != null)
        copy.add(
            new RecurrenceId<>(
                parameters(occurrence.component(), RECURRENCE_ID),
                reportValue(occurrence.recurrenceId())));
      expandAlarms(parsed, occurrence, copy, timezone);
      normalizeTimezones(parsed, copy, zones);
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
    var result = CalendarReports.copy(parsed);
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

  private void reserveOutput(CalendarContent.Parsed parsed, Component component) {
    long estimate =
        outputEstimates.computeIfAbsent(
            component, value -> Math.addExact(CalendarReportSize.estimate(parsed, value), 1024L));
    if (estimate > MAX_REPORT_BYTES - reportBytes) throw QueryException.limit();
    reportBytes += estimate;
  }

  private void normalizeTimezones(
      CalendarContent.Parsed parsed, Component component, Map<String, FloatingTimezone> zones) {
    var properties = new ArrayList<Property>();
    for (Property property : component.getProperties()) {
      var id = property.getParameter(Parameter.TZID);
      if (id.isEmpty()) {
        properties.add(property);
        continue;
      }
      budget.charge(1);
      var zone =
          zones.computeIfAbsent(
              id.get().getValue(),
              key -> {
                for (var definition : parsed.calendar().getComponents(Component.VTIMEZONE))
                  if (definition.getRequiredProperty(TZID).getValue().equals(key))
                    return FloatingTimezone.from(
                        (net.fortuna.ical4j.model.component.VTimeZone) definition);
                return FloatingTimezone.of(
                    net.fortuna.ical4j.model.TimeZoneRegistry.getGlobalZoneId(key));
              });
      String type =
          property
              .getParameter(Parameter.VALUE)
              .map(p -> p.getValue().toUpperCase(Locale.ROOT))
              .orElse(property instanceof DateProperty<?> ? "DATE-TIME" : "TEXT");
      var values = new ArrayList<String>();
      if (type.equals("DATE-TIME")) {
        for (String value : property.getValue().split(",", -1)) values.add(utcValue(value, zone));
      } else if (type.equals("PERIOD")) {
        for (String value : parsed.lexicalValue(property).split(",", -1)) {
          var period = net.fortuna.ical4j.model.Period.<Temporal>parse(value);
          Temporal start = period.getStart();
          if (start instanceof LocalDateTime local) start = local.atZone(zone.zoneId());
          String tail = value.substring(value.indexOf('/') + 1);
          Temporal end =
              tail.startsWith("P") || tail.startsWith("+P")
                  ? TemporalSupport.duration(tail).addTo(start, zone.zoneId())
                  : period.getEnd();
          if (end instanceof LocalDateTime local) end = local.atZone(zone.zoneId());
          CalendarTemporalCoverage.checkTemporal(start, zone.coverageEnd());
          CalendarTemporalCoverage.checkTemporal(end, zone.coverageEnd());
          values.add(
              new TemporalAdapter<>(TemporalSupport.instant(start, zone.zoneId()))
                  + "/"
                  + new TemporalAdapter<>(TemporalSupport.instant(end, zone.zoneId())));
        }
      } else
        throw new QueryException(
            QueryException.Kind.UNSUPPORTED_FILTER,
            "Cannot expand an untyped timezone-qualified extension value");
      var parameters =
          new ParameterList(
              property.getParameterList().getAll().stream()
                  .filter(p -> !p.getName().equals(Parameter.TZID))
                  .toList());
      var converted = new XProperty(property.getName(), parameters, String.join(",", values));
      converted.setPrefix(property.getPrefix());
      properties.add(converted);
    }
    component.setPropertyList(new PropertyList(properties));
    if (component instanceof ComponentContainer<?> nested)
      for (var child : nested.getComponents()) normalizeTimezones(parsed, child, zones);
  }

  private static String utcValue(String value, FloatingTimezone zone) {
    var date = TemporalAdapter.parse(value, zone.zoneId()).getTemporal();
    CalendarTemporalCoverage.checkTemporal(date, zone.coverageEnd());
    return new TemporalAdapter<>(date.toInstant()).toString();
  }

  private static ParameterList parameters(Component component, String name) {
    var property = component.getProperty(name);
    return new ParameterList(
        property.stream()
            .flatMap(p -> p.getParameterList().getAll().stream())
            .filter(
                p ->
                    !Set.of(Parameter.TZID, Parameter.VALUE, Parameter.RANGE).contains(p.getName()))
            .toList());
  }

  @SuppressWarnings("unchecked")
  private void expandAlarms(
      CalendarContent.Parsed parsed,
      Occurrence occurrence,
      Component copy,
      FloatingTimezone timezone) {
    if (!(occurrence.component() instanceof ComponentContainer<?> original)
        || !(copy instanceof ComponentContainer<?> destination)) return;
    var expanded = new ArrayList<Component>();
    for (Component child : original.getComponents()) {
      if (!child.getName().equals(Component.VALARM)) {
        expanded.add(CalendarReports.componentCopy(parsed, child));
        continue;
      }
      // Parent DTSTART becomes UTC in an expanded report. Absolute alarm instances retain
      // the original nominal-day/DST trigger instants after that representation change.
      for (Instant instant :
          AlarmCalculator.triggers(parsed, child, occurrence, timezone.zoneId(), budget)) {
        budget.instance();
        reserveOutput(parsed, child);
        var alarm = CalendarReports.componentCopy(parsed, child);
        alarm.removeAll(TRIGGER, REPEAT, DURATION);
        var trigger = new Trigger(instant);
        for (var parameter : parameters(child, TRIGGER).getAll())
          if (!parameter.getName().equals(Parameter.RELATED)) trigger.add(parameter);
        alarm.add(trigger);
        expanded.add(alarm);
      }
    }
    ((ComponentContainer<Component>) destination).setComponentList(new ComponentList<>(expanded));
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
