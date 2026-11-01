package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import java.time.*;
import java.util.*;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.query.CalendarFilter.*;
import org.busystack.caldav.calendar.recurrence.*;
import org.junit.jupiter.api.Test;

class CalendarTimezoneCoverageTest {
  private static String timezone(String rule) {
    return "BEGIN:VTIMEZONE\r\nTZID:Custom/Bounded\r\nBEGIN:STANDARD\r\nDTSTART:20001029T030000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU"
        + rule
        + "\r\nEND:STANDARD\r\nBEGIN:DAYLIGHT\r\nDTSTART:20000326T020000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU"
        + rule
        + "\r\nEND:DAYLIGHT\r\nEND:VTIMEZONE\r\n";
  }

  private static FloatingTimezone floating(String rule) {
    return FloatingTimezone.parse(
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack coverage fixture//EN\r\n"
            + timezone(rule)
            + "END:VCALENDAR\r\n");
  }

  @Test
  void unboundedIntervalRuleRetainsItsActualTwoYearCycleWithinCoverage() {
    var zone = floating(";INTERVAL=2");
    assertThat(zone.coverageEnd()).isNotNull();
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2028-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2029-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(1));
    assertThat(zone.zoneId().getRules().getTransitionRules()).isEmpty();
  }

  @Test
  void floatingQueriesFailOutsideCoverageInsteadOfUsingTheFinalOffsetTail() {
    var zone = floating(";INTERVAL=2");
    var parsed = parse("VEVENT", "DTSTART:20290601T120000\r\n");
    var filter =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(
                component(
                    "VEVENT",
                    range("2029-06-01T11:00:00Z", "2029-06-01T11:01:00Z"),
                    List.of(),
                    List.of())));
    assertThat(new CalendarQuery(QueryLimits.defaults()).matches(parsed, filter, zone)).isTrue();
    var far =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(
                component(
                    "VEVENT",
                    range("2090-06-01T00:00:00Z", "2090-06-02T00:00:00Z"),
                    List.of(),
                    List.of())));
    assertThatThrownBy(() -> new CalendarQuery(QueryLimits.defaults()).matches(parsed, far, zone))
        .isInstanceOfSatisfying(
            QueryException.class,
            e -> assertThat(e.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
  }

  @Test
  void embeddedCoverageSurvivesReparseAndProtectsExpansion() {
    String resource =
        timezone(";INTERVAL=2")
            + "BEGIN:VEVENT\r\nUID:semantic\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=Custom/Bounded:20290601T120000\r\nEND:VEVENT\r\n";
    var parsed = parseComponents("VEVENT", resource);
    var again = parseComponents("VEVENT", resource);
    assertThat(parsed.zoneCoverage()).isNotEmpty();
    assertThat(again.zoneCoverage()).isNotEmpty();
    assertThat(point(parsed, "VEVENT", "2029-06-01T11:00:00Z")).isTrue();
    assertThat(CalendarReports.copy(parsed).toString())
        .contains("INTERVAL=2", "TZID:Custom/Bounded");
    assertThatThrownBy(
            () ->
                new CalendarRecurrence(QueryLimits.defaults())
                    .expand(
                        again,
                        range("2090-06-01T00:00:00Z", "2090-06-02T00:00:00Z"),
                        FloatingTimezone.of(UTC)))
        .isInstanceOf(QueryException.class);
  }

  @Test
  void aFarFutureObjectCanBeStoredButCannotBeInterpretedBeyondItsTimezoneCoverage() {
    var parsed =
        parseComponents(
            "VEVENT",
            timezone(";INTERVAL=2")
                + "BEGIN:VEVENT\r\nUID:semantic\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=Custom/Bounded:20900601T120000\r\nEND:VEVENT\r\n");
    assertThat(CalendarReports.copy(parsed).toString()).contains("20900601T120000");
    assertThatThrownBy(() -> point(parsed, "VEVENT", "2029-06-01T11:00:00Z"))
        .isInstanceOf(QueryException.class);
  }

  @Test
  void unsupportedAnnualShapesUseBoundedGenerationRatherThanSimplifiedAnnualRules() {
    for (String rule :
        List.of(
            ";BYSETPOS=1", ";BYMONTH=3,4", ";BYDAY=1SU,-1SU", ";BYYEARDAY=85", ";BYWEEKNO=13")) {
      String text = timezone("");
      String adjusted =
          rule.startsWith(";BYMONTH=")
              ? text.replace(";BYMONTH=3", rule)
              : rule.startsWith(";BYDAY=")
                  ? text.replace(";BYDAY=-1SU", rule)
                  : text.replace(";BYDAY=-1SU", ";BYDAY=-1SU" + rule);
      if (rule.equals(";BYWEEKNO=13")) {
        // Different observances must not prescribe conflicting offsets at the same UTC onset.
        // Keep this a valid unsupported-shape coverage fixture, with separate seasonal weeks.
        adjusted =
            adjusted.replace(
                "BYMONTH=10;BYDAY=-1SU;BYWEEKNO=13", "BYMONTH=10;BYDAY=-1SU;BYWEEKNO=43");
        String invalid = adjusted;
        assertThatThrownBy(
                () ->
                    FloatingTimezone.parse(
                        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack coverage fixture//EN\r\n"
                            + invalid
                            + "END:VCALENDAR\r\n"))
            .isInstanceOfSatisfying(
                QueryException.class,
                error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.INVALID_DATA));
        // RFC5545§3.3.10 forbids numeric BYDAY together with YEARLY BYWEEKNO.
        // Unnumbered Sundays retain the valid unsupported-shape coverage assertion below.
        adjusted = adjusted.replace("BYDAY=-1SU;BYWEEKNO=", "BYDAY=SU;BYWEEKNO=");
      }
      var zone =
          FloatingTimezone.parse(
              "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack coverage fixture//EN\r\n"
                  + adjusted
                  + "END:VCALENDAR\r\n");
      assertThat(zone.coverageEnd()).as(rule).isNotNull();
      assertThat(zone.zoneId().getRules().getTransitionRules()).isEmpty();
    }
  }

  @Test
  void paddedAlarmSearchCannotRunPastFloatingTimezoneCoverage() {
    var zone = floating(";INTERVAL=2");
    var near = zone.coverageEnd().minus(Duration.ofDays(30));
    String start =
        new net.fortuna.ical4j.model.TemporalAdapter<>(near.atZone(UTC).toLocalDateTime())
            .toString();
    var parsed =
        parse(
            "VEVENT",
            "DTSTART:"
                + start
                + "\r\nRRULE:FREQ=DAILY;COUNT=3\r\nBEGIN:VALARM\r\nTRIGGER:-P100D\r\nACTION:DISPLAY\r\nDESCRIPTION:Stored reminder\r\nEND:VALARM\r\n");
    var alarm = component("VALARM", new TimeRange(near, near.plusSeconds(1)), List.of(), List.of());
    var filter =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(component("VEVENT", null, List.of(), List.of(alarm))));
    assertThatThrownBy(
            () -> new CalendarQuery(QueryLimits.defaults()).matches(parsed, filter, zone))
        .isInstanceOfSatisfying(
            QueryException.class,
            e -> assertThat(e.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
  }

  @Test
  void futureRdatesDoNotSuppressInterveningAnnualRuleTransitions() {
    var text =
        timezone("")
            .replace(
                "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
                "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nRDATE:20300331T020000")
            .replace(
                "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU",
                "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nRDATE:20301027T030000");
    var zone =
        FloatingTimezone.parse(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack coverage fixture//EN\r\n"
                + text
                + "END:VCALENDAR\r\n");
    assertThat(zone.coverageEnd()).isNull();
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2029-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
    assertThat(zone.zoneId().getRules().getOffset(Instant.parse("2031-06-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
  }

  @Test
  void explicitPeriodDatesAreCheckedBeforeOutOfCoverageMatching() {
    var parsed =
        parseComponents(
            "VEVENT",
            timezone(";INTERVAL=2")
                + "BEGIN:VEVENT\r\nUID:semantic\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=Custom/Bounded:20290601T120000\r\nRDATE;TZID=Custom/Bounded;VALUE=PERIOD:20900601T120000/PT24H\r\nEND:VEVENT\r\n");
    assertThat(CalendarReports.copy(parsed).toString()).contains("20900601T120000/PT24H");
    assertThatThrownBy(() -> point(parsed, "VEVENT", "2029-06-01T11:00:00Z"))
        .isInstanceOf(QueryException.class);
  }
}
