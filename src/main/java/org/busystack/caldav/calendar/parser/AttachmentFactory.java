package org.busystack.caldav.calendar.parser;

import java.net.URI;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.PropertyFactory;
import net.fortuna.ical4j.model.property.Attach;

/** Keep the default 8BIT URI representation out of the upstream binary decoder. */
final class AttachmentFactory extends Attach.Factory {
  @Override
  public Attach createProperty(ParameterList parameters, String value) {
    if (parameters
        .getParameter(Parameter.ENCODING)
        .map(parameter -> parameter.getValue().equalsIgnoreCase("8BIT"))
        .orElse(false)) return new UriAttachment(parameters, value);
    return super.createProperty(parameters, value);
  }

  private static final class UriAttachment extends Attach {
    UriAttachment(ParameterList parameters, String value) {
      super(parameters, URI.create(value));
    }

    @Override
    protected PropertyFactory<Attach> newFactory() {
      return new AttachmentFactory();
    }
  }
}
