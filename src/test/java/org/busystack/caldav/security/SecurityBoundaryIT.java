package org.busystack.caldav.security;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.busystack.caldav.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "busystack.public-base-url=https://calendar.example/team",
      "busystack.issuer=https://calendar.example/team"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SecurityBoundaryIT {
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
  @Autowired JdbcTemplate jdbc;
  AccountService.IssuedCredential credential;
  java.util.UUID accountId;
  final HttpClient client =
      HttpClient.newBuilder()
          .sslContext(org.busystack.caldav.testing.TestTls.instance().context())
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    accountId = accounts.provision("user", "Test User", "correct horse battery staple", false);
    credential =
        accounts.issueDavCredential(accountId, "DAV test", Instant.now().plusSeconds(3600));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/team" + path));
  }

  private String basic(String secret) {
    return "Basic "
        + Base64.getEncoder().encodeToString(("user:" + secret).getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void exactDavMethodsPassFirewallAndChallengeWithoutHtmlRedirect() throws Exception {
    for (String method :
        new String[] {
          "OPTIONS",
          "PROPFIND",
          "PROPPATCH",
          "REPORT",
          "MKCALENDAR",
          "MKCOL",
          "COPY",
          "MOVE",
          "ACL",
          "PUT",
          "DELETE",
          "GET",
          "HEAD"
        }) {
      var response = send(request("/dav/").method(method, HttpRequest.BodyPublishers.noBody()));
      assertThat(response.statusCode()).as(method).isEqualTo(401);
      assertThat(response.headers().firstValue("WWW-Authenticate"))
          .hasValue("Basic realm=\"BusyStack DAV\", charset=\"UTF-8\"");
      assertThat(response.headers().firstValue("Location")).isEmpty();
    }
    assertThat(
            send(request("/dav/").method("TRACE", HttpRequest.BodyPublishers.noBody()))
                .statusCode())
        .isEqualTo(405);
  }

  @Test
  void primaryPasswordCannotAuthenticateDavAndDavCredentialCannotAccessBrowser() throws Exception {
    assertThat(
            send(request("/dav/missing")
                    .header("Authorization", basic("correct horse battery staple")))
                .statusCode())
        .isEqualTo(401);
    var dav = send(request("/dav/missing").header("Authorization", basic(credential.secret())));
    // No protocol adapter is registered yet. Authentication success reaches normal MVC routing.
    assertThat(dav.statusCode()).isEqualTo(404);
    assertThat(
            send(request("/admin").header("Authorization", basic(credential.secret())))
                .statusCode())
        .isEqualTo(302);
    accounts.revokeDav(accountId, credential.id());
    assertThat(
            send(request("/dav/missing").header("Authorization", basic(credential.secret())))
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void browserCsrfAndDavOriginBoundaryAreEnforced() throws Exception {
    assertThat(send(request("/logout").POST(HttpRequest.BodyPublishers.noBody())).statusCode())
        .isEqualTo(403);
    assertThat(
            send(request("/dav/missing")
                    .header("Authorization", basic(credential.secret()))
                    .header("Origin", "https://evil.example")
                    .PUT(HttpRequest.BodyPublishers.ofString("data")))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            send(request("/dav/missing")
                    .header("Authorization", basic(credential.secret()))
                    .header("Sec-Fetch-Site", "cross-site")
                    .method("DELETE", HttpRequest.BodyPublishers.noBody()))
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void configuredDiscoveryIgnoresForwardedHeadersAndDoesNotAdvertiseCapabilities()
      throws Exception {
    var response =
        send(
            request("/.well-known/caldav")
                .header("Forwarded", "host=evil.example;proto=http")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Prefix", "/evil"));
    assertThat(response.statusCode()).isEqualTo(308);
    assertThat(response.headers().firstValue("Location"))
        .hasValue("https://calendar.example/team/dav/");
    assertThat(response.headers().firstValue("DAV")).isEmpty();
  }

  @Test
  void savedLoginRedirectUsesConfiguredOriginWithAnUntrustedHostHeader() throws Exception {
    String first;
    try (var socket =
        (javax.net.ssl.SSLSocket)
            org.busystack.caldav.testing.TestTls.instance()
                .context()
                .getSocketFactory()
                .createSocket("localhost", port)) {
      var parameters = socket.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      socket.setSSLParameters(parameters);
      socket.setSoTimeout(15000);
      socket
          .getOutputStream()
          .write(
              ("GET /team/admin HTTP/1.1\r\nHost: attacker.invalid\r\nAccept: text/html\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      first = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(first)
        .startsWith("HTTP/1.1 302")
        .doesNotContain("Location: https://attacker.invalid");
    String cookie =
        first
            .lines()
            .filter(
                line ->
                    line.toLowerCase(java.util.Locale.ROOT).startsWith("set-cookie: jsessionid="))
            .findFirst()
            .orElseThrow()
            .substring("Set-Cookie: ".length())
            .split(";", 2)[0];
    var login = send(request("/login").header("Cookie", cookie));
    var csrf = new java.util.concurrent.atomic.AtomicReference<String>();
    new javax.swing.text.html.parser.ParserDelegator()
        .parse(
            new java.io.StringReader(login.body()),
            new javax.swing.text.html.HTMLEditorKit.ParserCallback() {
              @Override
              public void handleSimpleTag(
                  javax.swing.text.html.HTML.Tag tag,
                  javax.swing.text.MutableAttributeSet attributes,
                  int position) {
                if (tag == javax.swing.text.html.HTML.Tag.INPUT
                    && "_csrf"
                        .equals(attributes.getAttribute(javax.swing.text.html.HTML.Attribute.NAME)))
                  csrf.set(
                      (String) attributes.getAttribute(javax.swing.text.html.HTML.Attribute.VALUE));
              }
            },
            true);
    assertThat(csrf.get()).isNotBlank();
    String body =
        "username=user&password="
            + java.net.URLEncoder.encode("correct horse battery staple", StandardCharsets.UTF_8)
            + "&_csrf="
            + java.net.URLEncoder.encode(csrf.get(), StandardCharsets.UTF_8);
    var authenticated =
        send(
            request("/login")
                .header("Cookie", cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    assertThat(authenticated.statusCode()).isEqualTo(302);
    assertThat(authenticated.headers().firstValue("Location").orElseThrow())
        .startsWith("https://calendar.example/team/admin")
        .doesNotContain("attacker.invalid");
  }
}
