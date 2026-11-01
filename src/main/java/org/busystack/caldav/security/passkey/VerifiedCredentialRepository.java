package org.busystack.caldav.security.passkey;

import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/** Holds a framework credential update until the application's entire RP decision succeeds. */
final class VerifiedCredentialRepository implements UserCredentialRepository {
  private final AccountPasskeyCredentials delegate;
  private final ThreadLocal<VerificationUpdate> verifications = new ThreadLocal<>();

  VerifiedCredentialRepository(AccountPasskeyCredentials delegate) {
    this.delegate = delegate;
  }

  VerificationUpdate verification(Bytes credentialId) {
    if (verifications.get() != null) throw new IllegalStateException("Nested passkey verification");
    var update = new VerificationUpdate(credentialId);
    verifications.set(update);
    return update;
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
  public void save(CredentialRecord record) {
    var verification = verifications.get();
    if (verification == null) delegate.save(record);
    else {
      if (verification.record != null || !verification.id.equals(record.getCredentialId()))
        throw new BadCredentialsException("Unexpected credential update");
      verification.record = record;
    }
  }

  @Override
  public void delete(Bytes id) {
    delegate.delete(id);
  }

  final class VerificationUpdate implements AutoCloseable {
    private final Bytes id;
    private CredentialRecord record;

    private VerificationUpdate(Bytes id) {
      this.id = id;
    }

    CredentialRecord verifiedRecord() {
      if (record == null) throw new BadCredentialsException("Missing credential update");
      return record;
    }

    @Override
    public void close() {
      verifications.remove();
    }
  }
}
