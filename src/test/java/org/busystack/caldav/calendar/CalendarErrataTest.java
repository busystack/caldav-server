package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.time.ZoneOffset;
import java.util.List;
import org.busystack.caldav.calendar.query.CalendarDataProjection;
import org.busystack.caldav.calendar.query.FloatingTimezone;
import org.busystack.caldav.calendar.query.QueryLimits;
import org.junit.jupiter.api.Test;

/**
 * Independent lexical fixtures exercise verified RFC5545 corrections, not library serialization.
 */
class CalendarErrataTest {
  @Test
  void requestStatusCanOccurMoreThanOnceAndRetainsItsContentLineBoundaries() {
    var parsed =
        CalendarSemanticsTest.parse(
            "VEVENT",
            "DTSTART:20261027T100000Z\r\nREQUEST-STATUS:2.0;Success\r\nREQUEST-STATUS:2.1;Fallback\r\n");
    var event = parsed.calendar().getComponents().getFirst();
    assertThat(event.getProperties("REQUEST-STATUS")).hasSize(2);
    String output = project(parsed, "REQUEST-STATUS");
    assertThat(output)
        .contains("REQUEST-STATUS:2.0;Success\r\n", "REQUEST-STATUS:2.1;Fallback\r\n");
  }

  @Test
  void aCommaInAQuotedContactAlternateUriRemainsPartOfTheUri() {
    var parsed =
        CalendarSemanticsTest.parse(
            "VEVENT",
            "DTSTART:20261027T100000Z\r\nCONTACT;ALTREP=\"ldap://example.test/o=ABC%20Industries,c=US???(cn=Jim%20Dolittle)\":Jim Dolittle\\, ABC Industries\r\n");
    assertThat(
            parsed
                .calendar()
                .getComponents()
                .getFirst()
                .getRequiredProperty("CONTACT")
                .getRequiredParameter("ALTREP")
                .getValue())
        .isEqualTo("ldap://example.test/o=ABC%20Industries,c=US???(cn=Jim%20Dolittle)");
    assertThat(project(parsed, "CONTACT"))
        .contains("o=ABC%20Industries,c=US???(cn=Jim%20Dolittle)");
  }

  @Test
  void absoluteAlarmTriggerUsesDateTimeAndRepeatRetainsItsElapsedInterval() {
    var parsed =
        CalendarSemanticsTest.parse(
            "VEVENT",
            "DTSTART:20261027T100000Z\r\nBEGIN:VALARM\r\nACTION:AUDIO\r\nTRIGGER;VALUE=DATE-TIME:20261026T120000Z\r\nREPEAT:4\r\nDURATION:PT1H\r\nEND:VALARM\r\n");
    String output = parsed.calendar().toString();
    assertThat(output)
        .contains(
            "TRIGGER;VALUE=DATE-TIME:20261026T120000Z\r\n", "REPEAT:4\r\n", "DURATION:PT1H\r\n");
  }

  private String project(CalendarContent.Parsed parsed, String name) {
    var component =
        new CalendarDataProjection.ComponentSelection(
            "VEVENT",
            false,
            false,
            List.of(new CalendarDataProjection.PropertySelection(name, false)),
            List.of());
    var root =
        new CalendarDataProjection.ComponentSelection(
            "VCALENDAR", false, false, List.of(), List.of(component));
    return new CalendarDataProjection(QueryLimits.defaults())
        .project(
            parsed,
            new CalendarDataProjection.Selection(root, null, null, null),
            FloatingTimezone.of(ZoneOffset.UTC))
        .toString();
  }
}
