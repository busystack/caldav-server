package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import java.time.*;
import java.util.*;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.query.CalendarFilter.*;
import org.junit.jupiter.api.Test;

class CalendarEdgeSemanticsTest {
  @Test
  void relativeAlarmOutsideEventWindowStillMatchesRecurringParent() {
    var c =
        parse(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nRRULE:FREQ=DAILY;COUNT=5\r\nBEGIN:VALARM\r\nTRIGGER:-P2D\r\nACTION:DISPLAY\r\nDESCRIPTION:Stored reminder\r\nEND:VALARM\r\n");
    var alarm =
        component(
            "VALARM", range("2026-01-02T10:00:00Z", "2026-01-02T10:01:00Z"), List.of(), List.of());
    var filter =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(component("VEVENT", null, List.of(), List.of(alarm))));
    assertThat(new CalendarQuery(QueryLimits.defaults()).matches(c, filter, UTC)).isTrue();
  }

  @Test
  void repeatedAlarmCanReachAfterTheParentsStartQueryWindow() {
    var c =
        parse(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nRRULE:FREQ=DAILY;COUNT=5\r\nBEGIN:VALARM\r\nTRIGGER:PT1H\r\nREPEAT:2\r\nDURATION:P2D\r\nACTION:DISPLAY\r\nDESCRIPTION:Stored reminder\r\nEND:VALARM\r\n");
    var alarm =
        component(
            "VALARM", range("2026-01-07T11:00:00Z", "2026-01-07T11:01:00Z"), List.of(), List.of());
    var filter =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(component("VEVENT", null, List.of(), List.of(alarm))));
    assertThat(new CalendarQuery(QueryLimits.defaults()).matches(c, filter, UTC)).isTrue();
  }

  @Test
  void periodRdatesDistinguishNominalAndExactDaysAcrossDst() {
    String event =
        "DTSTART;TZID=America/New_York:20260301T120000\r\nRDATE;TZID=America/New_York;VALUE=PERIOD:20260307T120000/";
    assertThat(point(parse("VEVENT", event + "P1D\r\n"), "VEVENT", "2026-03-08T16:30:00Z"))
        .isFalse();
    assertThat(point(parse("VEVENT", event + "PT24H\r\n"), "VEVENT", "2026-03-08T16:30:00Z"))
        .isTrue();
  }

  @Test
  void openStartQueryIncludesExplicitRdateBeforeDtstart() {
    var c =
        parse(
            "VEVENT",
            "DTSTART:20260110T100000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nRDATE:20260101T100000Z\r\n");
    assertThat(match(c, "VEVENT", range(null, "2026-01-02T00:00:00Z"))).isTrue();
  }

  @Test
  void staticPropertyRangeDoesNotMaterializeAnUnrelatedRecurrenceWindow() {
    var c =
        parse(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nRRULE:FREQ=SECONDLY\r\nCREATED:20200101T000000Z\r\n");
    var property =
        new PropertyFilter(
            "CREATED",
            false,
            range("2020-01-01T00:00:00Z", "2020-01-02T00:00:00Z"),
            null,
            List.of());
    var filter =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(component("VEVENT", null, List.of(property), List.of())));
    assertThat(new CalendarQuery(QueryLimits.defaults()).matches(c, filter, UTC)).isTrue();
  }

  @Test
  void statusTransparencyAndFreebusyTypesAreCaseInsensitive() {
    var cancelled =
        parse(
            "VEVENT", "DTSTART:20260101T090000Z\r\nDTEND:20260101T120000Z\r\nSTATUS:cancelled\r\n");
    var transparent =
        parse(
            "VEVENT",
            "DTSTART:20260101T090000Z\r\nDTEND:20260101T120000Z\r\nTRANSP:transparent\r\n");
    var freebusy =
        parse(
            "VFREEBUSY",
            "DTSTART:20260101T000000Z\r\nDTEND:20260102T000000Z\r\nFREEBUSY;FBTYPE=busy-tentative:20260101T090000Z/20260101T100000Z\r\n");
    var result =
        new CalendarFreeBusy(QueryLimits.defaults())
            .query(
                List.of(cancelled, transparent, freebusy),
                range("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"),
                UTC);
    assertThat(result)
        .containsExactly(
            new CalendarFreeBusy.BusyPeriod(
                Instant.parse("2026-01-01T09:00:00Z"),
                Instant.parse("2026-01-01T10:00:00Z"),
                CalendarFreeBusy.BusyType.BUSY_TENTATIVE));
  }

