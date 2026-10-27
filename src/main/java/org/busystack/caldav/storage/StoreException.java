package org.busystack.caldav.storage;

/** Service failure; mapped to protocol-specific errors by the protocol adapter. */
public class StoreException extends RuntimeException {
  public enum Kind {
    NOT_FOUND,
    FORBIDDEN,
    PRECONDITION,
    UID_CONFLICT,
    INVALID_TOKEN,
    LIMIT,
    CONFLICT,
    EXISTS,
    INVALID_PROPERTY
  }

  private final Kind kind;

  public StoreException(Kind kind) {
    super(kind.name());
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
