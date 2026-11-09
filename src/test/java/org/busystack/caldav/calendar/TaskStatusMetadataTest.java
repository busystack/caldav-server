package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskStatusMetadataTest {
  private static String status(String fields) {
    return "STATUS:FAILED\r\nBEGIN:VSTATUS\r\nSTATUS:FAILED\r\n" + fields + "END:VSTATUS\r\n";
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "REASON:relative-reason\r\n",
        "REASON:/relative/reason\r\n",
        "REASON:\r\n",
        "REASON:https://example.invalid/with space\r\n",
        "SUBSTATE:NOT A TOKEN\r\n",
        "SUBSTATE:\r\n",
        "SUBSTATE:ERROR!\r\n"
      })
  void statusMetadataRequiresUriAndTokenSyntax(String fields) {
    assertThatThrownBy(() -> CalendarSemanticsTest.parse("VTODO", status(fields)))
        .isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "REASON:https://example.invalid/reason\r\n",
        "SUBSTATE:ERROR\r\n",
        "BEGIN:PARTICIPANT\r\nUID:participant\r\nPARTICIPANT-TYPE:ACTIVE\r\n"
            + "REASON:urn:reason:first\r\nREASON:urn:reason:second\r\nEND:PARTICIPANT\r\n",
        "BEGIN:PARTICIPANT\r\nUID:participant\r\nPARTICIPANT-TYPE:ACTIVE\r\n"
            + "PERCENT-COMPLETE:10\r\nPERCENT-COMPLETE:20\r\nEND:PARTICIPANT\r\n"
      })
  void metadataStaysInItsDefinedComponentAndCardinality(String fields) {
    assertThatThrownBy(() -> CalendarSemanticsTest.parse("VTODO", fields))
        .isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "REASON:https://example.invalid/reason?query=one%20two#fragment\r\n",
        "REASON:urn:example:reason\r\n",
        "SUBSTATE:OK\r\n",
        "SUBSTATE:ERROR\r\n",
        "SUBSTATE:SUSPENDED\r\n",
        "SUBSTATE:X-WAIT\r\n",
        "SUBSTATE:future-token\r\n"
      })
  void validStatusMetadataAndUnknownParametersArePreserved(String fields) {
    var parsed =
        CalendarSemanticsTest.parse(
            "VTODO",
            status(fields)
                + "ATTENDEE;REASON=opaque;SUBSTATE=opaque:mailto:synthetic@example.invalid\r\n");
    assertThat(parsed.calendar().copy().toString())
        .contains(fields.strip(), "REASON=opaque", "SUBSTATE=opaque");
  }
}
