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
  private final Deque<String> components = new ArrayDeque<>();
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
          "DERIVED",
          "ENCODING",
          "FMTTYPE",
          "FBTYPE",
          "LANGUAGE",
          "ORDER",
          "PARTSTAT",
          "RANGE",
          "RELATED",
          "RELTYPE",
          "ROLE",
          "RSVP",
          "SCHEMA",
          "SENT-BY",
          "TZID",
          "VALUE");

  public StrictContentHandler(
      Consumer<net.fortuna.ical4j.model.Calendar> consumer, TimeZoneRegistry registry) {
    super(consumer, registry, context());
  }

  private static ContentHandlerContext context() {
    return new ContentHandlerContext()
        .withComponentFactorySupplier(
            () -> {
              List<ComponentFactory<?>> factories =
                  new ArrayList<>(new DefaultComponentFactorySupplier().get());
              factories.removeIf(factory -> factory.supports(Component.VTODO));
              factories.addFirst(new TaskFactory());
              factories.removeIf(factory -> factory.supports(Component.VJOURNAL));
              factories.addFirst(new JournalFactory());
              factories.addFirst(new net.fortuna.ical4j.model.component.VStatus.Factory());
              factories.removeIf(factory -> factory.supports(Component.VTIMEZONE));
              factories.addFirst(new CalendarTimezoneFactory());
              return factories;
            })
        .withPropertyFactorySupplier(
            () -> {
              List<PropertyFactory<?>> factories =
                  new ArrayList<>(new DefaultPropertyFactorySupplier().get());
              factories.removeIf(factory -> factory.supports(Property.CLASS));
              factories.removeIf(factory -> factory.supports(Property.STATUS));
              factories.removeIf(factory -> factory.supports(Property.PARTICIPANT_TYPE));
              factories.removeIf(factory -> factory.supports(Property.ATTACH));
              factories.addFirst(new AttachmentFactory());
              factories.removeIf(factory -> factory.supports("STRUCTURED-DATA"));
              factories.addFirst(new StructuredDataFactory());
              factories.addFirst(new ClassificationFactory());
              factories.addFirst(new TaskFactory.StatusFactory());
              factories.addFirst(new TaskFactory.EstimatedDurationFactory());
              factories.addFirst(new net.fortuna.ical4j.model.property.TaskMode.Factory());
              factories.addFirst(new net.fortuna.ical4j.model.property.Reason.Factory());
              factories.addFirst(new TaskFactory.SubstateFactory());
              factories.addFirst(new ParticipantTypeFactory());
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
    // RFC 5545 §§3.3,3.8.1.1 define only URI and BINARY for ATTACH.
    if (Property.ATTACH.equals(currentProperty)
        && !declared.isEmpty()
        && !Set.of("URI", "BINARY").contains(declared))
      throw new IllegalArgumentException("Invalid attachment value type");
    if ("STRUCTURED-DATA".equals(currentProperty)) {
      // RFC 9073 §6.6 distinguishes linked data from inline text and binary.
      if (!Set.of("URI", "TEXT", "BINARY").contains(declared))
        throw new IllegalArgumentException("Structured data requires a supported VALUE");
      if (!declared.equals("URI")
          && (!currentParameters.containsKey("SCHEMA")
              || !currentParameters.containsKey("FMTTYPE")))
        throw new IllegalArgumentException("Inline structured data requires SCHEMA and FMTTYPE");
      boolean binary = declared.equals("BINARY");
      if (binary != "BASE64".equalsIgnoreCase(currentParameters.get("ENCODING")))
        throw new IllegalArgumentException("Invalid structured data encoding");
      if (binary) {
        if (value.length() % 4 != 0) throw new IllegalArgumentException("Invalid binary length");
        Base64.getDecoder().decode(value);
      } else if (declared.equals("TEXT")) {
        org.busystack.caldav.calendar.CalendarTextValues.scalar(value);
      }
    }
    if ("STYLED-DESCRIPTION".equals(currentProperty)) {
      if (!Set.of("URI", "TEXT").contains(declared))
        throw new IllegalArgumentException("Styled descriptions require VALUE=TEXT or URI");
      if (declared.equals("TEXT")) org.busystack.caldav.calendar.CalendarTextValues.scalar(value);
    }
    if (declared.equals("TIME")) {
      // §3.3.12 also applies to explicitly typed extension data. Preserve
      // floating/zoned spelling; a time-only value has no date to derive.
      for (String time : value.split(",", -1)) {
        if (!time.matches("(?i)[0-9]{6}Z?")
            || Integer.parseInt(time.substring(0, 2)) > 23
            || Integer.parseInt(time.substring(2, 4)) > 59
            || Integer.parseInt(time.substring(4, 6)) > 60
            || time.toUpperCase(Locale.ROOT).endsWith("Z")
                && currentParameters.containsKey(Parameter.TZID))
          throw new IllegalArgumentException("Invalid TIME value");
      }
    }
    // RFC 5545 §3.3.14 forbids negative zero; parsing to ZoneOffset loses its sign.
    if ((Set.of(Property.TZOFFSETFROM, Property.TZOFFSETTO).contains(currentProperty)
            || declared.equals("UTC-OFFSET"))
        && Set.of("-0000", "-000000").contains(value))
      throw new org.busystack.caldav.calendar.CalendarValidationException(
          org.busystack.caldav.calendar.CalendarValidationException.Reason.INVALID_DATA);
    if (declared.equals("URI")
        || declared.equals("CAL-ADDRESS")
        || Set.of(
                Property.ATTENDEE,
                Property.ORGANIZER,
                Property.URL,
                Property.TZURL,
                "CALENDAR-ADDRESS",
                "REASON")
            .contains(currentProperty)
        || Property.ATTACH.equals(currentProperty) && !declared.equals("BINARY")) uri(value);
    if (Property.REQUEST_STATUS.equals(currentProperty))
      org.busystack.caldav.calendar.CalendarTextValues.requestStatus(value);
    if (Property.RRULE.equals(currentProperty)
        || Property.EXRULE.equals(currentProperty)
        || declared.equals("RECUR"))
      org.busystack.caldav.calendar.RecurrenceRuleSyntax.validate(value);
    if (Set.of(Property.DURATION, "REFRESH-INTERVAL", "ESTIMATED-DURATION")
            .contains(currentProperty)
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
    String component = name.toUpperCase(Locale.ROOT);
    if (components.contains(Component.VJOURNAL)) {
      boolean permitted =
          switch (components.getFirst()) {
            case Component.VJOURNAL ->
                Set.of("PARTICIPANT", "VLOCATION", "VRESOURCE").contains(component);
            case "PARTICIPANT" -> Set.of("VLOCATION", "VRESOURCE").contains(component);
            default -> false;
          };
      if (!permitted) throw new IllegalArgumentException("Invalid journal child component");
    }
    components.push(component);
    super.startComponent(component);
  }

  @Override
  public void endComponent(String name) {
    super.endComponent(name.toUpperCase(Locale.ROOT));
    components.pop();
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
    if (Set.of(
                Parameter.CUTYPE,
                Parameter.ROLE,
                Parameter.PARTSTAT,
                Parameter.RELTYPE,
                Parameter.VALUE)
            .contains(name)
        && values.stream().anyMatch(value -> !value.matches("[A-Za-z0-9-]+")))
      throw new QueryException(
          QueryException.Kind.INVALID_DATA, "Invalid calendar parameter token");
    if (name.equals("SCHEMA")) values.forEach(StrictContentHandler::uri);
    if (name.equals("ORDER") && values.stream().anyMatch(value -> Integer.parseInt(value) < 1))
      throw new IllegalArgumentException("Invalid ORDER value");
    if (name.equals("DERIVED")
        && values.stream()
            .anyMatch(value -> !Set.of("TRUE", "FALSE").contains(value.toUpperCase(Locale.ROOT))))
      throw new IllegalArgumentException("Invalid DERIVED value");
    if (name.equals(Parameter.RANGE)
        && values.stream().anyMatch(value -> !value.equalsIgnoreCase("THISANDFUTURE")))
      throw new QueryException(QueryException.Kind.INVALID_DATA, "Invalid recurrence range");
    if (Set.of(
            Parameter.ALTREP,
            Parameter.DIR,
            Parameter.SENT_BY,
            Parameter.MEMBER,
            Parameter.DELEGATED_FROM,
            Parameter.DELEGATED_TO)
        .contains(name)) values.forEach(StrictContentHandler::uri);
    if (name.equals(Parameter.RSVP)
        && values.stream()
            .anyMatch(v -> !v.equalsIgnoreCase("TRUE") && !v.equalsIgnoreCase("FALSE")))
      throw new QueryException(QueryException.Kind.INVALID_DATA, "Invalid RSVP boolean");
    if (name.equals(Parameter.LANGUAGE)) {
      try {
        for (String value : values) {
          if (value.isEmpty()) throw new IllformedLocaleException("Empty language tag");
          // Validate BCP47 syntax through the supported JDK API. Do not replace
          // the original spelling, grandfathered form or property parameters.
          new Locale.Builder().setLanguageTag(value);
        }
      } catch (IllformedLocaleException error) {
        throw new QueryException(QueryException.Kind.INVALID_DATA, "Invalid LANGUAGE tag");
      }
    }
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

  private static void uri(String value) {
    try {
      if (new URI(value).isAbsolute()) return;
    } catch (java.net.URISyntaxException invalid) {
      // Report only a fixed diagnostic; submitted values may contain private data.
    }
    throw new org.busystack.caldav.calendar.CalendarValidationException(
        org.busystack.caldav.calendar.CalendarValidationException.Reason.INVALID_DATA);
  }
}
