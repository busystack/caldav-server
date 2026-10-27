package org.busystack.caldav.calendar.parser;

import java.util.List;
import net.fortuna.ical4j.data.ContentHandler;

/** Optional typed callback from the minimally patched iCal4j parameter-list loop. */
public interface ParameterListHandler extends ContentHandler {
  void parameterValues(String name, List<String> rawValues);
}
