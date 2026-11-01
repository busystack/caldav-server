package org.busystack.caldav.calendar.query;

import java.time.*;
import java.time.temporal.Temporal;
import java.time.zone.*;
import java.util.*;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.component.Observance;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.*;
import org.busystack.caldav.calendar.recurrence.WorkBudget;

/**
 * Application adaptation from chronological iCalendar observance onsets to Java zone rules. Only
 * recurrence generation is delegated to iCal4j. A perpetual tail is proven against the complete
 * Gregorian cycle after all finite eras and explicit dates, never selected by today's observance.
 */
final class ObservanceZoneRules {
  record Result(ZoneRules rules, Instant coverageEnd) {}

  private record Onset(Instant instant, ZoneOffset from, ZoneOffset to, boolean standard) {}

  private record Tail(ZoneOffsetTransitionRule rule, boolean standard) {}

  private record History(
      ZoneOffset base,
      ZoneOffset standard,
      ZoneOffset wall,
      List<ZoneOffsetTransition> standards,
      List<ZoneOffsetTransition> walls) {}

  private final WorkBudget budget;

  ObservanceZoneRules(WorkBudget budget) {
    this.budget = budget;
  }

  Result build(
      VTimeZone zone,
      Map<Observance, LocalDateTime> finiteEnds,
      LocalDateTime boundedEnd,
      boolean annual) {
    int finalEra = 1;
    for (Observance observance : zone.getComponents()) {
      finalEra = Math.max(finalEra, start(observance).getYear());
      for (var date : observance.getProperties(Property.RDATE))
        for (Temporal value : ((RDate<?>) date).getDates())
          finalEra = Math.max(finalEra, ((LocalDateTime) value).getYear());
      for (var property : observance.getProperties(Property.RRULE)) {
        var rule = ((RRule<?>) property).getRecur();
        if (rule.getUntil() != null || rule.getCount() > 0)
          finalEra = Math.max(finalEra, finiteEnds.get(observance).getYear());
      }
    }
    // The complete history ends after two whole years in the final recurrence regime. This is
    // an era boundary, not a fixed extension of the old materialization window.
    LocalDateTime tailStart = LocalDate.of(finalEra + 3, 1, 1).atStartOfDay();
    if (annual) {
      History history = history(onsets(zone, finiteEnds, tailStart));
      List<Tail> candidates = tails(zone, history.standard());
      List<ZoneOffsetTransitionRule> proven =
          prove(candidates, history.wall(), tailStart.getYear());
      if (proven != null)
        return new Result(
            ZoneRules.of(
                history.base(), history.base(), history.standards(), history.walls(), proven),
            null);
    }
    History history = history(onsets(zone, finiteEnds, boundedEnd));
    return new Result(
        ZoneRules.of(
            history.base(), history.base(), history.standards(), history.walls(), List.of()),
        boundedEnd.toInstant(ZoneOffset.MAX));
  }

  private List<Onset> onsets(
      VTimeZone zone, Map<Observance, LocalDateTime> finiteEnds, LocalDateTime infiniteEnd) {
    List<Onset> result = new ArrayList<>();
    for (Observance observance : zone.getComponents()) {
      LocalDateTime start = start(observance);
      ZoneOffset from =
          ((TzOffsetFrom) observance.getRequiredProperty(Property.TZOFFSETFROM)).getOffset();
      ZoneOffset to =
          ((TzOffsetTo) observance.getRequiredProperty(Property.TZOFFSETTO)).getOffset();
      Set<LocalDateTime> dates = new TreeSet<>();
      dates.add(start);
      for (var property : observance.getProperties(Property.RDATE))
        for (Temporal date : ((RDate<?>) property).getDates()) dates.add((LocalDateTime) date);
      for (var property : observance.getProperties(Property.RRULE)) {
        var recur = ((RRule<?>) property).getRecur();
        LocalDateTime end =
            recur.getUntil() != null || recur.getCount() > 0
                ? finiteEnds.get(observance)
                : infiniteEnd;
        budget.rule(recur, start, start, end);
        @SuppressWarnings("unchecked")
        var rule = (Recur<OffsetDateTime>) (Recur<?>) recur;
        try (var generated =
            rule.getDatesAsStream(
                start.atOffset(from),
                start.atOffset(from),
                end.atOffset(from),
                budget.limits().maximumInstances() + 1)) {
          generated
              .filter(date -> date.toLocalDateTime().isBefore(end))
              .forEach(
                  date -> {
                    budget.instance();
                    dates.add(date.toLocalDateTime());
                  });
        }
      }
      for (LocalDateTime date : dates) {
        budget.charge(1);
        result.add(
            new Onset(date.toInstant(from), from, to, observance.getName().equals("STANDARD")));
      }
    }
    result.sort(Comparator.comparing(Onset::instant));
    return result;
  }

