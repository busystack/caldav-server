package org.busystack.caldav.storage;

import java.util.Set;

/** HTTP ETag conditions after protocol parsing; weak validators never satisfy If-Match. */
public record WriteCondition(
    boolean createOnly, boolean mustExist, Set<String> matches, Set<String> excluded) {
  public WriteCondition {
    matches = Set.copyOf(matches);
    excluded = Set.copyOf(excluded);
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
    if ((createOnly && exists)
        || (mustExist && !exists)
        || (!matches.isEmpty()
            && (existing == null
                || matches.stream()
                    .noneMatch(tag -> !tag.startsWith("W/") && tag.equals(existing))))
        || (existing != null
            && excluded.stream().anyMatch(tag -> weak(tag).equals(weak(existing))))) {
      throw new StoreException(StoreException.Kind.PRECONDITION);
    }
  }

  private static String weak(String tag) {
    return tag.startsWith("W/") ? tag.substring(2) : tag;
  }
}
