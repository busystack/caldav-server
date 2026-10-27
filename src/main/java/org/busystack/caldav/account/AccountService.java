package org.busystack.caldav.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  public record Account(
      UUID id,
      String username,
      String displayName,
      String passwordHash,
      boolean administrator,
      boolean enabled,
      UUID securityEpoch) {
    @Override
    public String toString() {
      return "Account[id=" + id + ", username=" + username + "]";
    }
  }

  public record IssuedCredential(UUID id, String username, String secret, Instant expiresAt) {
    @Override
    public String toString() {
      return "IssuedCredential[id=" + id + ", secret=<redacted>]";
    }
  }

  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final org.busystack.caldav.security.DavSecretVerifier davVerifiers;
  private final org.busystack.caldav.security.passkey.AccountPasskeyCredentials passkeys;
  private final org.busystack.caldav.security.oidc.ExternalAccessPolicy externalAccess;
  private final org.busystack.caldav.security.session.ClientAccessRevocation clientAccess;
  private final org.springframework.security.crypto.keygen.StringKeyGenerator davSecrets =
      new org.springframework.security.crypto.keygen.Base64StringKeyGenerator(
          java.util.Base64.getUrlEncoder().withoutPadding(), 32);

  public AccountService(
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      org.busystack.caldav.security.passkey.AccountPasskeyCredentials passkeys,
      org.busystack.caldav.security.oidc.ExternalAccessPolicy externalAccess,
      org.busystack.caldav.security.session.ClientAccessRevocation clientAccess,
      org.busystack.caldav.security.DavSecretVerifier davVerifiers) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.davVerifiers = davVerifiers;
    this.passkeys = passkeys;
    this.externalAccess = externalAccess;
    this.clientAccess = clientAccess;
  }

  public boolean isClientAccessCurrent(UUID accountId) {
    return externalAccess.isCurrent(accountId);
  }

  public boolean isExternallyManaged(UUID accountId) {
    return externalAccess.isExternal(accountId);
  }

  @Transactional
  public UUID provision(
      String username, String displayName, String password, boolean administrator) {
    validatePassword(password);
    if (username == null
        || !username.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
        || displayName == null
        || displayName.isBlank()
        || displayName.codePointCount(0, displayName.length()) > 200
        || displayName.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid account name or display name");
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO accounts(id,username,display_name,password_hash,administrator) VALUES (?,?,?,?,?)",
        id,
        username,
        displayName,
        passwords.encode(password),
        administrator);
    return id;
  }

  private static void validatePassword(String password) {
    org.busystack.caldav.security.password.PasswordPolicy.validate(password);
  }

  /** Operator-only recovery entry point. No controller exposes this operation. */
  @Transactional
  public void recoverOffline(String username, String replacementPassword) {
    validatePassword(replacementPassword);
    var ids =
        jdbc.queryForList(
            "SELECT id FROM accounts WHERE username=? FOR UPDATE", UUID.class, username);
    if (ids.size() != 1) throw new IllegalArgumentException("Unknown recovery account");
    UUID id = ids.getFirst();
    if (isExternallyManaged(id))
      throw new IllegalArgumentException(
          "Externally managed accounts require upstream identity recovery");
    jdbc.update(
        "UPDATE accounts SET password_hash=?,enabled=true WHERE id=?",
        passwords.encode(replacementPassword),
        id);
    clearClientAccess(id, username);
  }

  /** Restores only an existing verified mapping; it never provisions a password fallback. */
  @Transactional
  public void recoverExternalOffline(String username, String issuer, String subject) {
    var ids =
        jdbc.queryForList(
            "SELECT id FROM accounts WHERE username=? FOR UPDATE", UUID.class, username);
    if (ids.size() != 1) throw new IllegalArgumentException("Unknown recovery account");
    UUID id = ids.getFirst();
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM external_identities WHERE account_id=? AND issuer=? AND subject=?)",
            Boolean.class,
            id,
            issuer,
            subject)))
      throw new IllegalArgumentException(
          "External recovery must match the existing issuer and subject");
    clearClientAccess(id, username);
    jdbc.update(
        "UPDATE external_identities SET last_verified_at='epoch'::timestamptz WHERE account_id=?",
        id);
    jdbc.update("UPDATE accounts SET password_hash=NULL,enabled=true WHERE id=?", id);
  }

  private void clearClientAccess(UUID id, String username) {
    clientAccess.revoke(id, username);
    jdbc.update("DELETE FROM account_recovery_codes WHERE account_id=?", id);
    jdbc.update("DELETE FROM dav_credentials WHERE account_id=?", id);
    jdbc.update("DELETE FROM oauth_refresh_families WHERE account_id=?", id);
    jdbc.update(
        "DELETE FROM oauth2_authorization WHERE principal_name IN (?,?) OR attributes::jsonb ->> 'busystack.account_id'=?",
        username,
        id.toString(),
        id.toString());
    jdbc.update(
        "DELETE FROM oauth2_authorization_consent WHERE principal_name IN (?,?)",
        username,
        id.toString());
    var keys =
        jdbc.queryForList(
            "SELECT c.credential_id FROM user_credentials c JOIN user_entities u ON u.id=c.user_entity_user_id WHERE u.account_id=?",
            String.class,
            id);
    keys.forEach(
        key ->
            passkeys.delete(org.springframework.security.web.webauthn.api.Bytes.fromBase64(key)));
    jdbc.update("DELETE FROM user_entities WHERE account_id=?", id);
  }

  public Account find(String username) {
    return jdbc
        .query("SELECT * FROM accounts WHERE username=?", AccountService::account, username)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public Account find(UUID id) {
    return jdbc.query("SELECT * FROM accounts WHERE id=?", AccountService::account, id).stream()
        .findFirst()
        .orElse(null);
  }

  /** Internal operation; browser issuance must supply recent MFA before calling this service. */
  @Transactional
  public IssuedCredential issueDavCredential(UUID accountId, String label, Instant expiresAt) {
    Account account = find(accountId);
    if (account == null || !account.enabled() || !isClientAccessCurrent(accountId))
      throw new IllegalArgumentException("Account unavailable");
    if (!expiresAt.isAfter(Instant.now())
        || expiresAt.isAfter(Instant.now().plusSeconds(366L * 86400)))
      throw new IllegalArgumentException("Credential expiry must be within one year");
    UUID id = UUID.randomUUID();
    String value = davSecrets.generateKey();
    jdbc.update(
        "INSERT INTO dav_credentials(id,account_id,label,verifier,expires_at) VALUES (?,?,?,?,?)",
        id,
        accountId,
        label,
        davVerifiers.encode(id, value),
        java.sql.Timestamp.from(expiresAt));
    return new IssuedCredential(id, account.username(), id + "." + value, expiresAt);
  }

  public boolean verifyDav(String username, String credential) {
    if (credential == null || credential.length() != 80 || credential.charAt(36) != '.')
      return false;
    UUID id;
    try {
      id = UUID.fromString(credential.substring(0, 36));
    } catch (IllegalArgumentException ex) {
      return false;
    }
    var verifiers =
        jdbc.query(
            "SELECT c.verifier,a.id FROM dav_credentials c JOIN accounts a ON a.id=c.account_id WHERE c.id=? AND a.username=? AND a.enabled AND c.revoked_at IS NULL AND c.expires_at > now()",
            (rs, row) -> new DavVerifier(rs.getString(1), rs.getObject(2, UUID.class)),
            id,
            username);
    if (verifiers.size() != 1) return false;
    var stored = verifiers.getFirst();
    String secret = credential.substring(37);
    if (!isClientAccessCurrent(stored.accountId())
        || !davVerifiers.matches(id, secret, stored.verifier())) return false;
    if (davVerifiers.current(stored.verifier())) return true;
    // Upgrade only a still-current credential; recovery/deletion/revocation can never revive it.
    return jdbc.update(
            "UPDATE dav_credentials c SET verifier=? WHERE c.id=? AND c.verifier=? AND c.revoked_at IS NULL AND c.expires_at>now() AND EXISTS(SELECT 1 FROM accounts a WHERE a.id=c.account_id AND a.enabled)",
            davVerifiers.encode(id, secret),
            id,
            stored.verifier())
        == 1;
  }

  private record DavVerifier(String verifier, UUID accountId) {}

  @Transactional
  public void revokeDav(UUID actor, UUID credentialId) {
    jdbc.update(
        "UPDATE dav_credentials SET revoked_at=now() WHERE id=? AND account_id=?",
        credentialId,
        actor);
  }

  @Transactional
  public void disable(UUID id) {
    jdbc.update("UPDATE accounts SET enabled=false WHERE id=?", id);
    jdbc.update(
        "UPDATE dav_credentials SET revoked_at=now() WHERE account_id=? AND revoked_at IS NULL",
        id);
    jdbc.update(
        "UPDATE oauth_refresh_families SET revoked_at=now() WHERE account_id=? AND revoked_at IS NULL",
        id);
    var usernames = jdbc.queryForList("SELECT username FROM accounts WHERE id=?", String.class, id);
    if (!usernames.isEmpty()) clientAccess.revoke(id, usernames.getFirst());
    jdbc.update("DELETE FROM account_recovery_codes WHERE account_id=?", id);
  }

  private static Account account(ResultSet rs, int row) throws SQLException {
    return new Account(
        rs.getObject("id", UUID.class),
        rs.getString("username"),
        rs.getString("display_name"),
        rs.getString("password_hash"),
        rs.getBoolean("administrator"),
        rs.getBoolean("enabled"),
        rs.getObject("security_epoch", UUID.class));
  }
}
