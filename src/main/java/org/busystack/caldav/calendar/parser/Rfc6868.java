package org.busystack.caldav.calendar.parser;

/** Sequential parameter escape decoding; content-line tokenization remains in iCal4j. */
public final class Rfc6868 {
  private Rfc6868() {}

  public static String decode(String value) {
    var decoded = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char c = value.charAt(index);
      if (c == '^' && index + 1 < value.length()) {
        char next = value.charAt(index + 1);
        if (next == '^' || next == 'n' || next == '\'') {
          decoded.append(next == 'n' ? '\n' : next == '\'' ? '"' : '^');
          index++;
          continue;
        }
      }
      decoded.append(c);
    }
    return decoded.toString();
  }

  public static String value(String raw) {
    return decode(net.fortuna.ical4j.util.Strings.unquote(raw));
  }
}
