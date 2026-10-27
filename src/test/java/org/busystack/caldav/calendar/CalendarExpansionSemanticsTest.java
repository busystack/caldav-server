package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import java.time.*;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.component.*;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.recurrence.*;
import org.junit.jupiter.api.Test;

class CalendarExpansionSemanticsTest {
  @Test
  void expandedUtcTimesRetainExtensionParameters() {
    var c =
        parse(
            "VEVENT",
            "DTSTART;TZID=America/New_York;X-ORIGINAL=kept:20260307T120000\r\nDTEND;TZID=America/New_York;X-END=retained:20260307T130000\r\nRRULE:FREQ=DAILY;COUNT=2\r\n");
    var expanded =
        new CalendarRecurrence(QueryLimits.defaults())
            .expand(
                c, range("2026-03-07T00:00:00Z", "2026-03-10T00:00:00Z"), FloatingTimezone.of(UTC));
    assertThat(expanded.getComponents()).hasSize(2);
    for (var event : expanded.getComponents()) {
      assertThat(
              event
                  .getRequiredProperty("DTSTART")
                  .getParameter("X-ORIGINAL")
                  .orElseThrow()
                  .getValue())
          .isEqualTo("kept");
      assertThat(event.getRequiredProperty("DTEND").getParameter("X-END").orElseThrow().getValue())
          .isEqualTo("retained");
      assertThat(event.getRequiredProperty("DTSTART").getParameter("TZID")).isEmpty();
    }
    assertThat(c.calendar().toString()).contains("TZID=America/New_York");
  }

  @Test
  void expandedAlarmInstantsPreserveNominalDayAndRepeatedTriggersAfterUtcConversion() {
    var c =
        parse(
            "VEVENT",
            "DTSTART;TZID=America/New_York:20260308T120000\r\nDURATION:PT1H\r\nRRULE:FREQ=DAILY;COUNT=2\r\nBEGIN:VALARM\r\nTRIGGER;X-TRIGGER=kept:-P1D\r\nREPEAT:1\r\nDURATION:PT24H\r\nACTION:DISPLAY\r\nDESCRIPTION:Stored reminder\r\nX-ALARM:preserved\r\nEND:VALARM\r\n");
    var expanded =
        new CalendarRecurrence(QueryLimits.defaults())
            .expand(
                c, range("2026-03-08T00:00:00Z", "2026-03-10T00:00:00Z"), FloatingTimezone.of(UTC));
    VEvent first = (VEvent) expanded.getComponents().getFirst();
    assertThat(first.getComponents()).hasSize(2);
    assertThat(first.toString())
        .contains("20260307T170000Z", "20260308T170000Z", "X-TRIGGER=kept", "X-ALARM:preserved")
        .doesNotContain("REPEAT:", "DURATION:", "TZID=");
    for (VAlarm alarm : first.getAlarms()) {
      var trigger =
          (net.fortuna.ical4j.model.property.Trigger) alarm.getRequiredProperty("TRIGGER");
      assertThat(trigger.isAbsolute()).isTrue();
    }
    assertThat(CalendarReports.copy(c).toString())
        .contains("TRIGGER;X-TRIGGER=kept:-P1D", "REPEAT:1", "DURATION:PT24H");
  }

  @Test
  void explicitDatetimeExtensionsAreNormalizedWithoutDanglingTimezoneReferences() {
    var parsed =
        parse(
            "VEVENT",
            "DTSTART;TZID=America/New_York:20260308T120000\r\nX-WHEN;VALUE=DATE-TIME;TZID=America/New_York;X-CUSTOM=retained:20260307T120000,20260308T120000\r\n");
    var expanded =
        new CalendarRecurrence(QueryLimits.defaults())
            .expand(
                parsed,
                range("2026-03-08T00:00:00Z", "2026-03-09T00:00:00Z"),
                FloatingTimezone.of(UTC));
    assertThat(expanded.toString())
        .contains("X-WHEN;VALUE=DATE-TIME;X-CUSTOM=retained:20260307T170000Z,20260308T160000Z")
        .doesNotContain("TZID=");
    assertThat(parsed.calendar().toString())
        .contains("X-WHEN;VALUE=DATE-TIME;TZID=America/New_York");
  }

  @Test
  void explicitPeriodExtensionsPreserveDurationMeaningWhileNormalizingTimezone() {
    var parsed =
        parse(
            "VEVENT",
            "DTSTART;TZID=America/New_York:20260308T120000\r\nX-PERIOD;VALUE=PERIOD;TZID=America/New_York:20260307T120000/PT24H\r\n");
    var expanded =
        new CalendarRecurrence(QueryLimits.defaults())
            .expand(
                parsed,
                range("2026-03-08T00:00:00Z", "2026-03-09T00:00:00Z"),
                FloatingTimezone.of(UTC));
    assertThat(expanded.toString())
        .contains("X-PERIOD;VALUE=PERIOD:20260307T170000Z/20260308T170000Z")
        .doesNotContain("TZID=");
  }

  @Test
  void largeRecurringResourcesAreRejectedBeforeUnboundedCloneAmplification() {
    var parsed =
        parse(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nRRULE:FREQ=DAILY;COUNT=100\r\nX-LARGE:"
                + "a".repeat(500000)
                + "\r\n");
    assertThatThrownBy(
            () ->
                new CalendarRecurrence(QueryLimits.defaults())
                    .expand(
                        parsed,
                        range("2026-01-01T00:00:00Z", "2026-04-11T00:00:00Z"),
                        FloatingTimezone.of(UTC)))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
  }

  @Test
  void expandedByteReservationIsCumulativeAcrossResourcesInOneReport() {
    var parsed =
        parse("VEVENT", "DTSTART:20260101T100000Z\r\nX-LARGE:" + "a".repeat(500000) + "\r\n");
    var recurrence = new CalendarRecurrence(QueryLimits.defaults());
    var range = range("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z");
    assertThatThrownBy(
            () -> {
              for (int index = 0; index < 10; index++)
                recurrence.expand(parsed, range, FloatingTimezone.of(UTC));
            })
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
  }
}
