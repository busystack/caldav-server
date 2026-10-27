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
    try {
      return expand(parsed, requested, timezone.zoneId());
    } finally {
      java.lang.ref.Reference.reachabilityFence(timezone);
    }
  }

  private List<Occurrence> expand(CalendarContent.Parsed parsed, TimeRange requested, ZoneId zone) {
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
        if (c.getRequiredProperty(RECURRENCE_ID).getParameter(Parameter.RANGE).isPresent())
          ranges.add(c);
      }
    ranges.sort(
        Comparator.comparing(
            c -> TemporalSupport.instant(TemporalSupport.date(c, RECURRENCE_ID), zone)));
    Duration padding = padding(parsed, components, zone);
    Instant first =
        requested.start() == null
            ? TemporalSupport.instant(seed, zone)
            : requested.start().minus(padding);
    Instant last =
        requested.end() == null
            ? first.plus(Duration.ofDays(budget.limits().maximumRangeDays()))
            : requested.end().plus(padding);
    if (!first.isBefore(last)) return List.of();
    if (Duration.between(first, last)
            .compareTo(
                Duration.ofDays(budget.limits().maximumRangeDays() + 2L)
                    .plus(padding.multipliedBy(2)))
        > 0) throw QueryException.limit();
    Temporal nativeFirst = nativeRange(first, seed, zone);
    Temporal nativeLast = nativeRange(last, seed, zone);
    var dates = new LinkedHashMap<Object, Temporal>();
    dates.put(key(seed, seed, zone), seed);
    var periodEnds = new HashMap<Object, Temporal>();
    for (var property : master.getProperties(RDATE)) {
      RDate<?> rdate = (RDate<?>) property;
      if (rdate.getPeriods().isPresent()) {
        for (var period : rdate.getPeriods().get()) {
          budget.charge(1);
          Temporal value = period.getStart();
          if (value instanceof LocalDateTime && property.getParameter(Parameter.TZID).isPresent())
            value = ((LocalDateTime) value).atZone(zoneFor(parsed, property, zone));
          Object id = key(value, seed, zone);
          dates.put(id, value);
          Temporal end = period.getEnd();
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
    for (var property : master.getProperties(RRULE)) {
      Recur<?> original = ((RRule<?>) property).getRecur();
      Temporal localSeed = nativeRange(TemporalSupport.instant(seed, zone), seed, zone);
      if (seed instanceof LocalDateTime || seed instanceof LocalDate) localSeed = seed;
      Temporal lower = original.getCount() > 0 ? localSeed : nativeFirst;
      budget.rule(original, localSeed, lower, nativeLast);
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
      Recur<Temporal> rule = new Recur.Builder<>(copied).count(null).until(until).build();
      try (var stream =
          rule.getDatesAsStream(
              localSeed, lower, nativeLast, budget.limits().maximumInstances() + 1)) {
        // Use the stream pipeline: iCal4j's spliterator may advance without emitting a value
        // while passing candidates before the requested period. A direct iterator stops early.
        stream
            .peek(candidate -> budget.instance())
            .map(candidate -> resolveCandidate(candidate, seed, zone))
            .filter(Objects::nonNull)
            .limit(original.getCount() > 0 ? original.getCount() : Long.MAX_VALUE)
            .forEach(resolved -> dates.putIfAbsent(key(resolved, seed, zone), resolved));
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
      if (z.getZone().getRules().getValidOffsets(local).isEmpty()) return null;
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
      CalendarContent.Parsed parsed, List<Component> components, ZoneId zone) {
    Duration max = Duration.ZERO;
    for (Component c : components) {
      Temporal start = TemporalSupport.date(c, DTSTART);
      if (start == null) continue;
      for (String name : List.of(DTEND, DUE, RECURRENCE_ID)) {
        Temporal end = TemporalSupport.date(c, name);
        if (end != null)
          max =
              max.compareTo(
                          Duration.between(
                                  TemporalSupport.instant(start, zone),
                                  TemporalSupport.instant(end, zone))
                              .abs())
                      < 0
                  ? Duration.between(
                          TemporalSupport.instant(start, zone), TemporalSupport.instant(end, zone))
                      .abs()
                  : max;
      }
      if (c.getProperty(DURATION).isPresent()) {
        Temporal end =
            TemporalSupport.duration(parsed, c.getRequiredProperty(DURATION)).addTo(start, zone);
        Duration d =
            Duration.between(
                    TemporalSupport.instant(start, zone), TemporalSupport.instant(end, zone))
                .abs();
        if (d.compareTo(max) > 0) max = d;
      }
    }
    return max;
  }

  private static ZoneId zoneFor(CalendarContent.Parsed parsed, Property property, ZoneId fallback) {
    String id = property.getParameter(Parameter.TZID).orElseThrow().getValue();
    for (var c : parsed.calendar().getComponents(Component.VTIMEZONE))
      if (c.getRequiredProperty(TZID).getValue().equals(id))
        return FloatingTimezone.from((net.fortuna.ical4j.model.component.VTimeZone) c).zoneId();
    return TimeZoneRegistry.getGlobalZoneId(id);
  }
}
