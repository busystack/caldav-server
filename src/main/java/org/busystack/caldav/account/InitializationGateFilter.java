package org.busystack.caldav.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** A configured provider cannot provision the first account before operator initialization. */
public final class InitializationGateFilter extends OncePerRequestFilter {
  private final InitializationService initialization;

  public InitializationGateFilter(InitializationService initialization) {
    this.initialization = initialization;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getServletPath().startsWith("/oauth2/authorization/")
        && !request.getServletPath().startsWith("/login/oauth2/code/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (initialization.available()) {
      response.setHeader("Cache-Control", "no-store");
      response.sendRedirect(request.getContextPath() + "/setup");
      return;
    }
    chain.doFilter(request, response);
  }
}
