package org.busystack.caldav.calendar;

/** Accepted calendar text must also be representable in a CalDAV XML calendar-data response. */
public final class CalendarTextProfile {
  private CalendarTextProfile() {}

  public static void validate(String text) {
    text.codePoints()
        .forEach(
            value -> {
              if (value < 0x20 && value != '\t' && value != '\r' && value != '\n'
                  || value == 0x7f
                  || value == 0xfffe
                  || value == 0xffff
                  || value >= 0xd800 && value <= 0xdfff)
                throw new IllegalArgumentException(
                    "Calendar contains an unsupported control or XML character");
            });
  }
}
