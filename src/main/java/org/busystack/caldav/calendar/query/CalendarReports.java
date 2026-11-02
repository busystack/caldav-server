package org.busystack.caldav.calendar.query;

import java.util.*;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.validate.*;
import org.busystack.caldav.calendar.CalendarContent;

/**
 * Creates report models without losing typed-value spellings such as exact versus nominal duration.
 */
public final class CalendarReports {
  private CalendarReports() {}

  public static net.fortuna.ical4j.model.Calendar copy(CalendarContent.Parsed parsed) {
    var copy = parsed.calendar().copy();
    restore(parsed, parsed.calendar(), copy);
    var originals = parsed.calendar().getComponents();
    for (int index = 0; index < originals.size(); index++)
      restoreTree(parsed, originals.get(index), copy.getComponents().get(index));
    return copy;
  }

  public static Component componentCopy(CalendarContent.Parsed parsed, Component original) {
    var copy = original.copy();
    restoreTree(parsed, original, copy);
    return copy;
  }

  private static void restoreTree(
      CalendarContent.Parsed parsed, Component original, Component copy) {
    restore(parsed, original, copy);
    if (original instanceof ComponentContainer<?> a && copy instanceof ComponentContainer<?> b)
      for (int index = 0; index < a.getComponents().size(); index++)
        restoreTree(parsed, a.getComponents().get(index), b.getComponents().get(index));
  }

  private static void restore(
      CalendarContent.Parsed parsed, PropertyContainer original, PropertyContainer copy) {
    var values = new ArrayList<Property>(copy.getProperties());
    List<Property> properties = original.getProperties();
    if (values.size() != properties.size())
      throw new IllegalStateException("Report property alignment failed");
    for (int index = 0; index < properties.size(); index++) {
      Property property = properties.get(index);
      String lexical = parsed.lexicalValues().get(property);
      if (lexical != null)
        values.set(
            index,
            new PreservedValue(
                property,
                lexical,
                new ParameterList(values.get(index).getParameterList().getAll()),
                lexical));
    }
    copy.setPropertyList(new PropertyList(values));
  }

  private static final class PreservedValue extends Property {
    private final Property original;
    private final String accepted;
    private String value;

    PreservedValue(Property original, String accepted, ParameterList parameters, String value) {
      super(original.getName(), parameters);
      this.original = original;
      this.accepted = accepted;
      this.value = value;
    }

    @Override
    public String getValue() {
      return value;
    }

    @Override
    public void setValue(String value) {
      if (!value.isEmpty() && !value.equals(accepted))
        throw new IllegalArgumentException("Report value must be original or omitted");
      this.value = value;
    }

    @Override
    public ValidationResult validate() {
      if (value.equals(accepted)) return original.validate();
      var result = new ValidationResult();
      result
          .getEntries()
          .add(
              new ValidationEntry(
                  "A projected omitted value is not a complete storage property",
                  ValidationEntry.Severity.ERROR,
                  getName()));
      return result;
    }

    @Override
    protected PropertyFactory<?> newFactory() {
      return new PropertyFactory<PreservedValue>() {
        @Override
        public PreservedValue createProperty() {
          return createProperty(new ParameterList(), "");
        }

        @Override
        public PreservedValue createProperty(ParameterList parameters, String value) {
          return new PreservedValue(original, accepted, parameters, value);
        }

        @Override
        public boolean supports(String name) {
          return original.getName().equalsIgnoreCase(name);
        }
      };
    }
  }
}
