package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.busystack.caldav.calendar.query.*;
import org.junit.jupiter.api.Test;

class CalendarFreeBusySourcesTest {
  @Test
  void eachCollectionUsesItsOwnFloatingTimezone() {
    var parsed =
        CalendarContent.parse(
            ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Own source fixtures//EN\r\nBEGIN:VEVENT\r\nUID:floating\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20260101T120000\r\nDURATION:PT1H\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "VEVENT");
    var range =
        new TimeRange(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
    var sources =
        List.of(
            new CalendarFreeBusy.Source(parsed, FloatingTimezone.of(ZoneOffset.UTC)),
            new CalendarFreeBusy.Source(parsed, FloatingTimezone.of(ZoneId.of("Asia/Kolkata"))));
    var result = new CalendarFreeBusy(QueryLimits.defaults()).querySources(sources, range);
    assertThat(result)
        .containsExactly(
            new CalendarFreeBusy.BusyPeriod(
                Instant.parse("2026-01-01T06:30:00Z"),
                Instant.parse("2026-01-01T07:30:00Z"),
                CalendarFreeBusy.BusyType.BUSY),
            new CalendarFreeBusy.BusyPeriod(
                Instant.parse("2026-01-01T12:00:00Z"),
                Instant.parse("2026-01-01T13:00:00Z"),
                CalendarFreeBusy.BusyType.BUSY));
  }

  @Test
  void oneBudgetCoversEverySourceIncludingTasksSkippedForFreeBusy() {
    var parsed =
        CalendarContent.parse(
            ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Own source fixtures//EN\r\nBEGIN:VEVENT\r\nUID:event\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20260101T120000Z\r\nDURATION:PT1H\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "VEVENT");
    var source = new CalendarFreeBusy.Source(parsed, FloatingTimezone.of(ZoneOffset.UTC));
    var range =
        new TimeRange(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
    var limited = new QueryLimits(2, 900, 500000, 3660, 256);
    assertThat(new CalendarFreeBusy(limited).querySources(List.of(source), range)).hasSize(1);
    assertThatThrownBy(
            () ->
                new CalendarFreeBusy(limited).querySources(List.of(source, source, source), range))
        .isInstanceOf(QueryException.class);
    assertThat(
            new CalendarFreeBusy(new QueryLimits(5, 900, 500000, 3660, 256))
                .querySources(List.of(source, source, source), range))
        .hasSize(1);
  }
}
