package org.busystack.caldav.protocol;

import static org.busystack.caldav.protocol.DavXml.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import org.busystack.caldav.calendar.query.CalendarDataProjection;
import org.busystack.caldav.calendar.query.CalendarFilter;
import org.busystack.caldav.calendar.query.TimeRange;
import org.w3c.dom.Element;

/** XML grammar belongs to the protocol; calendaring filters are immutable semantic values. */
final class CalendarQueryXml {
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
          .withResolverStyle(ResolverStyle.STRICT)
          .withZone(ZoneOffset.UTC);

  private CalendarQueryXml() {}

  static CalendarFilter.ComponentFilter filter(Element root) {
    Element filter = required(root, CAL, "filter");
    List<Element> children = children(filter);
    if (children.size() != 1 || !is(children.getFirst(), CAL, "comp-filter"))
      throw new DavException(403, c("valid-filter"));
    var result = component(children.getFirst());
    if (!"VCALENDAR".equals(result.name())) throw new DavException(403, c("valid-filter"));
    return result;
  }

  private static CalendarFilter.ComponentFilter component(Element node) {
    List<CalendarFilter.PropertyFilter> properties = new ArrayList<>();
    List<CalendarFilter.ComponentFilter> components = new ArrayList<>();
    for (Element child : children(node)) {
      if (is(child, CAL, "prop-filter")) properties.add(property(child));
      else if (is(child, CAL, "comp-filter")) components.add(component(child));
      else if (!is(child, CAL, "is-not-defined") && !is(child, CAL, "time-range"))
        throw new DavException(403, c("supported-filter"));
    }
    boolean absent = child(node, CAL, "is-not-defined") != null;
    if (absent && children(node).size() != 1) throw new DavException(403, c("valid-filter"));
    Element range = child(node, CAL, "time-range");
    return new CalendarFilter.ComponentFilter(
        nameAttribute(node), absent, range == null ? null : range(range), properties, components);
  }

  private static CalendarFilter.PropertyFilter property(Element node) {
    List<CalendarFilter.ParameterFilter> parameters = new ArrayList<>();
    for (Element child : children(node)) {
      if (is(child, CAL, "param-filter")) parameters.add(parameter(child));
      else if (!is(child, CAL, "is-not-defined")
          && !is(child, CAL, "time-range")
          && !is(child, CAL, "text-match")) throw new DavException(403, c("supported-filter"));
    }
    boolean absent = child(node, CAL, "is-not-defined") != null;
    Element range = child(node, CAL, "time-range"), text = child(node, CAL, "text-match");
    if (absent && children(node).size() != 1 || range != null && text != null)
      throw new DavException(403, c("valid-filter"));
    return new CalendarFilter.PropertyFilter(
        nameAttribute(node),
        absent,
        range == null ? null : range(range),
        text == null ? null : text(text),
        parameters);
  }

  private static CalendarFilter.ParameterFilter parameter(Element node) {
    boolean absent = child(node, CAL, "is-not-defined") != null;
    Element text = child(node, CAL, "text-match");
    for (Element child : children(node))
      if (!is(child, CAL, "is-not-defined") && !is(child, CAL, "text-match"))
        throw new DavException(403, c("supported-filter"));
    if (absent && children(node).size() != 1) throw new DavException(403, c("valid-filter"));
    return new CalendarFilter.ParameterFilter(
        nameAttribute(node), absent, text == null ? null : text(text));
  }

  private static CalendarFilter.TextMatch text(Element node) {
    String negate = node.getAttribute("negate-condition");
    if (!negate.isEmpty() && !negate.equals("yes") && !negate.equals("no"))
      throw new DavException(403, c("valid-filter"));
    if (!children(node).isEmpty() || node.getTextContent().length() > 8192)
      throw new DavException(403, c("valid-filter"));
    return new CalendarFilter.TextMatch(
        node.getTextContent(),
        node.hasAttribute("collation") ? node.getAttribute("collation") : null,
        negate.equals("yes"));
  }

  static TimeRange range(Element node) {
    return new TimeRange(
        node.hasAttribute("start") ? instant(node.getAttribute("start")) : null,
        node.hasAttribute("end") ? instant(node.getAttribute("end")) : null);
  }

  private static Instant instant(String value) {
    try {
      return Instant.from(UTC.parse(value));
    } catch (java.time.DateTimeException ex) {
      throw new DavException(403, c("valid-filter"));
    }
  }

  static CalendarDataProjection.Selection selection(Element node) {
    if (node.hasAttribute("content-type")
            && !node.getAttribute("content-type").equals("text/calendar")
        || node.hasAttribute("version") && !node.getAttribute("version").equals("2.0"))
      throw new DavException(403, c("supported-calendar-data"));
    for (Element child : children(node))
      if (!is(child, CAL, "comp")
          && !is(child, CAL, "expand")
          && !is(child, CAL, "limit-recurrence-set")
          && !is(child, CAL, "limit-freebusy-set")) throw new DavException(400, null);
    Element component = child(node, CAL, "comp"),
        expand = child(node, CAL, "expand"),
        limit = child(node, CAL, "limit-recurrence-set"),
        busy = child(node, CAL, "limit-freebusy-set");
    if (expand != null && limit != null) throw new DavException(400, null);
    return new CalendarDataProjection.Selection(
        component == null ? null : selectionComponent(component),
        expand == null ? null : range(expand),
        limit == null ? null : range(limit),
        busy == null ? null : range(busy));
  }

  private static CalendarDataProjection.ComponentSelection selectionComponent(Element node) {
    List<CalendarDataProjection.PropertySelection> properties = new ArrayList<>();
    List<CalendarDataProjection.ComponentSelection> components = new ArrayList<>();
    boolean allProps = child(node, CAL, "allprop") != null,
        allComps = child(node, CAL, "allcomp") != null;
    for (Element child : children(node)) {
      if (is(child, CAL, "prop")) {
        String novalue = child.getAttribute("novalue");
        if (!novalue.isEmpty() && !novalue.equals("yes") && !novalue.equals("no"))
          throw new DavException(400, null);
        properties.add(
            new CalendarDataProjection.PropertySelection(
                nameAttribute(child), novalue.equals("yes")));
      } else if (is(child, CAL, "comp")) components.add(selectionComponent(child));
      else if (!is(child, CAL, "allprop") && !is(child, CAL, "allcomp"))
        throw new DavException(400, null);
    }
    if (allProps && !properties.isEmpty() || allComps && !components.isEmpty())
      throw new DavException(400, null);
    return new CalendarDataProjection.ComponentSelection(
        nameAttribute(node), allProps, allComps, properties, components);
  }

  private static String nameAttribute(Element node) {
    String name = node.getAttribute("name");
    if (name.isBlank()
        || name.length() > 255
        || !name.codePoints()
            .allMatch(
                c ->
                    c >= 'A' && c <= 'Z'
                        || c >= 'a' && c <= 'z'
                        || c >= '0' && c <= '9'
                        || c == '-')) throw new DavException(400, null);
    return name.toUpperCase(java.util.Locale.ROOT);
  }
}
