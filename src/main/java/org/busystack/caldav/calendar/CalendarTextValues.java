package org.busystack.caldav.calendar;

import java.util.ArrayList;
import java.util.List;

/** Decodes a known list-TEXT property's original value once, retaining escaped list delimiters. */
public final class CalendarTextValues {
  private CalendarTextValues() {}

  /** Validate a known scalar TEXT value without decoding it or changing the authoritative bytes. */
  public static void scalar(String lexical) {
    for (int index = 0; index < lexical.length(); index++) {
      char value = lexical.charAt(index);
      if (value == ',' || value == ';') invalid();
      if (value == '\\') {
        if (++index == lexical.length()) invalid();
        char escaped = lexical.charAt(index);
        if (escaped != 'n' && escaped != 'N' && escaped != '\\' && escaped != ',' && escaped != ';')
          invalid();
      }
    }
  }

  public static List<String> list(String lexical) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int index = 0; index < lexical.length(); index++) {
      char value = lexical.charAt(index);
      if (value == ',') {
        values.add(current.toString());
        current.setLength(0);
      } else if (value == '\\') {
        if (++index == lexical.length()) invalid();
        char escaped = lexical.charAt(index);
        if (escaped == 'n' || escaped == 'N') current.append('\n');
        else if (escaped == '\\' || escaped == ',' || escaped == ';') current.append(escaped);
        else invalid();
      } else {
        if (value == ';') invalid();
        current.append(value);
      }
    }
    values.add(current.toString());
    return List.copyOf(values);
  }

  /** RFC 5545 §3.8.8.3: code;description[;extension], with ordinary TEXT escaping. */
  public static void requestStatus(String lexical) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < lexical.length(); index++) {
      char value = lexical.charAt(index);
      if (value == '\\') index++;
      else if (value == ';') {
        parts.add(lexical.substring(start, index));
        start = index + 1;
      }
    }
    parts.add(lexical.substring(start));
    if (parts.size() < 2
        || parts.size() > 3
        || !parts.getFirst().matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) invalid();
    for (String part : parts.subList(1, parts.size())) scalar(part);
  }

  private static void invalid() {
    throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
  }
}
