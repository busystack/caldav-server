package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.time.ZoneOffset;
import java.util.List;
import net.fortuna.ical4j.model.parameter.*;
import org.busystack.caldav.calendar.query.*;
import org.busystack.caldav.calendar.query.CalendarFilter.*;
import org.junit.jupiter.api.Test;

class CalendarParameterSemanticsTest {
  private CalendarContent.Parsed parsed(String parameters) {
    return CalendarSemanticsTest.parse(
        "VEVENT",
        "DTSTART:20260101T100000Z\r\nATTENDEE;" + parameters + ":mailto:owner@example.test\r\n");
  }

  @Test
  void caretEscapesAreDecodedOnceFromLeftToRight() {
    var p = parsed("CN=^^n;X-UNKNOWN=^N");
    assertThat(
            p.calendar()
                .getComponents()
                .getFirst()
                .getRequiredProperty("ATTENDEE")
                .getRequiredParameter("CN")
                .getValue())
        .isEqualTo("^n");
    assertThat(
            p.calendar()
                .getComponents()
                .getFirst()
                .getRequiredProperty("ATTENDEE")
                .getRequiredParameter("X-UNKNOWN")
                .getValue())
        .isEqualTo("^N");
    assertThat(matchesParameter(p, "CN", "^n")).isTrue();
    assertThat(matchesParameter(p, "CN", "\n")).isFalse();
  }

  @Test
  void literalQuotePairIsNotMistakenForSyntacticQuoting() {
    var p = parsed("CN=^'Babe^'");
    assertThat(
            p.calendar()
                .getComponents()
                .getFirst()
                .getRequiredProperty("ATTENDEE")
                .getRequiredParameter("CN")
                .getValue())
        .isEqualTo("\"Babe\"");
    assertThat(matchesParameter(p, "CN", "\"Babe\"")).isTrue();
  }

  @Test
  void quotedMemberListRetainsBothUrisIncludingAnEmbeddedComma() {
    var p =
        parsed(
            "MEMBER=\"mailto:first@example.test\",\"mailto:second@example.test?subject=Hi,there\"");
    Member member =
        p.calendar()
            .getComponents()
            .getFirst()
            .getRequiredProperty("ATTENDEE")
            .getRequiredParameter("MEMBER");
    assertThat(member.getGroups().stream().map(java.net.URI::toString))
        .containsExactly(
            "mailto:first@example.test", "mailto:second@example.test?subject=Hi,there");
    assertThat(matchesParameter(p, "MEMBER", "second@example.test?subject=Hi,there")).isTrue();
  }

  @Test
  void delegationAndUnknownMultiValuedParametersRoundTripWithoutChangingTheirMeaning() {
    var p =
        parsed(
            "DELEGATED-TO=\"mailto:first@example.test\",\"mailto:second@example.test\";X-LIST=^^n,^'quoted^'");
    DelegatedTo to =
        p.calendar()
            .getComponents()
            .getFirst()
            .getRequiredProperty("ATTENDEE")
            .getRequiredParameter("DELEGATED-TO");
    assertThat(to.getDelegatees()).hasSize(2);
    assertThat(matchesParameter(p, "X-LIST", "^n")).isTrue();
    assertThat(matchesParameter(p, "X-LIST", "\"quoted\"")).isTrue();
    String output = p.calendar().copy().toString();
    assertThat(output)
        .contains(
            "X-LIST=^^n,^'quoted^'",
            "DELEGATED-TO=\"mailto:first@example.test\",\"mailto:second@example.test\"");
  }

  @Test
  void partialProjectionRetainsDecodedParametersAndCorrectWireEncoding() {
    var p = parsed("CN=^^n;MEMBER=\"mailto:first@example.test\",\"mailto:second@example.test\"");
    var event =
        new CalendarDataProjection.ComponentSelection(
            "VEVENT",
            false,
            false,
            List.of(new CalendarDataProjection.PropertySelection("ATTENDEE", true)),
            List.of());
    var root =
        new CalendarDataProjection.ComponentSelection(
            "VCALENDAR", false, false, List.of(), List.of(event));
    var selection = new CalendarDataProjection.Selection(root, null, null, null);
    var output =
        new CalendarDataProjection(QueryLimits.defaults())
            .project(p, selection, FloatingTimezone.of(ZoneOffset.UTC))
            .toString();
    assertThat(output)
        .contains(
            "ATTENDEE;CN=^^n;MEMBER=\"mailto:first@example.test\",\"mailto:second@example.test\":\r\n");
  }

  @Test
  void aSingleValuedParameterCannotBecomeAList() {
    assertThatThrownBy(() -> parsed("CN=Alice,Bob")).isInstanceOf(QueryException.class);
  }

  private boolean matchesParameter(CalendarContent.Parsed parsed, String name, String text) {
    var property =
        new PropertyFilter(
            "ATTENDEE",
            false,
            null,
            null,
            List.of(new ParameterFilter(name, false, new TextMatch(text, "i;octet", false))));
    var event = new ComponentFilter("VEVENT", false, null, List.of(property), List.of());
    var root = new ComponentFilter("VCALENDAR", false, null, List.of(), List.of(event));
    return new CalendarQuery(QueryLimits.defaults()).matches(parsed, root, ZoneOffset.UTC);
  }
}
