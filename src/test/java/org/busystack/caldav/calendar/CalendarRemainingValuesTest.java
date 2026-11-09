package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.util.stream.Stream;
import org.busystack.caldav.calendar.query.QueryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarRemainingValuesTest {
  static CalendarContent.Parsed parse(String fields) {
    return CalendarSemanticsTest.parse(
        "VEVENT",
        "DTSTART:20261109T090000Z\r\n" + fields + (fields.endsWith("\r\n") ? "" : "\r\n"));
  }

  @Test
  void explicitDefaultAttachmentEncodingSurvivesModelCopies() {
    var parsed = parse("ATTACH;ENCODING=8BIT:https://example.invalid/file");
    var copied = parsed.calendar().copy();
    assertThat(copied.toString()).contains("ATTACH;ENCODING=8BIT:https://example.invalid/file");
    assertThat(copied.getComponents().getFirst().getRequiredProperty("ATTACH").getValue())
        .isEqualTo("https://example.invalid/file");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "240000",
        "236000",
        "235961",
        "0900",
        "09:00:00",
        "090000.5",
        "090000-0800",
        "",
        "090000Z,250000"
      })
  void declaredTimeRejectsMalformedOrOutOfRangeValues(String value) {
    assertThatThrownBy(() -> parse("X-TIME;VALUE=TIME:" + value))
        .isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"000000", "235959", "090000Z", "235960Z", "090000,100000"})
  void declaredTimePreservesValidFloatingUtcAndListValues(String value) {
    var parsed = parse("X-TIME;VALUE=TIME:" + value);
    var property = parsed.calendar().getComponents().getFirst().getRequiredProperty("X-TIME");
    assertThat(parsed.lexicalValue(property)).isEqualTo(value);
    assertThat(property.getParameter("VALUE").orElseThrow().getValue()).isEqualTo("TIME");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "X-TIME;VALUE=TIME;TZID=Europe/Berlin:090000Z",
        "X-TIME;VALUE=TIME;TZID=Europe/Berlin:090000,100000Z"
      })
  void utcTimeCannotCarryTimezoneReference(String field) {
    assertThatThrownBy(() -> parse(field)).isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "X-TIME;VALUE=TIME;TZID=Europe/Berlin:090000",
        "X-OPAQUE:240000",
        "X-OPAQUE;VALUE=X-FUTURE:240000"
      })
  void validZonedTimeAndUndeclaredFutureDataRemainPreserved(String field) {
    assertThatCode(() -> parse(field)).doesNotThrowAnyException();
  }

  static Stream<Arguments> attachments() {
    return Stream.of(
        Arguments.of("ATTACH:https://example.invalid/file", true),
        Arguments.of("ATTACH;VALUE=URI:urn:example:attachment", true),
        Arguments.of("ATTACH;ENCODING=8BIT:https://example.invalid/file", true),
        Arguments.of("ATTACH;FMTTYPE=text/plain;X-PARAM=opaque:https://example.invalid/file", true),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=BASE64;FMTTYPE=text/plain:SGVsbG8=", true),
        Arguments.of("ATTACH;ENCODING=BASE64;VALUE=BINARY:SGVsbG8=", true),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=BASE64:", true),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=BASE64:AA==", true),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=BASE64:AAA=", true),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=BASE64:AAAA", true),
        Arguments.of("ATTACH;VALUE=TEXT:https://example.invalid/file", false),
        Arguments.of("ATTACH;VALUE=X-FUTURE:https://example.invalid/file", false),
        Arguments.of("ATTACH;VALUE=BINARY:SGVsbG8=", false),
        Arguments.of("ATTACH;ENCODING=BASE64:https://example.invalid/file", false),
        Arguments.of("ATTACH;VALUE=URI;ENCODING=BASE64:SGVsbG8=", false),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=8BIT:SGVsbG8=", false),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=BASE64:SGVsbG8", false),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=BASE64:SGVsbG8!", false),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=BASE64:A===", false),
        Arguments.of("ATTACH;VALUE=BINARY;ENCODING=BASE64:AA=A", false),
        Arguments.of("ATTACH;FMTTYPE=malformed:https://example.invalid/file", false));
  }

  @ParameterizedTest
  @MethodSource("attachments")
  void attachmentsUseOnlyTheirDefinedRepresentationsAndParameters(String field, boolean valid) {
    if (valid) {
      var parsed = parse(field);
      var property = parsed.calendar().getComponents().getFirst().getRequiredProperty("ATTACH");
      assertThat(parsed.lexicalValue(property)).isEqualTo(field.substring(field.indexOf(':') + 1));
    } else assertThatThrownBy(() -> parse(field)).isInstanceOf(CalendarValidationException.class);
  }

  @Test
  void duplicateAttachmentFormatParameterHasTheRequiredInvalidDataRejection() {
    assertThatThrownBy(
            () -> parse("ATTACH;FMTTYPE=text/plain;FMTTYPE=text/html:https://example.invalid/file"))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.INVALID_DATA));
  }

  @Test
  void attachmentContextsKeepRepeatedEventTaskAndEmailDataButAudioHasOnlyOne() {
    String attachments =
        "ATTACH:https://example.invalid/file\r\nATTACH;VALUE=BINARY;ENCODING=BASE64:SGVsbG8=\r\n";
    assertThatCode(() -> parse(attachments)).doesNotThrowAnyException();
    assertThatCode(() -> CalendarSemanticsTest.parse("VTODO", attachments))
        .doesNotThrowAnyException();
    String email =
        "BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER:-PT5M\r\nDESCRIPTION:Reminder\r\nSUMMARY:Reminder\r\nATTENDEE:mailto:synthetic@example.invalid\r\n"
            + attachments
            + "END:VALARM\r\n";
    assertThatCode(() -> parse(email)).doesNotThrowAnyException();
    String audio =
        "BEGIN:VALARM\r\nACTION:AUDIO\r\nTRIGGER:-PT5M\r\nATTACH:https://example.invalid/sound\r\nEND:VALARM\r\n";
    assertThatCode(() -> parse(audio)).doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                parse(
                    audio.replace(
                        "END:VALARM", "ATTACH:https://example.invalid/second\r\nEND:VALARM")))
        .isInstanceOf(CalendarValidationException.class);
  }
}
