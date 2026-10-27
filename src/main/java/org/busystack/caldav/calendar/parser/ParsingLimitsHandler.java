package org.busystack.caldav.calendar.parser;

import java.io.IOException;
import java.util.List;
import net.fortuna.ical4j.data.ContentHandler;

/** Bounds the established parser's callbacks, before model or timezone construction. */
public final class ParsingLimitsHandler implements ParameterListHandler {
  private final ContentHandler delegate;
  private int calendars, depth, components, properties, parameters;

  public ParsingLimitsHandler(ContentHandler delegate) {
    this.delegate = delegate;
  }

  @Override
  public void startCalendar() {
    if (++calendars > 1) fail();
    delegate.startCalendar();
  }

  @Override
  public void endCalendar() throws IOException {
    delegate.endCalendar();
  }

  @Override
  public void startComponent(String name) {
    if (++depth > 8 || ++components > 2000) fail();
    delegate.startComponent(name);
  }

  @Override
  public void endComponent(String name) {
    depth--;
    delegate.endComponent(name);
  }

  @Override
  public void startProperty(String name) {
    if (++properties > 20000) fail();
    delegate.startProperty(name);
  }

  @Override
  public void endProperty(String name) {
    delegate.endProperty(name);
  }

  @Override
  public void propertyValue(String value) {
    delegate.propertyValue(value);
  }

  @Override
  public void parameter(String name, String value) {
    parameterValues(name, List.of(value));
  }

  @Override
  public void parameterValues(String name, List<String> values) {
    parameters += values.size();
    if (parameters > 40000) fail();
    if (delegate instanceof ParameterListHandler structured)
      structured.parameterValues(name, values);
    else delegate.parameter(name, String.join(",", values));
  }

  private static void fail() {
    throw new IllegalArgumentException("Calendar complexity exceeds supported limits");
  }
}
