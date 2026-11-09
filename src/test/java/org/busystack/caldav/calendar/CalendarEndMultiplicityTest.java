package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarEndMultiplicityTest {
  @ParameterizedTest
  @ValueSource(strings = {"DTEND:20261109T100000Z", "DTEND:20261109T110000Z"})
  void repeatedEndsHaveNoInventedSingletonRestrictionAndAllArePreserved(String second) {
    var parsed =
        CalendarSemanticsTest.parse(
            "VEVENT", "DTSTART:20261109T090000Z\r\nDTEND:20261109T100000Z\r\n" + second + "\r\n");
    assertThat(parsed.calendar().getComponents().getFirst().getProperties("DTEND")).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DTEND:20261109T080000Z",
        "DTEND:20261109T090000Z",
        "DTEND;VALUE=DATE:20261110",
        "DTEND:20261109T110000"
      })
  void everyEndMustSatisfyTheExplicitDtstartConstraints(String second) {
    assertThatThrownBy(
            () ->
                CalendarSemanticsTest.parse(
                    "VEVENT",
                    "DTSTART:20261109T090000Z\r\nDTEND:20261109T100000Z\r\n" + second + "\r\n"))
        .isInstanceOf(CalendarValidationException.class);
  }
}
