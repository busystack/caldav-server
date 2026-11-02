package org.busystack.caldav.security.oauth;

/** Adapters must map every reason to the same OAuth invalid_grant response. */
public final class RefreshRejectedException extends RuntimeException {
  public enum Reason {
    INVALID,
    REVOKED,
    EXPIRED,
    DISABLED,
    REPLAY
  }

  private final Reason reason;

  public RefreshRejectedException(Reason reason) {
    super("Refresh grant rejected");
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
