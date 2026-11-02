package org.busystack.caldav.calendar;

import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;

/** Checks RECUR rule-part structure before the library's typed model replaces duplicate keys. */
public final class RecurrenceRuleSyntax {
  private static final Pattern WEEKDAY =
      Pattern.compile("([+-]?[0-9]{1,2})?(?:SU|MO|TU|WE|TH|FR|SA)", Pattern.CASE_INSENSITIVE);

  private RecurrenceRuleSyntax() {}

  public static void validate(String value) {
    var parts = new HashSet<String>();
    for (String item : value.split(";", -1)) {
      int equals = item.indexOf('=');
      if (equals < 1 || equals == item.length() - 1 || item.indexOf('=', equals + 1) >= 0)
        invalid();
      String key = item.substring(0, equals).toUpperCase(Locale.ROOT);
      if (!parts.add(key)) invalid();
      if (key.equals("RSCALE"))
        throw new CalendarValidationException(
            CalendarValidationException.Reason.UNSUPPORTED_RSCALE);
      if (key.equals("COUNT") || key.equals("INTERVAL")) {
        String number = item.substring(equals + 1);
        if (!number.chars().allMatch(c -> c >= '0' && c <= '9')
            || number.chars().allMatch(c -> c == '0')) invalid();
      }
      if (key.equals("BYDAY"))
        for (String day : item.substring(equals + 1).split(",", -1)) {
          var weekday = WEEKDAY.matcher(day);
          if (!weekday.matches()) invalid();
          String ordinal = weekday.group(1);
          if (ordinal != null) {
            int offset = Integer.parseInt(ordinal);
            if (offset == 0 || Math.abs(offset) > 53) invalid();
          }
        }
    }
    if (!parts.contains("FREQ") || parts.contains("COUNT") && parts.contains("UNTIL")) invalid();
  }

  private static void invalid() {
    throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
  }
}
