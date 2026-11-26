package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.busystack.caldav.calendar.CalendarSemanticsTest.*;

import java.time.ZoneId;
import java.util.List;
import org.busystack.caldav.calendar.query.CalendarQuery;
import org.busystack.caldav.calendar.query.FloatingTimezone;
import org.busystack.caldav.calendar.query.QueryLimits;
import org.busystack.caldav.calendar.recurrence.CalendarRecurrence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarJournalTest {
  private static final String JOURNAL = "VJOURNAL";

  @Test
  void undatedInventoryAndPropertyMultiplicity() {
    var journal =
        parse(
            JOURNAL,
            "STATUS:DRAFT\r\nDESCRIPTION;LANGUAGE=en:One\r\nDESCRIPTION;LANGUAGE=fr:Deux\r\n"
                + "LINK;VALUE=UID:target\r\nLINK;VALUE=XML-REFERENCE:https://example.invalid/j.xml#xpointer(/j)\r\n"
                + "RELATED-TO;VALUE=UID:target\r\nX-KEEP;X-FLAG=original:value\r\n");
    assertThat(match(journal, JOURNAL, null)).isTrue();
    assertThat(point(journal, JOURNAL, "2026-01-01T00:00:00Z")).isFalse();
    final var entry = journal.calendar().getComponents().getFirst();
    assertThat(entry.getProperties("DESCRIPTION")).hasSize(2);
    assertThat(entry.<net.fortuna.ical4j.model.Property>getProperties("LINK"))
        .extracting(p -> p.getParameter("VALUE").orElseThrow().getValue())
        .containsExactly("UID", "XML-REFERENCE");
  }

  @Test
  void publishingExtensionsSurviveNestedReportCopies() {
    var journal =
        parse(
            JOURNAL,
            "DTSTART:20261125T090000Z\r\nCOLOR:darkblue\r\n"
                + "IMAGE;VALUE=URI;DISPLAY=BADGE:https://example.invalid/image.png\r\n"
                + "STYLED-DESCRIPTION;VALUE=TEXT;FMTTYPE=text/html:<b>Fixture</b>\r\n"
                + "STRUCTURED-DATA;VALUE=URI;SCHEMA=\"https://schema.org/Thing\":https://example.invalid/data.json\r\n"
                + "STRUCTURED-DATA;VALUE=URI:https://example.invalid/data.xml\r\n"
                + "BEGIN:PARTICIPANT\r\nUID:person\r\nPARTICIPANT-TYPE:CONTACT\r\n"
                + "BEGIN:VLOCATION\r\nUID:place\r\nNAME:Venue\r\nEND:VLOCATION\r\n"
                + "END:PARTICIPANT\r\nBEGIN:VRESOURCE\r\nUID:room\r\nRESOURCE-TYPE:ROOM\r\nEND:VRESOURCE\r\n");
    var expanded =
        new CalendarRecurrence(QueryLimits.defaults())
            .expand(
                journal,
                range("2026-11-25T09:00:00Z", "2026-11-25T09:00:01Z"),
                FloatingTimezone.of(UTC));
    assertThat(expanded.toString())
        .contains(
            "COLOR:darkblue",
            "DISPLAY=BADGE",
            "STYLED-DESCRIPTION",
            "SCHEMA=\"https://schema.org/Thing\"",
            "VALUE=URI:https://example.invalid/data.xml",
            "BEGIN:PARTICIPANT",
            "BEGIN:VLOCATION",
            "NAME:Venue",
            "BEGIN:VRESOURCE",
            "RESOURCE-TYPE:ROOM");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "STATUS:FAILED",
        "STATUS:COMPLETED",
        "STATUS:CONFIRMED",
        "DTEND:20261128T154500Z",
        "DUE:20261128T154500Z",
        "DURATION:PT1H",
        "TRANSP:TRANSPARENT",
        "SUMMARY:One\r\nSUMMARY:Two",
        "DTSTAMP:20261128T154500Z",
        "UID:semantic",
        "CONFERENCE:https://example.invalid/join",
        "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Alarm\r\nTRIGGER:-PT5M\r\nEND:VALARM"
      })
  void rejectsNonJournalProperties(String value) {
    assertThatThrownBy(() -> parse(JOURNAL, value + "\r\n"))
        .isInstanceOf(CalendarValidationException.class);
  }

  @Test
  void dateDayEndsAtLocalMidnightAcrossBothDstTransitions() {
    var zone = ZoneId.of("America/New_York");
    for (var bounds :
        List.of(
            List.of(
                "20260308", "2026-03-09T03:59:59Z", "2026-03-09T04:00:00Z", "2026-03-09T04:00:01Z"),
            List.of(
                "20261101",
                "2026-11-02T04:59:59Z",
                "2026-11-02T05:00:00Z",
                "2026-11-02T05:00:01Z"))) {
      var parsed = parse(JOURNAL, "DTSTART;VALUE=DATE:" + bounds.getFirst() + "\r\n");
      var filter =
          component(
              "VCALENDAR",
              null,
              List.of(),
              List.of(
                  component(JOURNAL, range(bounds.get(1), bounds.get(2)), List.of(), List.of())));
      assertThat(new CalendarQuery(QueryLimits.defaults()).matches(parsed, filter, zone)).isTrue();
      filter =
          component(
              "VCALENDAR",
              null,
              List.of(),
              List.of(
                  component(JOURNAL, range(bounds.get(2), bounds.get(3)), List.of(), List.of())));
      assertThat(new CalendarQuery(QueryLimits.defaults()).matches(parsed, filter, zone)).isFalse();
    }
  }

  @Test
  void periodRdatesMatchStartsWithoutAcquiringAnEnd() {
    var parsed =
        parse(
            JOURNAL,
            "DTSTART:20261125T090000Z\r\n"
                + "RDATE;VALUE=PERIOD:20261126T090000Z/20261126T110000Z,20261127T090000Z/PT2H\r\n"
                + "EXDATE:20261125T090000Z\r\n"
                + "DESCRIPTION;LANGUAGE=fr:Texte\r\n");
    assertThat(point(parsed, JOURNAL, "2026-11-25T09:00:00Z")).isFalse();
    assertThat(point(parsed, JOURNAL, "2026-11-26T09:00:00Z")).isTrue();
    assertThat(point(parsed, JOURNAL, "2026-11-26T10:00:00Z")).isFalse();
    assertThat(point(parsed, JOURNAL, "2026-11-27T09:00:00Z")).isTrue();
    var expanded =
        new CalendarRecurrence(QueryLimits.defaults())
            .expand(
                parsed,
                range("2026-11-25T00:00:00Z", "2026-11-28T00:00:00Z"),
                FloatingTimezone.of(UTC));
    assertThat(expanded.getComponents(JOURNAL)).hasSize(2);
    assertThat(expanded.toString())
        .contains(
            "DTSTART:20261126T090000Z", "DTSTART:20261127T090000Z", "DESCRIPTION;LANGUAGE=fr:Texte")
        .doesNotContain("DTEND", "DUE", "DURATION", "RDATE", "EXDATE");
  }

  @Test
  void detachedResourceAndMovedRecurrenceUseEffectiveDates() {
    String detached =
        event(":20261028T100000Z", "20261029T140000Z", "STATUS:FINAL\r\n")
            .replace("VEVENT", JOURNAL);
    var standalone = parseComponents(JOURNAL, detached);
    assertThat(point(standalone, JOURNAL, "2026-10-29T14:00:00Z")).isTrue();
    var series =
        parseComponents(
            JOURNAL,
            event(null, "20261027T100000Z", "RRULE:FREQ=DAILY;COUNT=3\r\n")
                    .replace("VEVENT", JOURNAL)
                + detached);
    assertThat(point(series, JOURNAL, "2026-10-28T10:00:00Z")).isFalse();
    assertThat(point(series, JOURNAL, "2026-10-29T10:00:00Z")).isTrue();
    assertThat(point(series, JOURNAL, "2026-10-29T14:00:00Z")).isTrue();
  }
}
