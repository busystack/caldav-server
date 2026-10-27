package org.busystack.caldav.calendar.recurrence;

import static net.fortuna.ical4j.model.Property.*;

import java.time.*;
import java.time.Duration;
import java.time.temporal.*;
import java.util.*;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.property.*;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.query.*;

/**
 * iCal4j generates RRULE dates; this layer applies exclusions, detached revisions and report
 * bounds.
 */
public final class RecurrenceExpander {
  private final WorkBudget budget;

  public RecurrenceExpander(WorkBudget budget) {
    this.budget = budget;
  }

  public List<Occurrence> occurrences(
      CalendarContent.Parsed parsed, TimeRange requested, FloatingTimezone timezone) {
    return occurrences(parsed, requested, timezone, false);
  }

  public List<Occurrence> occurrences(
      CalendarContent.Parsed parsed,
      TimeRange requested,
      FloatingTimezone timezone,
      boolean includeAlarms) {
    try {
      CalendarTemporalCoverage.check(parsed, requested, timezone);
      return expand(parsed, requested, timezone.zoneId(), includeAlarms, timezone.coverageEnd());
    } catch (java.time.DateTimeException | ArithmeticException ex) {
      throw QueryException.limit();
    } finally {
      java.lang.ref.Reference.reachabilityFence(timezone);
    }
  }

