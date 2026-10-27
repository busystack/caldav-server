package org.busystack.caldav.security.passkey;

import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.transaction.annotation.Transactional;

/** Spring's JDBC representation with immutable ownership/key and monotonic counters. */
public class AccountPasskeyCredentials implements UserCredentialRepository {
  private final JdbcTemplate jdbc;
  private final JdbcUserCredentialRepository delegate;

  public AccountPasskeyCredentials(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.delegate = new JdbcUserCredentialRepository(jdbc);
  }

  @Override
  public CredentialRecord findByCredentialId(Bytes id) {
    return delegate.findByCredentialId(id);
  }

  @Override
  public List<CredentialRecord> findByUserId(Bytes id) {
    return delegate.findByUserId(id);
  }

  @Override
  @Transactional
  public void save(CredentialRecord record) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        rs -> {},
        record.getCredentialId().toBase64UrlString());
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM passkey_revocations WHERE credential_id=?)",
            Boolean.class,
            record.getCredentialId().toBase64UrlString())))
      throw new BadCredentialsException("Credential revoked");
    var old = delegate.findByCredentialId(record.getCredentialId());
    if (old != null
        && (!old.getUserEntityUserId().equals(record.getUserEntityUserId())
            || !Arrays.equals(old.getPublicKey().getBytes(), record.getPublicKey().getBytes())
            || !old.getCreated().equals(record.getCreated())
            || old.getSignatureCount() > record.getSignatureCount()))
      throw new BadCredentialsException("Credential state changed");
    delegate.save(record);
  }

  @Override
  @Transactional
  public void delete(Bytes id) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, id.toBase64UrlString());
    jdbc.update(
        "INSERT INTO passkey_revocations(credential_id) VALUES (?) ON CONFLICT DO NOTHING",
        id.toBase64UrlString());
    delegate.delete(id);
  }
}
