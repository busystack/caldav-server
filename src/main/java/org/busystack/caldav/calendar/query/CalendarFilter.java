package org.busystack.caldav.calendar.query;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Typed RFC 4791 section 9.7 filter. XML parsing belongs to the protocol module. */
public final class CalendarFilter {
  private CalendarFilter() {}

  public record ComponentFilter(
      String name,
      boolean notDefined,
      TimeRange timeRange,
      List<PropertyFilter> properties,
      List<ComponentFilter> components) {
    public ComponentFilter {
      name = CalendarFilter.name(name);
      properties = List.copyOf(properties == null ? List.of() : properties);
      components = List.copyOf(components == null ? List.of() : components);
      if (notDefined && (timeRange != null || !properties.isEmpty() || !components.isEmpty()))
        throw QueryException.invalid("is-not-defined must be the only component condition");
    }
  }

  public record PropertyFilter(
      String name,
      boolean notDefined,
      TimeRange timeRange,
      TextMatch textMatch,
      List<ParameterFilter> parameters) {
    public PropertyFilter {
      name = CalendarFilter.name(name);
      parameters = List.copyOf(parameters == null ? List.of() : parameters);
      if (timeRange != null && textMatch != null
          || notDefined && (timeRange != null || textMatch != null || !parameters.isEmpty()))
        throw QueryException.invalid("Conflicting property filter conditions");
    }
  }

  public record ParameterFilter(String name, boolean notDefined, TextMatch textMatch) {
    public ParameterFilter {
      name = CalendarFilter.name(name);
      if (notDefined && textMatch != null)
        throw QueryException.invalid("Conflicting parameter filter conditions");
    }
  }

  public record TextMatch(String text, String collation, boolean negate) {
    public TextMatch {
      Objects.requireNonNull(text, "text");
      collation = collation == null || collation.equals("default") ? "i;ascii-casemap" : collation;
      if (!collation.equals("i;ascii-casemap") && !collation.equals("i;octet"))
        throw new QueryException(
            QueryException.Kind.UNSUPPORTED_COLLATION, "Unsupported text collation");
    }

    public boolean matches(String value) {
      boolean found =
          collation.equals("i;octet") ? value.contains(text) : ascii(value).contains(ascii(text));
      return negate != found;
    }
  }

  private static String name(String value) {
    if (value == null || value.isEmpty() || value.length() > 200)
      throw QueryException.invalid("Invalid filter name");
    for (int index = 0; index < value.length(); index++) {
      char c = value.charAt(index);
      if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-'))
        throw QueryException.invalid("Invalid filter name");
    }
    return value.toUpperCase(Locale.ROOT);
  }

  private static String ascii(String value) {
    var result = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      result.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
    }
    return result.toString();
  }
}
