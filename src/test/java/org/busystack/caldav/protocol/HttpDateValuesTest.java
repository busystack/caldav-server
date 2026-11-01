package org.busystack.caldav.protocol;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class HttpDateValuesTest {
  private static final String HEADER = "If-Unmodified-Since";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC);
  private static final Instant DATE = Instant.parse("1994-11-06T08:49:37Z");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Sun, 06 Nov 1994 08:49:37 GMT",
        "Sunday, 06-Nov-94 08:49:37 GMT",
        "Sun Nov  6 08:49:37 1994",
        " \tSun, 06 Nov 1994 08:49:37 GMT\t "
      })
  void acceptsTheThreeHttpDateFormats(String value) {
    var request = new MockHttpServletRequest();
    request.addHeader(HEADER, value);
    assertThat(HttpDateValues.parse(request, HEADER, CLOCK)).isEqualTo(DATE);
  }

  static Stream<Arguments> invalidFields() {
    return Stream.of(
        Arguments.of(""),
        Arguments.of("not a date"),
        Arguments.of("Monday, 06-Nov-94 08:49:37 GMT"),
        Arguments.of("Sat, 01 Jan 10000 00:00:00 GMT"),
        Arguments.of("Sat, 01 Jan +10000 00:00:00 GMT"),
        Arguments.of("Sat Jan  1 00:00:00 10000"),
        Arguments.of("Sat Jan  1 00:00:00 +10000"),
        Arguments.of("Sun, 06 Nov 1994 08:49:37 GMT, Sun, 06 Nov 1994 08:49:37 GMT"),
        Arguments.of("Sun Nov  6 08:49:37 1994, Sun Nov  6 08:49:37 1994"),
        Arguments.of("Sun, 06 Nov 1994 08:49:37 GMT; ignored=true"),
        Arguments.of("Sun, 06 Nov 1994 08:49:37 GMT trailing"),
        Arguments.of("Sun, 31 Feb 1994 08:49:37 GMT"),
        Arguments.of("Sun, 06 Nov 1994 08:49:37 GMT\r\n"),
        Arguments.of("Sun, 06 Nov 1994 08:49:37 GMT\u0000"),
        Arguments.of(" ".repeat(129)));
  }

  @ParameterizedTest
  @MethodSource("invalidFields")
  void ignoresInvalidCombinedOrPartiallyParsedValues(String value) {
    var request = new MockHttpServletRequest();
    request.addHeader(HEADER, value);
    assertThat(HttpDateValues.parse(request, HEADER, CLOCK)).isNull();
  }

  @Test
  void absentAndDuplicateFieldsAreIgnoredEvenWhenTheirValuesAgree() {
    var request = new MockHttpServletRequest();
    assertThat(HttpDateValues.parse(request, HEADER, CLOCK)).isNull();
    request.addHeader(HEADER, "Sun, 06 Nov 1994 08:49:37 GMT");
    request.addHeader(HEADER, "Sun, 06 Nov 1994 08:49:37 GMT");
    assertThat(HttpDateValues.parse(request, HEADER, CLOCK)).isNull();
  }

  @Test
  void canonicalOutputIsGmtImfFixdateAtWholeSecondPrecision() {
    assertThat(HttpDateValues.format(DATE.plusNanos(123456789)))
        .isEqualTo("Sun, 06 Nov 1994 08:49:37 GMT");
    assertThat(HttpDateValues.seconds(DATE.plusNanos(123456789))).isEqualTo(DATE);
    assertThat(HttpDateValues.seconds(null)).isNull();
    assertThat(HttpDateValues.seconds(Instant.parse("1969-12-31T23:59:59.999Z")))
        .isEqualTo(Instant.parse("1969-12-31T23:59:59Z"));
  }

  @Test
  void obsoleteYearsUseTheHttpFiftyYearRuleRatherThanTheServletDefaultWindow() {
    var request = new MockHttpServletRequest();
    request.addHeader(HEADER, "Wednesday, 01-Jan-70 00:00:00 GMT");
    assertThat(HttpDateValues.parse(request, HEADER, CLOCK))
        .isEqualTo(Instant.parse("2070-01-01T00:00:00Z"));
    request = new MockHttpServletRequest();
    request.addHeader(HEADER, "Sunday, 06-Nov-94 08:49:37 GMT");
    assertThat(HttpDateValues.parse(request, HEADER, CLOCK)).isEqualTo(DATE);
  }
}
