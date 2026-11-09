package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CalendarTemporalFormTest {
  static Stream<Arguments> fields() {
    return Stream.of("DTEND", "DUE", "RECURRENCE-ID")
        .flatMap(field -> Stream.of(true, false).map(floating -> Arguments.of(field, floating)));
  }

  @ParameterizedTest
  @MethodSource("fields")
  void linkedDatesDoNotMixFloatingAndAbsoluteForms(String field, boolean floatingStart) {
    String start = "20261109T090000" + (floatingStart ? "" : "Z");
    String end = "20261109T100000" + (floatingStart ? "Z" : "");
    assertThatThrownBy(
            () ->
                CalendarSemanticsTest.parse(
                    field.equals("DUE") ? "VTODO" : "VEVENT",
                    "DTSTART:" + start + "\r\n" + field + ":" + end + "\r\n"))
        .isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @MethodSource("fields")
  void matchingFloatingAndAbsoluteFormsRemainValid(String field, boolean floatingStart) {
    String suffix = floatingStart ? "" : "Z";
    assertThatCode(
            () ->
                CalendarSemanticsTest.parse(
                    field.equals("DUE") ? "VTODO" : "VEVENT",
                    "DTSTART:20261109T090000"
                        + suffix
                        + "\r\n"
                        + field
                        + ":20261109T100000"
                        + suffix
                        + "\r\n"))
        .doesNotThrowAnyException();
  }
}
