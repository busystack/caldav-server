package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import org.junit.jupiter.api.Test;

class CalendarDuplicateRecurrenceTest {
  @Test
  void recurrenceRangeParametersDoNotCreateADifferentInstanceIdentity() {
    String components =
        event(null, "20260101T100000Z", "RRULE:FREQ=DAILY;COUNT=3\r\n")
            + event(":20260102T100000Z", "20260102T110000Z", "")
            + event(";RANGE=THISANDFUTURE:20260102T100000Z", "20260102T120000Z", "");
    assertThatThrownBy(() -> parseComponents("VEVENT", components))
        .isInstanceOfSatisfying(
            CalendarValidationException.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(CalendarValidationException.Reason.INVALID_OBJECT));
  }

  @Test
  void equivalentUtcAndTimezoneIdentifiersCannotDuplicateOneInstance() {
    String components =
        event(null, "20260101T100000Z", "RRULE:FREQ=DAILY;COUNT=3\r\n")
            + event(":20260102T100000Z", "20260102T110000Z", "")
            + event(";TZID=America/New_York:20260102T050000", "20260102T120000Z", "");
    assertThatThrownBy(() -> parseComponents("VEVENT", components))
        .isInstanceOfSatisfying(
            CalendarValidationException.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(CalendarValidationException.Reason.INVALID_OBJECT));
  }
}
