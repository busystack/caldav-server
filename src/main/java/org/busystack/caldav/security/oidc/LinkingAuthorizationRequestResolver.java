package org.busystack.caldav.security.oidc;

import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** Associates an explicit linking intent with its own upstream authorization response. */
final class LinkingAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
  static final String PENDING_LINK =
      LinkingAuthorizationRequestResolver.class.getName() + ".pending";
  private static final String BOUND_LINK =
      LinkingAuthorizationRequestResolver.class.getName() + ".bound";

  private record BoundLink(String state, ExternalIdentityService.LinkIntent intent)
      implements Serializable {}

  private final OAuth2AuthorizationRequestResolver delegate;

  LinkingAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate) {
    this.delegate = delegate;
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    return bind(request, delegate.resolve(request));
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
    return bind(request, delegate.resolve(request, registrationId));
  }

  private OAuth2AuthorizationRequest bind(
      HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
    var session = request.getSession(false);
    if (authorization == null || session == null) return authorization;
    synchronized (session) {
      Object pending = session.getAttribute(PENDING_LINK);
      if (pending instanceof ExternalIdentityService.LinkIntent intent) {
        if (authorization.getState() == null || authorization.getState().isBlank())
          throw new IllegalStateException("Linking requires authorization response state");
        session.removeAttribute(PENDING_LINK);
        session.setAttribute(BOUND_LINK, new BoundLink(authorization.getState(), intent));
      }
    }
    return authorization;
  }

  static ExternalIdentityService.LinkIntent consume(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session == null) return null;
    synchronized (session) {
      Object pending = session.getAttribute(BOUND_LINK);
      if (pending instanceof BoundLink bound
          && bound.state().equals(request.getParameter("state"))) {
        session.removeAttribute(BOUND_LINK);
        return bound.intent();
      }
      return null;
    }
  }
}
