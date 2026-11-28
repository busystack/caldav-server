package org.busystack.caldav.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Serializes navigation updates across JDBC-session wrappers and across application nodes. */
@Component
public class BrowserFlowStore {
  private static final String SCOPE = "SPRING_SECURITY_BUSYSTACK_NAVIGATION_SCOPE";
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public BrowserFlowStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactions);
  }

  public <T> T read(HttpServletRequest request, String section, Class<T> type, Supplier<T> empty) {
    String scope = scope(request, false);
    return scope == null ? empty.get() : read(scope, section, type, empty);
  }

  public <T, R> R update(
      HttpServletRequest request,
      boolean create,
      String section,
      Class<T> type,
      Supplier<T> empty,
      Function<T, R> change) {
    String scope = scope(request, create);
    if (scope == null) return change.apply(empty.get());
    return transactions.execute(
        status -> {
          jdbc.query(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
              rs -> {},
              "busystack browser flow:" + section + ":" + scope);
          T value = read(scope, section, type, empty);
          R answer = change.apply(value);
          jdbc.update(
              """
          INSERT INTO browser_flow_state(scope,section,payload,expires_at)
          VALUES (?,?,?,now()+interval '15 minutes')
          ON CONFLICT (scope,section) DO UPDATE
          SET payload=excluded.payload,expires_at=excluded.expires_at
          """,
              scope,
              section,
              JsonMapper.shared().writeValueAsString(value));
          return answer;
        });
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
  void expire() {
    jdbc.update(
        """
          DELETE FROM browser_flow_state
          WHERE (scope, section) IN (
            SELECT scope, section FROM browser_flow_state WHERE expires_at<=now()
            ORDER BY expires_at LIMIT 2048 FOR UPDATE SKIP LOCKED
          )
          """);
  }

  private <T> T read(String scope, String section, Class<T> type, Supplier<T> empty) {
    return jdbc.query(
        """
        SELECT payload FROM browser_flow_state
        WHERE scope=? AND section=? AND expires_at>now()
        """,
        rs -> rs.next() ? JsonMapper.shared().readValue(rs.getString(1), type) : empty.get(),
        scope,
        section);
  }

  static String scope(HttpServletRequest request, boolean create) {
    var session = request.getSession(create);
    if (session == null) return null;
    if (session.getAttribute(SCOPE) instanceof String scope) return scope;
    // Concurrent first requests from an existing session derive the same scope.
    // Spring's newSession fixation strategy migrates Spring Security's attributes.
    String scope = fingerprint(session.getId());
    if (create) session.setAttribute(SCOPE, scope);
    return scope;
  }

  public static boolean current(Instant expires) {
    return expires.isAfter(Instant.now());
  }

  public static String fingerprint(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
