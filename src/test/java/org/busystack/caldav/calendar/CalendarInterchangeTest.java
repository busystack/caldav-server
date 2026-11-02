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

  @Test
  void splitsMultipleUidsAndRetainsRecurrenceExceptionsTogether() {
    String master = event("series").replace("X-DATA:", "RRULE:FREQ=DAILY;COUNT=3\nX-DATA:");
    String exception =
        event("series")
            .replace(
                "DTSTART:20261102T090000Z",
                "RECURRENCE-ID:20261103T090000Z\nDTSTART:20261103T110000Z");
    var items = interchange.read(bytes(object(master + exception + event("second"))), "VEVENT");
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
    var items = interchange.read(all.toByteArray(), "VEVENT");
    assertThat(items.get(0).content()).isEqualTo(a);
    assertThat(items.get(1).content()).isEqualTo(b);
  }

  @Test
  void taskProfilesAndWrongTypeAreExplicit() {
    var task =
        bytes(
            object(
                "BEGIN:VTODO\nUID:task\nDTSTAMP:20261101T000000Z\nSUMMARY:Undated task\nSTATUS:NEEDS-ACTION\nEND:VTODO\n"));
    assertThat(interchange.read(task, "VTODO").getFirst().rejection()).isEmpty();
    assertThat(interchange.read(task, "VEVENT").getFirst().rejection()).contains("destination");
    assertThat(
            interchange
                .read(
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
        interchange.read(
            bytes(
                object(
                    event("good")
                        + event("bad").replace("DTSTART:20261102T090000Z", "DTSTART:invalid"))),
            "VEVENT");
    assertThat(mixed).hasSize(2);
    assertThat(mixed.get(0).rejection()).isEmpty();
    assertThat(mixed.get(1).rejection()).isNotEmpty();
    assertThat(
            interchange
                .read(bytes(object(event("same") + event("same"))), "VEVENT")
                .getFirst()
                .rejection())
        .isNotEmpty();
    assertThat(interchange.read(bytes(object(event("same")) + object(event("same"))), "VEVENT"))
        .allSatisfy(i -> assertThat(i.rejection()).contains("multiple"));
  }

  @Test
  void malformedOversizedAndDeepFilesFailBeforeAnyImport() {
    assertThatThrownBy(() -> interchange.read(bytes("<script>not calendar</script>"), "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
    assertThatThrownBy(
            () -> interchange.read(new byte[CalendarInterchange.MAX_FILE_BYTES + 1], "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
    assertThatThrownBy(() -> interchange.read(bytes(object("BEGIN:VEVENT\n".repeat(9))), "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
    assertThatThrownBy(() -> interchange.read(bytes(object(event("a")) + "garbage"), "VEVENT"))
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
    var item = interchange.read(source, "VEVENT").getFirst();
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
        interchange
            .read(
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
