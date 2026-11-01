package org.busystack.caldav.calendar.parser;

import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.PropertyFactory;
import net.fortuna.ical4j.model.property.Clazz;
import net.fortuna.ical4j.validate.ValidationResult;

/** Application property-factory extension for RFC 5545 §3.8.1.3's open token vocabulary. */
final class ClassificationFactory extends Clazz.Factory {
  @Override
  public Clazz createProperty(ParameterList parameters, String value) {
    return new Classification(parameters, value);
  }

  private static final class Classification extends Clazz {
    Classification(ParameterList parameters, String value) {
      super(parameters, value);
    }

    @Override
    public ValidationResult validate() {
      // The grammar includes iana-token and x-name, rather than only the three familiar values.
      if (getValue() == null || !getValue().matches("[A-Za-z0-9-]+"))
        throw new IllegalArgumentException("Invalid CLASS token");
      return ValidationResult.EMPTY;
    }

    @Override
    protected PropertyFactory<Clazz> newFactory() {
      return new ClassificationFactory();
    }
  }
}
