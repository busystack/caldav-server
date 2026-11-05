package org.busystack.caldav.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.busystack.caldav.config.DeploymentProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Component;

/** Continuations are application paths, never request-host-derived absolute redirects. */
@Component
public class AuthenticationJourney {
  // Spring's new-session strategy retains security attributes while discarding
  // ordinary application state. This contains only a validated local GET path.
  private static final String DESTINATION = "SPRING_SECURITY_BUSYSTACK_CONTINUATION";
  private final String prefix;
  private final HttpSessionRequestCache cache = new HttpSessionRequestCache();

  public AuthenticationJourney(DeploymentProperties deployment) {
    prefix = deployment.publicBaseUrl().getPath();
  }

  public String safe(String candidate) {
    if (candidate == null
        || !candidate.startsWith(prefix + "/")
        || candidate.contains("\\")
        || candidate.contains("//")
        || candidate.matches(".*(?:^|/)\\.{1,2}(?:/|[?]|$).*")
        || candidate.contains("%")
        || candidate.codePoints().anyMatch(Character::isISOControl)) return prefix + "/";
    try {
      URI uri = URI.create(candidate);
      String path = uri.getPath();
      if (uri.isAbsolute()
          || uri.getRawAuthority() != null
          || uri.getFragment() != null
          || !uri.normalize().getPath().equals(path)) return prefix + "/";
      String local = path.substring(prefix.length());
      if (local.startsWith("/login")
          || local.startsWith("/setup")
          || local.startsWith("/auth/")
          || local.startsWith("/webauthn/")
          || local.equals("/logout")) return prefix + "/";
      return candidate;
    } catch (IllegalArgumentException invalid) {
      return prefix + "/";
    }
  }

  public void remember(HttpServletRequest request, String destination) {
    request.getSession().setAttribute(DESTINATION, safe(destination));
  }

  public void capture(HttpServletRequest request, HttpServletResponse response) {
    var saved = cache.getRequest(request, response);
    if (saved != null) {
      if ("GET".equals(saved.getMethod())) {
        URI uri = URI.create(saved.getRedirectUrl());
        // Keep encoded query values (OAuth state/PKCE), but validate the path separately.
        String path = safe(uri.getRawPath());
        String query = uri.getRawQuery();
        remember(request, path);
        if (path.equals(uri.getRawPath())
            && query != null
            && !query.codePoints().anyMatch(Character::isISOControl))
          request.getSession().setAttribute(DESTINATION, path + "?" + query);
      }
      cache.removeRequest(request, response);
    }
  }

  public String destination(HttpServletRequest request) {
    Object value = request.getSession().getAttribute(DESTINATION);
    return value instanceof String path ? path : prefix + "/";
  }

  public void primarySuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication auth)
      throws IOException {
    capture(request, response);
    response.sendRedirect(prefix + "/auth/continue");
  }

  public void passkeySuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication auth)
      throws IOException {
    capture(request, response);
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    response.getWriter().write("{\"redirectUrl\":\"" + prefix + "/auth/continue?verified\"}");
  }
}
