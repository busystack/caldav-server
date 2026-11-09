package org.busystack.caldav.calendar.parser;

import static net.fortuna.ical4j.model.Property.*;
import static net.fortuna.ical4j.validate.ValidationRule.ValidationType.One;

import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.component.*;
import net.fortuna.ical4j.validate.*;

/** Calendar-data observances permit repeated TZNAMEs (RFC 5545 §3.6.5). */
final class CalendarTimezoneFactory extends VTimeZone.Factory {
  // Retain upstream mandatory fields and validation of every original property.
  // Its iTIP-specific singleton TZNAME restriction does not apply to calendar data.
  private static final ComponentValidator<Observance> OBSERVANCE =
      new ComponentValidator<>(
          Component.VTIMEZONE, new ValidationRule<>(One, DTSTART, TZOFFSETFROM, TZOFFSETTO));

  @Override
  public VTimeZone createComponent() {
    return new Timezone(new PropertyList(), new ComponentList<>());
  }

  @Override
  public VTimeZone createComponent(PropertyList properties) {
    return new Timezone(properties, new ComponentList<>());
  }

  @Override
  public VTimeZone createComponent(PropertyList properties, ComponentList<?> children) {
    return new Timezone(properties, children);
  }

  private static final class Timezone extends VTimeZone {
    Timezone(PropertyList properties, ComponentList<?> children) {
      super(properties);
      java.util.List<Observance> observances = new java.util.ArrayList<>();
      for (Component child : children.getAll()) {
        if (!(child instanceof Observance observance))
          throw new IllegalArgumentException("Invalid timezone observance");
        observances.add(observance);
      }
      setComponentList(new ComponentList<>(observances));
    }

    @Override
    public ValidationResult validate(boolean recurse) {
      var result = ComponentValidator.VTIMEZONE.validate(this);
      if (getObservances().isEmpty())
        result
            .getEntries()
            .add(
                new ValidationEntry(
                    "Timezone requires an observance",
                    ValidationEntry.Severity.ERROR,
                    Component.VTIMEZONE));
      for (var observance : getObservances())
        result = result.merge(OBSERVANCE.validate(observance));
      return recurse ? result.merge(validateProperties()) : result;
    }

    @Override
    protected ComponentFactory<VTimeZone> newFactory() {
      return new CalendarTimezoneFactory();
    }
  }
}