  private List<Occurrence> expand(
      CalendarContent.Parsed parsed,
      TimeRange requested,
      ZoneId zone,
      boolean includeAlarms,
      Instant floatingEnd) {
    List<Component> components = new ArrayList<>();
    for (var c : parsed.calendar().getComponents())
      if (!Component.VTIMEZONE.equals(c.getName())) components.add(c);
    Component master =
        components.stream()
            .filter(c -> c.getProperty(RECURRENCE_ID).isEmpty())
            .findFirst()
            .orElse(null);
    if (master == null) {
      var result = new ArrayList<Occurrence>();
      for (Component c : components) {
        budget.instance();
        result.add(single(parsed, c, null, null, zone));
      }
      return result;
    }
    Temporal seed = TemporalSupport.date(master, DTSTART);
    boolean recurring =
        !master.getProperties(RRULE).isEmpty() || !master.getProperties(RDATE).isEmpty();
    if (!recurring) {
      var result = new ArrayList<Occurrence>();
      budget.instance();
      result.add(single(parsed, master, null, null, zone));
      for (Component c : components)
        if (c != master) {
          budget.instance();
          result.add(single(parsed, c, null, null, zone));
        }
      return result;
    }
    if (seed == null)
      throw new QueryException(
          QueryException.Kind.INVALID_DATA, "Recurrence requires an explicit DTSTART anchor");
    Map<Object, Component> overrides = new HashMap<>();
    List<Component> ranges = new ArrayList<>();
    for (Component c : components)
      if (c != master) {
        Temporal id = TemporalSupport.date(c, RECURRENCE_ID);
        if (id == null || overrides.put(key(id, seed, zone), c) != null)
          throw new QueryException(
              QueryException.Kind.INVALID_DATA, "Ambiguous recurrence override");
        var rangeParameter = c.getRequiredProperty(RECURRENCE_ID).getParameter(Parameter.RANGE);
        if (rangeParameter.isPresent()) {
          if (!rangeParameter.get().getValue().equalsIgnoreCase("THISANDFUTURE"))
            throw new QueryException(
                QueryException.Kind.INVALID_DATA, "Unsupported recurrence range value");
          ranges.add(c);
        }
      }
    ranges.sort(
        Comparator.comparing(
            c -> TemporalSupport.instant(TemporalSupport.date(c, RECURRENCE_ID), zone)));
    Duration padding = padding(parsed, components, zone, includeAlarms);
    Instant first =
        requested.start() == null
            ? TemporalSupport.instant(seed, zone)
            : requested.start().minus(padding);
    Instant last =
        requested.end() == null
            ? first.plus(Duration.ofDays(budget.limits().maximumRangeDays()))
            : requested.end().plus(padding);
    // Explicit RDATEs and detached instances may precede DTSTART; only skip the rule search.
    boolean searchRules = first.isBefore(last);
    if (Duration.between(first, last)
            .compareTo(
                Duration.ofDays(budget.limits().maximumRangeDays() + 2L)
                    .plus(padding.multipliedBy(2)))
        > 0) throw QueryException.limit();
    if (floatingEnd != null && last.isAfter(floatingEnd)) throw QueryException.limit();
    for (var bound : parsed.zoneCoverage().values())
      if (last.isAfter(bound)) throw QueryException.limit();
    Temporal[] bounds = ruleBounds(first, last, seed, zone);
    Temporal nativeFirst = bounds[0];
    Temporal nativeLast = bounds[1];
    var dates = new LinkedHashMap<Object, Temporal>();
    dates.put(key(seed, seed, zone), seed);
    var periodEnds = new HashMap<Object, Temporal>();
    for (var property : master.getProperties(RDATE)) {
      RDate<?> rdate = (RDate<?>) property;
      if (rdate.getPeriods().isPresent()) {
        // PERIOD has only comma-separated start/end-or-duration values. The established parser
        // validates each value; retain its lexical duration to distinguish P1D from PT24H.
        for (String rawPeriod : parsed.lexicalValue(property).split(",", -1)) {
          var period = net.fortuna.ical4j.model.Period.<Temporal>parse(rawPeriod);
          budget.charge(1);
          Temporal value = period.getStart();
          if (value instanceof LocalDateTime && property.getParameter(Parameter.TZID).isPresent())
            value = ((LocalDateTime) value).atZone(zoneFor(parsed, property, zone));
          Object id = key(value, seed, zone);
          dates.put(id, value);
          String endValue = rawPeriod.substring(rawPeriod.indexOf('/') + 1);
          Temporal end =
              endValue.startsWith("P") || endValue.startsWith("+P")
                  ? TemporalSupport.duration(endValue).addTo(value, zone)
                  : period.getEnd();
          if (end instanceof LocalDateTime && property.getParameter(Parameter.TZID).isPresent())
            end = ((LocalDateTime) end).atZone(zoneFor(parsed, property, zone));
          periodEnds.put(id, end);
        }
      } else
        for (Temporal value : rdate.getDates()) {
          budget.charge(1);
          dates.put(key(value, seed, zone), value);
        }
    }
    if (master.getProperties(RRULE).size() > 1)
      throw new QueryException(
          QueryException.Kind.INVALID_DATA, "Multiple RRULE recurrence sets are undefined");
    for (var property : searchRules ? master.getProperties(RRULE) : List.<Property>of()) {
      Recur<?> original = ((RRule<?>) property).getRecur();
      Temporal localSeed = nativeRange(TemporalSupport.instant(seed, zone), seed, zone);
      if (seed instanceof LocalDateTime || seed instanceof LocalDate) localSeed = seed;
      Temporal lower = original.getCount() > 0 ? localSeed : nativeFirst;
      @SuppressWarnings("unchecked")
      Recur<Temporal> copied = new Recur<>((String) property.getValue());
      Temporal until = original.getUntil();
      if (until != null) {
        if (localSeed instanceof LocalDate) {
          if (!(until instanceof LocalDate))
            throw new QueryException(
                QueryException.Kind.INVALID_DATA, "Recurrence UNTIL type differs from DTSTART");
        } else if (!(until instanceof LocalDateTime)
            || seed instanceof ZonedDateTime
            || seed instanceof OffsetDateTime
            || seed instanceof Instant) {
          until = nativeRange(TemporalSupport.instant(until, zone), seed, zone);
        }
      }
      if (until != null
          && net.fortuna.ical4j.model.TemporalComparator.INSTANCE.compare(until, lower) < 0)
        continue;
      Recur<Temporal> rule = new Recur.Builder<>(copied).count(null).until(until).build();
      int maximum = budget.limits().maximumRuleIncrements() - 5;
      int increments =
          original.getCount() > 0
              ? Math.min(maximum, (int) Math.min(maximum, 2L * original.getCount() + 8))
              : maximum;
      while (true) {
        Temporal upper = nativeLast;
        boolean capped = false;
        if (original.getCount() > 0) {
          Temporal cap =
              localSeed
                  .plus(
                      Math.multiplyExact((long) Math.max(1, original.getInterval()), increments),
                      frequencyUnit(original))
                  .plus(1, localSeed instanceof LocalDate ? ChronoUnit.DAYS : ChronoUnit.NANOS);
          if (net.fortuna.ical4j.model.TemporalComparator.INSTANCE.compare(cap, nativeLast) < 0) {
            upper = cap;
            capped = true;
          }
        }
        budget.rule(original, localSeed, lower, upper);
        List<Temporal> generated;
        try (var stream =
            rule.getDatesAsStream(
                localSeed, lower, upper, budget.limits().maximumInstances() + 1)) {
          // iCal4j's spliterator may advance without emitting pre-window candidates.
          // Keep its stream pipeline rather than adapting it to an Iterator.
          generated =
              stream
                  .peek(candidate -> budget.instance())
                  .map(candidate -> resolveCandidate(candidate, seed, zone))
                  .limit(original.getCount() > 0 ? original.getCount() : Long.MAX_VALUE)
                  .toList();
        }
        if (!capped || generated.size() >= original.getCount()) {
          for (var resolved : generated) dates.putIfAbsent(key(resolved, seed, zone), resolved);
          break;
        }
        if (increments == maximum) throw QueryException.limit();
        increments = Math.min(maximum, increments * 2);
      }
    }
    Set<Object> excluded = new HashSet<>();
    for (var property : master.getProperties(EXDATE))
      for (Temporal value : ((DateListProperty<?>) property).getDates())
        excluded.add(key(value, seed, zone));
    dates.keySet().removeAll(excluded);
    var result = new ArrayList<Occurrence>();
    for (var entry : dates.entrySet()) {
      if (overrides.containsKey(entry.getKey())) continue;
      Temporal original = entry.getValue();
      Component range = null;
      for (Component candidate : ranges) {
        Temporal rid = TemporalSupport.date(candidate, RECURRENCE_ID);
        if (TemporalSupport.instant(rid, zone).isAfter(TemporalSupport.instant(original, zone)))
          break;
        range = candidate;
      }
      Temporal shifted =
          range == null
              ? original
              : shifted(
                  original,
                  TemporalSupport.date(range, RECURRENCE_ID),
                  TemporalSupport.date(range, DTSTART),
                  zone);
      Occurrence occurrence =
          single(parsed, range == null ? master : range, shifted, original, zone);
      if (range == null && periodEnds.containsKey(entry.getKey())) {
        var effective = new HashMap<>(occurrence.dates());
        effective.put(
            Component.VTODO.equals(master.getName()) ? DUE : DTEND, periodEnds.get(entry.getKey()));
        occurrence =
            new Occurrence(
                master, original, effective, true, original, periodEnds.get(entry.getKey()));
      }
      result.add(occurrence);
      if (result.size() > budget.limits().maximumInstances()) throw QueryException.limit();
    }
    for (Component override : overrides.values()) {
      budget.instance();
      result.add(
          single(parsed, override, null, TemporalSupport.date(override, RECURRENCE_ID), zone));
    }
    if (requested.end() == null
        && master.getProperties(RRULE).stream()
            .map(p -> ((RRule<?>) p).getRecur())
            .anyMatch(r -> r.getUntil() == null && r.getCount() <= 0)) {
      // The caller can return true after a match. A negative answer beyond this horizon is not
      // proven.
      result.add(new Occurrence(new UnknownFuture(), null, Map.of(), false, null, null));
    }
    return result;
  }

