package org.busystack.caldav.calendar.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentContainer;
import net.fortuna.ical4j.model.Property;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.query.CalendarDataProjection.ComponentSelection;
import org.busystack.caldav.calendar.query.CalendarDataProjection.PropertySelection;
import org.busystack.caldav.calendar.query.CalendarDataProjection.Selection;
import org.junit.jupiter.api.Test;

class CalendarDataProjectionTest {
  private static final FloatingTimezone UTC = FloatingTimezone.of(ZoneOffset.UTC);
  private final CalendarDataProjection projection =
      new CalendarDataProjection(QueryLimits.defaults());

  @Test
  void wholeReportIsAnIndependentModelCopyAndDoesNotRewriteAuthoritativeData() {
    var parsed = fixture();
    String before = parsed.calendar().toString();
    var report = projection.project(parsed, null, UTC);
    report
        .getComponents()
        .getFirst()
        .setPropertyList(
            report.getComponents().getFirst().getPropertyList().removeAll(Property.SUMMARY));
    assertThat(parsed.calendar().toString()).isEqualTo(before);
    assertThat(report.toString()).doesNotContain("SUMMARY:Projection fixture");
  }

  @Test
  void selectedNestedComponentsAndPropertiesPreserveParametersWithNoValue() {
    var event =
        selected(
            "VEVENT",
            List.of(
                property("UID", false),
                property("DTSTART", true),
                property("ATTENDEE", true),
                property("X-CLIENT-DATA", false)),
            List.of(selected("VALARM", List.of(property("DESCRIPTION", true)), List.of())));
    var root = selected("VCALENDAR", List.of(property("VERSION", false)), List.of(event));
    var parsed = fixture();
    String before = parsed.calendar().toString();
    var report = projection.project(parsed, new Selection(root, null, null, null), UTC);
    assertThat(report.getPropertyList().getAll())
        .extracting(Property::getName)
        .containsExactly("VERSION");
    var returned = report.getComponents().getFirst();
    assertThat(returned.getPropertyList().getAll())
        .extracting(Property::getName)
        .containsExactly("UID", "DTSTART", "ATTENDEE", "ATTENDEE", "X-CLIENT-DATA");
    assertThat(returned.getProperty(Property.DTSTART).orElseThrow().toString())
        .isEqualTo("DTSTART;X-ORIGIN=client:\r\n");
    List<Property> attendees = returned.getProperties(Property.ATTENDEE);
    assertThat(attendees)
        .allSatisfy(
            property -> {
              assertThat(property.getValue()).isEmpty();
              assertThat(property.getParameter("CN")).isPresent();
            });
    assertThat(returned.getProperty("X-CLIENT-DATA").orElseThrow().getValue())
        .isEqualTo("opaque client data");
    var alarm = ((ComponentContainer<?>) returned).getComponents().getFirst();
    assertThat(alarm.getPropertyList().getAll())
        .extracting(Property::getName)
        .containsExactly(Property.DESCRIPTION);
    assertThat(alarm.getProperty(Property.DESCRIPTION).orElseThrow().getValue()).isEmpty();
    assertThat(parsed.calendar().toString()).isEqualTo(before);
  }

  @Test
  void duplicateSelectorsFormAUnionAndARequestedValueWinsOverNoValue() {
    var first = selected("VEVENT", List.of(property("UID", true)), List.of());
    var second =
        selected("vevent", List.of(property("UID", false), property("SUMMARY", false)), List.of());
    var root = new ComponentSelection("vcalendar", true, false, List.of(), List.of(first, second));
    var report = projection.project(fixture(), new Selection(root, null, null, null), UTC);
    var event = report.getComponents().getFirst();
    assertThat(event.getPropertyList().getAll())
        .extracting(Property::getName)
        .containsExactly("UID", "SUMMARY");
    assertThat(event.getProperty(Property.UID).orElseThrow().getValue())
        .isEqualTo("projection-fixture");
    assertThat(((ComponentContainer<?>) event).getComponents()).isEmpty();
    assertThat(report.getProperty(Property.PRODID)).isPresent();
  }

