package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.recurrence.*;
import org.junit.jupiter.api.Test;

class ThunderbirdCalendarFixturesTest {
  private static byte[] fixture(String name) throws IOException {
    try (var resource =
        ThunderbirdCalendarFixturesTest.class.getResourceAsStream("/calendar/clients/" + name)) {
      assertThat(resource).isNotNull();
      return resource.readAllBytes();
    }
  }

  @Test
  void nativeRecurringEventRetainsAlarmAndUnknownProperties() throws IOException {
    byte[] bytes = fixture("thunderbird-event.ics");
    String text = new String(bytes, StandardCharsets.UTF_8);
    assertThat(text.replace("\r\n", "")).doesNotContain("\n");
    var parsed = CalendarContent.parse(bytes, "VEVENT");
    var expanded =
        new CalendarRecurrence(QueryLimits.defaults())
            .expand(
                parsed,
                range("2026-10-27T00:00:00Z", "2026-10-31T00:00:00Z"),
                FloatingTimezone.of(UTC));
    assertThat(expanded.getComponents()).hasSize(3);
    assertThat(expanded.toString())
        .contains(
            "X-NATIVE-CLIENT:preserved",
            "X-MOZ-GENERATION:1",
            "TRIGGER;VALUE=DATE-TIME:20261027T094500Z",
            "BEGIN:VALARM");
    assertThat(point(parsed, "VEVENT", "2026-10-29T10:30:00Z")).isTrue();
    assertThat(point(parsed, "VEVENT", "2026-10-30T10:30:00Z")).isFalse();
    assertThat(CalendarReports.copy(parsed).toString())
        .contains("PRODID:-//Mozilla.org/NONSGML Mozilla Calendar V1.1//EN");
  }

  @Test
  void nativeCompletedUndatedTaskUsesTaskCompletionRangeSemantics() throws IOException {
    var parsed = CalendarContent.parse(fixture("thunderbird-task.ics"), "VTODO");
    var completed =
        (net.fortuna.ical4j.model.property.DateProperty<?>)
            parsed.calendar().getComponents().getFirst().getRequiredProperty("COMPLETED");
    Instant at = TemporalSupport.instant(completed.getDate(), UTC);
    assertThat(match(parsed, "VTODO", new TimeRange(at.minusSeconds(1), at))).isTrue();
    assertThat(match(parsed, "VTODO", new TimeRange(at.plusSeconds(1), at.plusSeconds(2))))
        .isFalse();
    assertThat(CalendarReports.copy(parsed).toString())
        .contains("STATUS:COMPLETED", "PERCENT-COMPLETE:100", "PRIORITY:2", "X-MOZ-GENERATION:1");
  }
}
