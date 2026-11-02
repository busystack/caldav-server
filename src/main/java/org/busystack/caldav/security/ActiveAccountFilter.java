package org.busystack.caldav.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.busystack.caldav.account.AccountService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** A previously created browser session must not keep a disabled account active. */
public final class ActiveAccountFilter extends OncePerRequestFilter {
  private final AccountService accounts;

  public ActiveAccountFilter(AccountService accounts) {
    this.accounts = accounts;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)) {
      var account = accounts.find(authentication.getName());
      if (account == null || !account.enabled() || !accounts.isClientAccessCurrent(account.id())) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        response.sendError(403);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
