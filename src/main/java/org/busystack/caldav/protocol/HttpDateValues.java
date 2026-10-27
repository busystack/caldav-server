package org.busystack.caldav.protocol;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.springframework.http.HttpHeaders;

/** Strict single HTTP-date fields around the embedded Servlet container's established parser. */
final class HttpDateValues {
  private static final DateTimeFormatter RFC850 =
      DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss 'GMT'", Locale.US)
          .withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter ASCTIME =
      new DateTimeFormatterBuilder()
          .appendPattern("EEE MMM ")
          .padNext(2)
          .appendValue(ChronoField.DAY_OF_MONTH)
          .appendPattern(" HH:mm:ss uuuu")
          .toFormatter(Locale.US)
          .withZone(ZoneOffset.UTC);

  private static final Set<String> LONG_DAYS =
      Arrays.stream(DayOfWeek.values())
          .map(day -> day.getDisplayName(TextStyle.FULL, Locale.US))
          .collect(Collectors.toUnmodifiableSet());

  private HttpDateValues() {}

  static Instant parse(HttpServletRequest request, String headerName) {
    return parse(request, headerName, Clock.systemUTC());
  }

  static Instant parse(HttpServletRequest request, String headerName, Clock clock) {
    var values = request.getHeaders(headerName);
    if (values == null || !values.hasMoreElements()) return null;
    String value = values.nextElement();
    if (values.hasMoreElements() || value == null || value.length() > 128) return null;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x20 && character != '\t' || character > 0x7e) return null;
    }
    value = value.strip();
    if (value.isEmpty()) return null;
    try {
      long parsed = FastHttpDateFormat.parseDate(value);
      if (parsed == -1) return null;
      Instant instant = seconds(Instant.ofEpochMilli(parsed));
      // The Servlet container parser can accept a date prefix or normalize an invalid date.
      // Require the entire single field to be one of the three standard serializations;
      // these formatters only format the Servlet result and do not parse input.
      if (value.equals(format(instant)) || value.equals(ASCTIME.format(instant))) return instant;
      String legacy = RFC850.format(instant);
      int comma = value.indexOf(',');
      if (comma < 0
          || !LONG_DAYS.contains(value.substring(0, comma))
          || !value.substring(comma).equals(legacy.substring(legacy.indexOf(',')))) return null;
      // RFC9110 section5.6.7 requires a two-digit year more than 50 years ahead
      // to denote the latest corresponding past year. Servlet date parsers can
      // use SimpleDateFormat's different default century window.
      ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC);
      ZonedDateTime corrected =
          instant
              .atZone(ZoneOffset.UTC)
              .withYear(
                  (now.getYear() / 100) * 100 + instant.atZone(ZoneOffset.UTC).getYear() % 100);
      if (corrected.isAfter(now.plusYears(50))) corrected = corrected.minusYears(100);
      return value.equals(RFC850.format(corrected)) ? corrected.toInstant() : null;
    } catch (IllegalArgumentException | java.time.DateTimeException exception) {
      return null;
    }
  }

  static String format(Instant instant) {
    var headers = new HttpHeaders();
    headers.setInstant(HttpHeaders.DATE, seconds(instant));
    return headers.getFirst(HttpHeaders.DATE);
  }

  static Instant seconds(Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS);
  }
}
