package org.busystack.caldav.calendar.parser;

import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import net.fortuna.ical4j.data.*;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.parameter.*;
import org.busystack.caldav.calendar.query.QueryException;

/** Uses iCal4j factories while retaining the parsed boundaries and RFC6868 literal characters. */
public final class StrictContentHandler extends DefaultContentHandler
    implements ParameterListHandler {
  private String currentProperty;
  private final Map<String, String> currentParameters = new HashMap<>();
  private static final Set<String> DATE_PROPERTIES =
      Set.of(
          Property.DTSTART,
          Property.DTEND,
          Property.DUE,
          Property.RECURRENCE_ID,
          Property.DTSTAMP,
          Property.CREATED,
          Property.LAST_MODIFIED,
          Property.COMPLETED,
          Property.RDATE,
          Property.EXDATE);
  private static final Set<String> UTC_PROPERTIES =
      Set.of(Property.DTSTAMP, Property.CREATED, Property.LAST_MODIFIED, Property.COMPLETED);
  private static final Set<String> SINGLE =
      Set.of(
          "ALTREP",
          "CN",
          "CUTYPE",
          "DIR",
          "ENCODING",
          "FMTTYPE",
          "FBTYPE",
          "LANGUAGE",
          "PARTSTAT",
          "RANGE",
          "RELATED",
          "RELTYPE",
          "ROLE",
          "RSVP",
          "SENT-BY",
          "TZID",
          "VALUE");

  public StrictContentHandler(
      Consumer<net.fortuna.ical4j.model.Calendar> consumer, TimeZoneRegistry registry) {
    super(consumer, registry, context());
  }

  private static ContentHandlerContext context() {
    return new ContentHandlerContext()
        .withPropertyFactorySupplier(
            () -> {
              List<PropertyFactory<?>> factories =
                  new ArrayList<>(new DefaultPropertyFactorySupplier().get());
              factories.removeIf(factory -> factory.supports(Property.CLASS));
              factories.addFirst(new ClassificationFactory());
              return factories;
            });
  }

  @Override
  public void startProperty(String name) {
    token(name);
    currentProperty = name.toUpperCase(Locale.ROOT);
    currentParameters.clear();
    super.startProperty(name.toUpperCase(Locale.ROOT));
  }

  @Override
  public void propertyValue(String value) {
    String declared = currentParameters.getOrDefault(Parameter.VALUE, "").toUpperCase(Locale.ROOT);
    if (Property.RRULE.equals(currentProperty)
        || Property.EXRULE.equals(currentProperty)
        || declared.equals("RECUR"))
      org.busystack.caldav.calendar.RecurrenceRuleSyntax.validate(value);
    if (Set.of(Property.DURATION, "REFRESH-INTERVAL").contains(currentProperty)
        || Property.TRIGGER.equals(currentProperty) && !declared.equals("DATE-TIME"))
      org.busystack.caldav.calendar.DurationSyntax.validate(value);
    else if (declared.equals("DURATION"))
      for (String duration : value.split(",", -1))
        org.busystack.caldav.calendar.DurationSyntax.validate(duration);
    if (declared.equals("PERIOD") || Property.FREEBUSY.equals(currentProperty))
      org.busystack.caldav.calendar.DurationSyntax.validatePeriodDurations(value);
    if (DATE_PROPERTIES.contains(currentProperty)) validateDateShape(value);
    if ((!Property.RELATED_TO.equals(currentProperty)
            || !"URI".equalsIgnoreCase(currentParameters.get(Parameter.VALUE)))
        && Set.of(
                Property.SUMMARY,
                Property.DESCRIPTION,
                Property.COMMENT,
                Property.LOCATION,
                Property.CONTACT,
                Property.UID,
                Property.PRODID,
                Property.TZID,
                Property.TZNAME,
                Property.RELATED_TO)
            .contains(currentProperty))
      org.busystack.caldav.calendar.CalendarTextValues.scalar(value);
    // A declared primitive type is meaningful even on an otherwise opaque extension property.
    switch (declared) {
      case "BOOLEAN" -> {
        if (!value.equalsIgnoreCase("TRUE") && !value.equalsIgnoreCase("FALSE"))
          throw new IllegalArgumentException("Invalid BOOLEAN value");
      }
      case "INTEGER" -> {
        for (String item : value.split(",", -1)) {
          if (!item.matches("[+-]?[0-9]+")) throw new IllegalArgumentException("Invalid INTEGER");
          Integer.parseInt(item);
        }
      }
      case "FLOAT" -> {
        for (String item : value.split(",", -1))
          if (!item.matches("[+-]?[0-9]+(?:\\.[0-9]+)?"))
            throw new IllegalArgumentException("Invalid FLOAT value");
      }
      default -> {
        /* Unknown extension types remain opaque. */
      }
    }
    if (Property.ATTACH.equals(currentProperty)) {
      boolean binary = "BINARY".equalsIgnoreCase(currentParameters.get(Parameter.VALUE));
      boolean encoded = "BASE64".equalsIgnoreCase(currentParameters.get(Parameter.ENCODING));
      if (binary != encoded)
        throw new IllegalArgumentException("Invalid attachment representation");
      if (binary) {
        if (value.length() % 4 != 0) throw new IllegalArgumentException("Invalid binary length");
        Base64.getDecoder().decode(value);
      }
    }
    super.propertyValue(value);
  }

  private void validateDateShape(String value) {
    String type = currentParameters.getOrDefault(Parameter.VALUE, "DATE-TIME");
    if (currentProperty.equals(Property.RDATE) && type.equalsIgnoreCase("PERIOD")) return;
    boolean date = type.equalsIgnoreCase("DATE");
    if ((!date && !type.equalsIgnoreCase("DATE-TIME"))
        || date && UTC_PROPERTIES.contains(currentProperty))
      throw new IllegalArgumentException("Invalid temporal value type");
    if ((date || value.toUpperCase(Locale.ROOT).endsWith("Z"))
        && currentParameters.containsKey(Parameter.TZID))
      throw new IllegalArgumentException("TZID is not permitted for DATE or UTC values");
    String[] values =
        (currentProperty.equals(Property.RDATE) || currentProperty.equals(Property.EXDATE))
            ? value.split(",", -1)
            : new String[] {value};
    for (String item : values) {
      if (!item.matches(date ? "[0-9]{8}" : "(?i)[0-9]{8}T[0-9]{6}Z?"))
        throw new IllegalArgumentException("Temporal value does not match its declared type");
      if (UTC_PROPERTIES.contains(currentProperty) && !item.toUpperCase(Locale.ROOT).endsWith("Z"))
        throw new IllegalArgumentException("UTC timestamp required");
    }
  }

  @Override
  public void startComponent(String name) {
    token(name);
    super.startComponent(name.toUpperCase(Locale.ROOT));
  }

  @Override
  public void endComponent(String name) {
    super.endComponent(name.toUpperCase(Locale.ROOT));
  }

  @Override
  public void parameter(String name, String value) {
    parameterValues(name, List.of(value));
  }

  @Override
  public void parameterValues(String supplied, List<String> rawValues) {
    token(supplied);
    String name = supplied.toUpperCase(Locale.ROOT);
    if (propertyBuilder == null) return;
    if (SINGLE.contains(name) && currentParameters.containsKey(name))
      throw new QueryException(QueryException.Kind.INVALID_DATA, "Duplicate calendar parameter");
    currentParameters.put(name, String.join(",", rawValues));
    if (name.equals(Parameter.TZID)
        && getComponentBuilder() != null
        && (getComponentBuilder().hasName(net.fortuna.ical4j.model.component.Observance.STANDARD)
            || getComponentBuilder()
                .hasName(net.fortuna.ical4j.model.component.Observance.DAYLIGHT)))
      throw new QueryException(
          QueryException.Kind.INVALID_DATA,
          "TZID is not permitted on timezone observance properties");
    var values = rawValues.stream().map(Rfc6868::value).toList();
    Parameter parameter;
    if (name.equals(Parameter.MEMBER)
        || name.equals(Parameter.DELEGATED_FROM)
        || name.equals(Parameter.DELEGATED_TO)) {
      List<URI> addresses = new ArrayList<>();
      try {
        for (String value : values) addresses.add(new URI(value));
      } catch (java.net.URISyntaxException ex) {
        throw new QueryException(
            QueryException.Kind.INVALID_DATA, "Invalid calendar address parameter");
      }
      parameter =
          name.equals(Parameter.MEMBER)
              ? new Member(addresses)
              : name.equals(Parameter.DELEGATED_FROM)
                  ? new DelegatedFrom(addresses)
                  : new DelegatedTo(addresses);
    } else {
      if (rawValues.size() > 1 && SINGLE.contains(name))
        throw new QueryException(
            QueryException.Kind.INVALID_DATA, "Calendar parameter is not multi-valued");
      var factory =
          new DefaultParameterFactorySupplier()
              .get().stream().filter(f -> f.supports(name)).findFirst();
      if (factory.isEmpty())
        parameter =
            values.size() > 1
                ? new StructuredParameter(name, values)
                : new XParameter(name, '"' + values.getFirst() + '"');
      else {
        String value = String.join(",", values);
        parameter = factory.get().createParameter(value);
        // These model constructors strip one outer quote pair; retain literal encoded quotes.
        if (parameter instanceof Encodable && value.startsWith("\"") && value.endsWith("\""))
          parameter = factory.get().createParameter('"' + value + '"');
      }
    }
    propertyBuilder.parameter(parameter);
  }

  private static void token(String name) {
    if (name.isEmpty() || name.length() > 200)
      throw new QueryException(QueryException.Kind.INVALID_DATA, "Invalid calendar parameter name");
    for (int index = 0; index < name.length(); index++) {
      char c = name.charAt(index);
      if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-'))
        throw new QueryException(
            QueryException.Kind.INVALID_DATA, "Invalid calendar parameter name");
    }
  }
}
