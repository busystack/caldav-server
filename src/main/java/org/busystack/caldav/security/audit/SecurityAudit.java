package org.busystack.caldav.security.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores bounded structured security metadata; no credentials, assertion bodies, or free text. */
@Service
public class SecurityAudit {
  public enum Action {
    PASSWORD_LOGIN,
    PASSWORD_CHANGED,
    ACCOUNT_DISABLED,
    OFFLINE_RECOVERY,
    PASSKEY_LOGIN,
    SESSION_REVOKED,
    RECOVERY_CODES_ISSUED,
    RECOVERY_USED
  }

  public enum Outcome {
    SUCCESS,
    FAILURE,
    THROTTLED
  }

  private final JdbcTemplate jdbc;

  public SecurityAudit(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void record(UUID account, Action action, Outcome outcome, String peer) {
    String peerHash =
        peer == null
            ? null
            : SecurityHashes.sha256(peer.length() > 128 ? peer.substring(0, 128) : peer);
    jdbc.update(
        "INSERT INTO security_audit(account_id,action,outcome,peer_hash) VALUES (?,?,?,?)",
        account,
        action.name(),
        outcome.name(),
        peerHash);
    jdbc.update(
        "DELETE FROM security_audit WHERE id <= (SELECT id FROM security_audit ORDER BY id DESC OFFSET 9999 LIMIT 1)");
  }

  @Scheduled(fixedDelay = 60000)
  @Transactional
  public void prune() {
    jdbc.update("DELETE FROM security_audit WHERE occurred_at < now()-interval '30 days'");
    jdbc.update("DELETE FROM login_throttle WHERE expires_at < now()");
    jdbc.update("DELETE FROM webauthn_ceremonies WHERE expires_at < now()");
  }
}
