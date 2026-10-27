package org.busystack.caldav.security.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Stops an already-running old login/session from surviving a committed account reset. */
public final class SessionEpochFilter extends OncePerRequestFilter {
  private final SessionSecurity sessions;

  public SessionEpochFilter(SessionSecurity sessions) {
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)) {
      try {
        sessions.assertCurrent(authentication);
      } catch (AccessDeniedException ex) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        response.sendError(403, "Account session has ended");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
