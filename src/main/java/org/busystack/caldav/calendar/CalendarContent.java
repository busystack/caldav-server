package org.busystack.caldav.calendar;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.data.UnfoldingReader;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import org.busystack.caldav.calendar.query.BoundedTimeZoneRegistry;

/** Model validation and indexing input only. The original octets remain authoritative. */
public final class CalendarContent {
  public record Parsed(
      String uid, Calendar calendar, java.util.Map<Property, String> lexicalValues) {
    public Parsed {
      lexicalValues =
          java.util.Collections.unmodifiableMap(new java.util.IdentityHashMap<>(lexicalValues));
    }

    public Parsed(String uid, Calendar calendar) {
      this(uid, calendar, java.util.Map.of());
    }

    public String lexicalValue(Property property) {
      return lexicalValues.getOrDefault(property, property.getValue());
    }
  }

  public static final int MAX_BYTES = 1048576;

  private CalendarContent() {}

  public static Parsed parse(byte[] bytes, String supportedComponent) {
    if (bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new IllegalArgumentException("Calendar resource exceeds the supported size");
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      var calendar = new AtomicReference<Calendar>();
      var handler =
          new org.busystack.caldav.calendar.parser.StrictContentHandler(
              calendar::set, new BoundedTimeZoneRegistry());
      var lexical =
          new LexicalContentHandler(
              new org.busystack.caldav.calendar.parser.ParsingLimitsHandler(handler));
      new org.busystack.caldav.calendar.parser.Ical4jCalendarParser()
          .parse(new UnfoldingReader(new StringReader(text)), lexical);
      Calendar model = calendar.get();
      if (model == null
          || model.validate().hasErrors()
          || model.getProperty(Property.METHOD).isPresent())
        throw new IllegalArgumentException("Invalid calendar resource");
      String uid = null;
      int masters = 0;
      Set<String> recurrenceIds = new HashSet<>();
      for (var component : model.getComponents()) {
        if (Component.VTIMEZONE.equals(component.getName())) continue;
        if (!component.getName().equals(supportedComponent))
          throw new IllegalArgumentException("Unsupported calendar component");
        String current =
            component
                .getProperty(Property.UID)
                .orElseThrow(() -> new IllegalArgumentException("UID is required"))
                .getValue();
        if (current.isBlank() || current.length() > 1024 || (uid != null && !uid.equals(current)))
          throw new IllegalArgumentException("A calendar resource must have one UID");
        uid = current;
        var recurrence = component.getProperty(Property.RECURRENCE_ID);
        if (recurrence.isEmpty()) masters++;
        else if (!recurrenceIds.add(recurrence.get().toString()))
          throw new IllegalArgumentException("Duplicate recurrence identifier");
      }
      if (uid == null || masters > 1)
        throw new IllegalArgumentException("Invalid calendar object resource");
      return new Parsed(uid, model, lexical.values(model));
    } catch (IOException | ParserException ex) {
      Throwable nested = ex;
      for (int i = 0; i < 8 && nested != null; i++, nested = nested.getCause()) {
        if (nested instanceof org.busystack.caldav.calendar.query.QueryException query) throw query;
      }
      // Library exception messages can contain submitted data; never return or log them.
      throw new IllegalArgumentException("Malformed calendar resource");
    }
  }
}
