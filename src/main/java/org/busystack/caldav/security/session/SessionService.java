package org.busystack.caldav.security.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
  public record SessionView(
      UUID id, Instant createdAt, Instant lastAccessAt, Instant expiresAt, boolean current) {}

  private final JdbcTemplate jdbc;
  private final SessionSecurity security;
  private final SecurityAudit audit;

  public SessionService(JdbcTemplate jdbc, SessionSecurity security, SecurityAudit audit) {
    this.jdbc = jdbc;
    this.security = security;
    this.audit = audit;
  }

  public List<SessionView> list(Authentication authentication, String currentId) {
    var account = security.assertCurrent(authentication);
    return jdbc.query(
        "SELECT primary_id,creation_time,last_access_time,expiry_time,session_id FROM spring_session WHERE principal_name=? AND expiry_time>? ORDER BY last_access_time DESC",
        (rs, row) ->
            new SessionView(
                UUID.fromString(rs.getString(1)),
                Instant.ofEpochMilli(rs.getLong(2)),
                Instant.ofEpochMilli(rs.getLong(3)),
                Instant.ofEpochMilli(rs.getLong(4)),
                rs.getString(5).equals(currentId)),
        account.username(),
        System.currentTimeMillis());
  }

  @Transactional
  public boolean revoke(
      Authentication authentication, UUID primaryId, String currentId, String peer) {
    RecentAuthentication.requireMfa(authentication);
    var account = security.assertCurrent(authentication);
    var deleted =
        jdbc.query(
            "DELETE FROM spring_session WHERE primary_id=? AND principal_name=? RETURNING session_id",
            (rs, row) -> rs.getString(1),
            primaryId.toString(),
            account.username());
    if (!deleted.isEmpty())
      audit.record(
          account.id(), SecurityAudit.Action.SESSION_REVOKED, SecurityAudit.Outcome.SUCCESS, peer);
    return !deleted.isEmpty() && deleted.getFirst().equals(currentId);
  }
}
