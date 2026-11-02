package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CalendarContentTest {
  private static byte[] calendar(String components) {
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack test//EN\r\n"
            + components
            + "END:VCALENDAR\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void acceptsUndatedCompletedTaskAndPreservesExtensionsInOriginalBytes() {
    byte[] bytes =
        calendar(
            "BEGIN:VTODO\r\nUID:task-one\r\nDTSTAMP:20261027T000000Z\r\nSUMMARY:買い物\r\nSTATUS:COMPLETED\r\nCOMPLETED:20261027T010000Z\r\nPERCENT-COMPLETE:100\r\nPRIORITY:3\r\nRELATED-TO:parent\r\nX-UNKNOWN;X-PARAM=\"a^'b\":retained\r\nATTACH:https://127.0.0.1:1/must-not-fetch\r\nEND:VTODO\r\n");
    assertThat(CalendarContent.parse(bytes, "VTODO").uid()).isEqualTo("task-one");
    assertThat(new String(bytes, StandardCharsets.UTF_8))
        .contains("X-UNKNOWN;X-PARAM=\"a^'b\":retained");
    assertThatThrownBy(() -> CalendarContent.parse(bytes, "VEVENT"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsUnboundedMasterAndDetachedCancelledExceptionWithoutExpansion() {
    byte[] bytes =
        calendar(
            "BEGIN:VEVENT\r\nUID:series\r\nDTSTAMP:20261027T000000Z\r\nDTSTART;VALUE=DATE:20260228\r\nDTEND;VALUE=DATE:20260301\r\nRRULE:FREQ=DAILY\r\nEXDATE;VALUE=DATE:20260302\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:series\r\nDTSTAMP:20261027T000000Z\r\nDTSTART;VALUE=DATE:20260303\r\nRECURRENCE-ID;VALUE=DATE:20260303\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\n");
    assertThat(CalendarContent.parse(bytes, "VEVENT").calendar().getComponents()).hasSize(2);
  }

  @Test
  void rejectsMixedUidAndCalendarMethodAndMalformedUtf8() {
    byte[] mixed =
        calendar(
            "BEGIN:VTODO\r\nUID:one\r\nDTSTAMP:20261027T000000Z\r\nEND:VTODO\r\nBEGIN:VTODO\r\nUID:two\r\nDTSTAMP:20261027T000000Z\r\nEND:VTODO\r\n");
    assertThatThrownBy(() -> CalendarContent.parse(mixed, "VTODO"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CalendarContent.parse(new byte[] {(byte) 0xc3, 0x28}, "VEVENT"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CalendarContent.parse(
                    calendar(
                        "METHOD:PUBLISH\r\nBEGIN:VTODO\r\nUID:one\r\nDTSTAMP:20261027T000000Z\r\nEND:VTODO\r\n"),
                    "VTODO"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void doesNotFetchEmbeddedTimeZoneUrl() throws Exception {
    try (var listener =
        new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      listener.setSoTimeout(100);
      byte[] data =
          calendar(
              "BEGIN:VTIMEZONE\r\nTZID:Custom/Zone\r\nTZURL:http://127.0.0.1:"
                  + listener.getLocalPort()
                  + "/must-not-fetch\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0530\r\nTZOFFSETTO:+0530\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:custom-zone\r\nDTSTAMP:20261027T000000Z\r\nDTSTART;TZID=Custom/Zone:20261027T120000\r\nEND:VEVENT\r\n");
      assertThat(CalendarContent.parse(data, "VEVENT").uid()).isEqualTo("custom-zone");
      assertThatThrownBy(listener::accept).isInstanceOf(java.net.SocketTimeoutException.class);
    }
  }

  @Test
  void boundsNestedAndOversizedInput() {
    assertThatThrownBy(
            () -> CalendarContent.parse(new byte[CalendarContent.MAX_BYTES + 1], "VTODO"))
        .isInstanceOf(IllegalArgumentException.class);
    String nested = "BEGIN:VTODO\r\n".repeat(100) + "END:VTODO\r\n".repeat(100);
    assertThatThrownBy(() -> CalendarContent.parse(calendar(nested), "VTODO"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