  public static boolean unknownFuture(Occurrence occurrence) {
    return occurrence.component() instanceof UnknownFuture;
  }

  private static final class UnknownFuture extends net.fortuna.ical4j.model.component.XComponent {
    UnknownFuture() {
      super("X-BUSYSTACK-QUERY-LIMIT");
    }
  }

  public static Occurrence single(
      CalendarContent.Parsed parsed,
      Component component,
      Temporal occurrenceStart,
      Temporal recurrenceId,
      ZoneId zone) {
    var fields = new HashMap<String, Temporal>();
    for (var p : component.getProperties())
      if (p instanceof DateProperty<?> d && d.getDate() != null)
        fields.put(p.getName(), d.getDate());
    Temporal start = TemporalSupport.date(component, DTSTART);
    Temporal anchor = occurrenceStart == null ? start : occurrenceStart;
    boolean duration = component.getProperty(DURATION).isPresent();
    if (anchor != null) {
      fields.put(DTSTART, anchor);
      for (String name : List.of(DTEND, DUE)) {
        Temporal end = TemporalSupport.date(component, name);
        if (end != null) fields.put(name, TemporalSupport.exactEnd(start, end, anchor, zone));
      }
      if (duration) {
        var p = component.getRequiredProperty(DURATION);
        fields.put(
            Component.VTODO.equals(component.getName()) ? DUE : DTEND,
            TemporalSupport.duration(parsed, p).addTo(anchor, zone));
      } else if (Component.VEVENT.equals(component.getName()) && !fields.containsKey(DTEND))
        fields.put(DTEND, anchor instanceof LocalDate d ? d.plusDays(1) : anchor);
    }
    Temporal originalStart = recurrenceId == null ? start : recurrenceId;
    Temporal originalEnd = fields.get(Component.VTODO.equals(component.getName()) ? DUE : DTEND);
    return new Occurrence(component, recurrenceId, fields, duration, originalStart, originalEnd);
  }

