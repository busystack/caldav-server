package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.parse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParticipantInterchangeTest {
  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "active", "INACTIVE", "SPONSOR"})
  void participantTypeAndParametersArePreservedThroughTheOfficialMutableModel(String type) {
    var parsed =
        parse(
            "VEVENT",
            "DTSTART:20260120T120000Z\r\nBEGIN:PARTICIPANT\r\n"
                + "UID:participant\r\nPARTICIPANT-TYPE;X-RETAIN=opaque:"
                + type
                + "\r\nCALENDAR-ADDRESS:mailto:synthetic@example.invalid\r\nEND:PARTICIPANT\r\n");
    String report = parsed.calendar().copy().toString();
    assertThat(report)
        .contains(
            "BEGIN:PARTICIPANT",
            "UID:participant",
            "PARTICIPANT-TYPE;X-RETAIN=opaque:" + type,
            "CALENDAR-ADDRESS:mailto:synthetic@example.invalid");
  }

  @Test
  void currentParticipantAndEventStatusComponentsRemainDataRatherThanAutomation() {
    for (String component : new String[] {"VEVENT", "VTODO"}) {
      String fields =
          (component.equals("VEVENT")
                  ? "DTSTART:20260120T120000Z\r\nSTATUS:CONFIRMED\r\n"
                  : "STATUS:FAILED\r\n")
              + "BEGIN:PARTICIPANT\r\nUID:participant\r\nPARTICIPANT-TYPE:ACTIVE\r\n"
              + "STATUS:FAILED\r\nPERCENT-COMPLETE:25\r\nREASON:https://example.invalid/reason\r\n"
              + "BEGIN:VSTATUS\r\nSTATUS:FAILED\r\nSUBSTATE:ERROR\r\nEND:VSTATUS\r\nEND:PARTICIPANT\r\n"
              + "BEGIN:VSTATUS\r\nSTATUS:"
              + (component.equals("VEVENT") ? "CONFIRMED" : "FAILED")
              + "\r\nCOMMENT:Preserved history\r\nEND:VSTATUS\r\n";
      var parsed = parse(component, fields);
      assertThat(parsed.calendar().copy().toString())
          .contains(
              "PERCENT-COMPLETE:25",
              "STATUS:FAILED",
              "REASON:https://example.invalid/reason",
              "COMMENT:Preserved history");
    }
  }
}
