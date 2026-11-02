package org.busystack.caldav.calendar.query;

import java.time.Instant;

/** UTC range; one absent endpoint denotes the corresponding infinity. */
public record TimeRange(Instant start, Instant end) {
  public TimeRange {
    if (start == null && end == null || start != null && end != null && !start.isBefore(end))
      throw QueryException.invalid("A time range requires an ordered start or end");
  }

  public boolean startsBefore(Instant value) {
    return start == null || start.isBefore(value);
  }

  public boolean startsAtOrBefore(Instant value) {
    return start == null || !start.isAfter(value);
  }

  public boolean endsAfter(Instant value) {
    return end == null || end.isAfter(value);
  }

  public boolean endsAtOrAfter(Instant value) {
    return end == null || !end.isBefore(value);
  }

  public boolean contains(Instant point) {
    return startsAtOrBefore(point) && endsAfter(point);
  }

  public boolean overlaps(Instant first, Instant last) {
    return first.equals(last) ? contains(first) : startsBefore(last) && endsAfter(first);
  }
}
