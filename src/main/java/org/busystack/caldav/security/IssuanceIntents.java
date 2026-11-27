package org.busystack.caldav.security;

import java.util.UUID;
import org.busystack.caldav.security.session.SessionSecurity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A receipt and its credential mutation share one database transaction. */
@Service
public class IssuanceIntents {
  public enum Purpose {
    APPLICATION_PASSWORD,
    RECOVERY_CODES
  }

  public record Intent(UUID id, UUID expectedGeneration) {}

  public record Claim(UUID id, boolean consumed, UUID result, UUID expectedGeneration) {}

  public static final class Unavailable extends RuntimeException {
    private final boolean busy;

    Unavailable(boolean busy, String message) {
      super(message);
      this.busy = busy;
    }

    public boolean busy() {
      return busy;
    }
  }

  private final JdbcTemplate jdbc;
  private final SessionSecurity sessions;

  public IssuanceIntents(JdbcTemplate jdbc, SessionSecurity sessions) {
    this.jdbc = jdbc;
    this.sessions = sessions;
  }

  @Transactional
  public Intent prepare(Authentication auth, Purpose purpose, String context) {
    var account = sessions.assertForReset(auth);
    jdbc.query("SELECT pg_advisory_xact_lock(912714321018)", rs -> {});
    jdbc.update("DELETE FROM browser_issuance_intents WHERE expires_at <= clock_timestamp()");
    jdbc.update(
        "DELETE FROM browser_issuance_intents WHERE account_id=? AND security_epoch<>?",
        account.id(),
        account.epoch());
    if (count("SELECT count(*) FROM browser_issuance_intents") >= 8192
        || count(
                "SELECT count(*) FROM browser_issuance_intents WHERE account_id=? AND consumed_at IS NULL",
                account.id())
            >= 8)
      throw new Unavailable(
          true, "Issuance is busy. Use a form you already opened or try again after it expires.");
    UUID id = UUID.randomUUID();
    UUID generation = purpose == Purpose.RECOVERY_CODES ? recoveryGeneration(account.id()) : null;
    jdbc.update(
        """
        INSERT INTO browser_issuance_intents(id,account_id,security_epoch,purpose,context,expected_generation,expires_at)
        VALUES (?,?,?,?,?,?,clock_timestamp() + interval '15 minutes')
        """,
        id,
        account.id(),
        account.epoch(),
        purpose.name(),
        context,
        generation);
    return new Intent(id, generation);
  }

  public Claim claim(SessionSecurity.Account account, UUID id, Purpose purpose, String context) {
    requireTransaction();
    if (id == null) throw expired();
    var rows =
        jdbc.query(
            """
        SELECT id,consumed_at,result_id,expected_generation FROM browser_issuance_intents
        WHERE id=? AND account_id=? AND security_epoch=? AND purpose=? AND context=?
          AND expires_at > clock_timestamp()
        FOR UPDATE
        """,
            (rs, row) ->
                new Claim(
                    rs.getObject(1, UUID.class),
                    rs.getTimestamp(2) != null,
                    rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class)),
            id,
            account.id(),
            account.epoch(),
            purpose.name(),
            context);
    if (rows.isEmpty()) throw expired();
    return rows.getFirst();
  }

  public void consume(Claim claim, UUID result) {
    requireTransaction();
    if (jdbc.update(
            """
        UPDATE browser_issuance_intents SET consumed_at=clock_timestamp(),result_id=?
        WHERE id=? AND consumed_at IS NULL
        """,
            result,
            claim.id())
        != 1) throw new IllegalStateException("Issuance claim lost");
  }

  public UUID recoveryGeneration(UUID account) {
    return jdbc.queryForObject(
        "SELECT recovery_code_generation FROM accounts WHERE id=?", UUID.class, account);
  }

  public static Unavailable expired() {
    return new Unavailable(
        false,
        "This issuance request is no longer available. Review your existing settings before starting again.");
  }

  public static Unavailable changed() {
    return new Unavailable(
        false,
        "A different set of recovery codes was generated. Review the current set before confirming a replacement.");
  }

  private long count(String sql, Object... values) {
    return java.util.Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, values));
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Issuance intent requires a credential transaction");
  }
}
