package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import org.busystack.caldav.calendar.query.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TimezoneNameInterchangeTest {
  private static String calendar(String kind, String fields) {
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Repeated timezone names//EN\r\n"
        + "BEGIN:VTIMEZONE\r\nTZID:Qualification/Fixed\r\nBEGIN:"
        + kind
        + "\r\n"
        + "DTSTART:19700101T000000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0200\r\n"
        + fields
        + "END:"
        + kind
        + "\r\nEND:VTIMEZONE\r\nEND:VCALENDAR\r\n";
  }

  @ParameterizedTest
  @ValueSource(strings = {"STANDARD", "DAYLIGHT"})
  void multilingualNamesSurviveValidationCopyAndTimezoneUse(String kind) {
    String names = "TZNAME;LANGUAGE=en:Fixed\r\nTZNAME;LANGUAGE=de:Fest\r\n";
    String timezone = calendar(kind, names);
    assertThat(
            FloatingTimezone.parse(timezone)
                .zoneId()
                .getRules()
                .getOffset(java.time.Instant.parse("2026-11-09T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    String data =
        timezone.replace(
            "END:VCALENDAR",
            "BEGIN:VEVENT\r\nUID:names\r\nDTSTAMP:20261109T000000Z\r\nDTSTART;TZID=Qualification/Fixed:20261109T090000\r\nEND:VEVENT\r\nEND:VCALENDAR");
    var parsed = CalendarContent.parse(data.getBytes(StandardCharsets.UTF_8), "VEVENT");
    assertThat(parsed.calendar().copy().toString()).contains(names);
    assertThat(CalendarSemanticsTest.point(parsed, "VEVENT", "2026-11-09T07:00:00Z")).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"STANDARD", "DAYLIGHT"})
  void repeatedNamesDoNotDisableUnrelatedMandatoryOrPropertyValidation(String kind) {
    String valid = calendar(kind, "TZNAME:First\r\nTZNAME:Second\r\n");
    for (String invalid :
        java.util.List.of(
            valid.replace("TZOFFSETFROM:+0200\r\n", ""),
            valid.replace("TZOFFSETTO:+0200", "TZOFFSETTO:+2400"),
            valid.replace("DTSTART:19700101T000000\r\n", "")))
      assertThatThrownBy(() -> FloatingTimezone.parse(invalid))
          .isInstanceOf(RuntimeException.class);
  }
}
