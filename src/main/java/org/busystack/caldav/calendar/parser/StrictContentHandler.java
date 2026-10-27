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
    super(consumer, registry);
  }

  @Override
  public void startProperty(String name) {
    token(name);
    super.startProperty(name.toUpperCase(Locale.ROOT));
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
        if (parameter instanceof Encodable)
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
