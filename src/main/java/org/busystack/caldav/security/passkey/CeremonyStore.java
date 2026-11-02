package org.busystack.caldav.security.passkey;

import java.util.UUID;
import org.busystack.caldav.security.session.SessionSecurity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable single-use guard around the framework's session-bound, cryptographic challenge check. */
@Service
public class CeremonyStore {
  public enum Purpose {
    AUTHENTICATE,
    REGISTER
  }

  private final JdbcTemplate jdbc;
  private final SessionSecurity sessions;

  public CeremonyStore(JdbcTemplate jdbc, SessionSecurity sessions) {
    this.jdbc = jdbc;
    this.sessions = sessions;
  }

  @Transactional
  public void issue(
      String sessionId, Purpose purpose, String challengeHash, Authentication authentication) {
    SessionSecurity.Account account = null;
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken))
      account = sessions.assertCurrent(authentication);
    if (purpose == Purpose.REGISTER && account == null)
      throw new org.springframework.security.access.AccessDeniedException(
          "Primary authentication required");
    jdbc.update(
        "INSERT INTO webauthn_ceremonies(id,session_id,purpose,account_id,security_epoch,recovery_epoch,challenge_hash,expires_at) SELECT ?,?,?,?,?,recovery_epoch,?,now()+interval '5 minutes' FROM system_state WHERE id=1 ON CONFLICT(session_id,purpose) DO UPDATE SET id=EXCLUDED.id,account_id=EXCLUDED.account_id,security_epoch=EXCLUDED.security_epoch,recovery_epoch=EXCLUDED.recovery_epoch,challenge_hash=EXCLUDED.challenge_hash,created_at=now(),expires_at=EXCLUDED.expires_at",
        UUID.randomUUID(),
        sessionId,
        purpose.name(),
        account == null ? null : account.id(),
        account == null ? null : account.epoch(),
        challengeHash);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean consume(String sessionId, Purpose purpose, String challengeHash) {
    if (sessionId == null || challengeHash == null) return false;
    var removed =
        jdbc.query(
            "DELETE FROM webauthn_ceremonies c USING system_state s WHERE c.session_id=? AND c.purpose=? AND c.challenge_hash=? AND c.expires_at>now() AND c.recovery_epoch=s.recovery_epoch AND s.id=1 AND (c.account_id IS NULL OR EXISTS(SELECT 1 FROM accounts a WHERE a.id=c.account_id AND a.enabled AND a.security_epoch=c.security_epoch)) RETURNING c.id",
            (rs, row) -> rs.getObject(1, UUID.class),
            sessionId,
            purpose.name(),
            challengeHash);
    return removed.size() == 1;
  }
}
