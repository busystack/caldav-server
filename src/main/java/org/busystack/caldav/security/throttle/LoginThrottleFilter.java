package org.busystack.caldav.security.throttle;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Surrounds the framework authentication filters without replacing their handlers or factor merge.
 */
public final class LoginThrottleFilter extends OncePerRequestFilter {
  private final LoginThrottleService throttle;

  public LoginThrottleFilter(LoginThrottleService throttle) {
    this.throttle = throttle;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !("/login".equals(request.getServletPath())
            || "/login/webauthn".equals(request.getServletPath()));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean password = "/login".equals(request.getServletPath());
    String username = password ? request.getParameter("username") : null;
    String peer = request.getRemoteAddr();
    SecurityAudit.Action action =
        password ? SecurityAudit.Action.PASSWORD_LOGIN : SecurityAudit.Action.PASSKEY_LOGIN;
    var decision = throttle.reserve(username, peer);
    if (!decision.allowed()) {
      throttle.denied(peer, action);
      response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
      response.setStatus(429);
      response.setContentType("text/plain;charset=UTF-8");
      response.getWriter().write("Too many sign-in attempts. Try again shortly.");
      return;
    }
    chain.doFilter(request, response);
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean success =
        response.getStatus() < 400
            && authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    throttle.result(success ? authentication.getName() : username, peer, action, success);
  }
}
