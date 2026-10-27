package org.busystack.caldav.security.session;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A trusted generation captured with credential state, rather than after credential verification.
 */
@Service
public class SessionSecurity {
  private static final String PREFIX = "BUSYSTACK_SESSION_";

  public record Account(UUID id, String username, UUID epoch) {}

  private final JdbcTemplate jdbc;
  private final org.busystack.caldav.security.oidc.ExternalAccessPolicy externalAccess;

  public SessionSecurity(
      JdbcTemplate jdbc, org.busystack.caldav.security.oidc.ExternalAccessPolicy externalAccess) {
    this.jdbc = jdbc;
    this.externalAccess = externalAccess;
  }

  public static SimpleGrantedAuthority authority(UUID capturedEpoch) {
    return new SimpleGrantedAuthority(PREFIX + capturedEpoch);
  }

  public static UUID capturedEpoch(
      java.util.Collection<? extends org.springframework.security.core.GrantedAuthority>
          authorities) {
    var generations =
        authorities.stream()
            .map(granted -> granted.getAuthority())
            .filter(value -> value != null && value.startsWith(PREFIX))
            .distinct()
            .toList();
    if (generations.size() != 1) return null;
    try {
      return UUID.fromString(generations.getFirst().substring(PREFIX.length()));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  public Account assertCurrent(Authentication authentication) {
    // A service mutation must keep the captured generation stable until its transaction commits.
    boolean transactional =
        org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive();
    return current(authentication, transactional ? " FOR SHARE" : "");
  }

  public Account assertForReset(Authentication authentication) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Security reset requires a transaction");
    return current(authentication, " FOR UPDATE");
  }

  private Account current(Authentication authentication, String lock) {
    if (authentication == null || !authentication.isAuthenticated())
      throw new AccessDeniedException("Current account authentication is required");
    var rows =
        jdbc.query(
            "SELECT id,username,security_epoch FROM accounts WHERE username=? AND enabled" + lock,
            (rs, row) ->
                new Account(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)),
            authentication.getName());
    if (rows.size() != 1) throw new AccessDeniedException("Account session is unavailable");
    Account account = rows.getFirst();
    if (!externalAccess.isCurrent(account.id()))
      throw new AccessDeniedException("Current upstream authentication is required");
    var generations =
        authentication.getAuthorities().stream()
            .map(granted -> granted.getAuthority())
            .filter(value -> value != null && value.startsWith(PREFIX))
            .distinct()
            .toList();
    if (generations.size() != 1
        || !generations.getFirst().equals(authority(account.epoch()).getAuthority()))
      throw new AccessDeniedException("Account session has ended");
    return account;
  }

  /** Internal lifecycle hook: call inside account disable/recovery transactions. */
  @Transactional
  public void invalidateAll(UUID accountId) {
    var names =
        jdbc.queryForList(
            "SELECT username FROM accounts WHERE id=? FOR UPDATE", String.class, accountId);
    if (names.isEmpty()) return;
    jdbc.update("UPDATE accounts SET security_epoch=gen_random_uuid() WHERE id=?", accountId);
    jdbc.update(
        "DELETE FROM spring_session WHERE principal_name IN (?,?)",
        names.getFirst(),
        accountId.toString());
    jdbc.update("DELETE FROM webauthn_ceremonies WHERE account_id=?", accountId);
  }
}
