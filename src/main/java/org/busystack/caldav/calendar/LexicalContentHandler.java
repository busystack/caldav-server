package org.busystack.caldav.calendar;

import java.io.IOException;
import java.util.*;
import net.fortuna.ical4j.data.ContentHandler;
import net.fortuna.ical4j.model.*;

/** Retains typed-value spelling that the library's temporal model normalizes away. */
final class LexicalContentHandler
    implements org.busystack.caldav.calendar.parser.ParameterListHandler {
  private static final Set<String> RETAIN =
      Set.of(
          Property.DURATION,
          Property.RDATE,
          Property.TRIGGER,
          Property.CATEGORIES,
          Property.RESOURCES);
  private final ContentHandler delegate;
  private final List<Frame> frames = new ArrayList<>();
  private final Deque<Frame> stack = new ArrayDeque<>();
  private String currentName;

  private static final class Frame {
    int nextProperty;
    final Map<Integer, String> values = new HashMap<>();
  }

  LexicalContentHandler(ContentHandler delegate) {
    this.delegate = delegate;
  }

  private void enter() {
    Frame frame = new Frame();
    frames.add(frame);
    stack.push(frame);
  }

  @Override
  public void startCalendar() {
    enter();
    delegate.startCalendar();
  }

  @Override
  public void endCalendar() throws IOException {
    delegate.endCalendar();
    stack.pop();
  }

  @Override
  public void startComponent(String name) {
    enter();
    delegate.startComponent(name);
  }

  @Override
  public void endComponent(String name) {
    delegate.endComponent(name);
    stack.pop();
  }

  @Override
  public void startProperty(String name) {
    currentName = name.toUpperCase(java.util.Locale.ROOT);
    delegate.startProperty(name);
  }

  @Override
  public void endProperty(String name) {
    delegate.endProperty(name);
    stack.peek().nextProperty++;
  }

  @Override
  public void propertyValue(String value) {
    if (currentName.equals(Property.CATEGORIES) || currentName.equals(Property.RESOURCES))
      CalendarTextValues.list(value);
    if (RETAIN.contains(currentName)) stack.peek().values.put(stack.peek().nextProperty, value);
    delegate.propertyValue(value);
  }

  @Override
  public void parameter(String name, String value) {
    delegate.parameter(name, value);
  }

  @Override
  public void parameterValues(String name, List<String> values) {
    if (delegate instanceof org.busystack.caldav.calendar.parser.ParameterListHandler structured)
      structured.parameterValues(name, values);
    else delegate.parameter(name, String.join(",", values));
  }

  Map<Property, String> values(net.fortuna.ical4j.model.Calendar calendar) {
    var result = new IdentityHashMap<Property, String>();
    int[] next = {0};
    collect(calendar, calendar.getComponents(), next, result);
    if (next[0] != frames.size())
      throw new IllegalStateException("Calendar property alignment failed");
    return Collections.unmodifiableMap(result);
  }

  private void collect(
      PropertyContainer container,
      List<? extends Component> children,
      int[] next,
      IdentityHashMap<Property, String> result) {
    Frame frame = frames.get(next[0]++);
    var properties = container.getProperties();
    if (properties.size() != frame.nextProperty)
      throw new IllegalStateException("Calendar property alignment failed");
    frame.values.forEach((index, value) -> result.put(properties.get(index), value));
    for (var child : children) {
      List<? extends Component> nested =
          child instanceof ComponentContainer<?> cc ? cc.getComponents() : List.of();
      collect(child, nested, next, result);
    }
  }
}
