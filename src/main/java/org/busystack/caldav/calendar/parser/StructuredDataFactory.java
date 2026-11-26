package org.busystack.caldav.calendar.parser;

import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.PropertyFactory;
import net.fortuna.ical4j.model.property.StructuredData;
import net.fortuna.ical4j.validate.ValidationResult;

/** Uses the official value model with admission grammar checked by StrictContentHandler. */
final class StructuredDataFactory extends StructuredData.Factory {
  @Override
  public StructuredData createProperty(ParameterList parameters, String value) {
    return new CalendarStructuredData(parameters, value);
  }

  private static final class CalendarStructuredData extends StructuredData {
    CalendarStructuredData(ParameterList parameters, String value) {
      super(parameters, value);
    }

    @Override
    public ValidationResult validate() {
      // RFC 9073 §6.6 permits opaque references to arbitrary schemas and URI
      // data without a SCHEMA. Storage does not fetch or interpret schemas.
      return new ValidationResult();
    }

    @Override
    protected PropertyFactory<StructuredData> newFactory() {
      return new StructuredDataFactory();
    }
  }
}
