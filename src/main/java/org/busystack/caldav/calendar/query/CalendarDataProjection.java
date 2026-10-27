package org.busystack.caldav.calendar.query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentContainer;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyContainer;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.property.FreeBusy;
import net.fortuna.ical4j.model.property.XProperty;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.recurrence.CalendarRecurrence;

/** RFC 4791 section 9.6 report projection. Stored representations are never mutated. */
public final class CalendarDataProjection {
  public record Selection(
      ComponentSelection component,
      TimeRange expand,
      TimeRange limitRecurrence,
      TimeRange limitFreeBusy) {
    public Selection {
      if (expand != null && limitRecurrence != null)
        throw QueryException.invalid("Expansion and recurrence limiting are mutually exclusive");
      for (TimeRange range : new TimeRange[] {expand, limitRecurrence, limitFreeBusy}) {
        if (range != null && (range.start() == null || range.end() == null))
          throw QueryException.invalid("Calendar-data time selections require both bounds");
      }
    }
  }

  public record ComponentSelection(
      String name,
      boolean allProperties,
      boolean allComponents,
      List<PropertySelection> properties,
      List<ComponentSelection> components) {
    public ComponentSelection {
      name = checkedName(name);
      properties = List.copyOf(properties);
      components = List.copyOf(components);
      if (allProperties && !properties.isEmpty() || allComponents && !components.isEmpty())
        throw QueryException.invalid("Explicit and all-item selectors cannot be combined");
    }
  }

  public record PropertySelection(String name, boolean noValue) {
    public PropertySelection {
      name = checkedName(name);
    }
  }

  private final QueryLimits limits;
  private final CalendarRecurrence recurrence;
  private long candidateWork;
  private int returnedComponents;
  private int returnedPeriods;
  private long reservedReportBytes;

  /** One instance belongs to one REPORT, including every calendar resource in its response. */
  public CalendarDataProjection(QueryLimits limits) {
    this.limits = Objects.requireNonNull(limits);
    this.recurrence = new CalendarRecurrence(limits);
  }

  public Calendar project(
      CalendarContent.Parsed parsed, Selection selection, FloatingTimezone floating) {
    Objects.requireNonNull(parsed);
    long sourceBytes = CalendarReportSize.estimate(parsed);
    reserveBytes(sourceBytes); // Input retained for this report.
    reserveBytes(sourceBytes); // Baseline report copy, before copying or temporal expansion.
    spend(parsed.calendar().getProperties().size());
    chargeInput(parsed.calendar());
    if (selection == null) {
      Calendar result = CalendarReports.copy(parsed);
      chargeOutput(result);
      return result;
    }
    if (selection.component() != null) {
      if (!Calendar.VCALENDAR.equals(selection.component().name()))
        throw QueryException.invalid("Calendar-data selection must start at VCALENDAR");
      validateComplexity(selection.component(), new int[] {0}, 0);
    }
    Calendar result =
        selection.expand() != null
            ? recurrence.expand(parsed, selection.expand(), floating)
            : selection.limitRecurrence() != null
                ? recurrence.limitRecurrence(parsed, selection.limitRecurrence(), floating)
                : CalendarReports.copy(parsed);
    if (selection.limitFreeBusy() != null) limitFreeBusy(result, selection.limitFreeBusy());
    if (selection.component() != null) {
      projectProperties(result, selection.component());
      projectChildren(result, selection.component());
    }
    chargeOutput(result);
    return result;
  }

  private void reserveBytes(long amount) {
    if (amount > CalendarReportSize.MAX_REPORT_BYTES - reservedReportBytes)
      throw QueryException.limit();
    reservedReportBytes += amount;
  }

  private void spend(long amount) {
    if (amount > limits.maximumCandidateWork() - candidateWork) throw QueryException.limit();
    candidateWork += amount;
  }

  private void chargeInput(ComponentContainer<?> owner) {
    for (Component component : owner.getComponents()) {
      spend(1L + component.getProperties().size());
      if (component instanceof ComponentContainer<?> nested) chargeInput(nested);
    }
  }

  private void chargeOutput(ComponentContainer<?> owner) {
    for (Component component : owner.getComponents()) {
      if (++returnedComponents > limits.maximumInstances()) throw QueryException.limit();
      if (component instanceof ComponentContainer<?> nested) chargeOutput(nested);
    }
  }

