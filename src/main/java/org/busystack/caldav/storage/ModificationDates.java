package org.busystack.caldav.storage;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** HTTP dates have whole-second precision and cannot be later than response origination. */
public final class ModificationDates {
  private ModificationDates() {}

  public static Instant effective(Instant modifiedAt, Instant origin) {
    if (modifiedAt == null) return null;
    return (modifiedAt.isAfter(origin) ? origin : modifiedAt).truncatedTo(ChronoUnit.SECONDS);
  }
}
