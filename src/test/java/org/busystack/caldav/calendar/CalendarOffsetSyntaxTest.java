package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CalendarOffsetSyntaxTest {
  static Stream<Arguments> offsets() {
    return Stream.of("TZOFFSETFROM", "TZOFFSETTO")
        .flatMap(name -> Stream.of("0000", "000000").map(zero -> Arguments.of(name, zero)));
  }

  private static String calendar(String property, String value) {
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Offset grammar//EN\r\n"
        + "BEGIN:VTIMEZONE\r\nTZID:Qualification/Fixed\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\n"
        + "TZOFFSETFROM:+0000\r\nTZOFFSETTO:+0000\r\nTZNAME:One\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
        + "BEGIN:VEVENT\r\nUID:offset\r\nDTSTAMP:20261109T000000Z\r\nDTSTART:20261109T090000Z\r\n"
        + (property.equals("X-OFFSET") ? "X-OFFSET;VALUE=UTC-OFFSET:" + value + "\r\n" : "")
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
  }

  @ParameterizedTest
  @MethodSource("offsets")
  void negativeZeroIsRejectedBeforeItsSignCanBeNormalized(String property, String zero) {
    String data =
        calendar(property, "-" + zero).replace(property + ":+0000", property + ":-" + zero);
    assertThatThrownBy(() -> CalendarContent.parse(data.getBytes(StandardCharsets.UTF_8), "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
    String extension = calendar("X-OFFSET", "-" + zero);
    assertThatThrownBy(
            () -> CalendarContent.parse(extension.getBytes(StandardCharsets.UTF_8), "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @MethodSource("offsets")
  void positiveZeroAndPrecisionRemainAcceptedWithoutInputMutation(String property, String zero) {
    byte[] data =
        calendar(property, "+" + zero)
            .replace(property + ":+0000", property + ":+" + zero)
            .getBytes(StandardCharsets.UTF_8);
    byte[] before = data.clone();
    assertThat(CalendarContent.parse(data, "VEVENT").uid()).isEqualTo("offset");
    assertThat(data).isEqualTo(before);
  }
}
