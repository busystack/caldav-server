package org.busystack.caldav.security.oidc;

import java.time.Instant;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.RecentAuthentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only the configured issuer and exact subject pair can select an internal account. */
@Service
public class ExternalIdentityService {
  public record LinkIntent(UUID accountId, String username, Instant issuedAt)
      implements java.io.Serializable {}

  private final JdbcTemplate jdbc;
  private final OidcProperties properties;
  private final AccountService accounts;
  private final org.busystack.caldav.security.session.ClientAccessRevocation revocation;
  private final org.busystack.caldav.security.session.SessionSecurity sessions;

  public ExternalIdentityService(
      JdbcTemplate jdbc,
      OidcProperties properties,
      AccountService accounts,
      org.busystack.caldav.security.session.ClientAccessRevocation revocation,
      org.busystack.caldav.security.session.SessionSecurity sessions) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.accounts = accounts;
    this.revocation = revocation;
    this.sessions = sessions;
  }

  public LinkIntent prepareLink(Authentication authentication) {
    RecentAuthentication.requireMfa(authentication);
    sessions.assertCurrent(authentication);
    var account = accounts.find(authentication.getName());
    if (account == null || !account.enabled() || !properties.enabled()) throw denied();
    return new LinkIntent(account.id(), account.username(), Instant.now());
  }

  @Transactional
  public AccountService.Account verifiedLogin(
      String issuer,
      String subject,
      Instant authenticatedAt,
      LinkIntent link,
      Authentication current) {
    Instant now = Instant.now();
    if (!properties.enabled()
        || !properties.issuer().toString().equals(issuer)
        || subject == null
        || subject.isBlank()
        || subject.length() > 255
        || authenticatedAt == null
        || authenticatedAt.isBefore(now.minusSeconds(300))
        || authenticatedAt.isAfter(now.plusSeconds(60))) throw denied();
    // One serialized decision per external identity covers creation and explicit linking.
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, issuer + "\n" + subject);
    var existing =
        jdbc.queryForList(
            "SELECT account_id FROM external_identities WHERE issuer=? AND subject=?",
            UUID.class,
            issuer,
            subject);
    UUID accountId;
    if (link != null) {
      RecentAuthentication.requireMfa(current);
      jdbc.query("SELECT id FROM accounts WHERE id=? FOR UPDATE", rs -> {}, link.accountId());
      try {
        sessions.assertCurrent(current);
      } catch (org.springframework.security.access.AccessDeniedException ex) {
        throw denied();
      }
      if (!link.issuedAt().isAfter(now.minusSeconds(300))
          || current == null
          || !link.username().equals(current.getName())) throw denied();
      var target = accounts.find(link.accountId());
      if (target == null || !target.enabled() || !target.username().equals(link.username()))
        throw denied();
      if (!existing.isEmpty() && !existing.getFirst().equals(target.id())) throw denied();
      var previous =
          jdbc.queryForList(
              "SELECT subject FROM external_identities WHERE account_id=? FOR UPDATE",
              String.class,
              target.id());
      if (!previous.isEmpty() && (existing.isEmpty() || !existing.getFirst().equals(target.id())))
        throw denied();
      accountId = target.id();
      if (existing.isEmpty()) {
        jdbc.update(
            "INSERT INTO external_identities(issuer,subject,account_id,source,last_verified_at) VALUES (?,?,?,'LINKED',?)",
            issuer,
            subject,
            accountId,
            java.sql.Timestamp.from(authenticatedAt));
        // Linking deliberately converts the account to upstream-managed primary authentication.
        jdbc.update("UPDATE accounts SET password_hash=NULL WHERE id=?", accountId);
        revocation.revoke(accountId, target.username());
      }
    } else if (!existing.isEmpty()) {
      accountId = existing.getFirst();
      String source =
          jdbc.queryForObject(
              "SELECT source FROM external_identities WHERE account_id=?", String.class, accountId);
      if ("ALLOWLIST".equals(source)
          && properties.allowedUsers().stream().noneMatch(user -> user.subject().equals(subject)))
        throw denied();
    } else {
      var allowed =
          properties.allowedUsers().stream()
              .filter(user -> user.subject().equals(subject))
              .findFirst()
              .orElseThrow(ExternalIdentityService::denied);
      if (accounts.find(allowed.username()) != null) throw denied();
      accountId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO accounts(id,username,display_name,password_hash,administrator) VALUES (?,?,?,NULL,false)",
          accountId,
          allowed.username(),
          allowed.displayName());
      jdbc.update(
          "INSERT INTO external_identities(issuer,subject,account_id,source,last_verified_at) VALUES (?,?,?,'ALLOWLIST',?)",
          issuer,
          subject,
          accountId,
          java.sql.Timestamp.from(authenticatedAt));
    }
    var account = accounts.find(accountId);
    if (account == null || !account.enabled()) throw denied();
    jdbc.update(
        "UPDATE external_identities SET last_verified_at=? WHERE issuer=? AND subject=?",
        java.sql.Timestamp.from(authenticatedAt.isAfter(now) ? now : authenticatedAt),
        issuer,
        subject);
    return account;
  }

  static OAuth2AuthenticationException denied() {
    return new OAuth2AuthenticationException(
        new OAuth2Error("access_denied"), "Upstream identity is not permitted");
  }
}