  private static ChronoUnit frequencyUnit(Recur<?> rule) {
    return switch (rule.getFrequency()) {
      case SECONDLY -> ChronoUnit.SECONDS;
      case MINUTELY -> ChronoUnit.MINUTES;
      case HOURLY -> ChronoUnit.HOURS;
      case DAILY -> ChronoUnit.DAYS;
      case WEEKLY -> ChronoUnit.WEEKS;
      case MONTHLY -> ChronoUnit.MONTHS;
      case YEARLY -> ChronoUnit.YEARS;
    };
  }

  private Temporal[] ruleBounds(Instant first, Instant last, Temporal seed, ZoneId floating) {
    ZoneId zone =
        seed instanceof ZonedDateTime z
            ? z.getZone()
            : seed instanceof OffsetDateTime o
                ? o.getOffset()
                : seed instanceof Instant ? ZoneOffset.UTC : floating;
    var rules = zone.getRules();
    int smallest = rules.getOffset(first).getTotalSeconds(), largest = smallest;
    var transition = rules.nextTransition(first.minusNanos(1));
    while (transition != null && !transition.getInstant().isAfter(last)) {
      budget.charge(1);
      smallest = Math.min(smallest, transition.getOffsetAfter().getTotalSeconds());
      largest = Math.max(largest, transition.getOffsetAfter().getTotalSeconds());
      transition = rules.nextTransition(transition.getInstant());
    }
    // Across an overlap, local start/end can run backwards. Search a conservative local
    // interval using every offset in this request, then apply exact instant predicates.
    var lower = LocalDateTime.ofInstant(first, ZoneOffset.ofTotalSeconds(smallest));
    var upper = LocalDateTime.ofInstant(last, ZoneOffset.ofTotalSeconds(largest));
    return seed instanceof LocalDate
        ? new Temporal[] {lower.toLocalDate(), upper.toLocalDate()}
        : new Temporal[] {lower, upper};
  }

  private static Temporal nativeRange(Instant value, Temporal seed, ZoneId zone) {
    if (seed instanceof LocalDate) return value.atZone(zone).toLocalDate();
    ZoneId actual =
        seed instanceof ZonedDateTime z
            ? z.getZone()
            : seed instanceof OffsetDateTime o
                ? o.getOffset()
                : seed instanceof Instant ? ZoneOffset.UTC : zone;
    return value.atZone(actual).toLocalDateTime();
  }

  private static Temporal resolveCandidate(Temporal candidate, Temporal seed, ZoneId zone) {
    if (seed instanceof LocalDate || seed instanceof LocalDateTime) return candidate;
    LocalDateTime local = (LocalDateTime) candidate;
    if (seed instanceof ZonedDateTime z) {
      // RFC5545 verified erratum4271 applies the pre-gap offset to nonexistent
      // local times. Java atZone shifts the gap by the same amount, preserving
      // that instant; the recurrence still counts toward COUNT.
      return local.atZone(z.getZone());
    }
    if (seed instanceof OffsetDateTime o) return local.atOffset(o.getOffset());
    return local.toInstant(ZoneOffset.UTC);
  }

  private static Object key(Temporal value, Temporal seed, ZoneId zone) {
    if (seed instanceof LocalDate) {
      if (!(value instanceof LocalDate))
        throw new QueryException(QueryException.Kind.INVALID_DATA, "Recurrence DATE types differ");
      return value;
    }
    if (seed instanceof LocalDateTime)
      return value instanceof LocalDateTime
          ? value
          : TemporalSupport.zoned(value, zone).toLocalDateTime();
    return TemporalSupport.instant(value, zone);
  }

