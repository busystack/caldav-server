package org.busystack.caldav.security.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * OAuth client access requires recent primary sign-in plus passkey verification, including consent
 * reuse.
 */
final class RecentMfaAuthorizationFilter extends OncePerRequestFilter {
  private final org.busystack.caldav.security.session.BrowserSession sessions;
  private final org.busystack.caldav.security.AuthenticationJourney journey;

  RecentMfaAuthorizationFilter(
      org.busystack.caldav.security.session.BrowserSession sessions,
      org.busystack.caldav.security.AuthenticationJourney journey) {
    this.sessions = sessions;
    this.journey = journey;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"/oauth2/authorize".equals(request.getServletPath());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)) {
      var state = sessions.state(request, authentication);
      if (state != org.busystack.caldav.security.session.BrowserSession.State.CURRENT
          || !org.busystack.caldav.security.RecentAuthentication.mfaCurrent(authentication)) {
        if (state == org.busystack.caldav.security.session.BrowserSession.State.EXPIRED) {
          SecurityContextHolder.clearContext();
          request.getSession(false).invalidate();
          authentication = null;
        }
        if ("GET".equals(request.getMethod())) {
          String flow =
              journey.start(
                  request,
                  org.busystack.caldav.security.BrowserAccessFilter.destination(request),
                  authentication);
          response.sendRedirect(
              request.getContextPath()
                  + (authentication == null ? "/login?session&flow=" : "/auth/continue?flow=")
                  + flow);
        } else if ("POST".equals(request.getMethod())
            && authentication != null
            && browserNavigation(request)) {
          String client = request.getParameter("client_id");
          String consentState = request.getParameter("state");
          if (client == null
              || client.length() > 200
              || consentState == null
              || consentState.length() > 1024) {
            response.sendError(403);
            return;
          }
          String destination =
              org.springframework.web.util.UriComponentsBuilder.fromPath(
                      request.getContextPath() + "/oauth/consent")
                  .queryParam("client_id", "{client}")
                  .queryParam("state", "{state}")
                  .encode()
                  .buildAndExpand(client, consentState)
                  .toUriString();
          String flow = journey.start(request, destination, authentication);
          response.setStatus(HttpServletResponse.SC_SEE_OTHER);
          response.setHeader("Cache-Control", "no-store");
          response.setHeader("Location", request.getContextPath() + "/auth/continue?flow=" + flow);
        } else response.sendError(403, "Recent primary and passkey verification required");
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private static boolean browserNavigation(HttpServletRequest request) {
    return "navigate".equals(request.getHeader("Sec-Fetch-Mode"))
        || request.getHeader("Accept") != null && request.getHeader("Accept").contains("text/html");
  }
}
