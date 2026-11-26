package org.busystack.caldav.calendar.parser;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentFactory;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VJournal;

/** Attaches RFC 9073 child components through the official VJournal container API. */
final class JournalFactory extends VJournal.Factory {
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
    protected ComponentFactory<VJournal> newFactory() {
      return new JournalFactory();
    }
  }
}