  private static History history(List<Onset> onsets) {
    if (onsets.isEmpty()) throw invalid("Timezone has no observance");
    ZoneOffset base = onsets.getFirst().from(), wall = base, standard = base;
    List<ZoneOffsetTransition> walls = new ArrayList<>(), standards = new ArrayList<>();
    Onset previous = null;
    for (Onset onset : onsets) {
      if (previous != null
          && onset.instant().equals(previous.instant())
          && !onset.to().equals(previous.to())) throw invalid("Conflicting timezone onsets");
      if (onset.standard() && !standard.equals(onset.to())) {
        standards.add(
            ZoneOffsetTransition.of(
                LocalDateTime.ofInstant(onset.instant(), standard), standard, onset.to()));
        standard = onset.to();
      }
      if (!wall.equals(onset.to())) {
        walls.add(
            ZoneOffsetTransition.of(
                LocalDateTime.ofInstant(onset.instant(), wall), wall, onset.to()));
        wall = onset.to();
      }
      previous = onset;
    }
    return new History(base, standard, wall, List.copyOf(standards), List.copyOf(walls));
  }

  private static List<Tail> tails(VTimeZone zone, ZoneOffset standard) {
    List<Tail> result = new ArrayList<>();
    for (Observance observance : zone.getComponents()) {
      LocalDateTime start = start(observance);
      ZoneOffset from =
          ((TzOffsetFrom) observance.getRequiredProperty(Property.TZOFFSETFROM)).getOffset();
      ZoneOffset to =
          ((TzOffsetTo) observance.getRequiredProperty(Property.TZOFFSETTO)).getOffset();
      for (var property : observance.getProperties(Property.RRULE)) {
        var rule = ((RRule<?>) property).getRecur();
        if (rule.getUntil() != null || rule.getCount() > 0 || from.equals(to)) continue;
        Month month =
            rule.getMonthList().isEmpty()
                ? start.getMonth()
                : Month.of(rule.getMonthList().getFirst().getMonthOfYear());
        int day =
            rule.getMonthDayList().isEmpty()
                ? start.getDayOfMonth()
                : rule.getMonthDayList().getFirst();
        DayOfWeek weekday = null;
        if (!rule.getDayList().isEmpty()) {
          var byday = rule.getDayList().getFirst();
          int ordinal = byday.getOffset();
          day = ordinal > 0 ? 1 + 7 * (ordinal - 1) : -1 - 7 * (-ordinal - 1);
          weekday =
              switch (byday.getDay()) {
                case MO -> DayOfWeek.MONDAY;
                case TU -> DayOfWeek.TUESDAY;
                case WE -> DayOfWeek.WEDNESDAY;
                case TH -> DayOfWeek.THURSDAY;
                case FR -> DayOfWeek.FRIDAY;
                case SA -> DayOfWeek.SATURDAY;
                case SU -> DayOfWeek.SUNDAY;
              };
        }
        var transition =
            ZoneOffsetTransitionRule.of(
                month,
                day,
                weekday,
                start.toLocalTime(),
                false,
                ZoneOffsetTransitionRule.TimeDefinition.WALL,
                standard,
                from,
                to);
        result.add(new Tail(transition, observance.getName().equals("STANDARD")));
      }
    }
    return result;
  }

  /** A Java lastRules array needs a stable chronological rule order and continuous offsets. */
  private List<ZoneOffsetTransitionRule> prove(
      List<Tail> candidates, ZoneOffset initial, int firstYear) {
    if (candidates.size() > 16) return null;
    List<Integer> expected = null;
    ZoneOffset wall = initial;
    for (int year = firstYear; year <= firstYear + 400; year++) {
      int currentYear = year;
      List<Integer> indexes = new ArrayList<>();
      for (int index = 0; index < candidates.size(); index++) indexes.add(index);
      indexes.sort(
          Comparator.comparing(
              index -> candidates.get(index).rule().createTransition(currentYear).getInstant()));
      List<Integer> effective = new ArrayList<>();
      Instant previous = null;
      ZoneOffset previousTo = null;
      for (int index : indexes) {
        budget.charge(1);
        Tail candidate = candidates.get(index);
        if (candidate.standard()
            && !candidate.rule().getStandardOffset().equals(candidate.rule().getOffsetAfter()))
          return null;
        var transition = candidate.rule().createTransition(year);
        if (previous != null
            && previous.equals(transition.getInstant())
            && !previousTo.equals(transition.getOffsetAfter())) return null;
        previous = transition.getInstant();
        previousTo = transition.getOffsetAfter();
        if (wall.equals(transition.getOffsetAfter())) continue;
        if (!wall.equals(transition.getOffsetBefore())) return null;
        wall = transition.getOffsetAfter();
        effective.add(index);
      }
      if (expected == null) expected = effective;
      else if (!expected.equals(effective)) return null;
    }
    if (expected == null) return List.of();
    return expected.stream().map(index -> candidates.get(index).rule()).toList();
  }

  private static LocalDateTime start(Observance observance) {
    var value = ((DtStart<?>) observance.getRequiredProperty(Property.DTSTART)).getDate();
    if (!(value instanceof LocalDateTime date)) throw invalid("Timezone onset must be local");
    return date;
  }

  private static QueryException invalid(String message) {
    return new QueryException(QueryException.Kind.INVALID_DATA, message);
  }
}
