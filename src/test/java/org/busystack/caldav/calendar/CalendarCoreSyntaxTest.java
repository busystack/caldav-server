package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarCoreSyntaxTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "URL:relative",
        "ATTENDEE:relative",
        "ORGANIZER:relative",
        "ATTACH:relative",
        "ATTENDEE;MEMBER=\"relative\":mailto:a@example.invalid",
        "ATTENDEE;DELEGATED-TO=\"relative\":mailto:a@example.invalid",
        "ATTENDEE;DELEGATED-FROM=\"relative\":mailto:a@example.invalid",
        "ATTENDEE;DIR=\"relative\":mailto:a@example.invalid",
        "ORGANIZER;SENT-BY=\"relative\":mailto:a@example.invalid",
        "DESCRIPTION;ALTREP=\"relative\":Description",
        "X-URI;VALUE=URI:relative",
        "X-ADDRESS;VALUE=CAL-ADDRESS:relative",
        "URL:",
        "ATTENDEE:"
      })
  void uriValuesRequireAnAbsoluteUri(String field) {
    assertThatThrownBy(
            () ->
                CalendarSemanticsTest.parse(
                    "VEVENT", "DTSTART:20261109T090000Z\r\n" + field + "\r\n"))
        .isInstanceOf(RuntimeException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "URL:urn:example:event", "ATTENDEE:mailto:synthetic@example.invalid",
        "ATTACH;VALUE=BINARY;ENCODING=BASE64:SGVsbG8=", "RELATED-TO:relative-text",
        "X-OPAQUE:relative-value", "X-OPAQUE;X-URI=relative-value:opaque",
        "ATTENDEE;MEMBER=\"mailto:a@example.invalid\",\"urn:example:group\":mailto:b@example.invalid"
      })
  void absoluteAndOpaqueNeighborsStayValid(String field) {
    assertThatCode(
            () ->
                CalendarSemanticsTest.parse(
                    "VEVENT", "DTSTART:20261109T090000Z\r\n" + field + "\r\n"))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2;Success",
        "2.0",
        "2.0;Success;Extra;Unexpected",
        "2.0;bad\\q",
        "-2.0;Success",
        "2.;Success",
        "2.0;unescaped,comma",
        "2.0;Success;bad\\q",
        "9.9.9.9;Too many code levels"
      })
  void requestStatusRequiresItsStructuredGrammar(String value) {
    assertThatThrownBy(
            () ->
                CalendarSemanticsTest.parse(
                    "VEVENT", "DTSTART:20261109T090000Z\r\nREQUEST-STATUS:" + value + "\r\n"))
        .isInstanceOf(CalendarValidationException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2.0;Success",
        "3.1.1;Invalid property;VALUE=DATE",
        "2.0;",
        "2.0;Semi\\;comma\\,slash\\\\line\\n;Opaque\\;detail",
        "9.99.999;Future code"
      })
  void requestStatusRetainsEscapedTextAndTwoOrThreeCodeLevels(String value) {
    assertThatCode(
            () ->
                CalendarSemanticsTest.parse(
                    "VEVENT", "DTSTART:20261109T090000Z\r\nREQUEST-STATUS:" + value + "\r\n"))
        .doesNotThrowAnyException();
  }
}
