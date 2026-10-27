package org.busystack.caldav.calendar.query;

/** Semantic failure; the protocol adapter maps the kind to the corresponding DAV error. */
public final class QueryException extends RuntimeException {
  public enum Kind {
    INVALID_FILTER,
    UNSUPPORTED_FILTER,
    UNSUPPORTED_COLLATION,
    LIMIT_EXCEEDED,
    INVALID_DATA
  }

  private final Kind kind;

  public QueryException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public static QueryException limit() {
    return new QueryException(
        Kind.LIMIT_EXCEEDED, "Calendar query exceeds the supported work limit");
  }

  public static QueryException invalid(String message) {
    return new QueryException(Kind.INVALID_FILTER, message);
  }
}
