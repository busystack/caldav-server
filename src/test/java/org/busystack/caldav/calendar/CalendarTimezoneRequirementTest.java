package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CalendarTimezoneRequirementTest {
  private static byte[] resource(String content) {
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack timezone requirement fixture//EN\r\n"
            + content
            + "END:VCALENDAR\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void builtinTimezoneNamesDoNotSubstituteForRequiredEmbeddedDefinitions() {
    var bytes =
        resource(
            "BEGIN:VEVENT\r\nUID:timezone-required\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=America/New_York:20260308T120000\r\nEND:VEVENT\r\n");
    assertThatThrownBy(() -> CalendarContent.parse(bytes, "VEVENT"))
        .isInstanceOfSatisfying(
            CalendarValidationException.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(CalendarValidationException.Reason.INVALID_OBJECT));
  }

  @Test
  void explicitDefinitionAfterTheObjectRemainsAcceptedAndAuthoritative() {
    var bytes =
        resource(
            "BEGIN:VEVENT\r\nUID:timezone-required\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=Custom/Offset:20260308T120000\r\nEND:VEVENT\r\nBEGIN:VTIMEZONE\r\nTZID:Custom/Offset\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0530\r\nTZOFFSETTO:+0530\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n");
    var parsed = CalendarContent.parse(bytes, "VEVENT");
    assertThat(CalendarSemanticsTest.point(parsed, "VEVENT", "2026-03-08T06:30:00Z")).isTrue();
    assertThat(parsed.calendar().getComponents()).hasSize(2);
  }

  @Test
  void timezoneReferencesOnNestedAndExtensionPropertiesAreAlsoRequired() {
    var bytes =
        resource(
            "BEGIN:VEVENT\r\nUID:timezone-required\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20260308T120000Z\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Stored reminder\r\nTRIGGER:-PT15M\r\nX-WHEN;VALUE=DATE-TIME;TZID=America/New_York:20260308T120000\r\nEND:VALARM\r\nEND:VEVENT\r\n");
    assertThatThrownBy(() -> CalendarContent.parse(bytes, "VEVENT"))
        .isInstanceOfSatisfying(
            CalendarValidationException.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(CalendarValidationException.Reason.INVALID_OBJECT));
  }
}
