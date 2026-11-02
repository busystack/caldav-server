package org.busystack.caldav.security.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable replay protection around a framework authorization mutation. The callback must use the
 * same transaction manager and datasource. Token generation and OAuth parsing belong to the
 * framework.
 */
@Service
public class RefreshFamilyStore {
  public record Family(
      String authorizationId, UUID accountId, String clientId, Instant expiresAt) {}

  public record Rotation<T>(T response, String successorToken, Instant successorExpiresAt) {}

  private record Attempt<T>(T response, RefreshRejectedException.Reason rejection) {}

  private record LockedFamily(Family family, boolean revoked, boolean epochValid) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final TransactionTemplate joiningTransaction;
  private final org.busystack.caldav.account.AccountService accounts;
  private final OAuthClientLock clients;

  public RefreshFamilyStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      org.busystack.caldav.account.AccountService accounts,
      OAuthClientLock clients) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.clients = clients;
    this.transaction = new TransactionTemplate(transactions);
    this.joiningTransaction = new TransactionTemplate(transactions);
    joiningTransaction.setTimeout(15);
    // A committed replay revocation must survive the adapter throwing invalid_grant afterwards.
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(15);
  }

  /** Persists initial framework authorization and family state in one transaction. */
  public void register(
      Family family, String token, Instant tokenExpiresAt, Runnable persistAuthorization) {
    Objects.requireNonNull(family);
    Objects.requireNonNull(persistAuthorization);
    String hash = tokenHash(token);
    if (!family.expiresAt().isAfter(Instant.now()) || !tokenExpiresAt.isAfter(Instant.now())) {
      throw new IllegalArgumentException("Refresh expiry must be in the future");
    }
    joiningTransaction.executeWithoutResult(
        status -> {
          if (!clients.matches(family.clientId(), null))
            throw new RefreshRejectedException(RefreshRejectedException.Reason.INVALID);
          lockAuthorization(family.authorizationId());
          if (!lockEnabledAccount(family.accountId()))
            throw new RefreshRejectedException(RefreshRejectedException.Reason.DISABLED);
          jdbc.update(
              "INSERT INTO oauth_refresh_families(authorization_id,account_id,client_id,recovery_epoch,expires_at) SELECT ?,?,?,recovery_epoch,? FROM system_state WHERE id=1",
              family.authorizationId(),
              family.accountId(),
              family.clientId(),
              Timestamp.from(family.expiresAt()));
          jdbc.update(
              "INSERT INTO oauth_refresh_tokens(token_hash,authorization_id,expires_at) VALUES (?,?,?)",
              hash,
              family.authorizationId(),
              Timestamp.from(earlier(tokenExpiresAt, family.expiresAt())));
          persistAuthorization.run();
        });
  }

  /**
   * Serializes validation, consumption, framework grant persistence, and successor registration.
   * Reusing a valid spent token revokes the entire family before this method throws.
   */
  public <T> T refresh(
      String token, String clientId, Function<Family, Rotation<T>> mutateAuthorization) {
    return refresh(token, clientId, null, mutateAuthorization);
  }

  public <T> T refresh(
      String token,
      String clientId,
      String registeredClientId,
      Function<Family, Rotation<T>> mutateAuthorization) {
    Objects.requireNonNull(mutateAuthorization);
    String hash = tokenHash(token);
    Attempt<T> outcome =
        transaction.execute(
            status -> {
              if (!clients.matches(clientId, registeredClientId))
                return rejected(RefreshRejectedException.Reason.INVALID);
              List<Family> candidates =
                  jdbc.query(
                      "SELECT f.authorization_id,f.account_id,f.client_id,f.expires_at FROM oauth_refresh_families f JOIN oauth_refresh_tokens t USING(authorization_id) WHERE t.token_hash=?",
                      (rs, row) ->
                          new Family(
                              rs.getString(1),
                              rs.getObject(2, UUID.class),
                              rs.getString(3),
                              rs.getTimestamp(4).toInstant()),
                      hash);
              if (candidates.isEmpty()
                  || !Objects.equals(candidates.getFirst().clientId(), clientId))
                return rejected(RefreshRejectedException.Reason.INVALID);
              Family candidate = candidates.getFirst();
              lockAuthorization(candidate.authorizationId());
              if (!lockEnabledAccount(candidate.accountId()))
                return rejected(RefreshRejectedException.Reason.DISABLED);
              List<LockedFamily> families =
                  jdbc.query(
                      "SELECT f.authorization_id,f.account_id,f.client_id,f.expires_at,f.revoked_at,(f.recovery_epoch=s.recovery_epoch) AS epoch_valid FROM oauth_refresh_families f CROSS JOIN system_state s WHERE f.authorization_id=? AND s.id=1 FOR UPDATE OF f",
                      (rs, row) ->
                          new LockedFamily(
                              new Family(
                                  rs.getString(1),
                                  rs.getObject(2, UUID.class),
                                  rs.getString(3),
                                  rs.getTimestamp(4).toInstant()),
                              rs.getTimestamp(5) != null,
                              rs.getBoolean(6)),
                      candidate.authorizationId());
              if (families.isEmpty()) return rejected(RefreshRejectedException.Reason.INVALID);
              LockedFamily locked = families.getFirst();
              Family family = locked.family();
              if (locked.revoked() || !locked.epochValid())
                return rejected(RefreshRejectedException.Reason.REVOKED);
              Instant now = Instant.now();
              if (!family.expiresAt().isAfter(now))
                return rejected(RefreshRejectedException.Reason.EXPIRED);
              var tokens =
                  jdbc.query(
                      "SELECT expires_at,consumed_at FROM oauth_refresh_tokens WHERE token_hash=? AND authorization_id=?",
                      (rs, row) ->
                          new Instant[] {
                            rs.getTimestamp(1).toInstant(),
                            rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()
                          },
                      hash,
                      family.authorizationId());
              if (tokens.isEmpty()) return rejected(RefreshRejectedException.Reason.INVALID);
              Instant[] stored = tokens.getFirst();
              if (stored[1] != null) {
                jdbc.update(
                    "UPDATE oauth_refresh_families SET revoked_at=now() WHERE authorization_id=?",
                    family.authorizationId());
                return rejected(RefreshRejectedException.Reason.REPLAY);
              }
              if (!stored[0].isAfter(now)) return rejected(RefreshRejectedException.Reason.EXPIRED);
              jdbc.update(
                  "UPDATE oauth_refresh_tokens SET consumed_at=now() WHERE token_hash=?", hash);
              Rotation<T> rotation =
                  Objects.requireNonNull(
                      mutateAuthorization.apply(family),
                      "Framework mutation must return a rotation");
              String successorHash = tokenHash(rotation.successorToken());
              if (hash.equals(successorHash) || !rotation.successorExpiresAt().isAfter(now))
                throw new IllegalStateException(
                    "Framework mutation did not rotate the refresh token");
              jdbc.update(
                  "INSERT INTO oauth_refresh_tokens(token_hash,authorization_id,expires_at) VALUES (?,?,?)",
                  successorHash,
                  family.authorizationId(),
                  Timestamp.from(earlier(rotation.successorExpiresAt(), family.expiresAt())));
              return new Attempt<>(Objects.requireNonNull(rotation.response()), null);
            });
    if (outcome == null) throw new IllegalStateException("Refresh transaction returned no outcome");
    if (outcome.rejection() != null) throw new RefreshRejectedException(outcome.rejection());
    return outcome.response();
  }

  /** One-statement check for reference-token authentication; no process-local validity cache. */
  public boolean isActive(String authorizationId) {
    Boolean enabled =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM oauth_refresh_families f JOIN accounts a ON a.id=f.account_id CROSS JOIN system_state s WHERE f.authorization_id=? AND a.enabled AND f.revoked_at IS NULL AND f.expires_at>now() AND f.recovery_epoch=s.recovery_epoch AND s.id=1)",
            Boolean.class,
            authorizationId);
    return Boolean.TRUE.equals(enabled);
  }

  public void revoke(UUID accountId, String authorizationId) {
    revoke(accountId, authorizationId, () -> {});
  }

  public void revoke(UUID accountId, String authorizationId, Runnable afterLock) {
    transaction.executeWithoutResult(
        status -> {
          var registrations =
              jdbc.queryForList(
                  "SELECT client_id FROM oauth_refresh_families WHERE authorization_id=? AND account_id=?",
                  String.class,
                  authorizationId,
                  accountId);
          if (registrations.isEmpty()) return;
          clients.lock(registrations.getFirst());
          lockAuthorization(authorizationId);
          lockEnabledAccount(accountId);
          afterLock.run();
          jdbc.update(
              "UPDATE oauth_refresh_families SET revoked_at=COALESCE(revoked_at,now()) WHERE authorization_id=? AND account_id=?",
              authorizationId,
              accountId);
        });
  }

  /** Serializes code exchange and refresh for the same grant across application processes. */
  void lockAuthorization(String authorizationId) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Grant lock requires an active transaction");
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, authorizationId);
  }

  void invalidateAuthorization(String authorizationId) {
    lockAuthorization(authorizationId);
    jdbc.update(
        "UPDATE oauth_refresh_families SET revoked_at=COALESCE(revoked_at,now()) WHERE authorization_id=?",
        authorizationId);
  }

  private boolean lockEnabledAccount(UUID accountId) {
    return jdbc
            .query(
                "SELECT enabled FROM accounts WHERE id=? FOR SHARE",
                (rs, row) -> rs.getBoolean(1),
                accountId)
            .stream()
            .findFirst()
            .orElse(false)
        && accountCurrent(accountId);
  }

  boolean accountCurrent(UUID accountId) {
    return accounts.isClientAccessCurrent(accountId);
  }

  private static Instant earlier(Instant first, Instant second) {
    return first.isBefore(second) ? first : second;
  }

  private static <T> Attempt<T> rejected(RefreshRejectedException.Reason reason) {
    return new Attempt<>(null, reason);
  }

  private static String tokenHash(String token) {
    if (token == null || token.isBlank() || token.length() > 4096)
      throw new RefreshRejectedException(RefreshRejectedException.Reason.INVALID);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("Required SHA-256 unavailable", ex);
    }
  }
}
