package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import java.time.*;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.recurrence.*;
import org.junit.jupiter.api.Test;

class CalendarTimezoneRdateTest {
  private static final String TIMEZONE =
      "BEGIN:VTIMEZONE\r\nTZID:Custom/FutureDates\r\nBEGIN:STANDARD\r\nDTSTART:20001029T030000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\nRDATE:20301027T030000\r\nEND:STANDARD\r\nBEGIN:DAYLIGHT\r\nDTSTART:20000326T020000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\nRDATE:20300331T020000\r\nEND:DAYLIGHT\r\nEND:VTIMEZONE\r\n";

  @Test
  void explicitObservanceDatesRetainHistoricalAndFutureTransitions() {
    var timezone =
        FloatingTimezone.parse(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack original timezone fixture//EN\r\n"
                + TIMEZONE
                + "END:VCALENDAR\r\n");
    var rules = timezone.zoneId().getRules();
    assertThat(rules.getOffset(Instant.parse("2000-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(rules.getOffset(Instant.parse("2029-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(1));
    assertThat(rules.getOffset(Instant.parse("2030-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(rules.getOffset(Instant.parse("2030-12-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(1));
  }

  @Test
  void embeddedFutureTransitionsDriveQueryAndExpandedUtcRepresentation() {
    var parsed =
        parseComponents(
            "VEVENT",
            TIMEZONE
                + "BEGIN:VEVENT\r\nUID:semantic\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=Custom/FutureDates:20300601T120000\r\nDTEND;TZID=Custom/FutureDates:20300601T130000\r\nEND:VEVENT\r\n");
    assertThat(point(parsed, "VEVENT", "2030-06-01T10:30:00Z")).isTrue();
    assertThat(point(parsed, "VEVENT", "2030-06-01T11:30:00Z")).isFalse();
    var expanded =
        new CalendarRecurrence(QueryLimits.defaults())
            .expand(
                parsed,
                range("2030-06-01T00:00:00Z", "2030-06-02T00:00:00Z"),
                FloatingTimezone.of(UTC));
    assertThat(expanded.toString())
        .contains("DTSTART:20300601T100000Z", "DTEND:20300601T110000Z")
        .doesNotContain("BEGIN:VTIMEZONE");
  }

  private static FloatingTimezone annual(String standardEnd, String daylightEnd) {
    var text =
        TIMEZONE
            .replace(
                "RDATE:20301027T030000", "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU" + standardEnd)
            .replace(
                "RDATE:20300331T020000", "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU" + daylightEnd);
    return FloatingTimezone.parse(
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack original timezone fixture//EN\r\n"
            + text
            + "END:VCALENDAR\r\n");
  }

  @Test
  void futureUntilRulesStopAfterTheirFinalOnset() {
    var zone = annual(";UNTIL=20301027T010000Z", ";UNTIL=20300331T010000Z");
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2030-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2031-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(1));
  }

  @Test
  void pastUntilRulesDoNotResurrectHistoricalDaylightSaving() {
    var zone = annual(";UNTIL=20011028T010000Z", ";UNTIL=20010325T010000Z");
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2001-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2002-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(1));
  }

  @Test
  void pastCountRulesAreMaterializedOnlyForTheirFiniteInstances() {
    var zone = annual(";COUNT=2", ";COUNT=2");
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2001-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2002-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(1));
  }

  @Test
  void futureCountRulesStopAfterTheirFinalInstance() {
    var zone = annual(";COUNT=31", ";COUNT=31");
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2030-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2031-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(1));
  }

  @Test
  void validUnboundedAnnualRulesRemainUnbounded() {
    var zone = annual("", "");
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2031-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2031-12-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(1));
  }

  @Test
  void finiteFutureWorkIsCheckedBeforeEitherBuilderMaterializesIt() {
    assertThatThrownBy(() -> annual(";UNTIL=99991031T010000Z", ";UNTIL=99990328T010000Z"))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
    assertThatThrownBy(() -> annual(";COUNT=100000", ";COUNT=100000"))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
  }

  @Test
  void annualRulesWithoutBymonthInheritTheirDtstartMonth() {
    var text =
        TIMEZONE
            .replace("RDATE:20301027T030000", "RRULE:FREQ=YEARLY")
            .replace("RDATE:20300331T020000", "RRULE:FREQ=YEARLY");
    var zone =
        FloatingTimezone.parse(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack original timezone fixture//EN\r\n"
                + text
                + "END:VCALENDAR\r\n");
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2031-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2031-12-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(1));
  }
}
