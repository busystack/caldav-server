package org.busystack.caldav.protocol;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.busystack.caldav.account.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "busystack.public-base-url=https://localhost",
      "busystack.issuer=https://localhost",
      "busystack.dav.repair-local-observance-until=false"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ObservanceUntilStrictIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("server.ssl.enabled", () -> true);
    registry.add(
        "server.ssl.key-store", () -> org.busystack.caldav.testing.TestTls.instance().keyStore());
    registry.add(
        "server.ssl.key-store-password",
        () -> org.busystack.caldav.testing.TestTls.instance().password());
  }

  @LocalServerPort int port;
  @Autowired AccountService accounts;
  @Autowired org.busystack.caldav.storage.CalendarStore calendars;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void explicitDisableRetainsOriginalRejectionAndAtomicStateOverHttps() throws Exception {
    UUID owner =
        accounts.provision("strict-repair", "Strict repair", "a disposable strict password", false);
    UUID collection = calendars.createCollection(owner, "Strict", "VEVENT");
    String token = calendars.currentSyncToken(owner, collection);
    String credential =
        accounts.issueDavCredential(owner, "strict", Instant.now().plusSeconds(3600)).secret();
    String authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(("strict-repair:" + credential).getBytes(StandardCharsets.UTF_8));
    var http =
        HttpClient.newBuilder()
            .sslContext(org.busystack.caldav.testing.TestTls.instance().context())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    for (String zone : List.of("vancouver", "berlin")) {
      byte[] original;
      try (var input =
          getClass()
              .getResourceAsStream("/calendar/observance-until/thunderbird159-" + zone + ".ics")) {
        original = Objects.requireNonNull(input).readAllBytes();
      }
      var uri =
          URI.create(
              "https://localhost:"
                  + port
                  + "/dav/calendars/"
                  + owner
                  + "/"
                  + collection
                  + "/"
                  + zone
                  + ".ics");
      var response =
          http.send(
              HttpRequest.newBuilder(uri)
                  .header("Authorization", authorization)
                  .header("Content-Type", "text/calendar")
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(original))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.body()).contains("valid-calendar-data");
      assertThat(calendars.get(owner, collection, zone + ".ics")).isNull();
      assertThat(calendars.currentSyncToken(owner, collection)).isEqualTo(token);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM calendar_resource_versions WHERE collection_id=?",
                  Long.class,
                  collection))
          .isZero();
    }
  }
}
