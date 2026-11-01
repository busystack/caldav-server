package org.busystack.caldav.calendar.parser;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.fortuna.ical4j.data.CalendarParserImpl;
import net.fortuna.ical4j.data.ContentHandler;
import net.fortuna.ical4j.data.ParserException;

/**
 * Bridges RFC 5545 parameter lists to the official iCal4j 4.4.0 scalar callback. Only parameter
 * headers are adapted; iCal4j parses components, properties and values. This application-owned
 * adapter never changes the authoritative stored representation.
 */
public final class ParameterInput implements ContentHandler {
  private record Parameter(String name, List<String> values) {}

  private final ContentHandler delegate;
  private final ArrayDeque<Parameter> parameters = new ArrayDeque<>();

  private ParameterInput(ContentHandler delegate) {
    this.delegate = delegate;
  }

  public static void parse(Reader input, ContentHandler handler)
      throws IOException, ParserException {
    var adapter = new ParameterInput(handler);
    var writer = new java.io.StringWriter();
    input.transferTo(writer);
    String source = writer.toString();
    var derived = new StringBuilder(source.length());
    for (String line : source.toString().split("(?<=\\n)", -1)) {
      adapter.header(line, derived);
    }
    new CalendarParserImpl().parse(new StringReader(derived.toString()), adapter);
    if (!adapter.parameters.isEmpty()) throw invalid();
  }

  private void header(String line, StringBuilder output) {
    int cursor = 0;
    while (cursor < line.length() && line.charAt(cursor) != ';' && line.charAt(cursor) != ':') {
      output.append(line.charAt(cursor++));
    }
    while (cursor < line.length() && line.charAt(cursor) == ';') {
      int start = ++cursor;
      while (cursor < line.length() && token(line.charAt(cursor))) cursor++;
      if (cursor == start || cursor >= line.length() || line.charAt(cursor) != '=') throw invalid();
      String name = line.substring(start, cursor++);
      var values = new ArrayList<String>();
      do {
        start = cursor;
        if (cursor < line.length() && line.charAt(cursor) == '"') {
          cursor++;
          while (cursor < line.length() && line.charAt(cursor) != '"') {
            if (line.charAt(cursor) == '\r' || line.charAt(cursor) == '\n') throw invalid();
            cursor++;
          }
          if (cursor >= line.length()) throw invalid();
          cursor++;
        } else {
          while (cursor < line.length() && ",;:".indexOf(line.charAt(cursor)) < 0) {
            if (line.charAt(cursor) == '"'
                || line.charAt(cursor) == '\r'
                || line.charAt(cursor) == '\n') throw invalid();
            cursor++;
          }
        }
        values.add(line.substring(start, cursor));
        if (values.size() > 1000 || cursor >= line.length()) throw invalid();
        char delimiter = line.charAt(cursor);
        if (delimiter == ';' || delimiter == ':') break;
        if (delimiter != ',') throw invalid();
        cursor++;
      } while (true);
      parameters.add(new Parameter(name, List.copyOf(values)));
      output.append(';').append(name).append("=BUSYSTACK");
    }
    output.append(line, cursor, line.length());
  }

  private static boolean token(char value) {
    return value >= 'a' && value <= 'z'
        || value >= 'A' && value <= 'Z'
        || value >= '0' && value <= '9'
        || value == '-';
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Malformed calendar parameter header");
  }

  @Override
  public void parameter(String name, String value) {
    var original = parameters.poll();
    if (original == null || !original.name().equals(name) || !value.equals("BUSYSTACK"))
      throw invalid();
    if (delegate instanceof ParameterListHandler lists)
      lists.parameterValues(name, original.values());
    else delegate.parameter(name, String.join(",", original.values()));
  }

  @Override
  public void startCalendar() {
    delegate.startCalendar();
  }

  @Override
  public void endCalendar() throws IOException {
    delegate.endCalendar();
  }

  @Override
  public void startComponent(String name) {
    delegate.startComponent(name);
  }

  @Override
  public void endComponent(String name) {
    delegate.endComponent(name);
  }

  @Override
  public void startProperty(String name) {
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
}
