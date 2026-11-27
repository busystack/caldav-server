package org.busystack.caldav.security.passkey;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.webauthn.api.Bytes;

/** Bridges a single framework assertion to its success handler on the same request. */
final class AcceptedAssertion implements AutoCloseable {
  static final String KEY = AcceptedAssertion.class.getName();
  private static final ThreadLocal<HttpServletRequest> REQUEST = new ThreadLocal<>();

  AcceptedAssertion(HttpServletRequest request) {
    REQUEST.set(request);
  }

  static void record(Bytes credential) {
    var request = REQUEST.get();
    if (request != null) request.setAttribute(KEY, credential.toBase64UrlString());
  }

  @Override
  public void close() {
    REQUEST.remove();
  }
}