  private void limitFreeBusy(Calendar calendar, TimeRange range) {
    if (Duration.between(range.start(), range.end())
            .compareTo(Duration.ofDays(limits.maximumRangeDays()))
        > 0) throw QueryException.limit();
    for (var component : calendar.getComponents()) {
      if (!Component.VFREEBUSY.equals(component.getName())) continue;
      var filtered = new ArrayList<Property>();
      for (Property property : component.getProperties()) {
        if (!(property instanceof FreeBusy freeBusy)) {
          filtered.add(property);
          continue;
        }
        var retained = new ArrayList<org.threeten.extra.Interval>();
        for (var interval : freeBusy.getIntervals()) {
          spend(1);
          if (range.overlaps(interval.getStart(), interval.getEnd())) {
            if (++returnedPeriods > limits.maximumInstances()) throw QueryException.limit();
            retained.add(interval);
          }
        }
        // Retain intersecting complete periods (including their FBTYPE), without clipping them.
        if (!retained.isEmpty()) filtered.add(new FreeBusy(property.getParameterList(), retained));
      }
      component.setPropertyList(new PropertyList(filtered));
    }
  }

  private void validateComplexity(ComponentSelection selection, int[] work, int depth) {
    if (depth > 8 || ++work[0] + selection.properties().size() > limits.maximumFilterNodes())
      throw QueryException.limit();
    work[0] += selection.properties().size();
    for (ComponentSelection child : selection.components())
      validateComplexity(child, work, depth + 1);
  }

  private static void projectProperties(PropertyContainer target, ComponentSelection selection) {
    if (selection.allProperties()) return;
    var requested = new LinkedHashMap<String, Boolean>();
    for (PropertySelection property : selection.properties())
      requested.merge(property.name(), property.noValue(), (first, second) -> first && second);
    var properties = new ArrayList<Property>();
    for (Property property : target.getProperties()) {
      Boolean noValue = requested.get(property.getName().toUpperCase(Locale.ROOT));
      if (noValue == null) continue;
      // A report may omit required values. A generic library property represents that projection
      // without forcing a date/URI parser to accept an empty value. Parameters remain on the copy.
      if (noValue) {
        var empty = new XProperty(property.getName(), property.getParameterList(), "");
        empty.setPrefix(property.getPrefix());
        properties.add(empty);
      } else properties.add(property);
    }
    target.setPropertyList(new PropertyList(properties));
  }

  private static <T extends Component> void projectChildren(
      ComponentContainer<T> owner, ComponentSelection selection) {
    if (selection.allComponents()) return;
    var selected = new ArrayList<T>();
    for (T component : owner.getComponents()) {
      List<ComponentSelection> matching =
          selection.components().stream()
              .filter(value -> value.name().equalsIgnoreCase(component.getName()))
              .toList();
      if (matching.isEmpty()) continue;
      ComponentSelection combined = combine(matching);
      projectProperties(component, combined);
      if (component instanceof ComponentContainer<?> nested) projectChildren(nested, combined);
      selected.add(component);
    }
    owner.setComponentList(new ComponentList<>(selected));
  }

  private static ComponentSelection combine(List<ComponentSelection> selections) {
    boolean allProperties = selections.stream().anyMatch(ComponentSelection::allProperties);
    boolean allComponents = selections.stream().anyMatch(ComponentSelection::allComponents);
    return new ComponentSelection(
        selections.getFirst().name(),
        allProperties,
        allComponents,
        allProperties
            ? List.of()
            : selections.stream().flatMap(value -> value.properties().stream()).toList(),
        allComponents
            ? List.of()
            : selections.stream().flatMap(value -> value.components().stream()).toList());
  }

  private static String checkedName(String name) {
    if (name == null
        || name.isEmpty()
        || name.length() > 255
        || !name.chars()
            .allMatch(
                value ->
                    value >= 'A' && value <= 'Z'
                        || value >= 'a' && value <= 'z'
                        || value >= '0' && value <= '9'
                        || value == '-'))
      throw QueryException.invalid("Invalid calendar selector name");
    return name.toUpperCase(Locale.ROOT);
  }
}
