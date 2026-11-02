package org.busystack.caldav.calendar;

/** CalDAV distinguishes malformed calendar data, resource shape and collection capabilities. */
public final class CalendarValidationException extends IllegalArgumentException {
  public enum Reason {
    INVALID_DATA,
    INVALID_OBJECT,
    UNSUPPORTED_COMPONENT,
    UNSUPPORTED_RSCALE
  }

  private final Reason reason;

  public CalendarValidationException(Reason reason) {
    super(
        switch (java.util.Objects.requireNonNull(reason)) {
          case INVALID_DATA -> "Calendar data is invalid";
          case INVALID_OBJECT -> "Calendar object resource restrictions were violated";
          case UNSUPPORTED_COMPONENT -> "Calendar component is unsupported by this collection";
          case UNSUPPORTED_RSCALE -> "Recurrence scale is unsupported by this server";
        });
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
