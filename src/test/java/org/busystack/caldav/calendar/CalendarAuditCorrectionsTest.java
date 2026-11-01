package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.query.CalendarFilter.*;
import org.busystack.caldav.calendar.recurrence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/**
 * Original independent fixtures plus standards-derived neighboring cases; no clock-specific oracle.
 */
class CalendarAuditCorrectionsTest {
  static Stream<Arguments> utf8FoldPositions() {
    return Stream.of(
        Arguments.of("€", 1),
        Arguments.of("€", 2),
        Arguments.of("🙂", 1),
        Arguments.of("🙂", 2),
        Arguments.of("🙂", 3));
  }

  @ParameterizedTest
  @MethodSource("utf8FoldPositions")
  void unfoldingRestoresUtf8EvenWhenAProducerFoldsInsideACodePoint(String character, int split)
      throws Exception {
    var output = new java.io.ByteArrayOutputStream();
    output.write(
        ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Fold//EN\r\nBEGIN:VEVENT\r\n"
                + "UID:fold\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20260101T100000Z\r\nSUMMARY:before")
            .getBytes(StandardCharsets.UTF_8));
    byte[] encoded = character.getBytes(StandardCharsets.UTF_8);
    output.write(encoded, 0, split);
    output.write("\r\n\t".getBytes(StandardCharsets.US_ASCII));
    output.write(encoded, split, encoded.length - split);
    output.write("after\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n".getBytes(StandardCharsets.US_ASCII));
    byte[] bytes = output.toByteArray(), original = bytes.clone();
    var parsed = CalendarContent.parse(bytes, "VEVENT");
    assertThat(
            parsed
                .calendar()
                .getComponents()
                .getFirst()
                .getRequiredProperty(net.fortuna.ical4j.model.Property.SUMMARY)
                .getValue())
        .isEqualTo("before" + character + "after");
    assertThat(CalendarParsingInput.reportText(bytes))
        .contains("SUMMARY:before" + character + "after\r\n")
        .doesNotContain("\uFFFD");
    assertThat(bytes).isEqualTo(original);
  }

  @ParameterizedTest
  @ValueSource(ints = {0x80, 0xC0, 0xC3, 0xFF})
  void unfoldingDoesNotMakeMalformedUtf8Acceptable(int malformed) throws Exception {
    byte[] valid = fixture("control-event.ics");
    byte[] input = Arrays.copyOf(valid, valid.length + 1);
    input[input.length - 1] = (byte) malformed;
    assertThatThrownBy(() -> CalendarContent.parse(input, "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
    assertThatThrownBy(() -> CalendarParsingInput.reportText(input))
        .isInstanceOf(CalendarValidationException.class);
  }

  @Test
  void reportTextPreservesOrdinaryUtf8FoldingAndSourceBytes() {
    String original = "SUMMARY:ordinary€\r\n folded🙂\r\n";
    byte[] bytes = original.getBytes(StandardCharsets.UTF_8), before = bytes.clone();
    assertThat(CalendarParsingInput.reportText(bytes)).isEqualTo(original);
    assertThat(bytes).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "CALSCALE:GREGORIAN\r\n",
        "CALSCALE:gReGoRiAn\r\n",
        "CALSCALE;X-AUDIT=control:gReGoRiAn\r\n"
      })
  void omittedAndExplicitGregorianCalendarScalesRemainAccepted(String scale) throws Exception {
    byte[] original = fixture("control-event.ics");
    byte[] bytes =
        new String(original, StandardCharsets.UTF_8)
            .replace("BEGIN:VCALENDAR\r\n", "BEGIN:VCALENDAR\r\n" + scale)
            .getBytes(StandardCharsets.UTF_8);
    byte[] before = bytes.clone();
    assertThat(CalendarContent.parse(bytes, "VEVENT").uid()).isNotBlank();
    assertThat(bytes).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CALSCALE:CHINESE\r\n",
        "CALSCALE:NOT-REGISTERED\r\n",
        "CALSCALE:GREGORIAN,OTHER\r\n",
        "CALSCALE:\r\n",
        "CALSCALE:GREGORIAN\r\nCALSCALE:GREGORIAN\r\n"
      })
  void invalidCalendarScaleGrammarAndRepeatedScaleAreRejected(String scale) throws Exception {
    byte[] bytes =
        new String(fixture("control-event.ics"), StandardCharsets.UTF_8)
            .replace("BEGIN:VCALENDAR\r\n", "BEGIN:VCALENDAR\r\n" + scale)
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> CalendarContent.parse(bytes, "VEVENT"))
        .isInstanceOfSatisfying(
            CalendarValidationException.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(CalendarValidationException.Reason.INVALID_DATA));
  }

  @ParameterizedTest
  @ValueSource(strings = {"20290531T230000", "20290531"})
  void timezoneOnlyParsingRejectsObservanceUntilWithoutUtcForm(String until) throws Exception {
    String calendar = timezoneFixture().replace("UNTIL=20290531T230000Z", "UNTIL=" + until);
    assertThat(calendar).contains("UNTIL=" + until + "\r\n");
    assertThatThrownBy(() -> FloatingTimezone.parse(calendar))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.INVALID_DATA));
  }

  @Test
  void timezoneOnlyParsingRetainsValidUtcUntilAndFutureEra() throws Exception {
    var timezone = FloatingTimezone.parse(timezoneFixture());
    assertThat(timezone.zoneId().getRules().getOffset(Instant.parse("2040-04-01T10:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(2));
  }

  private static String timezoneFixture() throws Exception {
    String fixture = new String(fixture("future-zone.ics"), StandardCharsets.UTF_8);
    int start = fixture.indexOf("BEGIN:VTIMEZONE"), end = fixture.indexOf("END:VTIMEZONE");
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Timezone admission//EN\r\n"
        + fixture.substring(start, end + "END:VTIMEZONE".length())
        + "\r\nEND:VCALENDAR\r\n";
  }

  static byte[] fixture(String name) throws Exception {
    try (var input =
        CalendarAuditCorrectionsTest.class.getResourceAsStream("/calendar/audit/" + name)) {
      if (input == null) throw new IllegalStateException("Missing independent fixture " + name);
      return input.readAllBytes();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "invalid-date-hours-duration.ics",
        "invalid-event-end-before-start.ics",
        "invalid-event-negative-duration.ics",
        "invalid-event-no-start.ics",
        "invalid-rule-count-and-until.ics",
        "invalid-rule-repeat-count.ics",
        "invalid-rule-repeat-freq.ics",
        "invalid-rule-until-type.ics",
        "invalid-todo-no-start-duration.ics"
      })
  void originalInvalidFixturesFailAtTheWriteBoundary(String name) throws Exception {
    byte[] bytes = fixture(name), before = bytes.clone();
    assertThatThrownBy(
            () -> CalendarContent.parse(bytes, name.contains("todo") ? "VTODO" : "VEVENT"))
        .isInstanceOfSatisfying(
            CalendarValidationException.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(CalendarValidationException.Reason.INVALID_DATA));
    assertThat(bytes).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "control-event.ics",
        "control-last-ordinary-second.ics",
        "future-zone.ics",
        "valid-lowercase-delimiters.ics",
        "valid-rfc9073-location.ics",
        "valid-rfc9074-alarm.ics",
        "valid-rfc9253-relationship.ics",
        "valid-text-list.ics",
        "valid-unknown-x-properties.ics"
      })
  void originalLegalNeighborsAreAcceptedWithoutChangingInput(String name) throws Exception {
    byte[] bytes = fixture(name), before = bytes.clone();
    assertThat(
            CalendarContent.parse(bytes, name.contains("relationship") ? "VTODO" : "VEVENT").uid())
        .isNotBlank();
    assertThat(bytes).isEqualTo(before);
  }

  @Test
  void futureEraWorksForQueryBusyAndExpansionBeyondTheFormerWindow() throws Exception {
    byte[] bytes = fixture("future-zone.ics");
    var parsed = CalendarContent.parse(bytes, "VEVENT");
    assertThat(parsed.zoneCoverage()).isEmpty();
    assertThat(point(parsed, "VEVENT", "2040-04-01T10:00:00Z")).isTrue();
    assertThat(point(parsed, "VEVENT", "2040-04-01T11:00:00Z")).isFalse();
    var window = range("2040-04-01T09:00:00Z", "2040-04-01T13:00:00Z");
    assertThat(new CalendarFreeBusy(QueryLimits.defaults()).query(List.of(parsed), window, UTC))
        .containsExactly(
            new CalendarFreeBusy.BusyPeriod(
                Instant.parse("2040-04-01T10:00:00Z"),
                Instant.parse("2040-04-01T11:00:00Z"),
                CalendarFreeBusy.BusyType.BUSY));
    assertThat(
            new CalendarRecurrence(QueryLimits.defaults())
                .expand(parsed, window, FloatingTimezone.of(UTC))
                .toString())
        .contains("DTSTART:20400401T100000Z", "DTEND:20400401T110000Z");
    assertThat(CalendarContent.parse(bytes, "VEVENT").zoneCoverage()).isEmpty();
    assertThat(bytes).isEqualTo(fixture("future-zone.ics"));
  }

  @Test
  void chronologicalRulesDoNotDependOnTodaysEraForTasksExceptionsAndDifferentOffsets()
      throws Exception {
    String original = new String(fixture("future-zone.ics"), StandardCharsets.UTF_8);
    String recurring =
        original.replace(
            "DTSTART;TZID=AuditRules:20400401T120000\r\n",
            "DTSTART;TZID=AuditRules:20400401T120000\r\nRRULE:FREQ=DAILY;COUNT=3\r\n");
    String exception =
        "BEGIN:VEVENT\r\nUID:future-zone\r\nDTSTAMP:20261027T000000Z\r\n"
            + "RECURRENCE-ID;TZID=AuditRules:20400402T120000\r\nDTSTART;TZID=AuditRules:20400402T140000\r\n"
            + "DURATION:PT1H\r\nEND:VEVENT\r\n";
    var parsed =
        CalendarContent.parse(
            recurring
                .replace("END:VCALENDAR", exception + "END:VCALENDAR")
                .getBytes(StandardCharsets.UTF_8),
            "VEVENT");
    assertThat(point(parsed, "VEVENT", "2040-04-02T10:00:00Z")).isFalse();
    assertThat(point(parsed, "VEVENT", "2040-04-02T12:00:00Z")).isTrue();
    var task =
        CalendarContent.parse(
            original.replace("VEVENT", "VTODO").getBytes(StandardCharsets.UTF_8), "VTODO");
    assertThat(point(task, "VTODO", "2040-04-01T10:30:00Z")).isTrue();
    assertThat(point(task, "VTODO", "2040-04-01T11:30:00Z")).isFalse();
    String different =
        original
            .replace("+0100", "-0330")
            .replace("+0200", "-0230")
            .replace("20290531T230000Z", "20290601T033000Z");
    var changed = CalendarContent.parse(different.getBytes(StandardCharsets.UTF_8), "VEVENT");
    assertThat(point(changed, "VEVENT", "2040-04-01T14:30:00Z")).isTrue();
    assertThat(point(changed, "VEVENT", "2040-04-01T13:30:00Z")).isFalse();
    // Future era starts in2030, so the old June onset still determines April2028.
    var historical =
        CalendarContent.parse(
            original.replace("20400401", "20280401").getBytes(StandardCharsets.UTF_8), "VEVENT");
    assertThat(point(historical, "VEVENT", "2028-04-01T11:00:00Z")).isTrue();
  }

  @Test
  void mixedCaseStructuralNamesRemainDistinctFromCaseSensitiveValues() {
    String zone =
        "bEgIn:vTimeZone\r\nTZID:CaseSensitive/Zone\r\nbEgIn:standard\r\n"
            + "DTSTART:19700101T000000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0200\r\neNd:StAnDaRd\r\neNd:VTIMEZONE\r\n";
    for (String type : List.of("VEVENT", "VTODO")) {
      String body =
          "bEgIn:vcalendar\r\nVERSION:2.0\r\nPRODID:-//CaseControls//EN\r\n"
              + zone
              + "bEgIn:"
              + type.toLowerCase(Locale.ROOT)
              + "\r\nUID:CaseSensitive-Uid\r\nDTSTAMP:20261027T000000Z\r\n"
              + "DTSTART;TZID=CaseSensitive/Zone:20400401T120000\r\nSUMMARY:MixedCase Text\r\n"
              + "bEgIn:valarm\r\nACTION:DISPLAY\r\nDESCRIPTION:KeepCase\r\nTRIGGER:-PT5M\r\neNd:VaLaRm\r\n"
              + "eNd:"
              + type
              + "\r\neNd:Vcalendar\r\n";
      var parsed = CalendarContent.parse(body.getBytes(StandardCharsets.UTF_8), type);
      assertThat(parsed.uid()).isEqualTo("CaseSensitive-Uid");
      assertThat(
              parsed
                  .calendar()
                  .getComponents(type)
                  .getFirst()
                  .getRequiredProperty("SUMMARY")
                  .getValue())
          .isEqualTo("MixedCase Text");
      assertThatThrownBy(
              () ->
                  CalendarContent.parse(
                      body.replace("eNd:VaLaRm", "eNd:VTODO").getBytes(StandardCharsets.UTF_8),
                      type))
          .isInstanceOf(CalendarValidationException.class);
    }
  }

  @Test
  void floatingAndAbsoluteDateTimesUseTheReportsTimezoneRatherThanAGuessedWriteZone() {
    var parsed = parse("VEVENT", "DTSTART:20261027T100000\r\nDTEND:20261027T140000Z\r\n");
    var filter =
        component(
            "VCALENDAR",
            null,
            List.of(),
            List.of(
                component(
                    "VEVENT",
                    range("2026-10-27T09:00:00Z", "2026-10-27T09:30:00Z"),
                    List.of(),
                    List.of())));
    assertThat(new CalendarQuery(QueryLimits.defaults()).matches(parsed, filter, ZoneOffset.UTC))
        .isFalse();
    assertThat(
            new CalendarQuery(QueryLimits.defaults())
                .matches(parsed, filter, ZoneOffset.ofHours(2)))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"i;octet", "i;ascii-casemap"})
  void listTextIsDecodedOnceBeforePositiveAndNegatedMatching(String collation) {
    for (String property : List.of("CATEGORIES", "RESOURCES")) {
      String fields =
          "DTSTART:20261027T120000Z\r\n"
              + property
              + ":alpha\\;beta,comma\\,value,back\\\\slash,line\\nfeed,literal\\\\n\r\n"
              + property
              + ":second\r\nSUMMARY:summary\\;control\r\n";
      var parsed = parse("VEVENT", fields);
      for (String expected :
          List.of(
              "alpha;beta", "comma,value", "back\\slash", "line\nfeed", "literal\\n", "second")) {
        assertThat(text(parsed, property, expected, collation, false))
            .as(property + " " + expected)
            .isTrue();
      }
      assertThat(text(parsed, property, "absent", collation, false)).isFalse();
      assertThat(text(parsed, property, "absent", collation, true)).isTrue();
      assertThat(text(parsed, property, "literal\n", collation, false)).isFalse();
      assertThat(text(parsed, "SUMMARY", "summary;control", collation, false)).isTrue();
      assertThat(CalendarReports.copy(parsed).toString()).contains("literal\\\\n");
    }
    var one = parse("VEVENT", "DTSTART:20261027T120000Z\r\nCATEGORIES:alpha\\;beta,other\r\n");
    assertThat(text(one, "CATEGORIES", "alpha;beta", collation, true)).isFalse();
  }

  private static boolean text(
      CalendarContent.Parsed parsed,
      String property,
      String value,
      String collation,
      boolean negate) {
    var filter =
        new PropertyFilter(
            property, false, null, new TextMatch(value, collation, negate), List.of());
    return new CalendarQuery(QueryLimits.defaults())
        .matches(
            parsed,
            component(
                "VCALENDAR",
                null,
                List.of(),
                List.of(component("VEVENT", null, List.of(filter), List.of()))),
            UTC);
  }

  static Stream<Arguments> invalidCorpus() {
    return Stream.of(
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=WEEKLY;BYMONTHDAY=1\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=DAILY;BYYEARDAY=1\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=MONTHLY;BYWEEKNO=1\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=DAILY;BYDAY=1MO\r\n"),
        Arguments.of(
            "VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=YEARLY;BYWEEKNO=1;BYDAY=1MO\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=YEARLY;BYSETPOS=1\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=DAILY;COUNT=0\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=DAILY;INTERVAL=0\r\n"),
        Arguments.of("VEVENT", "DTSTART;VALUE=DATE:20260101\r\nDTEND:20260102T100000Z\r\n"),
        Arguments.of("VTODO", "DTSTART;VALUE=DATE:20260101\r\nDUE:20260102T100000Z\r\n"),
        Arguments.of("VTODO", "DTSTART:20260102T100000Z\r\nDUE:20260101T100000Z\r\n"),
        Arguments.of("VTODO", "DTSTART:20260101T100000Z\r\nDURATION:-PT1H\r\n"),
        Arguments.of("VTODO", "COMPLETED:20260101T100000\r\n"),
        Arguments.of("VTODO", "PERCENT-COMPLETE:101\r\n"),
        Arguments.of("VTODO", "PRIORITY:10\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nSEQUENCE:-1\r\n"),
        Arguments.of(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nRDATE;VALUE=PERIOD:20260102T100000Z/20260101T100000Z\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nCATEGORIES:invalid\\q\r\n"),
        Arguments.of(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Repeat\r\nTRIGGER:-PT5M\r\nREPEAT:2\r\nEND:VALARM\r\n"),
        Arguments.of(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Absolute\r\nTRIGGER;VALUE=DATE-TIME:20260101T090000\r\nEND:VALARM\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nATTACH;VALUE=BINARY:SGVsbG8=\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101\r\n"),
        Arguments.of("VEVENT", "DTSTART;VALUE=DATE:20260101T100000Z\r\n"),
        Arguments.of("VEVENT", "DTSTART;VALUE=DATE-TIME:20260101\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nRDATE:20260102\r\n"),
        Arguments.of(
            "VEVENT", "DTSTART:20260101T100000Z\r\nEXDATE;VALUE=DATE:20260102T100000Z\r\n"),
        Arguments.of(
            "VEVENT", "DTSTART:20260101T100000Z\r\nATTACH;VALUE=BINARY;ENCODING=BASE64:!bad\r\n"));
  }

  @ParameterizedTest
  @MethodSource("invalidCorpus")
  void supportedNormativeNegativeCorpus(String type, String fields) {
    assertThatThrownBy(() -> parse(type, fields)).isInstanceOf(CalendarValidationException.class);
  }

  @Test
  void conflictingTimezoneOnsetsFailExplicitlyRatherThanGuessingAnOffset() {
    String zone =
        "BEGIN:VTIMEZONE\r\nTZID:Conflict\r\nBEGIN:STANDARD\r\nDTSTART:20000101T010000\r\n"
            + "TZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\nEND:STANDARD\r\nBEGIN:DAYLIGHT\r\n"
            + "DTSTART:20000101T020000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0300\r\nEND:DAYLIGHT\r\nEND:VTIMEZONE\r\n";
    assertThatThrownBy(
            () ->
                FloatingTimezone.parse(
                    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Conflict//EN\r\n"
                        + zone
                        + "END:VCALENDAR\r\n"))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.INVALID_DATA));
  }

  static Stream<Arguments> legalCorpus() {
    return Stream.of(
        Arguments.of("VEVENT", "DTSTART;VALUE=DATE:20260101\r\nDURATION:P2D\r\n"),
        Arguments.of("VEVENT", "DTSTART;VALUE=DATE:20260101\r\nDURATION:P1W\r\n"),
        Arguments.of("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:COUNT=2;FREQ=DAILY\r\n"),
        Arguments.of(
            "VEVENT", "DTSTART:20260101T100000\r\nRRULE:FREQ=DAILY;UNTIL=20260103T100000\r\n"),
        Arguments.of(
            "VEVENT", "DTSTART;VALUE=DATE:20260101\r\nRRULE:FREQ=YEARLY;UNTIL=20290101\r\n"),
        Arguments.of(
            "VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=YEARLY;BYMONTH=1;BYDAY=1MO\r\n"),
        Arguments.of(
            "VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:FREQ=MONTHLY;BYDAY=MO;BYSETPOS=-1\r\n"),
        Arguments.of(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nATTACH;VALUE=BINARY;ENCODING=BASE64:SGVsbG8=\r\n"),
        Arguments.of("VTODO", ""),
        Arguments.of("VTODO", "DUE:20260101T100000Z\r\n"),
        Arguments.of("VTODO", "DTSTART;VALUE=DATE:20260101\r\nDURATION:P2D\r\n"),
        Arguments.of("VTODO", "PERCENT-COMPLETE:0\r\nPRIORITY:0\r\nSTATUS:NEEDS-ACTION\r\n"),
        Arguments.of(
            "VTODO",
            "PERCENT-COMPLETE:100\r\nPRIORITY:9\r\nSTATUS:COMPLETED\r\nCOMPLETED:20260101T100000Z\r\n"),
        Arguments.of(
            "VTODO",
            "DUE:20260101T100000Z\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Due-only\r\nTRIGGER;RELATED=END:-PT5M\r\nEND:VALARM\r\n"),
        Arguments.of(
            "VEVENT",
            "DTSTART:20260101T100000Z\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Repeated\r\nTRIGGER:-PT5M\r\nREPEAT:2\r\nDURATION:PT5M\r\nEND:VALARM\r\n"));
  }

  @ParameterizedTest
  @MethodSource("legalCorpus")
  void legalNeighborCorpusRemainsAccepted(String type, String fields) {
    assertThat(parse(type, fields).uid()).isEqualTo("semantic");
  }

  @Test
  void unadvertisedRecurrenceScaleFailsExplicitlyInsteadOfExecutingGregorianFallback() {
    assertThatThrownBy(
            () ->
                parse("VEVENT", "DTSTART:20260101T100000Z\r\nRRULE:RSCALE=CHINESE;FREQ=YEARLY\r\n"))
        .isInstanceOfSatisfying(
            CalendarValidationException.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(CalendarValidationException.Reason.UNSUPPORTED_RSCALE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "GEO:91;0",
        "GEO:0;181",
        "COMMENT:bad\\q",
        "SUMMARY:unescaped,comma",
        "DESCRIPTION:unescaped;semicolon",
        "COMPLETED:20260101T000000Z",
        "PERCENT-COMPLETE:10",
        "TRIGGER:-PT5M",
        "X-BOOLEAN;VALUE=BOOLEAN:maybe",
        "X-INTEGER;VALUE=INTEGER:2147483648",
        "X-FLOAT;VALUE=FLOAT:NaN",
        "CLASS:invalid token"
      })
  void supplementalCoreTypeAndContextConstraints(String property) {
    assertThatThrownBy(() -> parse("VEVENT", "DTSTART:20260101T100000Z\r\n" + property + "\r\n"))
        .isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CLASS:X-CUSTOM-2",
        "CLASS:OTHER-CLASS",
        "CLASS:private",
        "GEO:90;180",
        "GEO:-90;-180",
        "COMMENT:a\\,b",
        "SUMMARY:escaped\\;semicolon",
        "DESCRIPTION:line\\nother",
        "X-BOOLEAN;VALUE=BOOLEAN:true",
        "X-INTEGER;VALUE=INTEGER:-2147483648",
        "X-FLOAT;VALUE=FLOAT:+3.14",
        "RELATED-TO;VALUE=URI:https://example.test/path;a,b",
        "X-OPAQUE:preserve;arbitrary,structured\\q"
      })
  void supplementalLegalNeighborsRetainOpaqueExtensionData(String property) {
    var parsed = parse("VEVENT", "DTSTART:20260101T100000Z\r\n" + property + "\r\n");
    assertThat(parsed.uid()).isEqualTo("semantic");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ACTION;X-MARK=1:display\r\nTRIGGER:-PT5M\r\n",
        "ACTION:X-CUSTOM-2\r\n",
        "ACTION:X-CUSTOM-2\r\nTRIGGER:-PT5M\r\nTRIGGER:-PT6M\r\n",
        "ACTION:EMAIL\r\nDESCRIPTION:Message\r\nTRIGGER:-PT5M\r\n"
      })
  void alarmRequirementsDoNotDependOnActionSpelling(String alarm) {
    assertThatThrownBy(
            () ->
                parse(
                    "VEVENT",
                    "DTSTART:20260101T100000Z\r\nBEGIN:VALARM\r\n" + alarm + "END:VALARM\r\n"))
        .isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"ACTION;X-MARK=1:display\r\nDESCRIPTION:Message\r\n", "ACTION:X-CUSTOM-2\r\n"})
  void legalCaseAndExtensionAlarmActionsArePreserved(String action) {
    assertThat(
            parse(
                    "VEVENT",
                    "DTSTART:20260101T100000Z\r\nBEGIN:VALARM\r\n"
                        + action
                        + "TRIGGER:-PT5M\r\nEND:VALARM\r\n")
                .uid())
        .isEqualTo("semantic");
  }
}
