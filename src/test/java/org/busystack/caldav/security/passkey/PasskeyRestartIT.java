package org.busystack.caldav.security.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.busystack.caldav.BusyStackApplication;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.testing.SignedPasskeyClient;
import org.busystack.caldav.testing.TestTls;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A new production application JVM must re-read and update verified passkey state correctly. */
@Testcontainers
class PasskeyRestartIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @Test
  void verifiedBackupStatePersistsAcrossApplicationProcessRestartAndContinuesBothDirections()
      throws Exception {
    var database =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    var arguments =
        new ArrayList<>(
            List.of(
                "--server.port=0",
                "--busystack.public-base-url=https://localhost/team",
                "--busystack.issuer=https://localhost/team",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.ssl.enabled=true",
                "--server.ssl.key-store=" + TestTls.instance().keyStore(),
                "--server.ssl.key-store-password=" + TestTls.instance().password()));
    SignedPasskeyClient.Credential key;
    try (var first =
        new SpringApplicationBuilder(BusyStackApplication.class)
            .run(arguments.toArray(String[]::new))) {
      first
          .getBean(AccountService.class)
          .provision("restart-passkey", "Restart passkey", "Independent restart password", false);
      int port = ((WebServerApplicationContext) first).getWebServer().getPort();
      try (var transport = new SignedPasskeyClient(port, "/team", "https://localhost")) {
        key =
            transport.enroll(
                transport.passwordLogin("restart-passkey", "Independent restart password"));
        assertThat(
                transport
                    .assertCredential(transport.options(transport.browser()), key, "correct", 0x1d)
                    .statusCode())
            .isEqualTo(200);
        assertThat(state(database, key)).isTrue();
      }
    }
    // The signing key remains only in this synthetic authenticator's memory. The server starts
    // from database state in a distinct JVM, without re-enrollment or a seeded authentication.
    int port;
    try (var reserved =
        new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      port = reserved.getLocalPort();
    }
    arguments.set(0, "--server.port=" + port);
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-cp",
                System.getProperty("java.class.path"),
                BusyStackApplication.class.getName()));
    command.addAll(arguments);
    Path log = Path.of("target/qualification/passkey-process-restart.log");
    Files.createDirectories(log.getParent());
    Process child =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    var transport = new SignedPasskeyClient(port, "/team", "https://localhost");
    try {
      awaitReady(child, transport, log);
      assertThat(state(database, key)).isTrue();
      long previous = counter(database, key);
      assertThat(
              transport
                  .assertCredential(transport.options(transport.browser()), key, "correct", 0x0d)
                  .statusCode())
          .isEqualTo(200);
      assertThat(state(database, key)).isFalse();
      assertThat(counter(database, key)).isGreaterThan(previous);
      previous = counter(database, key);
      assertThat(
              transport
                  .assertCredential(
                      transport.options(transport.browser()), key, "invalid-signature", 0x1d)
                  .statusCode())
          .isEqualTo(401);
      assertThat(state(database, key)).isFalse();
      assertThat(counter(database, key)).isEqualTo(previous);
    } finally {
      transport.close();
      child.destroy();
      if (!child.waitFor(30, TimeUnit.SECONDS)) {
        child.destroyForcibly();
        assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static boolean state(JdbcTemplate jdbc, SignedPasskeyClient.Credential key) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT backup_state FROM user_credentials WHERE credential_id=?",
            Boolean.class,
            key.id()));
  }

  private static long counter(JdbcTemplate jdbc, SignedPasskeyClient.Credential key) {
    return jdbc.queryForObject(
        "SELECT signature_count FROM user_credentials WHERE credential_id=?", Long.class, key.id());
  }

  private static void awaitReady(Process child, SignedPasskeyClient transport, Path log)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
    try (var browser = transport.browser()) {
      while (System.nanoTime() < deadline) {
        assertThat(child.isAlive()).as(Files.readString(log)).isTrue();
        try {
          if (transport.get(browser, "/actuator/health/readiness").statusCode() == 200) return;
        } catch (java.io.IOException starting) {
          // Readiness is polled against the actual new TLS listener, without weakening trust.
        }
        Thread.sleep(100);
      }
    }
    throw new AssertionError("Restarted server did not become ready: " + Files.readString(log));
  }
}
