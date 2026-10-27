package org.busystack.caldav.security.oauth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.RecentAuthentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Account-interface operations expose grant metadata, never access or refresh token values. */
@Service
public class OAuthGrantService {
  public record Grant(
      String id,
      String clientId,
      String clientName,
      Instant createdAt,
      Instant expiresAt,
      Instant revokedAt) {}

  private final JdbcTemplate jdbc;
  private final AccountService accounts;
  private final RefreshFamilyStore families;
  private final org.busystack.caldav.security.session.SessionSecurity sessions;

  public OAuthGrantService(
      JdbcTemplate jdbc,
      AccountService accounts,
      RefreshFamilyStore families,
      org.busystack.caldav.security.session.SessionSecurity sessions) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.families = families;
    this.sessions = sessions;
  }

  public List<Grant> list(Authentication authentication) {
    return list(actor(authentication));
  }

  public void revoke(Authentication authentication, String authorizationId) {
    RecentAuthentication.requireMfa(authentication);
    UUID accountId = actor(authentication);
    families.revoke(
        accountId,
        authorizationId,
        () -> {
          sessions.assertCurrent(authentication);
          removeConsent(accountId, authorizationId);
        });
  }

  List<Grant> list(UUID accountId) {
    requireAccount(accountId);
    return jdbc.query(
        "SELECT f.authorization_id,f.client_id,COALESCE(c.client_name,f.client_id),f.created_at,f.expires_at,f.revoked_at FROM oauth_refresh_families f LEFT JOIN oauth2_registered_client c ON c.client_id=f.client_id WHERE f.account_id=? ORDER BY f.created_at DESC",
        (rs, row) ->
            new Grant(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
        accountId);
  }

  void revoke(UUID accountId, String authorizationId) {
    requireAccount(accountId);
    families.revoke(accountId, authorizationId, () -> removeConsent(accountId, authorizationId));
  }

  private void removeConsent(UUID accountId, String authorizationId) {
    jdbc.update(
        "DELETE FROM oauth2_authorization_consent c USING oauth2_authorization a,oauth_refresh_families f WHERE a.id=f.authorization_id AND f.authorization_id=? AND f.account_id=? AND c.registered_client_id=a.registered_client_id AND c.principal_name=a.principal_name",
        authorizationId,
        accountId);
  }

  private UUID actor(Authentication authentication) {
    sessions.assertCurrent(authentication);
    if (authentication == null || !authentication.isAuthenticated())
      throw new AccessDeniedException("Account authentication required");
    var account = accounts.find(authentication.getName());
    if (account == null || !account.enabled())
      throw new AccessDeniedException("Account unavailable");
    return account.id();
  }

  private void requireAccount(UUID id) {
    var account = accounts.find(id);
    if (account == null || !account.enabled())
      throw new AccessDeniedException("Account unavailable");
  }
}
