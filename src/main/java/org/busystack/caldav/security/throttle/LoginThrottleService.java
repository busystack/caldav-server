package org.busystack.caldav.security.throttle;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.security.audit.SecurityHashes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded, temporary account/connection-peer backoff; attempted blocked requests never extend it.
 */
@Service
public class LoginThrottleService {
  public record Decision(boolean allowed, long retryAfterSeconds) {}

  private record Limit(String key, int attempts, Duration window, Duration cooldown) {}

  private record Bucket(Instant started, int attempts, Instant blocked) {}

  private final JdbcTemplate jdbc;
  private final SecurityAudit audit;

  public LoginThrottleService(JdbcTemplate jdbc, SecurityAudit audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Transactional
  public Decision reserve(String username, String peer) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended('busystack.login-throttle.capacity',0))",
        rs -> {});
    jdbc.update("DELETE FROM login_throttle WHERE expires_at < now()");
    Instant now = jdbc.queryForObject("SELECT now()", (rs, row) -> rs.getTimestamp(1).toInstant());
    List<Limit> limits = new ArrayList<>();
    if (username != null && !username.isBlank())
      limits.add(new Limit(accountKey(username), 5, Duration.ofMinutes(5), Duration.ofSeconds(30)));
    limits.add(
        new Limit(
            SecurityHashes.sha256("peer:" + peer(peer)),
            30,
            Duration.ofMinutes(1),
            Duration.ofSeconds(60)));
    limits.sort(Comparator.comparing(Limit::key));
    long existing = jdbc.queryForObject("SELECT count(*) FROM login_throttle", Long.class);
    long required =
        limits.stream()
            .filter(
                limit ->
                    !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM login_throttle WHERE bucket_key=?)",
                            Boolean.class,
                            limit.key())))
            .count();
    if (existing + required > 10000) return new Decision(false, 60);
    long retry = 0;
    for (Limit limit : limits) {
      jdbc.update(
          "INSERT INTO login_throttle(bucket_key,window_started,attempts,expires_at) VALUES (?,?,0,?) ON CONFLICT DO NOTHING",
          limit.key(),
          Timestamp.from(now),
          Timestamp.from(now.plusSeconds(3600)));
      Bucket bucket =
          jdbc.queryForObject(
              "SELECT window_started,attempts,blocked_until FROM login_throttle WHERE bucket_key=? FOR UPDATE",
              (rs, row) ->
                  new Bucket(
                      rs.getTimestamp(1).toInstant(),
                      rs.getInt(2),
                      rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()),
              limit.key());
      if (bucket.blocked() != null && bucket.blocked().isAfter(now)) {
        retry =
            Math.max(retry, Math.max(1, Duration.between(now, bucket.blocked()).toSeconds() + 1));
        continue;
      }
      boolean reset =
          bucket.blocked() != null || !bucket.started().plus(limit.window()).isAfter(now);
      int attempted = reset ? 0 : bucket.attempts();
      Instant start = reset ? now : bucket.started();
      if (attempted >= limit.attempts()) {
        Instant blocked = now.plus(limit.cooldown());
        jdbc.update(
            "UPDATE login_throttle SET blocked_until=?,expires_at=? WHERE bucket_key=?",
            Timestamp.from(blocked),
            Timestamp.from(now.plusSeconds(3600)),
            limit.key());
        retry = Math.max(retry, limit.cooldown().toSeconds());
      } else
        jdbc.update(
            "UPDATE login_throttle SET window_started=?,attempts=?,blocked_until=NULL,expires_at=? WHERE bucket_key=?",
            Timestamp.from(start),
            attempted + 1,
            Timestamp.from(now.plusSeconds(3600)),
            limit.key());
    }
    return new Decision(retry == 0, retry);
  }

  @Transactional
  public void result(String username, String peer, SecurityAudit.Action action, boolean success) {
    List<UUID> accounts =
        username == null
            ? List.of()
            : jdbc.queryForList("SELECT id FROM accounts WHERE username=?", UUID.class, username);
    audit.record(
        accounts.isEmpty() ? null : accounts.getFirst(),
        action,
        success ? SecurityAudit.Outcome.SUCCESS : SecurityAudit.Outcome.FAILURE,
        peer(peer));
    if (success && username != null)
      jdbc.update("DELETE FROM login_throttle WHERE bucket_key=?", accountKey(username));
  }

  public void denied(String peer, SecurityAudit.Action action) {
    audit.record(null, action, SecurityAudit.Outcome.THROTTLED, peer(peer));
  }

  static String accountKey(String username) {
    String bounded = username.length() > 256 ? username.substring(0, 256) : username;
    return SecurityHashes.sha256(
        "account:"
            + Normalizer.normalize(bounded.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
  }

  private static String peer(String peer) {
    return peer == null ? "unknown" : peer.length() > 128 ? peer.substring(0, 128) : peer;
  }
}
