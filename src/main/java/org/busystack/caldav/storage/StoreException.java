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

  public enum RequiredAccess {
    READ,
    WRITE,
    OWNER
  }

  private final Kind kind;
  private final RequiredAccess requiredAccess;
  private final java.util.UUID collection;

  public StoreException(Kind kind) {
    this(kind, null, null);
  }

  public StoreException(Kind kind, RequiredAccess requiredAccess, java.util.UUID collection) {
    super(kind.name());
    if (requiredAccess != null && (kind != Kind.FORBIDDEN || collection == null))
      throw new IllegalArgumentException("Access failures require a collection");
    this.kind = kind;
    this.requiredAccess = requiredAccess;
    this.collection = collection;
  }

  public Kind kind() {
    return kind;
  }

  public RequiredAccess requiredAccess() {
    return requiredAccess;
  }

  public java.util.UUID collection() {
    return collection;
  }
}
