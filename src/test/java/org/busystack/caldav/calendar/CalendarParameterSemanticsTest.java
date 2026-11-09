package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.time.ZoneOffset;
import java.util.List;
import net.fortuna.ical4j.model.parameter.*;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.query.CalendarFilter.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarParameterSemanticsTest {
  private CalendarContent.Parsed parsed(String parameters) {
    return CalendarSemanticsTest.parse(
        "VEVENT",
        "DTSTART:20260101T100000Z\r\nATTENDEE;" + parameters + ":mailto:owner@example.test\r\n");
  }

  @Test
  void caretEscapesAreDecodedOnceFromLeftToRight() {
    var p = parsed("CN=^^n;X-UNKNOWN=^N");
    assertThat(
            p.calendar()
                .getComponents()
                .getFirst()
                .getRequiredProperty("ATTENDEE")
                .getRequiredParameter("CN")
                .getValue())
        .isEqualTo("^n");
    assertThat(
            p.calendar()
                .getComponents()
                .getFirst()
                .getRequiredProperty("ATTENDEE")
                .getRequiredParameter("X-UNKNOWN")
                .getValue())
        .isEqualTo("^N");
    assertThat(matchesParameter(p, "CN", "^n")).isTrue();
    assertThat(matchesParameter(p, "CN", "\n")).isFalse();
  }

  @Test
  void literalQuotePairIsNotMistakenForSyntacticQuoting() {
    var p = parsed("CN=^'Babe^'");
    assertThat(
            p.calendar()
                .getComponents()
                .getFirst()
                .getRequiredProperty("ATTENDEE")
                .getRequiredParameter("CN")
                .getValue())
        .isEqualTo("\"Babe\"");
    assertThat(matchesParameter(p, "CN", "\"Babe\"")).isTrue();
  }

  @Test
  void quotedMemberListRetainsBothUrisIncludingAnEmbeddedComma() {
    var p =
        parsed(
            "MEMBER=\"mailto:first@example.test\",\"mailto:second@example.test?subject=Hi,there\"");
    Member member =
        p.calendar()
            .getComponents()
            .getFirst()
            .getRequiredProperty("ATTENDEE")
            .getRequiredParameter("MEMBER");
    assertThat(member.getGroups().stream().map(java.net.URI::toString))
        .containsExactly(
            "mailto:first@example.test", "mailto:second@example.test?subject=Hi,there");
    assertThat(matchesParameter(p, "MEMBER", "second@example.test?subject=Hi,there")).isTrue();
  }

  @Test
  void delegationAndUnknownMultiValuedParametersRoundTripWithoutChangingTheirMeaning() {
    var p =
        parsed(
            "DELEGATED-TO=\"mailto:first@example.test\",\"mailto:second@example.test\";X-LIST=^^n,^'quoted^'");
    DelegatedTo to =
        p.calendar()
            .getComponents()
            .getFirst()
            .getRequiredProperty("ATTENDEE")
            .getRequiredParameter("DELEGATED-TO");
    assertThat(to.getDelegatees()).hasSize(2);
    assertThat(matchesParameter(p, "X-LIST", "^n")).isTrue();
    assertThat(matchesParameter(p, "X-LIST", "\"quoted\"")).isTrue();
    String output = p.calendar().copy().toString();
    assertThat(output)
        .contains(
            "X-LIST=^^n,^'quoted^'",
            "DELEGATED-TO=\"mailto:first@example.test\",\"mailto:second@example.test\"");
  }

  @Test
  void partialProjectionRetainsDecodedParametersAndCorrectWireEncoding() {
    var p = parsed("CN=^^n;MEMBER=\"mailto:first@example.test\",\"mailto:second@example.test\"");
    var event =
        new CalendarDataProjection.ComponentSelection(
            "VEVENT",
            false,
            false,
            List.of(new CalendarDataProjection.PropertySelection("ATTENDEE", true)),
            List.of());
    var root =
        new CalendarDataProjection.ComponentSelection(
            "VCALENDAR", false, false, List.of(), List.of(event));
    var selection = new CalendarDataProjection.Selection(root, null, null, null);
    var output =
        new CalendarDataProjection(QueryLimits.defaults())
            .project(p, selection, FloatingTimezone.of(ZoneOffset.UTC))
            .toString();
    assertThat(output)
        .contains(
            "ATTENDEE;CN=^^n;MEMBER=\"mailto:first@example.test\",\"mailto:second@example.test\":\r\n");
  }

  @Test
  void aSingleValuedParameterCannotBecomeAList() {
    assertThatThrownBy(() -> parsed("CN=Alice,Bob")).isInstanceOf(QueryException.class);
  }

  @Test
  void parameterAdapterKeepsQuotedDelimitersAndPlaceholderLiterals() {
    var p = parsed("CN=\"BUSYSTACK;A:B,C=D\";X-LIST=BUSYSTACK,\"a;b:c,d\"");
    assertThat(matchesParameter(p, "CN", "BUSYSTACK;A:B,C=D")).isTrue();
    assertThat(matchesParameter(p, "X-LIST", "a;b:c,d")).isTrue();
  }

  @Test
  void malformedParameterHeadersNeverBecomeSafePlaceholders() {
    for (String value :
        List.of("CN=\"unterminated", "CN=\"Alice\"junk", "CN=Ali\"ce", "CN", "=Alice"))
      assertThatThrownBy(() -> parsed(value)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void parameterAdapterBoundsListValuesBeforeModelConstruction() {
    assertThatThrownBy(
            () -> parsed("X-LIST=" + String.join(",", java.util.Collections.nCopies(1001, "x"))))
        .isInstanceOf(RuntimeException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CUTYPE=BAD VALUE",
        "ROLE=BAD VALUE",
        "PARTSTAT=BAD VALUE",
        "RELTYPE=BAD VALUE",
        "VALUE=BAD VALUE",
        "CUTYPE=",
        "ROLE=",
        "PARTSTAT=",
        "RELTYPE=",
        "VALUE="
      })
  void openParameterVocabulariesStillRequireTokens(String parameter) {
    assertThatThrownBy(() -> tokenParameter(parameter)).isInstanceOf(RuntimeException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CUTYPE=X-ROBOT",
        "ROLE=FUTURE-ROLE",
        "PARTSTAT=X-FUTURE",
        "RELTYPE=FINISHTOSTART",
        "VALUE=X-FUTURE"
      })
  void openParameterVocabulariesPreserveFutureTokens(String parameter) {
    assertThatCode(() -> tokenParameter(parameter)).doesNotThrowAnyException();
  }

  private CalendarContent.Parsed tokenParameter(String parameter) {
    if (parameter.startsWith("VALUE="))
      return CalendarSemanticsTest.parse(
          "VEVENT", "DTSTART:20261109T090000Z\r\nX-OPAQUE;" + parameter + ":opaque\r\n");
    if (parameter.startsWith("RELTYPE="))
      return CalendarSemanticsTest.parse(
          "VEVENT", "DTSTART:20261109T090000Z\r\nRELATED-TO;" + parameter + ":other\r\n");
    return parsed(parameter);
  }

  @ParameterizedTest
  @ValueSource(strings = {"THISANDPRIOR", "X-FUTURE", "", "BAD VALUE"})
  void recurrenceRangeUsesOnlyTheCurrentStandardValue(String range) {
    assertThatThrownBy(
            () ->
                CalendarSemanticsTest.parse(
                    "VEVENT",
                    "DTSTART:20261109T090000Z\r\nRECURRENCE-ID;RANGE="
                        + range
                        + ":20261109T090000Z\r\n"))
        .isInstanceOf(RuntimeException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"RSVP=MAYBE", "RSVP=", "LANGUAGE=en_US", "LANGUAGE="})
  void invalidKnownParameterValuesAreRejectedBeforeModelCoercion(String parameter) {
    assertThatThrownBy(() -> parsed(parameter)).isInstanceOf(RuntimeException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "RSVP=true",
        "RSVP=FALSE",
        "LANGUAGE=en-US",
        "LANGUAGE=zh-Hant-TW",
        "LANGUAGE=i-klingon",
        "LANGUAGE=x-private"
      })
  void validBooleanAndLanguageParametersRemainPreserved(String parameter) {
    var parsed = parsed(parameter);
    var original = parsed.calendar().getComponents().getFirst().getRequiredProperty("ATTENDEE");
    var copied =
        parsed.calendar().copy().getComponents().getFirst().getRequiredProperty("ATTENDEE");
    String name = parameter.substring(0, parameter.indexOf('='));
    assertThat(copied.getRequiredParameter(name).getValue())
        .isEqualTo(original.getRequiredParameter(name).getValue());
    assertThat(original.getRequiredParameter(name).getValue())
        .isEqualToIgnoringCase(parameter.substring(parameter.indexOf('=') + 1));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CUTYPE=INDIVIDUAL",
        "MEMBER=\"mailto:group@example.invalid\"",
        "ROLE=REQ-PARTICIPANT",
        "PARTSTAT=ACCEPTED",
        "RSVP=TRUE",
        "DELEGATED-TO=\"mailto:other@example.invalid\"",
        "DELEGATED-FROM=\"mailto:other@example.invalid\"",
        "SENT-BY=\"mailto:agent@example.invalid\"",
        "CN=Synthetic",
        "DIR=\"https://example.invalid/directory\""
      })
  void alarmAttendeesDoNotAcquireEventParticipationParameters(String parameter) {
    assertThatThrownBy(
            () ->
                CalendarSemanticsTest.parse(
                    "VEVENT", "DTSTART:20261109T090000Z\r\n" + emailAlarm(";" + parameter)))
        .isInstanceOf(CalendarValidationException.class);
    assertThatCode(() -> parsed(parameter)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ";X-KEEP=opaque", ";LANGUAGE=en"})
  void plainAndExtendedAlarmDeliveryAddressesRemainValid(String parameters) {
    var parsed =
        CalendarSemanticsTest.parse(
            "VEVENT", "DTSTART:20261109T090000Z\r\n" + emailAlarm(parameters));
    assertThat(parsed.calendar().copy().toString())
        .contains("ATTENDEE" + parameters + ":mailto:synthetic@example.invalid");
  }

  private static String emailAlarm(String parameters) {
    return "BEGIN:VALARM\r\nACTION:EMAIL\r\nDESCRIPTION:Synthetic reminder\r\nSUMMARY:Synthetic alarm\r\nTRIGGER:-PT5M\r\nATTENDEE"
        + parameters
        + ":mailto:synthetic@example.invalid\r\nEND:VALARM\r\n";
  }

  private boolean matchesParameter(CalendarContent.Parsed parsed, String name, String text) {
    var property =
        new PropertyFilter(
            "ATTENDEE",
            false,
            null,
            null,
            List.of(new ParameterFilter(name, false, new TextMatch(text, "i;octet", false))));
    var event = new ComponentFilter("VEVENT", false, null, List.of(property), List.of());
    var root = new ComponentFilter("VCALENDAR", false, null, List.of(), List.of(event));
    return new CalendarQuery(QueryLimits.defaults()).matches(parsed, root, ZoneOffset.UTC);
  }
}