  private static Temporal shifted(Temporal value, Temporal rid, Temporal replacement, ZoneId zone) {
    if (replacement == null) return value;
    if (value instanceof LocalDate d
        && rid instanceof LocalDate r
        && replacement instanceof LocalDate s) return d.plusDays(ChronoUnit.DAYS.between(r, s));
    LocalDateTime originalLocal = TemporalSupport.zoned(rid, zone).toLocalDateTime();
    LocalDateTime replacementLocal = TemporalSupport.zoned(replacement, zone).toLocalDateTime();
    Duration difference = Duration.between(originalLocal, replacementLocal);
    if (value instanceof LocalDateTime l) return l.plus(difference);
    ZonedDateTime original = TemporalSupport.zoned(value, zone);
    ZonedDateTime result = original.toLocalDateTime().plus(difference).atZone(original.getZone());
    if (value instanceof OffsetDateTime) return result.toOffsetDateTime();
    if (value instanceof Instant) return result.toInstant();
    return result;
  }

  private static Duration padding(
      CalendarContent.Parsed parsed,
      List<Component> components,
      ZoneId zone,
      boolean includeAlarms) {
    Duration max = Duration.ZERO;
    for (Component c : components) {
      Temporal start = TemporalSupport.date(c, DTSTART);
      if (start == null) continue;
      for (String name : List.of(DTEND, DUE, RECURRENCE_ID)) {
        Temporal end = TemporalSupport.date(c, name);
        if (end != null) {
          Duration reach =
              start instanceof LocalDate a && end instanceof LocalDate b
                  ? durationBound(
                      new TemporalSupport.CalendarDuration(
                          ChronoUnit.DAYS.between(a, b), Duration.ZERO))
                  : Duration.between(
                          TemporalSupport.instant(start, zone), TemporalSupport.instant(end, zone))
                      .abs();
          if (reach.compareTo(max) > 0) max = reach;
        }
      }
      if (start instanceof LocalDate && c.getName().equals(Component.VEVENT))
        max = max.compareTo(Duration.ofHours(60)) < 0 ? Duration.ofHours(60) : max;
      if (c.getProperty(DURATION).isPresent()) {
        Duration d =
            durationBound(TemporalSupport.duration(parsed, c.getRequiredProperty(DURATION)));
        if (d.compareTo(max) > 0) max = d;
      }
    }
    if (includeAlarms) {
      Duration alarmMax = Duration.ZERO;
      for (Component c : components)
        if (c instanceof ComponentContainer<?> nested)
          for (Component alarm : nested.getComponents()) {
            if (!alarm.getName().equals(Component.VALARM)) continue;
            Trigger trigger = alarm.getRequiredProperty(TRIGGER);
            if (trigger.isAbsolute()) continue;
            Duration reach = durationBound(TemporalSupport.duration(parsed, trigger));
            int repeat = alarm.getProperty(REPEAT).map(p -> ((Repeat) p).getCount()).orElse(0);
            if (repeat < 0)
              throw new QueryException(
                  QueryException.Kind.INVALID_DATA, "Negative alarm repeat count");
            if (repeat > 0)
              reach =
                  reach.plus(
                      durationBound(
                              TemporalSupport.duration(parsed, alarm.getRequiredProperty(DURATION)))
                          .multipliedBy(repeat));
            if (reach.compareTo(alarmMax) > 0) alarmMax = reach;
          }
      max = max.plus(alarmMax);
    }
    return max;
  }

  private static Duration durationBound(TemporalSupport.CalendarDuration duration) {
    try {
      // ZoneOffset permits -18h..+18h. One nominal addition can differ from elapsed days
      // by at most their 36h span, including custom VTIMEZONE definitions.
      return Duration.ofDays(Math.abs(duration.days()))
          .plus(duration.clock().abs())
          .plus(duration.days() == 0 ? Duration.ZERO : Duration.ofHours(36));
    } catch (ArithmeticException ex) {
      throw QueryException.limit();
    }
  }

  private static ZoneId zoneFor(CalendarContent.Parsed parsed, Property property, ZoneId fallback) {
    String id = property.getParameter(Parameter.TZID).orElseThrow().getValue();
    for (var c : parsed.calendar().getComponents(Component.VTIMEZONE))
      if (c.getRequiredProperty(TZID).getValue().equals(id))
        return FloatingTimezone.from((net.fortuna.ical4j.model.component.VTimeZone) c).zoneId();
    return TimeZoneRegistry.getGlobalZoneId(id);
  }
}
