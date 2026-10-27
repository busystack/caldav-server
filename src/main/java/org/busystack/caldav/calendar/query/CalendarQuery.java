package org.busystack.caldav.calendar.query;

import static net.fortuna.ical4j.model.Property.*;

import java.time.*;
import java.util.*;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.property.*;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.query.CalendarFilter.*;
import org.busystack.caldav.calendar.recurrence.*;

/**
 * One instance per REPORT: cumulative work budget covers all resources evaluated by that report.
 */
public final class CalendarQuery {
  private final QueryLimits limits;
  private final WorkBudget budget;
  private final RecurrenceExpander recurrence;

  public CalendarQuery(QueryLimits limits) {
    this.limits = limits;
    budget = new WorkBudget(limits);
    recurrence = new RecurrenceExpander(budget);
  }

  public boolean matches(CalendarContent.Parsed parsed, ComponentFilter filter, ZoneId zone) {
    return matches(parsed, filter, FloatingTimezone.of(zone));
  }

  public boolean matches(
      CalendarContent.Parsed parsed, ComponentFilter filter, FloatingTimezone timezone) {
    validate(filter);
    if (!filter.name().equals("VCALENDAR"))
      throw QueryException.invalid("The root component filter must target VCALENDAR");
    if (filter.notDefined()) return false;
    if (filter.timeRange() != null) throw unsupported();
    if (!properties(parsed, parsed.calendar(), null, filter.properties(), timezone.zoneId()))
      return false;
    for (ComponentFilter child : filter.components())
      if (!components(parsed, parsed.calendar().getComponents(), child, null, timezone))
        return false;
    return true;
  }

  private boolean components(
      CalendarContent.Parsed parsed,
      List<? extends Component> available,
      ComponentFilter filter,
      Occurrence owner,
      FloatingTimezone timezone) {
    var selected = available.stream().filter(c -> c.getName().equals(filter.name())).toList();
    if (filter.notDefined()) return selected.isEmpty();
    if (selected.isEmpty()) return false;
    boolean primary =
        filter.name().equals(Component.VEVENT) || filter.name().equals(Component.VTODO);
    TimeRange window = window(filter);
    List<Occurrence> occurrences;
    if (primary && window != null) occurrences = recurrence.occurrences(parsed, window, timezone);
    else {
      occurrences = new ArrayList<>();
      for (Component c : selected)
        occurrences.add(RecurrenceExpander.single(parsed, c, null, null, timezone.zoneId()));
    }
    boolean unknown = false;
    for (Occurrence occurrence : occurrences) {
      if (RecurrenceExpander.unknownFuture(occurrence)) {
        unknown = true;
        continue;
      }
      if (!occurrence.component().getName().equals(filter.name())) continue;
      budget.charge(1);
      if (filter.timeRange() != null) {
        boolean overlaps;
        if (filter.name().equals(Component.VALARM)) {
          if (owner == null) throw unsupported();
          overlaps =
              AlarmCalculator.triggers(
                      parsed, occurrence.component(), owner, timezone.zoneId(), budget)
                  .stream()
                  .anyMatch(filter.timeRange()::contains);
        } else
          overlaps = TimeRangeMatcher.matches(occurrence, filter.timeRange(), timezone.zoneId());
        if (!overlaps) continue;
      }
      if (!properties(
          parsed, occurrence.component(), occurrence, filter.properties(), timezone.zoneId()))
        continue;
      boolean children = true;
      for (ComponentFilter child : filter.components()) {
        List<? extends Component> nested =
            occurrence.component() instanceof ComponentContainer<?> container
                ? container.getComponents()
                : List.of();
        if (!components(parsed, nested, child, primary ? occurrence : owner, timezone)) {
          children = false;
          break;
        }
      }
      if (children) return true;
    }
    if (unknown) throw QueryException.limit();
    return false;
  }

