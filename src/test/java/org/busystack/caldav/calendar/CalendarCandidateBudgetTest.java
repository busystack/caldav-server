package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import java.util.*;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.query.CalendarFilter.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarCandidateBudgetTest {
  private static final TimeRange WINDOW = range("2026-10-27T00:00:00Z", "2026-10-28T00:00:00Z");
  private static final QueryLimits SMALL = new QueryLimits(2, 900, 500000, 3660, 256);

  private static ComponentFilter root(ComponentFilter child) {
    return component("VCALENDAR", null, List.of(), List.of(child));
  }

  private static void selective(
      ComponentFilter filter, CalendarContent.Parsed unrelated, CalendarContent.Parsed matching) {
    var query = new CalendarQuery(SMALL);
    for (int i = 0; i < 5; i++) assertThat(query.matches(unrelated, filter, UTC)).isFalse();
    assertThat(query.matches(matching, filter, UTC)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"VEVENT", "VTODO"})
  void unrelatedOriginalsUseCandidateWorkRatherThanGeneratedInstanceCapacity(String type) {
    selective(
        root(component(type, WINDOW, List.of(), List.of())),
        parse(type, "DTSTART:20000101T100000Z\r\n"),
        parse(type, "DTSTART:20261027T100000Z\r\n"));
  }

  @Test
  void datePropertyAndAlarmFiltersKeepTheirOwnTimeRangeSemantics() {
    selective(
        root(
            component(
                "VEVENT",
                null,
                List.of(new PropertyFilter("DTSTART", false, WINDOW, null, List.of())),
                List.of())),
        parse("VEVENT", "DTSTART:20000101T100000Z\r\n"),
        parse("VEVENT", "DTSTART:20261027T100000Z\r\n"));
    String alarm =
        "DTSTART:20000101T100000Z\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Stored reminder\r\nTRIGGER;VALUE=DATE-TIME:%s\r\nEND:VALARM\r\n";
    selective(
        root(
            component(
                "VEVENT",
                null,
                List.of(),
                List.of(component("VALARM", WINDOW, List.of(), List.of())))),
        parse("VEVENT", alarm.formatted("20000101T100000Z")),
        parse("VEVENT", alarm.formatted("20261027T100000Z")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualMatchedResourcesHaveOneCumulativeBoundWithOrWithoutATimeRange(boolean ranged) {
    var query = new CalendarQuery(SMALL);
    var parsed = parse("VEVENT", "DTSTART:20261027T100000Z\r\n");
    var filter = root(component("VEVENT", ranged ? WINDOW : null, List.of(), List.of()));
    assertThat(query.matches(parsed, filter, UTC)).isTrue();
    assertThat(query.matches(parsed, filter, UTC)).isTrue();
    assertThatThrownBy(() -> query.matches(parsed, filter, UTC))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
  }

  @Test
  void nonmatchingOriginalsStillConsumeTheSharedCandidateWorkBound() {
    var query = new CalendarQuery(new QueryLimits(100, 900, 10, 3660, 256));
    var parsed = parse("VEVENT", "DTSTART:20000101T100000Z\r\n");
    var filter = root(component("VEVENT", WINDOW, List.of(), List.of()));
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 20; i++) query.matches(parsed, filter, UTC);
            })
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
  }

  @Test
  void generatedRecurrencesRetainTheGlobalInstanceBoundEvenWhenNoTextMatches() {
    var query = new CalendarQuery(new QueryLimits(3, 900, 500000, 3660, 256));
    var parsed =
        parse(
            "VEVENT",
            "DTSTART:20261027T100000Z\r\nRRULE:FREQ=DAILY;COUNT=2\r\nSUMMARY:Excluded\r\n");
    var filter =
        root(
            component(
                "VEVENT",
                range("2026-10-27T00:00:00Z", "2026-10-30T00:00:00Z"),
                List.of(
                    new PropertyFilter(
                        "SUMMARY", false, null, new TextMatch("Needle", null, false), List.of())),
                List.of()));
    assertThat(query.matches(parsed, filter, UTC)).isFalse();
    assertThatThrownBy(() -> query.matches(parsed, filter, UTC))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
  }

  @Test
  void freeBusyRetainsOnlyMatchingPeriodsWhileBoundingCandidateWork() {
    var unrelated = parse("VEVENT", "DTSTART:20000101T100000Z\r\nDURATION:PT1H\r\n");
    var matching = parse("VEVENT", "DTSTART:20261027T100000Z\r\nDURATION:PT1H\r\n");
    var result =
        new CalendarFreeBusy(SMALL)
            .query(
                List.of(unrelated, unrelated, unrelated, unrelated, unrelated, matching),
                WINDOW,
                UTC);
    assertThat(result)
        .containsExactly(
            new CalendarFreeBusy.BusyPeriod(
                java.time.Instant.parse("2026-10-27T10:00:00Z"),
                java.time.Instant.parse("2026-10-27T11:00:00Z"),
                CalendarFreeBusy.BusyType.BUSY));
  }
}
