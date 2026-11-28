package org.busystack.caldav.security.session;

import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.busystack.caldav.security.BrowserFlowStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.stereotype.Component;

/** A completed browser session is backed by a specific accepted, still-owned assertion key. */
@Component
public class BrowserSession {
  public static final Duration MAX_AGE = Duration.ofHours(8);
  private static final String PROOF = BrowserSession.class.getName() + ".proof";
  public static final String CURRENT = BrowserSession.class.getName() + ".current";

  public enum State {
    INCOMPLETE,
    CURRENT,
    KEY_REMOVED,
    EXPIRED
  }

  public record Proof(UUID account, UUID epoch, String key, Instant established, Instant asserted)
      implements Serializable {}

  private final JdbcTemplate jdbc;
  private final SessionSecurity sessions;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public BrowserSession(JdbcTemplate jdbc, SessionSecurity sessions) {
    this(jdbc, sessions, Clock.systemUTC());
  }

  BrowserSession(JdbcTemplate jdbc, SessionSecurity sessions, Clock clock) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.clock = clock;
  }

  public void complete(
      HttpServletRequest request, Authentication authentication, String verifiedKey) {
    var account = sessions.assertCurrent(authentication);
    Instant assertion = assertion(authentication);
    if (assertion == null
        || assertion.isAfter(clock.instant())
        || verifiedKey == null
        || !owns(account.id(), verifiedKey))
      throw new org.springframework.security.access.AccessDeniedException(
          "Verified passkey unavailable");
    var session = request.getSession();
    Object prior = session.getAttribute(PROOF);
    Instant established =
        prior instanceof Proof proof
                && proof.account().equals(account.id())
                && proof.epoch().equals(account.epoch())
            ? proof.established()
            : assertion;
    session.setAttribute(
        PROOF, new Proof(account.id(), account.epoch(), verifiedKey, established, assertion));
  }

  public State state(HttpServletRequest request, Authentication authentication) {
    var session = request.getSession(false);
    if (session == null || !(session.getAttribute(PROOF) instanceof Proof proof))
      return State.INCOMPLETE;
    Instant now = clock.instant();
    if (proof.established().isAfter(now) || !proof.established().plus(MAX_AGE).isAfter(now))
      return State.EXPIRED;
    var account = sessions.assertCurrent(authentication);
    if (!proof.account().equals(account.id())
        || !proof.epoch().equals(account.epoch())
        || !proof.asserted().equals(assertion(authentication))) {
      session.removeAttribute(PROOF);
      return State.INCOMPLETE;
    }
    if (!owns(account.id(), proof.key())) {
      session.removeAttribute(PROOF);
      return State.KEY_REMOVED;
    }
    return State.CURRENT;
  }

  public static boolean completed(HttpServletRequest request) {
    return Boolean.TRUE.equals(request.getAttribute(CURRENT));
  }

  public Proof requireProof(HttpServletRequest request, Authentication authentication) {
    if (state(request, authentication) != State.CURRENT)
      throw new org.springframework.security.access.AccessDeniedException(
          "Current passkey verification is required");
    return (Proof) request.getSession(false).getAttribute(PROOF);
  }

  /** Called with the account locked in the privileged transaction. */
  public void lockLinkProof(
      HttpServletRequest request,
      Authentication authentication,
      String sessionBinding,
      Proof bound) {
    var session = request.getSession(false);
    if (session == null
        || !sessionBinding.equals(BrowserFlowStore.fingerprint(session.getId()))
        || !bound.equals(requireProof(request, authentication)))
      throw new org.springframework.security.access.AccessDeniedException(
          "Link verification ended");
    var current =
        jdbc.queryForList(
            "SELECT session_id FROM spring_session WHERE session_id=? AND expiry_time>? FOR UPDATE",
            String.class,
            session.getId(),
            clock.millis());
    var keys =
        jdbc.queryForList(
            """
        SELECT c.credential_id FROM user_credentials c
        JOIN user_entities u ON u.id=c.user_entity_user_id
        WHERE c.credential_id=? AND u.account_id=? FOR UPDATE OF c
        """,
            String.class,
            bound.key(),
            bound.account());
    if (current.isEmpty() || keys.isEmpty())
      throw new org.springframework.security.access.AccessDeniedException(
          "Link verification ended");
  }

  public Instant overallDeadline(HttpServletRequest request) {
    var session = request.getSession(false);
    return session != null && session.getAttribute(PROOF) instanceof Proof proof
        ? proof.established().plus(MAX_AGE)
        : null;
  }

  private boolean owns(UUID account, String key) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM user_credentials c JOIN user_entities u ON u.id=c.user_entity_user_id WHERE c.credential_id=? AND u.account_id=?)",
            Boolean.class,
            key,
            account));
  }

  private static Instant assertion(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .filter(FactorGrantedAuthority.class::isInstance)
        .map(FactorGrantedAuthority.class::cast)
        .filter(factor -> FactorGrantedAuthority.WEBAUTHN_AUTHORITY.equals(factor.getAuthority()))
        .map(FactorGrantedAuthority::getIssuedAt)
        .max(Instant::compareTo)
        .orElse(null);
  }
}