  @Test
  void emptyAndExplicitAllSelectorsHaveDifferentMeaningAndUnknownNamesAreAllowed() {
    var empty = selected("VCALENDAR", List.of(), List.of());
    var none = projection.project(fixture(), new Selection(empty, null, null, null), UTC);
    assertThat(none.getProperties()).isEmpty();
    assertThat(none.getComponents()).isEmpty();
    var all = new ComponentSelection("VCALENDAR", true, true, List.of(), List.of());
    assertThat(projection.project(fixture(), new Selection(all, null, null, null), UTC).toString())
        .isEqualTo(fixture().calendar().toString());
    var unknown =
        selected(
            "VCALENDAR",
            List.of(property("X-ABSENT", false)),
            List.of(selected("X-UNKNOWN", List.of(), List.of())));
    assertThat(
            projection
                .project(fixture(), new Selection(unknown, null, null, null), UTC)
                .getComponents())
        .isEmpty();
  }

  @Test
  void malformedAndExcessiveSelectionsFailBeforeReturningAReport() {
    assertThatThrownBy(() -> property("UID\r\nINJECTED", false)).isInstanceOf(QueryException.class);
    assertThatThrownBy(
            () ->
                new ComponentSelection(
                    "VEVENT", true, false, List.of(property("UID", false)), List.of()))
        .isInstanceOf(QueryException.class);
    var wrongRoot = selected("VEVENT", List.of(), List.of());
    assertThatThrownBy(
            () -> projection.project(fixture(), new Selection(wrongRoot, null, null, null), UTC))
        .isInstanceOf(QueryException.class);
    var root =
        selected(
            "VCALENDAR",
            List.of(property("VERSION", false)),
            List.of(selected("VEVENT", List.of(property("UID", false)), List.of())));
    var bounded = new CalendarDataProjection(new QueryLimits(10, 10, 100, 10, 3));
    assertThatThrownBy(() -> bounded.project(fixture(), new Selection(root, null, null, null), UTC))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
    var range =
        new TimeRange(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));
    assertThatThrownBy(() -> new Selection(null, range, range, null))
        .isInstanceOf(QueryException.class);
    assertThatThrownBy(() -> new Selection(null, new TimeRange(range.start(), null), null, null))
        .isInstanceOf(QueryException.class);
  }

  @Test
  void freeBusyLimitingRetainsWholeIntersectingPeriodsAndTheirType() {
    var parsed =
        CalendarContent.parse(
            ("BEGIN:VCALENDAR\r\n"
                    + "VERSION:2.0\r\n"
                    + "PRODID:-//Freebusy textual fixture//EN\r\n"
                    + "BEGIN:VFREEBUSY\r\n"
                    + "UID:busy-projection\r\n"
                    + "DTSTAMP:20261027T000000Z\r\n"
                    + "DTSTART:20261001T000000Z\r\n"
                    + "DTEND:20261101T000000Z\r\n"
                    + "FREEBUSY;FBTYPE=BUSY-TENTATIVE:20261027T090000Z/20261027T110000Z,20261027T130000Z/PT1H\r\n"
                    + "FREEBUSY;FBTYPE=FREE:20261027T120000Z/PT30M\r\n"
                    + "END:VFREEBUSY\r\n"
                    + "END:VCALENDAR\r\n")
                .getBytes(StandardCharsets.UTF_8),
            Component.VFREEBUSY);
    String original = parsed.calendar().toString();
    var range =
        new TimeRange(Instant.parse("2026-10-27T10:00:00Z"), Instant.parse("2026-10-27T12:00:00Z"));
    var report = projection.project(parsed, new Selection(null, null, null, range), UTC);
    var values = report.getComponents().getFirst().getProperties(Property.FREEBUSY);
    assertThat(values).hasSize(1);
    assertThat(values.getFirst().getParameter("FBTYPE").orElseThrow().getValue())
        .isEqualTo("BUSY-TENTATIVE");
    var intervals = ((net.fortuna.ical4j.model.property.FreeBusy) values.getFirst()).getIntervals();
    assertThat(intervals).hasSize(1);
    assertThat(intervals.getFirst().getStart()).isEqualTo(Instant.parse("2026-10-27T09:00:00Z"));
    assertThat(intervals.getFirst().getEnd()).isEqualTo(Instant.parse("2026-10-27T11:00:00Z"));
    assertThat(parsed.calendar().toString()).isEqualTo(original);
  }

  @Test
  void oneReportBudgetCoversMultipleResourceProjections() {
    var limited = new CalendarDataProjection(new QueryLimits(3, 10, 1000, 10, 50));
    limited.project(fixture(), null, UTC);
    assertThatThrownBy(() -> limited.project(fixture(), null, UTC))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
    var work = new CalendarDataProjection(new QueryLimits(100, 10, 25, 10, 50));
    work.project(fixture(), null, UTC);
    assertThatThrownBy(() -> work.project(fixture(), null, UTC))
        .isInstanceOfSatisfying(
            QueryException.class,
            error -> assertThat(error.kind()).isEqualTo(QueryException.Kind.LIMIT_EXCEEDED));
  }

  @Test
  void temporalExpansionRunsBeforeRequestedPropertyProjection() {
    var parsed =
        CalendarContent.parse(
            ("BEGIN:VCALENDAR\r\n"
                    + "VERSION:2.0\r\n"
                    + "PRODID:-//Expansion projection fixture//EN\r\n"
                    + "BEGIN:VEVENT\r\n"
                    + "UID:expanded-projection\r\n"
                    + "DTSTAMP:20261027T000000Z\r\n"
                    + "DTSTART:20261028T100000Z\r\n"
                    + "DTEND:20261028T110000Z\r\n"
                    + "RRULE:FREQ=DAILY;COUNT=3\r\n"
                    + "SUMMARY:Preserved source\r\n"
                    + "END:VEVENT\r\n"
                    + "END:VCALENDAR\r\n")
                .getBytes(StandardCharsets.UTF_8),
            Component.VEVENT);
    var root =
        new ComponentSelection(
            "VCALENDAR",
            true,
            false,
            List.of(),
            List.of(
                selected(
                    "VEVENT",
                    List.of(
                        property("UID", false),
                        property("RECURRENCE-ID", false),
                        property("DTSTART", false)),
                    List.of())));
    var range =
        new TimeRange(Instant.parse("2026-10-28T09:00:00Z"), Instant.parse("2026-10-30T11:00:00Z"));
    var result = projection.project(parsed, new Selection(root, range, null, null), UTC);
    List<net.fortuna.ical4j.model.component.CalendarComponent> expanded = result.getComponents();
    assertThat(expanded).hasSize(3);
    assertThat(expanded)
        .allSatisfy(
            component -> {
              assertThat(component.getProperty(Property.RRULE)).isEmpty();
              assertThat(component.getProperty(Property.DTEND)).isEmpty();
              assertThat(component.getProperty(Property.SUMMARY)).isEmpty();
            });
    assertThat(
            expanded.stream()
                .map(component -> component.getProperty(Property.DTSTART).orElseThrow().getValue()))
        .containsExactly("20261028T100000Z", "20261029T100000Z", "20261030T100000Z");
    assertThat(parsed.calendar().getComponents().getFirst().getProperty(Property.RRULE))
        .isPresent();
  }

  private static ComponentSelection selected(
      String name, List<PropertySelection> properties, List<ComponentSelection> components) {
    return new ComponentSelection(name, false, false, properties, components);
  }

  private static PropertySelection property(String name, boolean noValue) {
    return new PropertySelection(name, noValue);
  }

  private static CalendarContent.Parsed fixture() {
    return CalendarContent.parse(
        ("BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//Independent textual fixture//EN\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:projection-fixture\r\n"
                + "DTSTAMP:20261027T000000Z\r\n"
                + "DTSTART;X-ORIGIN=client:20261028T100000Z\r\n"
                + "SUMMARY:Projection fixture\r\n"
                + "ATTENDEE;CN=First Client:mailto:first@example.test\r\n"
                + "ATTENDEE;CN=Second Client:mailto:second@example.test\r\n"
                + "X-CLIENT-DATA;X-FORMAT=legacy:opaque client data\r\n"
                + "BEGIN:VALARM\r\n"
                + "TRIGGER:-PT5M\r\n"
                + "ACTION:DISPLAY\r\n"
                + "DESCRIPTION:Alarm fixture\r\n"
                + "END:VALARM\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n")
            .getBytes(StandardCharsets.UTF_8),
        Component.VEVENT);
  }
}
