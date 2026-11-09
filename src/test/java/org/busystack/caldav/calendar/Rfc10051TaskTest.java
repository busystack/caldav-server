package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import java.time.ZoneOffset;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.recurrence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Rfc10051TaskTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "ESTIMATED-DURATION:PT30M\r\n",
        "TASK-MODE:CLIENT\r\n",
        "ATTENDEE;PARTSTAT=FAILED:mailto:synthetic@example.invalid\r\n",
        "BEGIN:VSTATUS\r\nSTATUS:FAILED\r\nSUBSTATE:ERROR\r\nREASON:https://example.invalid/reason\r\nDTSTAMP:20261108T000000Z\r\nEND:VSTATUS\r\n"
      })
  void registeredTaskMetadataIsValid(String fields) {
    assertThatCode(() -> parse("VTODO", "STATUS:FAILED\r\nDURATION:PT1H\r\n" + fields))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PT1H", "P2D", "P1W"})
  void durationOnlyTasksStayUndatedInQueryAndExpansion(String value) {
    var parsed = parse("VTODO", "DURATION:" + value + "\r\n");
    var range = range("2030-01-01T00:00:00Z", "2030-01-02T00:00:00Z");
    assertThat(match(parsed, "VTODO", range)).isTrue();
    var recurrence = new CalendarRecurrence(QueryLimits.defaults());
    var expanded = recurrence.expand(parsed, range, FloatingTimezone.of(ZoneOffset.UTC));
    var task = expanded.getComponents().getFirst();
    assertThat(task.getProperty("DTSTART")).isEmpty();
    assertThat(task.getProperty("DUE")).isEmpty();
    assertThat(task.getRequiredProperty("DURATION").getValue()).isEqualTo(value);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"PENDING", "FAILED", "NEEDS-ACTION", "COMPLETED", "IN-PROCESS", "CANCELLED"})
  void currentStatusesAndTheirParametersSurviveCopiesAndRecurringExpansion(String status) {
    var parsed =
        parse(
            "VTODO",
            "DTSTART:20261108T090000Z\r\nDURATION:PT1H\r\n"
                + "RRULE:FREQ=DAILY;COUNT=2\r\nSTATUS;X-REASON=retained:"
                + status
                + "\r\n");
    var copy = parsed.calendar().copy();
    assertThat(copy.validate().hasErrors()).isFalse();
    var expanded =
        new CalendarRecurrence(QueryLimits.defaults())
            .expand(
                parsed,
                range("2026-11-08T00:00:00Z", "2026-11-10T00:00:00Z"),
                FloatingTimezone.of(ZoneOffset.UTC));
    assertThat(expanded.getComponents()).hasSize(2);
    for (var task : expanded.getComponents()) {
      assertThat(task.getRequiredProperty("STATUS").getValue()).isEqualTo(status);
      assertThat(task.getRequiredProperty("STATUS").getRequiredParameter("X-REASON").getValue())
          .isEqualTo("retained");
      assertThat(task.getRequiredProperty("DUE").getValue()).endsWith("100000Z");
    }
  }

  @Test
  void currentTaskMetadataAndStatusComponentsArePreservedWithoutAutomation() {
    var parsed =
        parse(
            "VTODO",
            "STATUS:FAILED\r\nDURATION:PT1H\r\n"
                + "ESTIMATED-DURATION:PT30M\r\nTASK-MODE:CLIENT\r\n"
                + "ATTENDEE;PARTSTAT=FAILED;REASON=\"https://example.invalid/reason\":mailto:synthetic@example.invalid\r\n"
                + "BEGIN:VSTATUS\r\nSTATUS:FAILED\r\nSUBSTATE:ERROR\r\n"
                + "REASON:https://example.invalid/reason\r\nDTSTAMP:20261108T000000Z\r\nEND:VSTATUS\r\n");
    assertThat(parsed.calendar().toString())
        .contains("STATUS:FAILED", "PARTSTAT=FAILED", "ESTIMATED-DURATION:PT30M", "BEGIN:VSTATUS");
    assertThat(parsed.calendar().copy().toString()).contains("STATUS:FAILED", "SUBSTATE:ERROR");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DURATION:PT0S",
        "DURATION:-PT1H",
        "DURATION:P1DT",
        "DURATION:P1W2D",
        "DUE:20261108T090000Z\r\nDURATION:PT1H",
        "DURATION:PT1H\r\nDURATION:PT2H",
        "STATUS:BOGUS",
        "STATUS:CONFIRMED",
        "STATUS:PENDING\r\nSTATUS:FAILED",
        "ESTIMATED-DURATION:-PT1H",
        "ESTIMATED-DURATION:PT0S",
        "STATUS:PENDING\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT1H\r\nEND:VALARM",
        "BEGIN:VSTATUS\r\nSTATUS:FAILED\r\nEND:VSTATUS",
        "STATUS:PENDING\r\nBEGIN:VSTATUS\r\nEND:VSTATUS"
      })
  void currentTaskSupportDoesNotAdmitInvalidData(String fields) {
    assertThatThrownBy(() -> parse("VTODO", fields + "\r\n"))
        .isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "FAILED", "BOGUS"})
  void taskStatusesDoNotBecomeValidEventStatuses(String status) {
    assertThatThrownBy(
            () -> parse("VEVENT", "DTSTART:20261108T090000Z\r\nSTATUS:" + status + "\r\n"))
        .isInstanceOf(CalendarValidationException.class);
  }
}
