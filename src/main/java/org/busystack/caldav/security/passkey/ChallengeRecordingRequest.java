package org.busystack.caldav.security.passkey;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpSession;
import java.util.Base64;
import java.util.Enumeration;
import org.busystack.caldav.security.audit.SecurityHashes;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;

/** Observes Spring's typed options save before the HTTP challenge response is written. */
final class ChallengeRecordingRequest extends HttpServletRequestWrapper {
  private static final String PREFIX = ChallengeRecordingRequest.class.getName() + ".";
  private final CeremonyStore ceremonies;

  ChallengeRecordingRequest(HttpServletRequest request, CeremonyStore ceremonies) {
    super(request);
    this.ceremonies = ceremonies;
  }

  static String attribute(CeremonyStore.Purpose purpose) {
    return PREFIX + purpose.name();
  }

  @Override
  public HttpSession getSession() {
    return getSession(true);
  }

  @Override
  public HttpSession getSession(boolean create) {
    HttpSession session = super.getSession(create);
    return session == null ? null : new ObservedSession(session);
  }

  private final class ObservedSession implements HttpSession {
    private final HttpSession delegate;

    ObservedSession(HttpSession delegate) {
      this.delegate = delegate;
    }

    @Override
    public void setAttribute(String name, Object value) {
      if (value instanceof PublicKeyCredentialCreationOptions options)
        record(CeremonyStore.Purpose.REGISTER, options.getChallenge());
      else if (value instanceof PublicKeyCredentialRequestOptions options)
        record(CeremonyStore.Purpose.AUTHENTICATE, options.getChallenge());
      delegate.setAttribute(name, value);
    }

    private void record(CeremonyStore.Purpose purpose, Bytes challenge) {
      String hash = SecurityHashes.sha256(Base64.getEncoder().encodeToString(challenge.getBytes()));
      ceremonies.issue(
          delegate.getId(), purpose, hash, SecurityContextHolder.getContext().getAuthentication());
      delegate.setAttribute(attribute(purpose), hash);
    }

    @Override
    public long getCreationTime() {
      return delegate.getCreationTime();
    }

    @Override
    public String getId() {
      return delegate.getId();
    }

    @Override
    public long getLastAccessedTime() {
      return delegate.getLastAccessedTime();
    }

    @Override
    public ServletContext getServletContext() {
      return delegate.getServletContext();
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
      delegate.setMaxInactiveInterval(interval);
    }

    @Override
    public int getMaxInactiveInterval() {
      return delegate.getMaxInactiveInterval();
    }

    @Override
    public Object getAttribute(String name) {
      return delegate.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
      return delegate.getAttributeNames();
    }

    @Override
    public void removeAttribute(String name) {
      delegate.removeAttribute(name);
    }

    @Override
    public void invalidate() {
      delegate.invalidate();
    }

    @Override
    public boolean isNew() {
      return delegate.isNew();
    }

    @Override
    public HttpSession.Accessor getAccessor() {
      return delegate.getAccessor();
    }
  }
}
