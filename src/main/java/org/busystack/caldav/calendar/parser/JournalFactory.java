package org.busystack.caldav.calendar.parser;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentFactory;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VJournal;
import net.fortuna.ical4j.model.property.Status;
import net.fortuna.ical4j.validate.ValidationResult;

/** Validates journal extensions and attaches their children through the official VJournal API. */
final class JournalFactory extends VJournal.Factory {
  @Override
  public VJournal createComponent() {
    return createComponent(new PropertyList());
  }

  @Override
  public VJournal createComponent(PropertyList properties) {
    return createComponent(properties, new ComponentList<>());
  }

  @Override
  public VJournal createComponent(PropertyList properties, ComponentList<?> children) {
    return new Journal(properties, children);
  }

  private static final class Journal extends VJournal {
    Journal(PropertyList properties, ComponentList<?> children) {
      super(properties);
      setComponentList(new ComponentList<Component>(children.getAll()));
    }

    @Override
    public ValidationResult validate(boolean recurse) {
      var status = getProperty(Property.STATUS);
      if (status.isEmpty() || status.get().getParameters().isEmpty()) {
        return super.validate(recurse);
      }
      // The pinned VJournal validator compares the whole property to an
      // immutable status constant. RFC 5545 §3.8.1.11 permits extension
      // parameters, and RFC 9073 §5.3 permits DERIVED. Check applicability on
      // a value-only view and retain the original property in the parsed model.
      var properties =
          getProperties().stream()
              .map(
                  property ->
                      property.getName().equals(Property.STATUS)
                          ? new Status.Factory()
                              .createProperty(new ParameterList(), property.getValue())
                          : property)
              .toList();
      var applicability = new VJournal(new PropertyList(properties));
      applicability.setComponentList(new ComponentList<Component>(getComponents()));
      return applicability.validate(recurse).merge(status.get().validate());
    }

    @Override
    protected ComponentFactory<VJournal> newFactory() {
      return new JournalFactory();
    }
  }
}
