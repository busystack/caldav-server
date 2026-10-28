package org.busystack.caldav.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Disposable CA and localhost certificate; production trust settings are never changed. */
public final class TestTls {
  private static final TestTls INSTANCE = create();
  private final Path serverStore;
  private final SSLContext context;

  private TestTls(Path serverStore, SSLContext context) {
    this.serverStore = serverStore;
    this.context = context;
  }

  public static TestTls instance() {
    return INSTANCE;
  }

  public String keyStore() {
    return serverStore.toUri().toString();
  }

  public SSLContext context() {
    return context;
  }

  public String password() {
    return "test-only-keystore-password";
  }

  private static TestTls create() {
    try {
      Path dir = Files.createTempDirectory("busystack-test-tls-");
      Path ca = dir.resolve("ca.p12"),
          server = dir.resolve("server.p12"),
          caCert = dir.resolve("ca.pem"),
          csr = dir.resolve("server.csr"),
          signed = dir.resolve("server.pem");
      String password = "test-only-keystore-password";
      keytool(
          "-genkeypair",
          "-alias",
          "ca",
          "-dname",
          "CN=BusyStack disposable test CA",
          "-keyalg",
          "EC",
          "-groupname",
          "secp256r1",
          "-ext",
          "bc:c=ca:true",
          "-ext",
          "ku:c=keyCertSign,cRLSign",
          "-validity",
          "7",
          "-keystore",
          ca.toString(),
          "-storepass",
          password,
          "-noprompt");
      keytool(
          "-exportcert",
          "-alias",
          "ca",
          "-keystore",
          ca.toString(),
          "-storepass",
          password,
          "-rfc",
          "-file",
          caCert.toString());
      keytool(
          "-genkeypair",
          "-alias",
          "server",
          "-dname",
          "CN=localhost",
          "-keyalg",
          "EC",
          "-groupname",
          "secp256r1",
          "-validity",
          "7",
          "-keystore",
          server.toString(),
          "-storepass",
          password,
          "-noprompt");
      keytool(
          "-certreq",
          "-alias",
          "server",
          "-keystore",
          server.toString(),
          "-storepass",
          password,
          "-file",
          csr.toString());
      keytool(
          "-gencert",
          "-alias",
          "ca",
          "-keystore",
          ca.toString(),
          "-storepass",
          password,
          "-infile",
          csr.toString(),
          "-outfile",
          signed.toString(),
          "-rfc",
          "-validity",
          "7",
          "-ext",
          "san=dns:localhost,ip:127.0.0.1",
          "-ext",
          "eku=serverAuth",
          "-ext",
          "ku=digitalSignature");
      keytool(
          "-importcert",
          "-alias",
          "ca",
          "-keystore",
          server.toString(),
          "-storepass",
          password,
          "-file",
          caCert.toString(),
          "-noprompt");
      keytool(
          "-importcert",
          "-alias",
          "server",
          "-keystore",
          server.toString(),
          "-storepass",
          password,
          "-file",
          signed.toString(),
          "-noprompt");
      var trusted = KeyStore.getInstance("PKCS12");
      trusted.load(null, null);
      try (var input = Files.newInputStream(caCert)) {
        trusted.setCertificateEntry(
            "test-ca",
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input));
      }
      var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(trusted);
      var context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      for (Path path : List.of(ca, server, caCert, csr, signed)) path.toFile().deleteOnExit();
      dir.toFile().deleteOnExit();
      return new TestTls(server, context);
    } catch (Exception ex) {
      throw new IllegalStateException("Could not create isolated TLS fixture", ex);
    }
  }

  private static void keytool(String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(arguments));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    if (process.waitFor() != 0)
      throw new IllegalStateException("Test certificate generation failed: " + output);
  }
}
