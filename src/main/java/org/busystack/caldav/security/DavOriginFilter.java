package org.busystack.caldav.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** DAV credentials are for non-browser clients; block cookie and cached-Basic CSRF contexts. */
final class DavOriginFilter extends OncePerRequestFilter {
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
  private final java.net.URI base;

  DavOriginFilter(org.busystack.caldav.config.DeploymentProperties deployment) {
    base = deployment.publicBaseUrl();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin"), site = request.getHeader("Sec-Fetch-Site");
    boolean trustedOrigin = origin == null || sameOrigin(origin);
    boolean trustedSite = site == null || site.equals("none") || site.equals("same-origin");
    if (!SAFE.contains(request.getMethod()) && (!trustedOrigin || !trustedSite)) {
      response.sendError(403);
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean sameOrigin(String value) {
    try {
      var origin = java.net.URI.create(value);
      return origin.getHost() != null
          && origin.getUserInfo() == null
          && origin.getRawQuery() == null
          && origin.getRawFragment() == null
          && origin.getRawPath().isEmpty()
          && base.getScheme().equalsIgnoreCase(origin.getScheme())
          && base.getHost().equalsIgnoreCase(origin.getHost())
          && port(base) == port(origin);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static int port(java.net.URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
