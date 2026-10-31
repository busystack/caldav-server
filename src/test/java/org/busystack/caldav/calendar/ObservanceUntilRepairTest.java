package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.fortuna.ical4j.model.Recur;
import org.busystack.caldav.config.DavCompatibilityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ObservanceUntilRepairTest {
  private final CalendarAdmission admission =
      new CalendarAdmission(new DavCompatibilityProperties(true, true));
  private final CalendarAdmission strict =
      new CalendarAdmission(new DavCompatibilityProperties(true, false));

  static byte[] fixture(String name) throws Exception {
    try (var stream =
        ObservanceUntilRepairTest.class.getResourceAsStream(
            "/calendar/observance-until/thunderbird159-" + name + ".ics")) {
      return Objects.requireNonNull(stream).readAllBytes();
    }
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] data) {
    return new String(data, StandardCharsets.UTF_8);
  }

  static String calendar(String offset, String until) {
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Observance input fixture//EN\r\n"
        + "BEGIN:VTIMEZONE\r\nTZID:Submitted/Zone\r\nBEGIN:STANDARD\r\nDTSTART:20200101T020000\r\n"
        + "TZOFFSETFROM:"
        + offset
        + "\r\nTZOFFSETTO:+0000\r\n"
        + "RRULE:FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=1;UNTIL="
        + until
        + "\r\n"
        + "END:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:repair-test\r\nDTSTAMP:20261031T000000Z\r\n"
        + "DTSTART;TZID=Submitted/Zone:20261109T100000\r\nDTEND;TZID=Submitted/Zone:20261109T110000\r\n"
        + "X-OPAQUE;X-ONE=untouched:UNTIL=20260308T020000 ☃\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
  }

  @ParameterizedTest
  @ValueSource(strings = {"vancouver", "berlin"})
  void capturedUploadsRequireRepairAndAllOtherBytesSurvive(String name) throws Exception {
    byte[] input = fixture(name);
    assertThatThrownBy(() -> strict.admit(input, "VEVENT")).isInstanceOf(RuntimeException.class);
    var result = admission.admit(input, "VEVENT");
    assertThat(result.repairedRules()).isEqualTo(name.equals("vancouver") ? 6 : 7);
    // Every changed logical line differs only in its UNTIL value; original evidence is unchanged.
    String[] before = text(input).split("\r\n"), after = text(result.content()).split("\r\n");
    assertThat(after).hasSameSizeAs(before);
    for (int i = 0; i < before.length; i++) {
      if (before[i].startsWith("RRULE:") && before[i].contains("UNTIL="))
        assertThat(after[i].replaceAll("UNTIL=[0-9TZ]+", "UNTIL=<boundary>"))
            .isEqualTo(before[i].replaceAll("UNTIL=[0-9TZ]+", "UNTIL=<boundary>"));
      else assertThat(after[i]).isEqualTo(before[i]);
    }
    var second = admission.admit(result.content(), "VEVENT");
    assertThat(second.transformed()).isFalse();
    assertThat(second.content()).isEqualTo(result.content());
    assertThat(strict.admit(result.content(), "VEVENT").content()).isEqualTo(result.content());
  }

  @ParameterizedTest
  @ValueSource(strings = {"vancouver", "berlin"})
  void eachCapturedBoundaryIncludesFinalLocalOnsetAndExcludesNext(String name) throws Exception {
    var date = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss");
    String original = text(fixture(name));
    var parsed = admission.admit(fixture(name), "VEVENT").parsed();
    var zone =
        (net.fortuna.ical4j.model.component.VTimeZone)
            parsed.calendar().getComponents("VTIMEZONE").getFirst();
    int checked = 0;
    for (var observance : zone.getComponents()) {
      var property = observance.getProperty("RRULE");
      if (property.isEmpty()) continue;
      var corrected = ((net.fortuna.ical4j.model.property.RRule<?>) property.get()).getRecur();
      if (corrected.getUntil() == null) continue;
      var offset =
          ((net.fortuna.ical4j.model.property.TzOffsetFrom)
                  observance.getRequiredProperty("TZOFFSETFROM"))
              .getOffset();
      var start =
          (LocalDateTime)
              ((net.fortuna.ical4j.model.property.DtStart<?>)
                      observance.getRequiredProperty("DTSTART"))
                  .getDate();
      LocalDateTime cutoff =
          org.busystack.caldav.calendar.recurrence.TemporalSupport.instant(
                  corrected.getUntil(), ZoneOffset.UTC)
              .atOffset(offset)
              .toLocalDateTime();
      // Locate original local boundary; evaluate the same recurrence in the client's local-onset
      // domain and in the corrected fixed-TZOFFSETFROM domain. No host timezone is consulted.
      assertThat(original).contains("UNTIL=" + date.format(cutoff));
      String rule = property.get().getValue();
      String unbounded = rule.replaceAll(";UNTIL=[^;]+", "");
      var localRule = new Recur<LocalDateTime>(unbounded);
      var neighbors = localRule.getDates(start, cutoff.minusYears(1), cutoff.plusYears(2), 10);
      assertThat(neighbors).contains(cutoff);
      LocalDateTime next =
          neighbors.stream().filter(x -> x.isAfter(cutoff)).findFirst().orElseThrow();
      var localBounded = new Recur<LocalDateTime>(unbounded + ";UNTIL=" + date.format(cutoff));
      var expected = localBounded.getDates(start, cutoff.minusYears(1), next.plusSeconds(1), 10);
      var utcBounded = new Recur<OffsetDateTime>(rule);
      var actual =
          utcBounded.getDates(
              start.atOffset(offset),
              cutoff.minusYears(1).atOffset(offset),
              next.plusSeconds(1).atOffset(offset),
              10);
      assertThat(actual.stream().map(OffsetDateTime::toLocalDateTime).toList())
          .containsExactlyElementsOf(expected);
      assertThat(actual.stream().map(OffsetDateTime::toLocalDateTime).toList())
          .contains(cutoff)
          .doesNotContain(next);
      var zonedStart =
          (java.time.ZonedDateTime)
              ((net.fortuna.ical4j.model.property.DtStart<?>)
                      parsed
                          .calendar()
                          .getComponents("VEVENT")
                          .getFirst()
                          .getRequiredProperty("DTSTART"))
                  .getDate();
      Instant finalOnset = cutoff.toInstant(offset);
      assertThat(zonedStart.getZone().getRules().getOffset(finalOnset.minusNanos(1)))
          .isEqualTo(offset);
      assertThat(zonedStart.getZone().getRules().getOffset(finalOnset))
          .isEqualTo(
              ((net.fortuna.ical4j.model.property.TzOffsetTo)
                      observance.getRequiredProperty("TZOFFSETTO"))
                  .getOffset());
      checked++;
    }
    assertThat(checked).isEqualTo(name.equals("vancouver") ? 6 : 7);
  }

  @ParameterizedTest
  @CsvSource({
    "-0800,20260308T020000,20260308T100000Z",
    "-0800,20260308t020000,20260308T100000Z",
    "+0530,20260101T020000,20251231T203000Z",
    "-081228,18840101T000000,18840101T081228Z",
    "+005328,18930401T000000,18930331T230632Z",
    "-0100,20261231T233000,20270101T003000Z",
    "+0000,20260101T020000,20260101T020000Z"
  })
  void conversionUsesFromOffsetIncludingSecondsAndRollover(
      String offset, String local, String utc) {
    String input = calendar(offset, local);
    var result = admission.admit(bytes(input), "VEVENT");
    assertThat(result.transformed()).isTrue();
    assertThat(result.content())
        .isEqualTo(bytes(input.replace("UNTIL=" + local + "\r\n", "UNTIL=" + utc + "\r\n")));
  }

  @Test
  void foldedRuleAndLowercaseBoundariesKeepUnrelatedFoldedUtf8AndBom() {
    String input =
        "\ufeff"
            + calendar("-0800", "20260308T020000")
                .replace("BEGIN:", "begin:")
                .replace("END:", "end:")
                .replace("UNTIL=20260308", "UN\r\n TIL=20260308")
                .replace("☃", "before\r\n\t☃after");
    var result = admission.admit(bytes(input), "VEVENT");
    assertThat(text(result.content())).contains("UNTIL=20260308T100000Z", "before\r\n\t☃after");
    assertThat(text(result.content())).startsWith("\ufeffbegin:VCALENDAR");
    assertThat(admission.admit(result.content(), "VEVENT").content()).isEqualTo(result.content());
  }

  @Test
  void changedLongLineIsRefoldedAndValidObjectsAreByteIdentical() {
    String input =
        calendar("-0800", "20260308T020000")
            .replace("RRULE:", "RRULE;X-LONG=" + "a".repeat(80) + ":");
    var result = admission.admit(bytes(input), "VEVENT");
    String changed = text(result.content());
    assertThat(changed).contains("\r\n ");
    assertThat(changed.replace("\r\n ", "")).contains("UNTIL=20260308T100000Z");
    assertThat(admission.admit(result.content(), "VEVENT").content()).isEqualTo(result.content());
    String valid = calendar("-0800", "20260308T100000Z");
    assertThat(admission.admit(bytes(valid), "VEVENT").content()).isEqualTo(bytes(valid));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "20260230T020000",
        "20260308",
        "20261308T020000",
        "20260308T240000",
        "20260308T020060",
        "00000101T020000"
      })
  void invalidOrUnrepresentableLocalBoundariesAreRejected(String until) {
    assertThatThrownBy(() -> admission.admit(bytes(calendar("-0800", until)), "VEVENT"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void ambiguityAndUnrelatedInvalidDataRemainRejected() throws Exception {
    String input = calendar("-0800", "20260308T020000");
    for (String invalid :
        List.of(
            input.replace("TZOFFSETFROM:-0800\r\n", ""),
            input.replace("TZOFFSETFROM:-0800", "TZOFFSETFROM:-0800\r\nTZOFFSETFROM:-0700"),
            input.replace("TZOFFSETTO:+0000\r\n", ""),
            input.replace("DTSTART:20200101T020000\r\n", ""),
            input.replace("TZOFFSETFROM:-0800", "TZOFFSETFROM:-0000"),
            input.replace("TZOFFSETFROM:-0800", "TZOFFSETFROM:+0860"),
            input.replace("FREQ=YEARLY", "FREQ=YEARLY;FREQ=YEARLY"),
            input.replace("FREQ=YEARLY", "FREQ=YEARLY;COUNT=2"),
            input.replace("BYMONTHDAY=1", "BYDAY=0MO"),
            input.replace("DTEND;TZID=Submitted/Zone:20261109T110000", "DURATION:P1W2D"),
            input.replaceFirst("BEGIN:VCALENDAR", "XBEGIN:VCALENDAR"),
            input.replace("DTSTAMP:20261031T000000Z\r\n", ""),
            input.replace("END:VEVENT", "RRULE:FREQ=DAILY;UNTIL=20261201T100000\r\nEND:VEVENT")))
      assertThatThrownBy(() -> admission.admit(bytes(invalid), "VEVENT"))
          .isInstanceOf(RuntimeException.class);
    for (String name :
        List.of(
            "invalid-zero-byday.ics",
            "invalid-plus-zero-byday.ics",
            "invalid-weeks-days.ics",
            "invalid-leading-token.ics")) {
      try (var stream =
          getClass().getResourceAsStream("/calendar/independent-verification/" + name)) {
        byte[] negative = Objects.requireNonNull(stream).readAllBytes();
        assertThatThrownBy(() -> admission.admit(negative, "VEVENT"))
            .isInstanceOf(RuntimeException.class);
      }
    }
  }

  @Test
  void textExtensionAndOtherComponentRulesAreNeverRepaired() {
    String valid =
        calendar("-0800", "20260308T100000Z")
            .replace(
                "END:VEVENT",
                "DESCRIPTION:RRULE:UNTIL=20260308T020000\r\nX-RRULE:UNTIL=20260308T020000\r\nEND:VEVENT");
    assertThat(admission.admit(bytes(valid), "VEVENT").content()).isEqualTo(bytes(valid));
    String event =
        valid.replace("END:VEVENT", "RRULE:FREQ=DAILY;UNTIL=20261201T100000\r\nEND:VEVENT");
    assertThatThrownBy(() -> admission.admit(bytes(event), "VEVENT"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> admission.admit(bytes(event.replace("VEVENT", "VTODO")), "VTODO"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void correctionCannotExceedExistingByteLimit() {
    String input = calendar("-0800", "20260308T020000");
    input =
        input.replace(
            "X-OPAQUE;",
            "X-PADDING:"
                + "a".repeat(CalendarContent.MAX_BYTES - bytes(input).length - 12)
                + "\r\nX-OPAQUE;");
    byte[] boundary = bytes(input);
    assertThat(boundary).hasSize(CalendarContent.MAX_BYTES);
    assertThatThrownBy(() -> admission.admit(boundary, "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
  }

  @Test
  void taskAdmissionUsesTheSameAuthoritativeTimezoneAndPreservesOpaqueProperties()
      throws Exception {
    String input =
        text(fixture("vancouver"))
            .replace("VEVENT", "VTODO")
            .replace("DTEND;", "DUE;")
            .replace(
                "END:VTODO",
                "STATUS:NEEDS-ACTION\r\nPERCENT-COMPLETE:25\r\nX-TASK:kept\r\nEND:VTODO");
    var accepted = admission.admit(bytes(input), "VTODO");
    assertThat(accepted.repairedRules()).isEqualTo(6);
    assertThat(text(accepted.content())).contains("X-TASK:kept", "PERCENT-COMPLETE:25");
    assertThat(CalendarSemanticsTest.point(accepted.parsed(), "VTODO", "2026-11-09T17:30:00Z"))
        .isTrue();
    assertThat(CalendarSemanticsTest.point(accepted.parsed(), "VTODO", "2026-11-09T18:30:00Z"))
        .isFalse();
  }
}
