package org.busystack.caldav.calendar.recurrence;

import java.time.*;
import java.time.temporal.*;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.property.DateProperty;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.query.QueryException;

/** Calendar arithmetic around typed iCal4j values; never parses content-line syntax. */
public final class TemporalSupport {
  private TemporalSupport() {}

  public static Temporal date(Component component, String name) {
    var property = component.getProperty(name).orElse(null);
    return property instanceof DateProperty<?> date ? date.getDate() : null;
  }

  public static Instant instant(Temporal value, ZoneId floating) {
    if (value instanceof Instant i) return i;
    if (value instanceof OffsetDateTime o) return o.toInstant();
    if (value instanceof ZonedDateTime z) return z.toInstant();
    if (value instanceof LocalDateTime l) return l.atZone(floating).toInstant();
    if (value instanceof LocalDate l) return l.atStartOfDay(floating).toInstant();
    throw new QueryException(
        QueryException.Kind.INVALID_DATA, "Unsupported calendar date representation");
  }

  public static ZonedDateTime zoned(Temporal value, ZoneId floating) {
    if (value instanceof ZonedDateTime z) return z;
    if (value instanceof OffsetDateTime o) return o.toZonedDateTime();
    if (value instanceof Instant i) return i.atZone(ZoneOffset.UTC);
    if (value instanceof LocalDateTime l) return l.atZone(floating);
    if (value instanceof LocalDate l) return l.atStartOfDay(floating);
    throw new QueryException(
        QueryException.Kind.INVALID_DATA, "Unsupported calendar date representation");
  }

  public static Temporal nativeBound(Instant instant, Temporal seed, ZoneId floating) {
    if (seed instanceof LocalDate) return instant.atZone(floating).toLocalDate();
    if (seed instanceof LocalDateTime) return instant.atZone(floating).toLocalDateTime();
    if (seed instanceof ZonedDateTime z) return instant.atZone(z.getZone());
    if (seed instanceof OffsetDateTime o) return instant.atOffset(o.getOffset());
    return instant;
  }

  public record CalendarDuration(long days, Duration clock) {
    public Temporal addTo(Temporal value, ZoneId floating) {
      try {
        if (value instanceof LocalDate d && clock.isZero()) return d.plusDays(days);
        ZonedDateTime result = zoned(value, floating).plusDays(days).plus(clock);
        if (value instanceof LocalDateTime) return result.toLocalDateTime();
        if (value instanceof OffsetDateTime) return result.toOffsetDateTime();
        if (value instanceof Instant) return result.toInstant();
        return result;
      } catch (DateTimeException | ArithmeticException ex) {
        throw QueryException.limit();
      }
    }
  }

  @SuppressWarnings("deprecation")
  public static CalendarDuration duration(CalendarContent.Parsed parsed, Property property) {
    return duration(parsed.lexicalValue(property));
  }

  @SuppressWarnings("deprecation")
  public static CalendarDuration duration(String rawValue) {
    var value = new net.fortuna.ical4j.model.Dur(rawValue);
    long days = Math.addExact(Math.multiplyExact((long) value.getWeeks(), 7), value.getDays());
    Duration clock =
        Duration.ofHours(value.getHours())
            .plusMinutes(value.getMinutes())
            .plusSeconds(value.getSeconds());
    return new CalendarDuration(
        value.isNegative() ? -days : days, value.isNegative() ? clock.negated() : clock);
  }

  public static Temporal exactEnd(Temporal seed, Temporal end, Temporal occurrence, ZoneId zone) {
    if (seed instanceof LocalDate a
        && end instanceof LocalDate b
        && occurrence instanceof LocalDate c) return c.plusDays(ChronoUnit.DAYS.between(a, b));
    Duration elapsed = Duration.between(instant(seed, zone), instant(end, zone));
    ZonedDateTime result = zoned(occurrence, zone).plus(elapsed);
    if (occurrence instanceof LocalDateTime) return result.toLocalDateTime();
    if (occurrence instanceof OffsetDateTime) return result.toOffsetDateTime();
    if (occurrence instanceof Instant) return result.toInstant();
    return result;
  }
}
