package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.query.CalendarFilter.*;
import org.busystack.caldav.calendar.recurrence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CalendarSemanticsTest {
  static final ZoneId UTC = ZoneOffset.UTC;

  static CalendarContent.Parsed parse(String type, String fields) {
    return parseComponents(
        type,
        "BEGIN:"
            + type
            + "\r\nUID:semantic\r\nDTSTAMP:20260101T000000Z\r\n"
            + fields
            + "END:"
            + type
            + "\r\n");
  }

  static CalendarContent.Parsed parseComponents(String type, String components) {
    components = withBuiltinTimezones(components);
    return CalendarContent.parse(
        ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack original semantic fixtures//EN\r\n"
                + components
                + "END:VCALENDAR\r\n")
            .getBytes(StandardCharsets.UTF_8),
        type);
  }

  static String withBuiltinTimezones(String components) {
    for (String zone : List.of("America/New_York", "Europe/Berlin")) {
      if (components.contains("TZID=" + zone) && !components.contains("TZID:" + zone)) {
        String path = "/calendar/" + zone.replace('/', '-') + "-ical4j4.4.0.ics";
        try (var resource = CalendarSemanticsTest.class.getResourceAsStream(path)) {
          if (resource == null) throw new IllegalStateException("Missing pinned timezone fixture");
          components = new String(resource.readAllBytes(), StandardCharsets.UTF_8) + components;
        } catch (java.io.IOException ex) {
          throw new java.io.UncheckedIOException(ex);
        }
      }
    }
    return components;
  }

  static TimeRange range(String first, String last) {
    return new TimeRange(
        first == null ? null : Instant.parse(first), last == null ? null : Instant.parse(last));
  }

  static ComponentFilter component(
      String type,
      TimeRange range,
      List<PropertyFilter> properties,
      List<ComponentFilter> children) {
    return new ComponentFilter(type, false, range, properties, children);
  }

  static boolean match(CalendarContent.Parsed parsed, String type, TimeRange range) {
    return new CalendarQuery(QueryLimits.defaults())
        .matches(
            parsed,
            component(
                "VCALENDAR",
                null,
                List.of(),
                List.of(component(type, range, List.of(), List.of()))),
            UTC);
  }

  static boolean point(CalendarContent.Parsed parsed, String type, String instant) {
    var start = Instant.parse(instant);
    return match(parsed, type, new TimeRange(start, start.plusSeconds(1)));
  }

  static String event(String rid, String start, String extras) {
    return "BEGIN:VEVENT\r\nUID:semantic\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:"
        + start
        + "\r\n"
        + (rid == null ? "" : "RECURRENCE-ID" + rid + "\r\n")
        + extras
        + "END:VEVENT\r\n";
  }

  static Stream<Arguments> taskCases() {
    return Stream.of(
        Arguments.of("", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", true),
        Arguments.of(
            "DTSTART:20260101T100000Z\r\n", "2026-01-01T10:00:00Z", "2026-01-01T10:01:00Z", true),
        Arguments.of(
            "DTSTART:20260101T100000Z\r\n", "2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", false),
        Arguments.of(
            "DUE:20260101T100000Z\r\n", "2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", true),
        Arguments.of(
            "DUE:20260101T100000Z\r\n", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", false),
        Arguments.of(
            "DTSTART:20260101T090000Z\r\nDURATION:PT1H\r\n",
            "2026-01-01T10:00:00Z",
            "2026-01-01T11:00:00Z",
            true),
        Arguments.of(
            "DTSTART:20260101T090000Z\r\nDUE:20260101T100000Z\r\n",
            "2026-01-01T10:00:00Z",
            "2026-01-01T11:00:00Z",
            false),
        Arguments.of(
            "COMPLETED:20260101T100000Z\r\n", "2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", true),
        Arguments.of(
            "CREATED:20260101T100000Z\r\n", "2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", false),
        Arguments.of(
            "CREATED:20260101T100000Z\r\n", "2027-01-01T09:00:00Z", "2027-01-01T10:00:00Z", true),
        Arguments.of(
            "CREATED:20260101T090000Z\r\nCOMPLETED:20260101T110000Z\r\n",
            "2026-01-01T10:00:00Z",
            "2026-01-01T10:01:00Z",
            true),
        Arguments.of(
            "CREATED:20260101T090000Z\r\nCOMPLETED:20260101T110000Z\r\n",
            "2026-01-01T12:00:00Z",
            "2026-01-01T13:00:00Z",
            false));
  }

  @ParameterizedTest
  @MethodSource("taskCases")
  void taskTimeRangesFollowAllRfcRows(String fields, String start, String end, boolean expected) {
    assertThat(match(parse("VTODO", fields), "VTODO", range(start, end))).isEqualTo(expected);
  }

  @Test
  void zeroDurationEventsUsePointSemanticsAndAllDayEndIsExclusive() {
    var event = parse("VEVENT", "DTSTART:20260101T100000Z\r\n");
    assertThat(point(event, "VEVENT", "2026-01-01T10:00:00Z")).isTrue();
    assertThat(match(event, "VEVENT", range("2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z")))
        .isFalse();
    var day = parse("VEVENT", "DTSTART;VALUE=DATE:20260228\r\n");
    assertThat(point(day, "VEVENT", "2026-02-28T23:59:59Z")).isTrue();
    assertThat(point(day, "VEVENT", "2026-03-01T00:00:00Z")).isFalse();
  }

  @Test
  void nominalAndExactDayDurationsRemainDistinctAcrossDst() {
    String base = "DTSTART;TZID=America/New_York:20260307T120000\r\nDURATION:";
    assertThat(point(parse("VEVENT", base + "P1D\r\n"), "VEVENT", "2026-03-08T16:30:00Z"))
        .isFalse();
    assertThat(point(parse("VEVENT", base + "PT24H\r\n"), "VEVENT", "2026-03-08T16:30:00Z"))
        .isTrue();
    assertThat(point(parse("VEVENT", base + "P1DT1H\r\n"), "VEVENT", "2026-03-08T17:30:00Z"))
        .isFalse();
  }

  @Test
  void recurrenceUnionExclusionAndFiniteCountDoNotLoseRdates() {
    var c =
        parse(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nRRULE:FREQ=DAILY;COUNT=5\r\nRDATE:20260110T100000Z\r\nEXDATE:20260102T100000Z\r\n");
    assertThat(point(c, "VEVENT", "2026-01-02T10:00:00Z")).isFalse();
    assertThat(point(c, "VEVENT", "2026-01-03T10:00:00Z")).isTrue();
    assertThat(point(c, "VEVENT", "2026-01-06T10:00:00Z")).isFalse();
    assertThat(point(c, "VEVENT", "2026-01-10T10:00:00Z")).isTrue();
  }

  @Test
  void detachedMoveSuppressesOriginalAndIncludesMovedTimeOutsideOriginalQuery() {
    var c =
        parseComponents(
            "VEVENT",
            event(null, "20260101T100000Z", "RRULE:FREQ=DAILY;COUNT=5\r\n")
                + event(":20260103T100000Z", "20260120T100000Z", ""));
    assertThat(point(c, "VEVENT", "2026-01-03T10:00:00Z")).isFalse();
    assertThat(point(c, "VEVENT", "2026-01-20T10:00:00Z")).isTrue();
  }

  @Test
  void rangeShiftsFutureButLeavesExplicitDetachedInstancesIndependent() {
    var c =
        parseComponents(
            "VEVENT",
            event(null, "20260101T100000Z", "RRULE:FREQ=DAILY;COUNT=5\r\nDURATION:PT1H\r\n")
                + event(
                    ";RANGE=THISANDFUTURE:20260103T100000Z",
                    "20260103T120000Z",
                    "DURATION:PT2H\r\n")
                + event(":20260105T100000Z", "20260105T090000Z", "DURATION:PT1H\r\n"));
    assertThat(point(c, "VEVENT", "2026-01-04T10:00:00Z")).isFalse();
    assertThat(point(c, "VEVENT", "2026-01-04T13:00:00Z")).isTrue();
    assertThat(point(c, "VEVENT", "2026-01-05T09:00:00Z")).isTrue();
    assertThat(point(c, "VEVENT", "2026-01-05T12:00:00Z")).isFalse();
  }

  @Test
  void nonexistentZonedOccurrencesUsePreGapOffsetAndConsumeCount() {
    var c =
        parse(
            "VEVENT",
            "DTSTART;TZID=America/New_York:20260307T023000\r\nRRULE:FREQ=DAILY;COUNT=3\r\n");
    assertThat(point(c, "VEVENT", "2026-03-07T07:30:00Z")).isTrue();
    assertThat(point(c, "VEVENT", "2026-03-08T07:30:00Z")).isTrue();
    assertThat(point(c, "VEVENT", "2026-03-09T06:30:00Z")).isTrue();
    assertThat(point(c, "VEVENT", "2026-03-10T06:30:00Z")).isFalse();
    assertThat(point(c, "VEVENT", "2026-03-11T06:30:00Z")).isFalse();
  }

  @Test
  void leapDayRecurrenceUsesCalendarLibraryRules() {
    var c = parse("VEVENT", "DTSTART;VALUE=DATE:20240229\r\nRRULE:FREQ=YEARLY\r\n");
    assertThat(point(c, "VEVENT", "2028-02-29T12:00:00Z")).isTrue();
    assertThat(point(c, "VEVENT", "2025-02-28T12:00:00Z")).isFalse();
  }

  @Test
  void floatingDatesUseReportTimezoneWithoutChangingZonedData() {
    var c = parse("VEVENT", "DTSTART:20260101T120000\r\n");
    var filter =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(
                component(
                    "VEVENT",
                    range("2026-01-01T06:30:00Z", "2026-01-01T06:31:00Z"),
                    List.of(),
                    List.of())));
    assertThat(
            new CalendarQuery(QueryLimits.defaults()).matches(c, filter, ZoneId.of("Asia/Kolkata")))
        .isTrue();
    assertThat(new CalendarQuery(QueryLimits.defaults()).matches(c, filter, UTC)).isFalse();
  }

  @Test
  void customEmbeddedTimezoneIsAuthoritative() {
    String timezone =
        "BEGIN:VTIMEZONE\r\nTZID:Custom/Offset\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0530\r\nTZOFFSETTO:+0530\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n";
    var c =
        parseComponents(
            "VEVENT",
            timezone
                + "BEGIN:VEVENT\r\nUID:semantic\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=Custom/Offset:20260101T120000\r\nEND:VEVENT\r\n");
    assertThat(point(c, "VEVENT", "2026-01-01T06:30:00Z")).isTrue();
    var floating =
        FloatingTimezone.parse(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Own timezone fixture//EN\r\n"
                + timezone
                + "END:VCALENDAR\r\n");
    assertThat(floating.zoneId().getRules().getOffset(Instant.parse("2026-01-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
  }

  @Test
  void propertiesParametersAndNegatedTextUseDeclaredCollations() {
    var c =
        parse(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nSUMMARY:Team Ä meeting\r\nATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test\r\n");
    var props =
        List.of(
            new PropertyFilter(
                "summary", false, null, new TextMatch("TEAM Ä", null, false), List.of()),
            new PropertyFilter(
                "ATTENDEE",
                false,
                null,
                new TextMatch("ALICE", null, false),
                List.of(
                    new ParameterFilter(
                        "PARTSTAT", false, new TextMatch("accepted", null, false)))));
    var filter =
        component(
            "VCALENDAR", null, List.of(), List.of(component("VEVENT", null, props, List.of())));
    assertThat(new CalendarQuery(QueryLimits.defaults()).matches(c, filter, UTC)).isTrue();
    assertThat(new TextMatch("ä", null, false).matches("Ä")).isFalse();
    assertThat(new TextMatch("team", "i;octet", false).matches("Team")).isFalse();
    assertThat(new TextMatch("cancelled", null, true).matches("CONFIRMED")).isTrue();
    assertThatThrownBy(() -> new TextMatch("x", "i;unicode-casemap", false))
        .isInstanceOf(QueryException.class);
  }

  @Test
  void repeatedNestedAlarmsUseStoredTriggerAndParentOccurrence() {
    var c =
        parse(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nDURATION:PT1H\r\nBEGIN:VALARM\r\nTRIGGER:-PT10M\r\nREPEAT:2\r\nDURATION:PT5M\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nEND:VALARM\r\n");
    var alarm =
        component(
            "VALARM", range("2026-01-01T09:55:00Z", "2026-01-01T09:56:00Z"), List.of(), List.of());
    var filter =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(component("VEVENT", null, List.of(), List.of(alarm))));
    assertThat(new CalendarQuery(QueryLimits.defaults()).matches(c, filter, UTC)).isTrue();
  }

  @Test
  void freeBusyHonorsStatusTransparencyAndCoalescesOnlyEqualTypes() {
    var a = parse("VEVENT", "DTSTART:20260101T090000Z\r\nDTEND:20260101T100000Z\r\n");
    var b = parse("VEVENT", "DTSTART:20260101T100000Z\r\nDTEND:20260101T110000Z\r\n");
    var tentative =
        parse(
            "VEVENT", "DTSTART:20260101T093000Z\r\nDTEND:20260101T103000Z\r\nSTATUS:TENTATIVE\r\n");
    var transparent =
        parse(
            "VEVENT",
            "DTSTART:20260101T090000Z\r\nDTEND:20260101T120000Z\r\nTRANSP:TRANSPARENT\r\n");
    var cancelled =
        parse(
            "VEVENT", "DTSTART:20260101T090000Z\r\nDTEND:20260101T120000Z\r\nSTATUS:CANCELLED\r\n");
    var periods =
        new CalendarFreeBusy(QueryLimits.defaults())
            .query(
                List.of(a, b, tentative, transparent, cancelled),
                range("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"),
                UTC);
    assertThat(periods)
        .containsExactly(
            new CalendarFreeBusy.BusyPeriod(
                Instant.parse("2026-01-01T09:00:00Z"),
                Instant.parse("2026-01-01T11:00:00Z"),
                CalendarFreeBusy.BusyType.BUSY),
            new CalendarFreeBusy.BusyPeriod(
                Instant.parse("2026-01-01T09:30:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"),
                CalendarFreeBusy.BusyType.BUSY_TENTATIVE));
  }

  @Test
  void finiteWorkLimitsFailRatherThanReturnTruncatedAnswers() {
    var c = parse("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=SECONDLY\r\n");
    assertThat(point(c, "VEVENT", "2026-01-01T10:00:30Z")).isTrue();
    assertThatThrownBy(
            () -> match(c, "VEVENT", range("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")))
        .isInstanceOf(QueryException.class);
  }

  @Test
  void hostileTimezoneRecurrenceIsRejectedBeforeRuleMaterialization() {
    String zone =
        "BEGIN:VTIMEZONE\r\nTZID:Hostile/Zone\r\nBEGIN:STANDARD\r\nDTSTART:19000101T000000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\nRRULE:FREQ=SECONDLY\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n";
    assertThatThrownBy(() -> parseComponents("VEVENT", zone + event(null, "20260101T100000Z", "")))
        .isInstanceOf(QueryException.class);
  }
}
