package org.busystack.caldav.calendar.parser;

import java.util.List;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.ParameterCodec;

/**
 * An unknown multi-valued parameter keeps its value boundaries when report copies are serialized.
 */
public final class StructuredParameter extends Parameter {
  private final List<String> values;

  public StructuredParameter(String name, List<String> values) {
    super(name);
    this.values = List.copyOf(values);
  }

  public List<String> values() {
    return values;
  }

  @Override
  public String getValue() {
    return values.stream()
        .map(
            value -> {
              try {
                return ParameterCodec.INSTANCE.encode(value);
              } catch (org.apache.commons.codec.EncoderException ex) {
                throw new IllegalStateException("Parameter encoding failed", ex);
              }
            })
        .collect(java.util.stream.Collectors.joining(","));
  }
}