  @Test
  void rangeAndAlarmRelatedEnumerationsAreCaseInsensitive() {
    var c =
        parseComponents(
            "VEVENT",
            event(null, "20260101T100000Z", "RRULE:FREQ=DAILY;COUNT=5\r\nDURATION:PT1H\r\n")
                + event(
                    ";RANGE=thisandfuture:20260103T100000Z",
                    "20260103T120000Z",
                    "DURATION:PT2H\r\nBEGIN:VALARM\r\nTRIGGER;RELATED=end:-PT10M\r\nACTION:DISPLAY\r\nDESCRIPTION:Stored reminder\r\nEND:VALARM\r\n"));
    var alarm =
        component(
            "VALARM", range("2026-01-04T13:50:00Z", "2026-01-04T13:51:00Z"), List.of(), List.of());
    var filter =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(component("VEVENT", null, List.of(), List.of(alarm))));
    assertThat(new CalendarQuery(QueryLimits.defaults()).matches(c, filter, UTC)).isTrue();
    assertThat(new TextMatch("end", "i;octet", false).matches("END")).isFalse();
  }

  @Test
  void nominalDurationsUseAConservativeWindowAcrossDifferentDstSeasons() {
    var c =
        parse(
            "VEVENT",
            "DTSTART;TZID=America/New_York:20260307T120000\r\nDURATION:P1D\r\nRRULE:FREQ=DAILY\r\n");
    assertThat(point(c, "VEVENT", "2026-11-01T16:30:00Z")).isTrue();
  }

  @Test
  void localRecurrenceSearchBoundsDoNotReverseAcrossFallBackOverlap() {
    var c =
        parse(
            "VEVENT",
            "DTSTART;TZID=America/New_York:20261031T015955\r\nRRULE:FREQ=DAILY;COUNT=3\r\n");
    assertThat(match(c, "VEVENT", range("2026-11-01T05:59:50Z", "2026-11-01T06:00:10Z"))).isTrue();
  }

  @Test
  void smallFiniteSeriesFromLongAgoDoesNotRequireSearchingEveryInterveningDay() {
    var parsed =
        parse(
            "VEVENT",
            "DTSTART:20000101T100000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nRDATE:20260101T100000Z\r\n");
    assertThat(point(parsed, "VEVENT", "2026-01-01T10:00:00Z")).isTrue();
    assertThat(point(parsed, "VEVENT", "2026-01-02T10:00:00Z")).isFalse();
  }

  @Test
  void finiteCountUsesPreGapOffsetEvenWhenTheQueryIsMuchLater() {
    var parsed =
        parse(
            "VEVENT",
            "DTSTART;TZID=America/New_York:20060331T023000\r\nRRULE:FREQ=DAILY;COUNT=3\r\n");
    assertThat(point(parsed, "VEVENT", "2006-04-02T07:30:00Z")).isTrue();
    assertThat(point(parsed, "VEVENT", "2006-04-03T06:30:00Z")).isFalse();
    assertThat(point(parsed, "VEVENT", "2026-01-01T10:00:00Z")).isFalse();
  }

  @Test
  void expiredUntilSeriesAndFutureAnchorsDoNotCauseUnnecessaryHistoricalSearches() {
    var expired =
        parse(
            "VEVENT", "DTSTART:20000101T100000Z\r\nRRULE:FREQ=SECONDLY;UNTIL=20000101T100003Z\r\n");
    assertThat(point(expired, "VEVENT", "2026-01-01T10:00:00Z")).isFalse();
    var future = parse("VEVENT", "DTSTART:20300101T100000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\n");
    assertThat(point(future, "VEVENT", "2026-01-01T10:00:00Z")).isFalse();
  }

  @Test
  void invalidMonthDatesStillDoNotConsumeCountUnderVerifiedErratum4271() {
    var parsed = parse("VEVENT", "DTSTART:20260131T100000Z\r\nRRULE:FREQ=MONTHLY;COUNT=3\r\n");
    assertThat(point(parsed, "VEVENT", "2026-03-31T10:00:00Z")).isTrue();
    assertThat(point(parsed, "VEVENT", "2026-05-31T10:00:00Z")).isTrue();
    assertThat(point(parsed, "VEVENT", "2026-07-31T10:00:00Z")).isFalse();
  }
}
