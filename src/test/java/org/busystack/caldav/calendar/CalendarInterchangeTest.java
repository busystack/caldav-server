package org.busystack.caldav.calendar;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.busystack.caldav.config.DavCompatibilityProperties;
import org.junit.jupiter.api.Test;

class CalendarInterchangeTest {
  final CalendarInterchange interchange =
      new CalendarInterchange(new CalendarAdmission(new DavCompatibilityProperties(true, true)));

  private static byte[] bytes(String text) {
    return text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
  }

  private static String object(String components) {
    return "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Interchange//EN\n"
        + components
        + "END:VCALENDAR\n";
  }

  private static String event(String uid) {
    return "BEGIN:VEVENT\nUID:"
        + uid
        + "\nDTSTAMP:20261101T000000Z\nDTSTART:20261102T090000Z\nSUMMARY:Résumé\\, family\nX-DATA:preserve\nEND:VEVENT\n";
  }

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

  private java.util.List<CalendarInterchange.Item> read(byte[] bytes, String component) {
    var source = temporary.resolve(java.util.UUID.randomUUID() + ".ics");
    var index = temporary.resolve(java.util.UUID.randomUUID() + ".index");
    try {
      java.nio.file.Files.write(source, bytes);
      try (var file = interchange.open(source, index)) {
        var items = new java.util.ArrayList<CalendarInterchange.Item>();
        for (int n = 0; n < file.size(); n++) items.add(interchange.item(file, n, component));
        return items;
      }
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  @Test
  void invalidDuplicateNeighborsDoNotRejectValidSeriesAndEscapedUidsKeepTheirIdentity() {
    var items =
        read(
            bytes(
                object(event("same"))
                    + object(event("same").replace("DTSTART:20261102T090000Z", "DTSTART:invalid"))),
            "VEVENT");
    assertThat(items).hasSize(2);
    assertThat(items.getFirst().rejection()).isEmpty();
    assertThat(items.getLast().rejection()).isNotEmpty();
    for (String uid :
        java.util.List.of("escaped\\,uid", "escaped\\;uid", "escaped\\\\uid", "MixedCaseUID")) {
      byte[] body = bytes(object(event(uid)));
      var item = read(body, "VEVENT").getFirst();
      assertThat(item.rejection()).isEmpty();
      assertThat(item.uid()).isEqualTo(CalendarContent.parse(body, "VEVENT").uid());
      assertThat(item.content()).isEqualTo(body);
    }
  }

  @Test
  void indexedSingleAndStreamInputsExceedOldByteAndItemLimitsWithoutRetainingBodies()
      throws Exception {
    for (boolean stream : new boolean[] {false, true}) {
      var input = temporary.resolve("large-" + stream + ".ics");
      var index = temporary.resolve("index-" + stream);
      String padding = "x".repeat(8500);
      try (var out = java.nio.file.Files.newOutputStream(input)) {
        if (!stream) out.write(bytes("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Interchange//EN\n"));
        for (int n = 0; n < 1100; n++) {
          String value = event("large-" + n).replace("X-DATA:preserve", "X-DATA:" + padding);
          if (stream) {
            String zone =
                "BEGIN:VTIMEZONE\nTZID:SameLocalName\nBEGIN:STANDARD\nDTSTART:19700101T000000\nTZOFFSETFROM:"
                    + (n % 2 == 0 ? "+0200" : "-0300")
                    + "\nTZOFFSETTO:"
                    + (n % 2 == 0 ? "+0200" : "-0300")
                    + "\nEND:STANDARD\nEND:VTIMEZONE\n";
            value =
                value.replace(
                    "DTSTART:20261102T090000Z", "DTSTART;TZID=SameLocalName:20261102T090000");
            if (n % 100 == 0) {
              value =
                  value.replace("X-DATA:", "RRULE:FREQ=DAILY;COUNT=2\nX-DATA:")
                      + event("large-" + n)
                          .replace(
                              "DTSTART:20261102T090000Z",
                              "RECURRENCE-ID;TZID=SameLocalName:20261103T090000\nDTSTART;TZID=SameLocalName:20261103T110000");
            }
            out.write(
                bytes(
                    object(zone + value)
                        .replace(
                            "PRODID:-//Interchange//EN",
                            "PRODID:-//Interchange//EN\nX-CALENDAR-CONTEXT:" + n)));
          } else out.write(bytes(value));
        }
        if (!stream) out.write(bytes("END:VCALENDAR\n"));
      }
      assertThat(java.nio.file.Files.size(input)).isGreaterThan(8L * 1024 * 1024);
      try (var file = interchange.open(input, index)) {
        assertThat(file.size()).isEqualTo(1100);
        for (int n = 0; n < file.size(); n++) {
          var item = interchange.item(file, n, "VEVENT");
          assertThat(item.uid()).isEqualTo("large-" + n);
          assertThat(item.rejection()).isEmpty();
          assertThat(new String(item.content(), StandardCharsets.UTF_8))
              .contains("X-DATA:" + padding);
          if (stream) {
            String text = new String(item.content(), StandardCharsets.UTF_8);
            assertThat(text)
                .contains(
                    "X-CALENDAR-CONTEXT:" + n + "\r\n",
                    "TZOFFSETTO:" + (n % 2 == 0 ? "+0200" : "-0300"));
            assertThat(
                    CalendarContent.parse(item.content(), "VEVENT")
                        .calendar()
                        .getComponents("VEVENT"))
                .hasSize(n % 100 == 0 ? 2 : 1);
          }
        }
      }
    }
  }

  @Test
  void indexedFramingPreservesContextsExceptionsAndInvalidNeighbors() throws Exception {
    String master = event("series").replace("X-DATA:", "RRULE:FREQ=DAILY;COUNT=3\nX-DATA:");
    String exception =
        event("series")
            .replace(
                "DTSTART:20261102T090000Z",
                "RECURRENCE-ID:20261103T090000Z\nDTSTART:20261103T110000Z");
    var input = temporary.resolve("mixed.ics");
    var index = temporary.resolve("index");
    java.nio.file.Files.write(
        input,
        bytes(
            object(master + event("other") + exception)
                + object(event("duplicate"))
                + object(event("duplicate"))));
    try (var file = interchange.open(input, index)) {
      assertThat(file.size()).isEqualTo(4);
      var series = interchange.item(file, 0, "VEVENT");
      assertThat(series.rejection()).isEmpty();
      assertThat(
              CalendarContent.parse(series.content(), "VEVENT").calendar().getComponents("VEVENT"))
          .hasSize(2);
      assertThat(interchange.item(file, 1, "VEVENT").uid()).isEqualTo("other");
      assertThat(interchange.item(file, 2, "VEVENT").rejection()).contains("multiple");
      assertThat(interchange.item(file, 3, "VEVENT").rejection()).contains("multiple");
    }
    java.nio.file.Files.write(input, bytes(object("BEGIN:VEVENT\nEND:VTODO\n")));
    assertThatThrownBy(() -> interchange.open(input, index))
        .isInstanceOf(CalendarValidationException.class);
  }

  @Test
  void splitsMultipleUidsAndRetainsRecurrenceExceptionsTogether() {
    String master = event("series").replace("X-DATA:", "RRULE:FREQ=DAILY;COUNT=3\nX-DATA:");
    String exception =
        event("series")
            .replace(
                "DTSTART:20261102T090000Z",
                "RECURRENCE-ID:20261103T090000Z\nDTSTART:20261103T110000Z");
    var items = read(bytes(object(master + exception + event("second"))), "VEVENT");
    assertThat(items).hasSize(2).allSatisfy(i -> assertThat(i.rejection()).isEmpty());
    var parsed = CalendarContent.parse(items.getFirst().content(), "VEVENT");
    assertThat(parsed.uid()).isEqualTo("series");
    assertThat(parsed.calendar().getComponents("VEVENT")).hasSize(2);
    assertThat(new String(items.getFirst().content(), StandardCharsets.UTF_8))
        .contains("RRULE:FREQ=DAILY;COUNT=3", "RECURRENCE-ID:20261103T090000Z", "X-DATA:preserve");
  }

  @Test
  void losslessCalendarStreamsRetainEachOriginalRepresentation() {
    byte[] a = bytes(object(event("a"))), b = bytes(object(event("b")));
    var all = new java.io.ByteArrayOutputStream();
    all.writeBytes(a);
    all.writeBytes(b);
    var items = read(all.toByteArray(), "VEVENT");
    assertThat(items.get(0).content()).isEqualTo(a);
    assertThat(items.get(1).content()).isEqualTo(b);
  }

  @Test
  void taskProfilesAndWrongTypeAreExplicit() {
    var task =
        bytes(
            object(
                "BEGIN:VTODO\nUID:task\nDTSTAMP:20261101T000000Z\nSUMMARY:Undated task\nSTATUS:NEEDS-ACTION\nEND:VTODO\n"));
    assertThat(read(task, "VTODO").getFirst().rejection()).isEmpty();
    assertThat(read(task, "VEVENT").getFirst().rejection()).contains("destination");
    assertThat(
            read(
                    bytes(
                        object(
                            "BEGIN:VJOURNAL\nUID:journal\nDTSTAMP:20261101T000000Z\nEND:VJOURNAL\n")),
                    "VEVENT")
                .getFirst()
                .rejection())
        .contains("Unsupported");
  }

  @Test
  void invalidItemsAreReportedAndDuplicateMastersNeverDiscarded() {
    var mixed =
        read(
            bytes(
                object(
                    event("good")
                        + event("bad").replace("DTSTART:20261102T090000Z", "DTSTART:invalid"))),
            "VEVENT");
    assertThat(mixed).hasSize(2);
    assertThat(mixed.get(0).rejection()).isEmpty();
    assertThat(mixed.get(1).rejection()).isNotEmpty();
    assertThat(read(bytes(object(event("same") + event("same"))), "VEVENT").getFirst().rejection())
        .isNotEmpty();
    assertThat(read(bytes(object(event("same")) + object(event("same"))), "VEVENT"))
        .allSatisfy(i -> assertThat(i.rejection()).contains("multiple"));
  }

  @Test
  void malformedOversizedAndDeepFilesFailBeforeAnyImport() {
    assertThatThrownBy(() -> read(bytes("<script>not calendar</script>"), "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
    assertThatThrownBy(() -> read(new byte[CalendarInterchange.MAX_FILE_BYTES + 1], "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
    assertThatThrownBy(() -> read(bytes(object("BEGIN:VEVENT\n".repeat(9))), "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
    assertThatThrownBy(() -> read(bytes(object(event("a")) + "garbage"), "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
  }

  @Test
  void timezonesAndNominalDurationBytesRemainUntouched() {
    String zone =
        "BEGIN:VTIMEZONE\nTZID:Custom\nBEGIN:STANDARD\nDTSTART:19700101T000000\nTZOFFSETFROM:+0200\nTZOFFSETTO:+0200\nEND:STANDARD\nEND:VTIMEZONE\n";
    String event =
        event("zone")
            .replace(
                "DTSTART:20261102T090000Z", "DTSTART;TZID=Custom:20261102T090000\nDURATION:P1D");
    byte[] source = bytes(object(zone + event));
    var item = read(source, "VEVENT").getFirst();
    assertThat(item.rejection()).isEmpty();
    assertThat(item.content()).isEqualTo(source);
    assertThat(
            CalendarContent.parse(item.content(), "VEVENT").calendar().getComponents("VTIMEZONE"))
        .hasSize(1);
  }

  @Test
  void knownThunderbirdObservanceRepairUsesExistingAdmission() throws Exception {
    String zone =
        "BEGIN:VTIMEZONE\nTZID:Custom\nBEGIN:STANDARD\nDTSTART:19700101T000000\nTZOFFSETFROM:+0200\nTZOFFSETTO:+0200\nRRULE:FREQ=YEARLY;BYMONTH=1;UNTIL=20300101T000000\nEND:STANDARD\nEND:VTIMEZONE\n";
    var item =
        read(
                bytes(
                    object(
                        zone
                            + event("repair")
                                .replace(
                                    "DTSTART:20261102T090000Z",
                                    "DTSTART;TZID=Custom:20261102T090000"))),
                "VEVENT")
            .getFirst();
    assertThat(item.rejection()).isEmpty();
    assertThat(new String(item.content(), StandardCharsets.UTF_8))
        .contains("UNTIL=20291231T220000Z");
  }
}
