package org.busystack.caldav.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.busystack.caldav.security.password.PasswordPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local issuance and a bounded, transactional browser claim. Never logs supplied values. */
@Service
public class InitializationService {
  private final JdbcTemplate jdbc;
  private final AccountService accounts;

  public InitializationService(JdbcTemplate jdbc, AccountService accounts) {
    this.jdbc = jdbc;
    this.accounts = accounts;
  }

  public boolean available() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT NOT initialized FROM installation_state WHERE id=1", Boolean.class));
  }

  @Transactional
  public String issueCode() {
    lock();
    if (!available())
      throw new IllegalStateException("Administrator initialization is unavailable");
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    jdbc.update(
        "UPDATE installation_state SET code_verifier=?,expires_at=?,failed_attempts=0 WHERE id=1",
        verifier(code),
        Timestamp.from(Instant.now().plusSeconds(900)));
    return code;
  }

  /** Invalid attempts return normally so their durable counters are committed. */
  @Transactional
  public Map<String, String> initialize(
      String code, String username, String password, String confirmation) {
    lock();
    var row = jdbc.queryForMap("SELECT * FROM installation_state WHERE id=1");
    byte[] expected = (byte[]) row.get("code_verifier");
    boolean matches =
        code != null
            && code.matches("[A-Za-z0-9_-]{43}")
            && expected != null
            && MessageDigest.isEqual(expected, verifier(code));
    if (Boolean.TRUE.equals(row.get("initialized"))
        || !matches
        || !((Timestamp) row.get("expires_at")).toInstant().isAfter(Instant.now())) {
      jdbc.update(
          "UPDATE installation_state SET failed_attempts=LEAST(5,failed_attempts+1),"
              + "code_verifier=CASE WHEN failed_attempts>=4 THEN NULL ELSE code_verifier END,"
              + "expires_at=CASE WHEN failed_attempts>=4 THEN NULL ELSE expires_at END WHERE id=1");
      return Map.of(
          "code", "Initialization code is unavailable. Obtain a fresh code locally and try again.");
    }
    Map<String, String> errors = new LinkedHashMap<>();
    if (!AccountService.validUsername(username))
      errors.put(
          "username",
          "Use 1–64 letters, numbers, dots, underscores or hyphens, starting with a letter or number.");
    try {
      PasswordPolicy.validate(password);
    } catch (IllegalArgumentException invalid) {
      errors.put("password", invalid.getMessage());
    }
    if (password == null || !password.equals(confirmation))
      errors.put("confirmation", "The passwords must match.");
    if (!errors.isEmpty()) return errors;
    var id = accounts.provision(username, username, password, true);
    jdbc.update(
        "UPDATE installation_state SET initial_administrator=?,code_verifier=NULL,expires_at=NULL WHERE id=1",
        id);
    return Map.of();
  }

  private void lock() {
    jdbc.queryForObject("SELECT id FROM installation_state WHERE id=1 FOR UPDATE", Integer.class);
  }

  private static byte[] verifier(String code) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(("busystack-initialization-v1:" + code).getBytes(StandardCharsets.US_ASCII));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
