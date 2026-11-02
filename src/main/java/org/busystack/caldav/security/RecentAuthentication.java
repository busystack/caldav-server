package org.busystack.caldav.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;

/** One policy shared by the browser and authorization-server security boundaries. */
public final class RecentAuthentication {
  public static final Duration MAX_AGE = Duration.ofMinutes(10);

  private RecentAuthentication() {}

  public static void requirePassword(Authentication authentication) {
    if (!has(authentication, FactorGrantedAuthority.PASSWORD_AUTHORITY))
      throw new AccessDeniedException("Recent password authentication is required");
  }

  public static void requireMfa(Authentication authentication) {
    requirePrimary(authentication);
    if (!has(authentication, FactorGrantedAuthority.WEBAUTHN_AUTHORITY))
      throw new AccessDeniedException("Recent password and passkey authentication are required");
  }

  public static void requirePrimary(Authentication authentication) {
    boolean upstream =
        has(authentication, FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
            && authentication.getAuthorities().stream()
                .anyMatch(
                    authority -> "BUSYSTACK_TRUSTED_UPSTREAM".equals(authority.getAuthority()));
    if (!has(authentication, FactorGrantedAuthority.PASSWORD_AUTHORITY) && !upstream)
      throw new AccessDeniedException("Recent primary authentication is required");
  }

  private static boolean has(Authentication authentication, String authority) {
    if (authentication == null || !authentication.isAuthenticated()) return false;
    Instant now = Instant.now();
    return authentication.getAuthorities().stream()
        .anyMatch(
            granted ->
                granted instanceof FactorGrantedAuthority factor
                    && factor.getAuthority().equals(authority)
                    && factor.getIssuedAt().isAfter(now.minus(MAX_AGE))
                    && !factor.getIssuedAt().isAfter(now));
  }
}
