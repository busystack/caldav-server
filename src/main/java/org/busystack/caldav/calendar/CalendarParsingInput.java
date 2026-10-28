package org.busystack.caldav.calendar;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.fortuna.ical4j.data.UnfoldingReader;

/**
 * Adapts only structural content-line identifiers to the parser's canonical spelling. RFC 5545
 * names are case insensitive; property values, parameters and authoritative octets are not changed.
 * This is an input adapter, not a replacement calendar parser.
 */
public final class CalendarParsingInput {
  private CalendarParsingInput() {}

  /**
   * XML calendar-data needs characters rather than the authoritative octets. Keep ordinary source
   * folding intact; recover a split UTF-8 code point by unfolding only when raw decoding fails.
   */
  public static String reportText(byte[] original) {
    try {
      return strictUtf8(original, original.length);
    } catch (CharacterCodingException foldedOrMalformed) {
      try {
        return decode(original);
      } catch (CharacterCodingException malformed) {
        throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
      }
    }
  }

  /** Restore folded octets before decoding, including a fold inside a UTF-8 code point (§3.1). */
  static String decode(byte[] original) throws CharacterCodingException {
    byte[] unfolded = new byte[original.length];
    int length = 0;
    for (int index = 0; index < original.length; index++) {
      if (original[index] == '\r'
          && index + 2 < original.length
          && original[index + 1] == '\n'
          && (original[index + 2] == ' ' || original[index + 2] == '\t')) {
        index += 2;
      } else unfolded[length++] = original[index];
    }
    return strictUtf8(unfolded, length);
  }

  private static String strictUtf8(byte[] original, int length) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(original, 0, length))
        .toString();
  }

  public static Reader reader(String text) throws IOException {
    var unfolded = new StringBuilder(text.length());
    try (var input = new UnfoldingReader(new StringReader(text))) {
      int character;
      while ((character = input.read()) != -1) unfolded.append((char) character);
    }
    String value = unfolded.toString();
    StringBuilder normalized = new StringBuilder(value.length());
    int from = 0;
    while (from < value.length()) {
      int newline = value.indexOf('\n', from);
      int end = newline < 0 ? value.length() : newline + 1;
      int contentEnd = end;
      if (contentEnd > from && value.charAt(contentEnd - 1) == '\n') contentEnd--;
      if (contentEnd > from && value.charAt(contentEnd - 1) == '\r') contentEnd--;
      String line = value.substring(from, contentEnd);
      int colon = line.indexOf(':');
      if (colon > 0
          && (line.substring(0, colon).equalsIgnoreCase("BEGIN")
              || line.substring(0, colon).equalsIgnoreCase("END"))
          && identifier(line.substring(colon + 1))) {
        normalized.append(line.toUpperCase(Locale.ROOT));
      } else normalized.append(line);
      normalized.append(value, contentEnd, end);
      from = end;
    }
    return new StringReader(normalized.toString());
  }

  private static boolean identifier(String value) {
    return !value.isEmpty()
        && value
            .chars()
            .allMatch(
                c ->
                    c >= 'A' && c <= 'Z'
                        || c >= 'a' && c <= 'z'
                        || c >= '0' && c <= '9'
                        || c == '-');
  }
}
