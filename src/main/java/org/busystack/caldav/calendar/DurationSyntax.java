package org.busystack.caldav.calendar;

import java.util.regex.Pattern;

/** RFC 5545 §3.3.6 lexical alternatives, checked before temporal model normalization. */
public final class DurationSyntax {
  private static final String TIME =
      "T(?:[0-9]+H(?:[0-9]+M(?:[0-9]+S)?)?|[0-9]+M(?:[0-9]+S)?|[0-9]+S)";
  private static final Pattern DURATION =
      Pattern.compile(
          "[+-]?P(?:[0-9]+W|[0-9]+D(?:" + TIME + ")?|" + TIME + ")", Pattern.CASE_INSENSITIVE);

  private DurationSyntax() {}

  public static void validate(String value) {
    if (!DURATION.matcher(value).matches()) invalid();
  }

  /** PERIOD may end with a date-time or a duration; date-time parsing remains delegated. */
  public static void validatePeriodDurations(String value) {
    for (String period : value.split(",", -1)) {
      int slash = period.indexOf('/');
      if (slash < 1 || slash != period.lastIndexOf('/') || slash == period.length() - 1) invalid();
      String end = period.substring(slash + 1);
      if (end.charAt(0) < '0' || end.charAt(0) > '9') validate(end);
    }
  }

  private static void invalid() {
    throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
  }
}
