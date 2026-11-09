package org.busystack.caldav.calendar.parser;

import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.PropertyFactory;
import net.fortuna.ical4j.model.property.ParticipantType;

/** The official parser needs a mutable Encodable property while decoding its value. */
final class ParticipantTypeFactory extends ParticipantType.Factory {
  @Override
  public ParticipantType createProperty(ParameterList parameters, String value) {
    // The upstream ACTIVE constant cannot accept PropertyBuilder's decoded value.
    // Its public mutable model retains the same validation and submitted parameters.
    return new MutableParticipantType(parameters, value);
  }

  private static final class MutableParticipantType extends ParticipantType {
    MutableParticipantType(ParameterList parameters, String value) {
      super(parameters, value);
    }

    @Override
    protected PropertyFactory<ParticipantType> newFactory() {
      // Report/model copies must use the same supported mutable constructor;
      // the default factory would return ACTIVE without its parameters.
      return new ParticipantTypeFactory();
    }
  }
}
