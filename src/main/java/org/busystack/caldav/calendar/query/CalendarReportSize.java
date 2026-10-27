package org.busystack.caldav.calendar.query;

import java.util.function.Function;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentContainer;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyContainer;
import org.busystack.caldav.calendar.CalendarContent;

/** Conservative UTF-8/XML allocation estimate without serializing an entire calendar. */
public final class CalendarReportSize {
  public static final long MAX_REPORT_BYTES = 16L * 1024 * 1024;

  private CalendarReportSize() {}

  public static long estimate(CalendarContent.Parsed parsed) {
    return calendar(parsed.calendar(), parsed::lexicalValue);
  }

  public static long estimate(CalendarContent.Parsed parsed, Component component) {
    return component(component, parsed::lexicalValue);
  }

  public static long estimate(Calendar calendar) {
    return calendar(calendar, Property::getValue);
  }

  public static long estimate(Component component) {
    return component(component, Property::getValue);
  }

  private static long calendar(Calendar calendar, Function<Property, String> values) {
    long size = add(64, properties(calendar, values));
    for (Component component : calendar.getComponents())
      size = add(size, component(component, values));
    return size;
  }

  private static long component(Component component, Function<Property, String> values) {
    long size = add(add(64, text(component.getName()) * 2), properties(component, values));
    if (component instanceof ComponentContainer<?> nested)
      for (Component child : nested.getComponents()) size = add(size, component(child, values));
    return size;
  }

  private static long properties(PropertyContainer owner, Function<Property, String> values) {
    long size = 0;
    for (Property property : owner.getProperties()) {
      size = add(size, add(32, add(text(property.getName()), text(values.apply(property)))));
      size = add(size, text(property.getPrefix()));
      for (var parameter : property.getParameterList().getAll())
        size = add(size, add(32, add(text(parameter.getName()), text(parameter.getValue()))));
    }
    return size;
  }

  private static long text(String value) {
    // Six bytes per UTF-16 code unit bounds UTF-8, XML escaping, and iCalendar escaping/folding.
    return value == null ? 0 : 6L * value.length();
  }

  private static long add(long first, long second) {
    try {
      return Math.addExact(first, second);
    } catch (ArithmeticException error) {
      throw QueryException.limit();
    }
  }
}
