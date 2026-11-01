package org.busystack.caldav.security.passkey;

/** A rejected registration is a client ceremony error, not a request to restart browser login. */
final class PasskeyRegistrationRejectedException extends RuntimeException {
  PasskeyRegistrationRejectedException(String message) {
    super(message);
  }

  PasskeyRegistrationRejectedException(Throwable cause) {
    super("Invalid passkey registration", cause);
  }
}
