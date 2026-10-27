package org.busystack.caldav.storage;

import java.time.Instant;
import java.util.Set;

/** HTTP conditions after protocol parsing; evaluation occurs under the mutation lock. */
public record WriteCondition(
    boolean createOnly,
    boolean mustExist,
    Set<String> matches,
    Set<String> excluded,
    Instant unmodifiedSince) {
  public WriteCondition {
    matches = Set.copyOf(matches);
    excluded = Set.copyOf(excluded);
    if (unmodifiedSince != null)
      unmodifiedSince = unmodifiedSince.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  }

  public WriteCondition(
      boolean createOnly, boolean mustExist, Set<String> matches, Set<String> excluded) {
    this(createOnly, mustExist, matches, excluded, null);
  }

  public WriteCondition(boolean createOnly, boolean mustExist, Set<String> matches) {
    this(createOnly, mustExist, matches, Set.of());
  }

  public static WriteCondition none() {
    return new WriteCondition(false, false, Set.of());
  }

  public static WriteCondition create() {
    return new WriteCondition(true, false, Set.of());
  }

  public static WriteCondition match(String etag) {
    return new WriteCondition(false, true, Set.of(etag));
  }

  public void check(String existing) {
    check(existing, existing != null);
  }

  public void check(String existing, boolean exists) {
    check(existing, exists, null);
  }

  public void check(String existing, boolean exists, Instant modifiedAt) {
    // Evaluate after the caller's locks. Capturing the clock before a lock wait could
    // clamp a newly committed mutation to the earlier request's starting time.
    Instant effective = ModificationDates.effective(modifiedAt, Instant.now());
    if ((createOnly && exists)
        || (mustExist && !exists)
        || (!matches.isEmpty()
            && (existing == null
                || matches.stream()
                    .noneMatch(tag -> !tag.startsWith("W/") && tag.equals(existing))))
        || (exists
            && unmodifiedSince != null
            && effective != null
            && effective.isAfter(unmodifiedSince))
        || (existing != null
            && excluded.stream().anyMatch(tag -> weak(tag).equals(weak(existing))))) {
      throw new StoreException(StoreException.Kind.PRECONDITION);
    }
  }

  private static String weak(String tag) {
    return tag.startsWith("W/") ? tag.substring(2) : tag;
  }
}
