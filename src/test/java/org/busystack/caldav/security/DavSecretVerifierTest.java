package org.busystack.caldav.security;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class DavSecretVerifierTest {
  final DavSecretVerifier verifier = new DavSecretVerifier(new BCryptPasswordEncoder(12));

  @Test
  void randomSecretVerifiersAreVersionedDomainAndCredentialBoundAndRejectUnknownFormats() {
    UUID id = UUID.randomUUID();
    String secret = "test-only-random-secret-256-bits-placeholder";
    String stored = verifier.encode(id, secret);
    assertThat(stored).startsWith(DavSecretVerifier.PREFIX).doesNotContain(secret);
    assertThat(verifier.matches(id, secret, stored)).isTrue();
    assertThat(verifier.matches(UUID.randomUUID(), secret, stored)).isFalse();
    assertThat(verifier.matches(id, secret + "wrong", stored)).isFalse();
    for (String malformed :
        new String[] {
          "{dav-sha256-v2}abc",
          DavSecretVerifier.PREFIX + "x".repeat(64),
          DavSecretVerifier.PREFIX + "abc",
          "{noop}" + secret
        }) assertThat(verifier.matches(id, secret, malformed)).isFalse();
  }
}
