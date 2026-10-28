package org.busystack.caldav.calendar.query;

import java.time.ZoneId;
import java.util.Objects;
import net.fortuna.ical4j.model.component.VTimeZone;

/** Keeps a calendar-specific ical4j timezone registry alive for a report's whole evaluation. */
public final class FloatingTimezone {
  private final ZoneId zoneId;
  private final BoundedTimeZoneRegistry registry;

  private FloatingTimezone(ZoneId zoneId, BoundedTimeZoneRegistry registry) {
    this.zoneId = Objects.requireNonNull(zoneId);
    this.registry = registry;
  }

  public static FloatingTimezone of(ZoneId zone) {
    return new FloatingTimezone(zone, null);
  }

  public static FloatingTimezone from(VTimeZone component) {
    var registry = new BoundedTimeZoneRegistry();
    var timezone = new net.fortuna.ical4j.model.TimeZone(component);
    registry.register(timezone, false);
    return new FloatingTimezone(registry.getZoneId(timezone.getID()), registry);
  }

  public static FloatingTimezone parse(String content) {
    if (content == null || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
      throw QueryException.limit();
    org.busystack.caldav.calendar.CalendarTextProfile.validate(content);
    try {
      var registry = new BoundedTimeZoneRegistry();
      var result =
          new java.util.concurrent.atomic.AtomicReference<net.fortuna.ical4j.model.Calendar>();
      var handler =
          new org.busystack.caldav.calendar.parser.ParsingLimitsHandler(
              new org.busystack.caldav.calendar.parser.StrictContentHandler(result::set, registry));
      new org.busystack.caldav.calendar.parser.Ical4jCalendarParser()
          .parse(org.busystack.caldav.calendar.CalendarParsingInput.reader(content), handler);
      var calendar = result.get();
      if (calendar.getComponents().size() != 1
          || !(calendar.getComponents().getFirst() instanceof VTimeZone zone)
          || calendar.validate().hasErrors())
        throw QueryException.invalid("A calendar timezone must contain exactly one VTIMEZONE");
      var timezone = new net.fortuna.ical4j.model.TimeZone(zone);
      return new FloatingTimezone(registry.getZoneId(timezone.getID()), registry);
    } catch (java.io.IOException | net.fortuna.ical4j.data.ParserException ex) {
      Throwable nested = ex;
      for (int index = 0; index < 8 && nested != null; index++, nested = nested.getCause())
        if (nested instanceof QueryException query) throw query;
      throw QueryException.invalid("Malformed calendar timezone");
    }
  }

  public java.time.Instant coverageEnd() {
    return registry == null ? null : registry.coverage().get(zoneId.getId());
  }

  public ZoneId zoneId() {
    java.lang.ref.Reference.reachabilityFence(registry);
    return zoneId;
  }
}
