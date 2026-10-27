package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CalendarTextProfileTest {
  @Test
  void unsupportedControlsAndXmlNoncharactersAreRejectedBeforeCalendarParsing() {
    for (int invalid : new int[] {0, 1, 8, 11, 12, 31, 127, 0xfffe, 0xffff}) {
      String text =
          "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Own text fixtures//EN\r\nBEGIN:VTODO\r\nUID:text\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:a"
              + new String(Character.toChars(invalid))
              + "b\r\nEND:VTODO\r\nEND:VCALENDAR\r\n";
      assertThatThrownBy(
              () -> CalendarContent.parse(text.getBytes(StandardCharsets.UTF_8), "VTODO"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void tabsCrlfFoldsAndSupplementaryUnicodeRemainAcceptedAndUnmodified() {
    String text =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Own text fixtures//EN\r\nBEGIN:VTODO\r\nUID:text\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:買い物 🗓️\r\n\tcontinued\r\nEND:VTODO\r\nEND:VCALENDAR\r\n";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8), original = bytes.clone();
    assertThat(CalendarContent.parse(bytes, "VTODO").uid()).isEqualTo("text");
    assertThat(bytes).isEqualTo(original);
  }
}
