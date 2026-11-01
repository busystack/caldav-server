package org.busystack.caldav.calendar.parser;

import java.util.List;
import net.fortuna.ical4j.data.ContentHandler;

/** Application callback retaining parameter-list boundaries around the official parser. */
public interface ParameterListHandler extends ContentHandler {
  void parameterValues(String name, List<String> rawValues);
}
