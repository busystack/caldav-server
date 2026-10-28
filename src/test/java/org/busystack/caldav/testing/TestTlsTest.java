package org.busystack.caldav.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;

class TestTlsTest {
  @Test
  void disposableAuthorityHasCriticalCertificateSigningUsage() throws Exception {
    var tls = TestTls.instance();
    var store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(Path.of(URI.create(tls.keyStore())))) {
      store.load(input, tls.password().toCharArray());
    }
    var authority = (X509Certificate) store.getCertificate("ca");
    assertThat(authority.getBasicConstraints()).isGreaterThanOrEqualTo(0);
    assertThat(authority.getCriticalExtensionOIDs()).contains("2.5.29.15");
    assertThat(authority.getKeyUsage()[5]).isTrue();
    assertThat(authority.getKeyUsage()[6]).isTrue();
    var server = (X509Certificate) store.getCertificate("server");
    server.verify(authority.getPublicKey());
    assertThat(server.getBasicConstraints()).isEqualTo(-1);
    assertThat(server.getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.1");
  }
}
