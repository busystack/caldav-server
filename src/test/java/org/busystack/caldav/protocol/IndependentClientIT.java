package org.busystack.caldav.protocol;

import static org.assertj.core.api.Assertions.*;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.testing.TestTls;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = "busystack.dav.allow-sync-depth-one=true")
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class IndependentClientIT {
  static final int PORT = port();

  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("server.port", () -> PORT);
    registry.add("busystack.public-base-url", () -> "https://localhost:" + PORT + "/team");
    registry.add("busystack.issuer", () -> "https://localhost:" + PORT + "/team");
    registry.add("server.ssl.enabled", () -> true);
    registry.add("server.ssl.key-store", () -> TestTls.instance().keyStore());
    registry.add("server.ssl.key-store-password", () -> TestTls.instance().password());
  }

  @Autowired AccountService accounts;

  @Test
  void independentPythonCalendarAndTaskClientUsesActualHttpsPostgresqlServer() throws Exception {
    var id =
        accounts.provision(
            "independent", "Independent client", "a secure independent password", false);
    var credential =
        accounts.issueDavCredential(
            id, "python-caldav qualification", Instant.now().plusSeconds(3600));
    Path privateDirectory = Files.createTempDirectory("busystack-independent-");
    Files.setPosixFilePermissions(
        privateDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    Path secret = privateDirectory.resolve("secret"), output = privateDirectory.resolve("result");
    try {
      Files.writeString(secret, credential.secret(), StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(
          secret, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      String python =
          System.getProperty(
              "busystack.client-python",
              System.getenv().getOrDefault("BUSYSTACK_CLIENT_PYTHON", "python3"));
      ProcessBuilder builder = new ProcessBuilder(python, "qualification/clients/python_caldav.py");
      builder.environment().put("BUSYSTACK_CLIENT_URL", "https://localhost:" + PORT + "/team/dav/");
      builder.environment().put("BUSYSTACK_CLIENT_USER", credential.username());
      builder.environment().put("BUSYSTACK_CLIENT_SECRET_FILE", secret.toString());
      builder
          .environment()
          .put(
              "BUSYSTACK_CLIENT_CA",
              Path.of(URI.create(TestTls.instance().keyStore()))
                  .getParent()
                  .resolve("ca.pem")
                  .toString());
      builder.redirectErrorStream(true).redirectOutput(output.toFile());
      var process = builder.start();
      boolean finished = process.waitFor(180, TimeUnit.SECONDS);
      if (!finished) process.destroyForcibly();
      assertThat(finished).as("Independent client timed out").isTrue();
      String result = Files.readString(output);
      assertThat(process.exitValue())
          .withFailMessage(
              "Independent client failed. Install pinned qualification/clients/requirements.txt; this required test does not skip missing Python/dependencies. Output:%n%s",
              result)
          .isZero();
      assertThat(result).contains("\"status\": \"passed\"", "\"version\": \"3.6.0\"");
      Files.createDirectories(Path.of("target/qualification"));
      Files.writeString(Path.of("target/qualification/python-caldav.json"), result);
      String desktop =
          System.getProperty(
              "busystack.thunderbird-binary", System.getenv("BUSYSTACK_THUNDERBIRD_BINARY"));
      if (desktop == null || desktop.isBlank()) {
        Files.writeString(
            Path.of("target/qualification/thunderbird.json"),
            "{\"client\":\"Thunderbird\",\"status\":\"unexecuted\",\"reason\":\"BUSYSTACK_THUNDERBIRD_BINARY was not configured\"}\n");
      } else {
        assertThat(Files.isExecutable(Path.of(desktop)))
            .as("Configured Thunderbird prerequisite")
            .isTrue();
        builder.command(python, "qualification/clients/thunderbird.py");
        builder.environment().put("BUSYSTACK_THUNDERBIRD_BINARY", desktop);
        var desktopProcess = builder.start();
        boolean desktopFinished = desktopProcess.waitFor(360, TimeUnit.SECONDS);
        if (!desktopFinished) desktopProcess.destroyForcibly();
        assertThat(desktopFinished).as("Desktop-client qualification timed out").isTrue();
        String desktopResult = Files.readString(output);
        assertThat(desktopProcess.exitValue())
            .withFailMessage("Desktop-client qualification failed:%n%s", desktopResult)
            .isZero();
        assertThat(desktopResult).contains("\"status\": \"passed\"", "\"client\": \"Thunderbird\"");
        Files.writeString(Path.of("target/qualification/thunderbird.json"), desktopResult);
      }
    } finally {
      Files.deleteIfExists(secret);
      Files.deleteIfExists(output);
      Files.deleteIfExists(privateDirectory);
    }
  }

  private static int port() {
    try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("No isolated test port", ex);
    }
  }
}
