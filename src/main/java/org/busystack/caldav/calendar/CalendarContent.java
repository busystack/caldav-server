package org.busystack.caldav.calendar;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import org.busystack.caldav.calendar.query.BoundedTimeZoneRegistry;

/** Strict model validation/indexing; admission supplies the authoritative representation octets. */
public final class CalendarContent {
  public record Parsed(
      String uid,
      Calendar calendar,
      java.util.Map<Property, String> lexicalValues,
      java.util.Map<String, java.time.Instant> zoneCoverage) {
    public Parsed {
      lexicalValues =
          java.util.Collections.unmodifiableMap(new java.util.IdentityHashMap<>(lexicalValues));
      zoneCoverage = java.util.Map.copyOf(zoneCoverage);
    }

    public Parsed(String uid, Calendar calendar) {
      this(uid, calendar, java.util.Map.of(), java.util.Map.of());
    }

    public Parsed(String uid, Calendar calendar, java.util.Map<Property, String> lexicalValues) {
      this(uid, calendar, lexicalValues, java.util.Map.of());
    }

    public String lexicalValue(Property property) {
      return lexicalValues.getOrDefault(property, property.getValue());
    }
  }

  public static final int MAX_BYTES = 1048576;

  private CalendarContent() {}

  public static Parsed parse(byte[] bytes, String supportedComponent) {
    if (bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
    try {
      String text = CalendarParsingInput.decode(bytes);
      CalendarTextProfile.validate(text);
      var calendar = new AtomicReference<Calendar>();
      var registry = new BoundedTimeZoneRegistry();
      var handler =
          new org.busystack.caldav.calendar.parser.StrictContentHandler(calendar::set, registry);
      var lexical =
          new LexicalContentHandler(
              new org.busystack.caldav.calendar.parser.ParsingLimitsHandler(handler));
      new org.busystack.caldav.calendar.parser.Ical4jCalendarParser()
          .parse(CalendarParsingInput.reader(text), lexical);
      Calendar model = calendar.get();
      if (model == null)
        throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
      Set<String> componentTypes = new HashSet<>();
      for (var component : model.getComponents())
        if (!Component.VTIMEZONE.equals(component.getName()))
          componentTypes.add(component.getName());
      if (model.getProperty(Property.METHOD).isPresent() || componentTypes.size() != 1)
        throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_OBJECT);
      validateTimezones(model);
      if (model.validate().hasErrors())
        throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
      CalendarConstraints.validate(model, lexical.values(model));
      String uid = null;
      int masters = 0;
      Set<java.time.temporal.Temporal> recurrenceIds = new HashSet<>();
      for (var component : model.getComponents()) {
        if (Component.VTIMEZONE.equals(component.getName())) continue;
        if (!component.getName().equals(supportedComponent))
          throw new CalendarValidationException(
              CalendarValidationException.Reason.UNSUPPORTED_COMPONENT);
        String current =
            component
                .getProperty(Property.UID)
                .orElseThrow(
                    () ->
                        new CalendarValidationException(
                            CalendarValidationException.Reason.INVALID_DATA))
                .getValue();
        if (current.isBlank() || current.length() > 1024 || (uid != null && !uid.equals(current)))
          throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_OBJECT);
        uid = current;
        var recurrence = component.getProperty(Property.RECURRENCE_ID);
        if (recurrence.isEmpty()) masters++;
        else if (!recurrenceIds.add(
            recurrenceKey((net.fortuna.ical4j.model.property.DateProperty<?>) recurrence.get())))
          throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_OBJECT);
      }
      if (uid == null || masters > 1)
        throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_OBJECT);
      return new Parsed(uid, model, lexical.values(model), registry.coverage());
    } catch (CalendarValidationException ex) {
      throw ex;
    } catch (IllegalArgumentException ex) {
      // Library and lexical validation messages may contain submitted values.
      throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
    } catch (IOException | ParserException ex) {
      Throwable nested = ex;
      for (int i = 0; i < 8 && nested != null; i++, nested = nested.getCause()) {
        if (nested instanceof CalendarValidationException invalid) throw invalid;
        if (nested instanceof org.busystack.caldav.calendar.query.QueryException query) throw query;
      }
      // Library exception messages can contain submitted data; never return or log them.
      throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
    }
  }

  private static void validateTimezones(Calendar model) {
    Set<String> defined = new HashSet<>();
    for (var component : model.getComponents(Component.VTIMEZONE)) {
      String id = component.getRequiredProperty(Property.TZID).getValue();
      if (!defined.add(id))
        throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_OBJECT);
    }
    validateTimezoneProperties(model, defined);
    for (var component : model.getComponents()) validateTimezoneTree(component, defined);
  }

  private static void validateTimezoneTree(Component component, Set<String> defined) {
    validateTimezoneProperties(component, defined);
    if (component instanceof net.fortuna.ical4j.model.ComponentContainer<?> nested)
      for (var child : nested.getComponents()) validateTimezoneTree(child, defined);
  }

  private static void validateTimezoneProperties(
      net.fortuna.ical4j.model.PropertyContainer owner, Set<String> defined) {
    for (var property : owner.getProperties())
      for (var parameter : property.getParameters(net.fortuna.ical4j.model.Parameter.TZID))
        if (!defined.contains(parameter.getValue()))
          throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_OBJECT);
  }

  private static java.time.temporal.Temporal recurrenceKey(
      net.fortuna.ical4j.model.property.DateProperty<?> property) {
    var value = property.getDate();
    if (value instanceof java.time.ZonedDateTime date) return date.toInstant();
    if (value instanceof java.time.OffsetDateTime date) return date.toInstant();
    return value;
  }
}
