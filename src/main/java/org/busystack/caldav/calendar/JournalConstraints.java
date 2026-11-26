package org.busystack.caldav.calendar;

import static net.fortuna.ical4j.model.Property.*;

import java.time.temporal.Temporal;
import java.util.Locale;
import java.util.Set;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentContainer;

final class JournalConstraints {
  private static final Set<String> SINGLETONS =
      Set.of(
          CLASS,
          CREATED,
          DTSTART,
          LAST_MODIFIED,
          ORGANIZER,
          RECURRENCE_ID,
          SEQUENCE,
          STATUS,
          SUMMARY,
          URL,
          "COLOR");
  private static final Set<String> DISALLOWED =
      Set.of(
          DTEND,
          DUE,
          DURATION,
          COMPLETED,
          PERCENT_COMPLETE,
          TRANSP,
          GEO,
          LOCATION,
          PRIORITY,
          "CONFERENCE",
          "NAME",
          "REFRESH-INTERVAL",
          "SOURCE",
          "CALENDAR-ADDRESS",
          "PARTICIPANT-TYPE",
          "LOCATION-TYPE",
          "RESOURCE-TYPE");
  private static final Set<String> STATUSES = Set.of("DRAFT", "FINAL", "CANCELLED");
  private static final Set<String> CHILDREN = Set.of("PARTICIPANT", "VLOCATION", "VRESOURCE");

  private JournalConstraints() {}

  static void validate(Component journal, Temporal start) {
    // RFC 5545 §3.6.3 and RFC 7986 §5.9. RRULE multiplicity is a SHOULD and
    // stays representable. The report engine diagnoses undefined expansion.
    if (journal.getProperties(UID).size() != 1
        || journal.getProperties(DTSTAMP).size() != 1
        || SINGLETONS.stream().anyMatch(name -> journal.getProperties(name).size() > 1)
        || DISALLOWED.stream().anyMatch(name -> !journal.getProperties(name).isEmpty())) invalid();
    for (var status : journal.getProperties(STATUS))
      if (!STATUSES.contains(status.getValue().toUpperCase(Locale.ROOT))) invalid();
    var styled = journal.getProperties("STYLED-DESCRIPTION");
    if (styled.size() > 1
        && styled.stream()
                .filter(
                    property ->
                        property
                            .getParameter("DERIVED")
                            .map(parameter -> !parameter.getValue().equalsIgnoreCase("TRUE"))
                            .orElse(true))
                .count()
            != 1) invalid();
    if (start == null
        && (!journal.getProperties(RRULE).isEmpty()
            || !journal.getProperties(RDATE).isEmpty()
            || !journal.getProperties(EXDATE).isEmpty())) invalid();
    if (journal instanceof ComponentContainer<?> container)
      for (Component child : container.getComponents()) {
        if (!CHILDREN.contains(child.getName())) invalid();
        validateChild(child);
      }
  }

  private static void validateChild(Component child) {
    if (child.getProperties(UID).size() != 1 || child.getRequiredProperty(UID).getValue().isBlank())
      invalid();
    boolean participant = child.getName().equals("PARTICIPANT");
    if (participant && child.getProperties("PARTICIPANT-TYPE").size() != 1) invalid();
    Set<String> singleton =
        switch (child.getName()) {
          case "PARTICIPANT" ->
              Set.of(
                  "CALENDAR-ADDRESS",
                  CREATED,
                  DESCRIPTION,
                  DTSTAMP,
                  GEO,
                  LAST_MODIFIED,
                  PRIORITY,
                  SEQUENCE,
                  STATUS,
                  SUMMARY,
                  URL);
          case "VLOCATION" -> Set.of(DESCRIPTION, GEO, "LOCATION-TYPE", "NAME");
          case "VRESOURCE" -> Set.of(DESCRIPTION, GEO, "NAME", "RESOURCE-TYPE");
          default -> throw new IllegalStateException("Unexpected journal child");
        };
    if (singleton.stream().anyMatch(name -> child.getProperties(name).size() > 1)) invalid();
    if (child instanceof ComponentContainer<?> nested)
      for (Component component : nested.getComponents()) {
        if (!participant || !Set.of("VLOCATION", "VRESOURCE").contains(component.getName()))
          invalid();
        validateChild(component);
      }
  }

  private static void invalid() {
    throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
  }
}
