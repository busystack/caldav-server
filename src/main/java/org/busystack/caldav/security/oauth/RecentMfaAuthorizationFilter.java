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
  private final org.springframework.security.web.savedrequest.HttpSessionRequestCache requestCache =
      new org.springframework.security.web.savedrequest.HttpSessionRequestCache();

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
      try {
        org.busystack.caldav.security.RecentAuthentication.requireMfa(authentication);
      } catch (org.springframework.security.access.AccessDeniedException ex) {
        if ("GET".equals(request.getMethod())) {
          requestCache.saveRequest(request, response);
          response.sendRedirect(request.getContextPath() + "/login?factor=required");
        } else response.sendError(403, "Recent primary and passkey verification required");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