  private boolean properties(
      CalendarContent.Parsed parsed,
      PropertyContainer container,
      Occurrence occurrence,
      List<PropertyFilter> filters,
      ZoneId zone) {
    for (PropertyFilter filter : filters) {
      var found = container.getProperties(filter.name());
      if (filter.notDefined()) {
        if (!found.isEmpty()) return false;
        continue;
      }
      boolean match = false;
      for (var property : found)
        if (property(parsed, property, occurrence, filter, zone)) {
          match = true;
          break;
        }
      if (!match
          && found.isEmpty()
          && filter.timeRange() != null
          && occurrence != null
          && (filter.name().equals(DTEND) || filter.name().equals(DUE))) {
        var effective = occurrence.dates().get(filter.name());
        if (effective != null && filter.parameters().isEmpty())
          match = filter.timeRange().contains(TemporalSupport.instant(effective, zone));
      }
      if (!match) return false;
    }
    return true;
  }

  private boolean property(
      CalendarContent.Parsed parsed,
      Property property,
      Occurrence occurrence,
      PropertyFilter filter,
      ZoneId zone) {
    budget.charge(1);
    if (filter.timeRange() != null) {
      if (!Set.of(COMPLETED, CREATED, DTEND, DTSTAMP, DTSTART, DUE, LAST_MODIFIED)
          .contains(property.getName())) throw unsupported();
      var temporal =
          occurrence == null
              ? (property instanceof DateProperty<?> d ? d.getDate() : null)
              : occurrence.dates().get(property.getName());
      if (temporal == null || !filter.timeRange().contains(TemporalSupport.instant(temporal, zone)))
        return false;
    }
    if (filter.textMatch() != null && !filter.textMatch().matches(property.getValue()))
      return false;
    for (ParameterFilter parameter : filter.parameters()) {
      var found = property.getParameters(parameter.name());
      if (parameter.notDefined()) {
        if (!found.isEmpty()) return false;
        continue;
      }
      if (found.isEmpty()
          || parameter.textMatch() != null
              && found.stream()
                  .flatMap(p -> parameterValues(p).stream())
                  .noneMatch(parameter.textMatch()::matches)) return false;
    }
    return true;
  }

  private static List<String> parameterValues(Parameter parameter) {
    if (parameter instanceof org.busystack.caldav.calendar.parser.StructuredParameter structured)
      return structured.values();
    if (parameter instanceof net.fortuna.ical4j.model.parameter.Member member)
      return member.getGroups().stream().map(java.net.URI::toString).toList();
    if (parameter instanceof net.fortuna.ical4j.model.parameter.DelegatedFrom from)
      return from.getDelegators().stream().map(java.net.URI::toString).toList();
    if (parameter instanceof net.fortuna.ical4j.model.parameter.DelegatedTo to)
      return to.getDelegatees().stream().map(java.net.URI::toString).toList();
    return List.of(parameter.getValue());
  }

  private TimeRange window(ComponentFilter filter) {
    List<TimeRange> ranges = new ArrayList<>();
    collectRanges(filter, ranges);
    if (ranges.isEmpty()) return null;
    Instant first = null, last = null;
    boolean openFirst = false, openLast = false;
    for (TimeRange range : ranges) {
      if (range.start() == null) openFirst = true;
      else if (first == null || range.start().isBefore(first)) first = range.start();
      if (range.end() == null) openLast = true;
      else if (last == null || range.end().isAfter(last)) last = range.end();
    }
    if (openFirst && openLast) throw QueryException.limit();
    return new TimeRange(openFirst ? null : first, openLast ? null : last);
  }

  private void collectRanges(ComponentFilter f, List<TimeRange> ranges) {
    if (f.timeRange() != null) ranges.add(f.timeRange());
    for (var p : f.properties()) if (p.timeRange() != null) ranges.add(p.timeRange());
    for (var c : f.components()) collectRanges(c, ranges);
  }

  private void validate(ComponentFilter filter) {
    record Node(ComponentFilter filter, int depth) {}
    var nodes = new ArrayDeque<Node>();
    nodes.push(new Node(filter, 0));
    int count = 0;
    while (!nodes.isEmpty()) {
      var node = nodes.pop();
      if (++count > limits.maximumFilterNodes() || node.depth() > 16) throw QueryException.limit();
      for (var p : node.filter().properties()) {
        count += 1 + p.parameters().size();
        if (count > limits.maximumFilterNodes()) throw QueryException.limit();
      }
      for (var c : node.filter().components()) nodes.push(new Node(c, node.depth() + 1));
    }
  }

  private static QueryException unsupported() {
    return new QueryException(
        QueryException.Kind.UNSUPPORTED_FILTER,
        "Time range semantics are undefined for this filter target");
  }
}
