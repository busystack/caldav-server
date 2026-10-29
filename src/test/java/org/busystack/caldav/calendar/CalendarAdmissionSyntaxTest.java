package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.busystack.caldav.calendar.query.FloatingTimezone;
import org.busystack.caldav.calendar.query.QueryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Lexical counterexamples must be checked before a typed model can normalize them away. */
class CalendarAdmissionSyntaxTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "invalid-leading-token",
        "invalid-zero-byday",
        "invalid-plus-zero-byday",
        "invalid-weeks-days"
      })
  void originalIndependentResidualInputsAreRejected(String name) throws Exception {
    byte[] bytes = fixture(name), original = bytes.clone();
    invalid(bytes, "VEVENT");
    assertThat(bytes).isEqualTo(original);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"valid-first-weekday", "valid-last-weekday", "valid-one-week", "valid-nine-days"})
  void originalIndependentLegalNeighborsRemainAccepted(String name) throws Exception {
    byte[] bytes = fixture(name), original = bytes.clone();
    assertThat(CalendarContent.parse(bytes, "VEVENT").uid()).isEqualTo(name);
    assertThat(bytes).isEqualTo(original);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0MO", "+0MO", "-0MO", "00MO", "+00MO", "-00MO", "MO,0TU"})
  void explicitZeroWeekdayOrdinalsAreRejectedForEventsTasksAndTypedRules(String byday) {
    for (String component : List.of("VEVENT", "VTODO"))
      invalid(content(component, "RRULE:FREQ=MONTHLY;BYDAY=" + byday + ";COUNT=2\r\n"), component);
    invalid(
        content("VEVENT", "X-RULE;VALUE=RECUR:FREQ=MONTHLY;BYDAY=" + byday + ";COUNT=2\r\n"),
        "VEVENT");
    assertThatThrownBy(() -> FloatingTimezone.parse(timezone(byday)))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.INVALID_DATA));
  }

  @ParameterizedTest
  @ValueSource(strings = {"MO", "MO,TU", "1MO", "+1MO", "-1MO", "01MO", "+01MO", "-01MO"})
  void omittedAndPermittedWeekdayOrdinalsRemainAccepted(String byday) {
    for (String component : List.of("VEVENT", "VTODO")) {
      byte[] bytes = content(component, "RRULE:FREQ=MONTHLY;BYDAY=" + byday + ";COUNT=2\r\n");
      assertThat(CalendarContent.parse(bytes, component).uid()).isEqualTo("lexical");
    }
    assertThat(FloatingTimezone.parse(timezone(byday))).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0MO", "+0MO", "-0MO"})
  void alreadyAcceptedLegacyRecurrencePropertiesUseTheSameLexicalGuard(String byday) {
    invalid(content("VEVENT", "EXRULE:FREQ=MONTHLY;BYDAY=" + byday + "\r\n"), "VEVENT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"MO", "+1MO", "-1MO"})
  void legacyPropertyPreservationDoesNotAddRecurrenceExecution(String byday) {
    byte[] bytes = content("VEVENT", "EXRULE:FREQ=MONTHLY;BYDAY=" + byday + "\r\n"),
        original = bytes.clone();
    assertThat(CalendarContent.parse(bytes, "VEVENT").uid()).isEqualTo("lexical");
    assertThat(bytes).isEqualTo(original);
  }

  static Stream<Arguments> durationContexts() {
    return Stream.of(
        Arguments.of("event", "VEVENT", "DURATION:%s\r\n"),
        Arguments.of("task", "VTODO", "DURATION:%s\r\n"),
        Arguments.of(
            "alarm repeat",
            "VEVENT",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Repeat\r\nTRIGGER:-PT5M\r\nREPEAT:2\r\nDURATION:%s\r\nEND:VALARM\r\n"),
        Arguments.of(
            "relative trigger",
            "VEVENT",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nTRIGGER:-%s\r\nEND:VALARM\r\n"),
        Arguments.of(
            "typed relative trigger",
            "VTODO",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nTRIGGER;VALUE=DURATION:-%s\r\nEND:VALARM\r\n"),
        Arguments.of("RDATE period", "VEVENT", "RDATE;VALUE=PERIOD:20260106T100000Z/%s\r\n"),
        Arguments.of("typed extension duration", "VEVENT", "X-INTERVAL;VALUE=DURATION:%s\r\n"),
        Arguments.of(
            "typed extension period", "VEVENT", "X-PERIOD;VALUE=PERIOD:20260106T100000Z/%s\r\n"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("durationContexts")
  void mixedWeekFormsAreRejectedWhereDurationValuesAreAccepted(
      String label, String component, String fields) {
    for (String value : List.of("P1W2D", "P1WT2H", "P1W2DT3H", "P1D2W"))
      invalid(content(component, fields.formatted(value)), component);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("durationContexts")
  void separateWeekAndDayTimeAlternativesRemainAccepted(
      String label, String component, String fields) {
    for (String value : List.of("P1W", "P9D", "P1DT2H3M4S", "PT2H", "PT3M4S", "PT4S")) {
      byte[] bytes = content(component, fields.formatted(value));
      assertThat(CalendarContent.parse(bytes, component).uid())
          .as(label + " " + value)
          .isEqualTo("lexical");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "XBEGIN:VCALENDAR",
        "BEGINX:VCALENDAR",
        "prefixBEGIN:VCALENDAR",
        "\uFEFFXBEGIN:VCALENDAR",
        "beg\u0131n:vcalendar",
        "\uFEFFbeg\u0131n:vcalendar",
        "BEGIN:VCALENDAR-suffix"
      })
  void initialCalendarBoundaryMustBeTheActualStructure(String boundary) {
    byte[] bytes =
        new String(content("VEVENT", ""), StandardCharsets.UTF_8)
            .replaceFirst("BEGIN:VCALENDAR", boundary)
            .getBytes(StandardCharsets.UTF_8);
    invalid(bytes, "VEVENT");
    assertThatThrownBy(
            () -> FloatingTimezone.parse(timezone("MO").replaceFirst("BEGIN:VCALENDAR", boundary)))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.INVALID_DATA));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "BEGIN:VCALENDAR",
        "begin:vcalendar",
        "bEgIn:vCaLeNdAr",
        "\uFEFFBEGIN:VCALENDAR",
        "\uFEFFbEgIn:vCaLeNdAr"
      })
  void caseVariationsAndOneLeadingBomRetainAuthoritativeBytes(String boundary) {
    byte[]
        bytes =
            new String(content("VEVENT", "X-OPAQUE:caseSensitiveValue\r\n"), StandardCharsets.UTF_8)
                .replaceFirst("BEGIN:VCALENDAR", boundary)
                .getBytes(StandardCharsets.UTF_8),
        original = bytes.clone();
    assertThat(CalendarContent.parse(bytes, "VEVENT").uid()).isEqualTo("lexical");
    assertThat(bytes).isEqualTo(original);
    assertThat(FloatingTimezone.parse(timezone("MO").replaceFirst("BEGIN:VCALENDAR", boundary)))
        .isNotNull();
  }

  @Test
  void otherRecognizedDurationPropertiesShareTheLexicalGuard() {
    for (String parameter : List.of("", ";VALUE=DURATION")) {
      byte[] invalid =
          new String(content("VEVENT", ""), StandardCharsets.UTF_8)
              .replace(
                  "VERSION:2.0\r\n", "VERSION:2.0\r\nREFRESH-INTERVAL" + parameter + ":P1W2D\r\n")
              .getBytes(StandardCharsets.UTF_8);
      invalid(invalid, "VEVENT");
    }
    // RFC7986 §5.7 requires VALUE=DURATION; a factory's missing-parameter tolerance is
    // not itself a positive normative control or a claim of full optional-property coverage.
    byte[] valid =
        new String(content("VEVENT", ""), StandardCharsets.UTF_8)
            .replace("VERSION:2.0\r\n", "VERSION:2.0\r\nREFRESH-INTERVAL;VALUE=DURATION:P1W\r\n")
            .getBytes(StandardCharsets.UTF_8);
    assertThat(CalendarContent.parse(valid, "VEVENT").uid()).isEqualTo("lexical");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "beg\u0131n:VEVENT",
        "beg\u0131n:VTODO",
        "beg\u0131n:VALARM",
        "beg\u0131n:VTIMEZONE"
      })
  void structuralPrefixMatchingDoesNotUseUnicodeCaseEquivalence(String boundary) {
    String component = boundary.substring(boundary.indexOf(':') + 1);
    String text =
        new String(
            content(component.equals("VTODO") ? "VTODO" : "VEVENT", ""), StandardCharsets.UTF_8);
    if (component.equals("VALARM"))
      text =
          text.replace(
              "END:VEVENT",
              "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Control\r\nTRIGGER:-PT5M\r\nEND:VALARM\r\nEND:VEVENT");
    else if (component.equals("VTIMEZONE")) {
      String zone = timezone("MO");
      assertThatThrownBy(() -> FloatingTimezone.parse(zone.replace("BEGIN:VTIMEZONE", boundary)))
          .isInstanceOfSatisfying(
              QueryException.class,
              error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.INVALID_DATA));
      return;
    }
    byte[] bytes = text.replace("BEGIN:" + component, boundary).getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () -> CalendarContent.parse(bytes, component.equals("VTODO") ? "VTODO" : "VEVENT"))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.INVALID_DATA));
  }

  @Test
  void asciiStructuralChecksPreserveUnicodeUidAndTextValues() {
    String uid = "lexical-\u0131-\u0130", summary = "MiXeD-\u0131-\u0130";
    byte[]
        bytes =
            new String(content("VEVENT", "SUMMARY:" + summary + "\r\n"), StandardCharsets.UTF_8)
                .replace("UID:lexical\r\n", "UID:" + uid + "\r\n")
                .getBytes(StandardCharsets.UTF_8),
        original = bytes.clone();
    var parsed = CalendarContent.parse(bytes, "VEVENT");
    assertThat(parsed.uid()).isEqualTo(uid);
    assertThat(
            parsed.calendar().getComponents().getFirst().getRequiredProperty("SUMMARY").getValue())
        .isEqualTo(summary);
    assertThat(bytes).isEqualTo(original);
  }

  @Test
  void untypedOpaqueExtensionsDoNotAcquireInventedDurationOrRecurrenceSemantics() {
    byte[] bytes = content("VEVENT", "X-OPAQUE:BYDAY=0MO;P1W2D;XBEGIN:VCALENDAR\r\n"),
        original = bytes.clone();
    assertThat(CalendarContent.parse(bytes, "VEVENT").uid()).isEqualTo("lexical");
    assertThat(bytes).isEqualTo(original);
  }

  private static void invalid(byte[] bytes, String component) {
    assertThatThrownBy(() -> CalendarContent.parse(bytes, component))
        .isInstanceOfSatisfying(
            CalendarValidationException.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(CalendarValidationException.Reason.INVALID_DATA));
  }

  private static byte[] fixture(String name) throws Exception {
    try (var stream =
        CalendarAdmissionSyntaxTest.class.getResourceAsStream(
            "/calendar/independent-verification/" + name + ".ics")) {
      if (stream == null) throw new IllegalStateException("Missing original independent fixture");
      return stream.readAllBytes();
    }
  }

  private static byte[] content(String component, String fields) {
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Lexical boundary regression//EN\r\nBEGIN:"
            + component
            + "\r\nUID:lexical\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20260105T100000Z\r\n"
            + fields
            + "END:"
            + component
            + "\r\nEND:VCALENDAR\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String timezone(String byday) {
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Lexical timezone//EN\r\nBEGIN:VTIMEZONE\r\nTZID:LexicalBoundary\r\n"
        + "BEGIN:STANDARD\r\nDTSTART:20260105T100000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0100\r\n"
        + "RRULE:FREQ=YEARLY;BYMONTH=1;BYDAY="
        + byday
        + ";COUNT=2\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nEND:VCALENDAR\r\n";
  }
}
