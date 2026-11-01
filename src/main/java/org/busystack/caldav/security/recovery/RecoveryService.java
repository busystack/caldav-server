package org.busystack.caldav.security.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.security.audit.SecurityHashes;
import org.busystack.caldav.security.passkey.AccountPasskeyCredentials;
import org.busystack.caldav.security.session.SessionSecurity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recovery is a second factor after recent primary authentication; it never replaces primary login.
 */
@Service
public class RecoveryService {
  public record IssuedCodes(List<String> codes, Instant expiresAt) {
    public IssuedCodes {
      codes = List.copyOf(codes);
    }

    @Override
    public String toString() {
      return "IssuedCodes[count=" + codes.size() + ", secrets=<redacted>]";
    }
  }

  public record Status(int remaining, Instant expiresAt) {}

  private record StoredCode(
      String hash, Instant expiresAt, boolean available, boolean epochValid) {}

  private final JdbcTemplate jdbc;
  private final SessionSecurity sessions;
  private final AccountService accounts;
  private final org.busystack.caldav.security.session.ClientAccessRevocation clients;
  private final AccountPasskeyCredentials passkeys;
  private final SecurityAudit audit;
  private final TransactionTemplate transaction;
  private final Base64StringKeyGenerator secrets =
      new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

  public RecoveryService(
      JdbcTemplate jdbc,
      SessionSecurity sessions,
      AccountService accounts,
      AccountPasskeyCredentials passkeys,
      SecurityAudit audit,
      PlatformTransactionManager transactions,
      org.busystack.caldav.security.session.ClientAccessRevocation clients) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.accounts = accounts;
    this.passkeys = passkeys;
    this.audit = audit;
    this.clients = clients;
    this.transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(15);
  }

  public Status status(Authentication authentication) {
    var account = sessions.assertCurrent(authentication);
    return jdbc.queryForObject(
        "SELECT count(*),max(c.expires_at) FROM account_recovery_codes c CROSS JOIN system_state s WHERE c.account_id=? AND c.used_at IS NULL AND c.revoked_at IS NULL AND c.expires_at>now() AND c.recovery_epoch=s.recovery_epoch AND s.id=1",
        (rs, row) ->
            new Status(
                rs.getInt(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()),
        account.id());
  }

  @Transactional
  public IssuedCodes issue(Authentication authentication, String peer) {
    RecentAuthentication.requireMfa(authentication);
    var account = sessions.assertForReset(authentication);
    requireCurrentAccess(account.id());
    jdbc.update(
        "UPDATE account_recovery_codes SET revoked_at=now() WHERE account_id=? AND used_at IS NULL AND revoked_at IS NULL",
        account.id());
    Instant expires = Instant.now().plusSeconds(365L * 86400);
    List<String> issued = new ArrayList<>(10);
    for (int index = 0; index < 10; index++) {
      UUID id = UUID.randomUUID();
      String code = id + "." + secrets.generateKey();
      jdbc.update(
          "INSERT INTO account_recovery_codes(id,account_id,token_hash,recovery_epoch,expires_at) SELECT ?,?,?,recovery_epoch,? FROM system_state WHERE id=1",
          id,
          account.id(),
          SecurityHashes.sha256(code),
          Timestamp.from(expires));
      issued.add(code);
    }
    audit.record(
        account.id(),
        SecurityAudit.Action.RECOVERY_CODES_ISSUED,
        SecurityAudit.Outcome.SUCCESS,
        peer);
    return new IssuedCodes(List.copyOf(issued), expires);
  }

  public void recover(Authentication authentication, String code, String peer) {
    RecentAuthentication.requirePrimary(authentication);
    var account = sessions.assertCurrent(authentication);
    UUID codeId = codeId(code);
    Boolean accepted =
        transaction.execute(
            status -> {
              lock(account.id());
              sessions.assertCurrent(authentication);
              requireCurrentAccess(account.id());
              List<StoredCode> matches =
                  codeId == null
                      ? List.of()
                      : jdbc.query(
                          "SELECT c.token_hash,c.expires_at,(c.used_at IS NULL AND c.revoked_at IS NULL),(c.recovery_epoch=s.recovery_epoch) FROM account_recovery_codes c CROSS JOIN system_state s WHERE c.id=? AND c.account_id=? AND s.id=1 FOR UPDATE OF c",
                          (rs, row) ->
                              new StoredCode(
                                  rs.getString(1),
                                  rs.getTimestamp(2).toInstant(),
                                  rs.getBoolean(3),
                                  rs.getBoolean(4)),
                          codeId,
                          account.id());
              if (matches.size() != 1
                  || !matches.getFirst().available()
                  || !matches.getFirst().epochValid()
                  || !matches.getFirst().expiresAt().isAfter(Instant.now())
                  || !MessageDigest.isEqual(
                      matches.getFirst().hash().getBytes(StandardCharsets.US_ASCII),
                      SecurityHashes.sha256(code).getBytes(StandardCharsets.US_ASCII))) {
                audit.record(
                    account.id(),
                    SecurityAudit.Action.RECOVERY_USED,
                    SecurityAudit.Outcome.FAILURE,
                    peer);
                return false;
              }
              jdbc.update("UPDATE account_recovery_codes SET used_at=now() WHERE id=?", codeId);
              clients.revoke(account.id(), account.username());
              var keys =
                  jdbc.queryForList(
                      "SELECT c.credential_id FROM user_credentials c JOIN user_entities u ON u.id=c.user_entity_user_id WHERE u.account_id=?",
                      String.class,
                      account.id());
              keys.forEach(key -> passkeys.delete(Bytes.fromBase64(key)));
              jdbc.update("DELETE FROM user_entities WHERE account_id=?", account.id());
              audit.record(
                  account.id(),
                  SecurityAudit.Action.RECOVERY_USED,
                  SecurityAudit.Outcome.SUCCESS,
                  peer);
              return true;
            });
    if (!Boolean.TRUE.equals(accepted))
      throw new AccessDeniedException("Recovery code is invalid or expired");
  }

  private void lock(UUID account) {
    jdbc.query("SELECT id FROM accounts WHERE id=? FOR UPDATE", rs -> {}, account);
  }

  private void requireCurrentAccess(UUID account) {
    if (!accounts.isClientAccessCurrent(account))
      throw new AccessDeniedException("Current primary authentication is required");
  }

  private static UUID codeId(String code) {
    if (code == null || code.length() != 80 || code.charAt(36) != '.') return null;
    try {
      return UUID.fromString(code.substring(0, 36));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
