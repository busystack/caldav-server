package org.busystack.caldav.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.config.DeploymentProperties;
import org.busystack.caldav.testing.SignedPasskeyClient;
import org.busystack.caldav.testing.TestTls;
import org.junit.jupiter.api.AfterAll;
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

/**
 * Production OIDC filters and discovery/JWKS/UserInfo clients against a signed disposable issuer.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "busystack.public-base-url=https://localhost/team",
      "busystack.issuer=https://localhost/team",
      "busystack.oidc.enabled=true",
      "busystack.oidc.client-id=boundary-client",
      "busystack.oidc.client-secret=boundary-client-secret",
      "busystack.oidc.allowed-users[0].subject=boundary-subject",
      "busystack.oidc.allowed-users[0].username=boundary-user",
      "busystack.oidc.allowed-users[0].display-name=Boundary User"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OidcBoundaryIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  private static final SSLContext PREVIOUS_TLS = defaultTls();
  private static final SSLSocketFactory PREVIOUS_SOCKET =
      HttpsURLConnection.getDefaultSSLSocketFactory();
  private static final Issuer PROVIDER = new Issuer();

  static {
    SSLContext.setDefault(TestTls.instance().context());
    HttpsURLConnection.setDefaultSSLSocketFactory(TestTls.instance().context().getSocketFactory());
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("server.ssl.enabled", () -> true);
    registry.add("server.ssl.key-store", () -> TestTls.instance().keyStore());
    registry.add("server.ssl.key-store-password", () -> TestTls.instance().password());
    registry.add("busystack.oidc.issuer", PROVIDER::issuer);
  }

  @AfterAll
  static void stop() {
    PROVIDER.server.stop(0);
    SSLContext.setDefault(PREVIOUS_TLS);
    HttpsURLConnection.setDefaultSSLSocketFactory(PREVIOUS_SOCKET);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired AccountService accounts;
  @Autowired OidcProperties properties;
  @Autowired DeploymentProperties deployment;
  @LocalServerPort int port;

  @BeforeEach
  void clear() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    PROVIDER.mode = "correct";
  }

  @Test
  void configuredDiscoveryRejectsAnIssuerMismatchOverNormallyVerifiedHttps() {
    PROVIDER.mode = "discovery-issuer";
    try {
      var registration = new ConfiguredOidcRegistrationRepository(properties, deployment);
      assertThatThrownBy(() -> registration.findByRegistrationId("upstream"))
          .isInstanceOf(RuntimeException.class);
    } finally {
      PROVIDER.mode = "correct";
    }
  }

  @Test
  void signedIssuerAudienceExpiryNonceSignatureAlgorithmAndUserInfoNegativesCannotProvision()
      throws Exception {
    for (String mode :
        List.of(
            "issuer",
            "audience",
            "azp",
            "expired",
            "future-issued",
            "nonce",
            "signature",
            "unknown-kid",
            "hmac-algorithm",
            "userinfo-subject")) {
      PROVIDER.mode = mode;
      var result = login();
      assertThat(result.statusCode()).as(mode + ": " + result.body()).isEqualTo(302);
      assertThat(result.headers().firstValue("Location").orElseThrow()).as(mode).contains("error");
      assertThat(accounts.find("boundary-user")).as(mode).isNull();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM external_identities", Long.class))
          .isZero();
    }
    assertThat(PROVIDER.userInfos.get()).isPositive();
    assertThat(PROVIDER.jwks.get()).isPositive();
  }

  @Test
  void validUpstreamIdentityAndARealJwksKeyRotationKeepTheSameNonAdministratorAccount()
      throws Exception {
    var first = login();
    assertThat(first.statusCode()).as(first.body()).isEqualTo(302);
    assertThat(first.headers().firstValue("Location").orElseThrow()).doesNotContain("error");
    var account = accounts.find("boundary-user");
    assertThat(account).isNotNull();
    assertThat(account.administrator()).isFalse();
    assertThat(account.passwordHash()).isNull();
    PROVIDER.mode = "multi-audience";
    var multi = login();
    assertThat(multi.headers().firstValue("Location").orElseThrow()).doesNotContain("error");
    assertThat(accounts.find("boundary-user").id()).isEqualTo(account.id());
    PROVIDER.rotate();
    var second = login();
    assertThat(second.statusCode()).as(second.body()).isEqualTo(302);
    assertThat(second.headers().firstValue("Location").orElseThrow()).doesNotContain("error");
    assertThat(accounts.find("boundary-user").id()).isEqualTo(account.id());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM external_identities", Long.class))
        .isEqualTo(1);
  }

  @Test
  void wrongStateIsRejectedBeforeAnyTokenRequestOrAccountProvisioning() throws Exception {
    try (var transport = new SignedPasskeyClient(port, "/team", "https://localhost")) {
      var browser = transport.browser();
      var begin = transport.get(browser, "/oauth2/authorization/upstream");
      String authorization = begin.headers().firstValue("Location").orElseThrow();
      var provider =
          browser.send(
              java.net.http.HttpRequest.newBuilder(URI.create(authorization)).GET().build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      String callback = provider.headers().firstValue("Location").orElseThrow();
      var parameters = SignedPasskeyClient.query(callback);
      parameters.put("state", "unrelated-state");
      int tokens = PROVIDER.tokens.get();
      var denied =
          transport.get(
              browser, "/login/oauth2/code/upstream?" + SignedPasskeyClient.form(parameters));
      assertThat(denied.statusCode()).isEqualTo(302);
      assertThat(denied.headers().firstValue("Location").orElseThrow()).contains("error");
      assertThat(PROVIDER.tokens.get()).isEqualTo(tokens);
      assertThat(accounts.find("boundary-user")).isNull();
    }
  }

  private java.net.http.HttpResponse<String> login() throws Exception {
    try (var transport = new SignedPasskeyClient(port, "/team", "https://localhost")) {
      var browser = transport.browser();
      var begin = transport.get(browser, "/oauth2/authorization/upstream");
      assertThat(begin.statusCode()).isEqualTo(302);
      String authorization = begin.headers().firstValue("Location").orElseThrow();
      var parameters = SignedPasskeyClient.query(authorization);
      assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
      assertThat(parameters.get("nonce")).isNotBlank();
      var provider =
          browser.send(
              java.net.http.HttpRequest.newBuilder(URI.create(authorization)).GET().build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(provider.statusCode()).isEqualTo(302);
      String callback = provider.headers().firstValue("Location").orElseThrow();
      return transport.get(browser, transport.localPath(callback));
    }
  }

  private static SSLContext defaultTls() {
    try {
      return SSLContext.getDefault();
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static final class Issuer {
    final HttpsServer server;
    final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
    final AtomicInteger tokens = new AtomicInteger();
    final AtomicInteger jwks = new AtomicInteger();
    final AtomicInteger userInfos = new AtomicInteger();
    volatile RSAKey signingKey;
    volatile String mode = "correct";

    Issuer() {
      try {
        rotate();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var stream =
            Files.newInputStream(Path.of(URI.create(TestTls.instance().keyStore())))) {
          store.load(stream, TestTls.instance().password().toCharArray());
        }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, TestTls.instance().password().toCharArray());
        var tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/provider/", this::handle);
        server.start();
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }

    void rotate() throws Exception {
      signingKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    }

    String issuer() {
      return "https://localhost:" + server.getAddress().getPort() + "/provider";
    }

    void handle(HttpExchange exchange) throws java.io.IOException {
      try {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/.well-known/openid-configuration")) {
          json(
              exchange,
              200,
              Map.ofEntries(
                  Map.entry(
                      "issuer",
                      mode.equals("discovery-issuer") ? "https://other.invalid" : issuer()),
                  Map.entry("authorization_endpoint", issuer() + "/authorize"),
                  Map.entry("token_endpoint", issuer() + "/token"),
                  Map.entry("jwks_uri", issuer() + "/jwks"),
                  Map.entry("userinfo_endpoint", issuer() + "/userinfo"),
                  Map.entry("response_types_supported", List.of("code")),
                  Map.entry("subject_types_supported", List.of("public")),
                  Map.entry("id_token_signing_alg_values_supported", List.of("RS256")),
                  Map.entry(
                      "token_endpoint_auth_methods_supported", List.of("client_secret_basic")),
                  Map.entry("code_challenge_methods_supported", List.of("S256"))));
        } else if (path.endsWith("/jwks")) {
          jwks.incrementAndGet();
          json(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toJSONObject());
        } else if (path.endsWith("/authorize")) {
          var query = SignedPasskeyClient.query("https://localhost" + exchange.getRequestURI());
          String code = UUID.randomUUID().toString();
          codes.put(code, query);
          exchange
              .getResponseHeaders()
              .set(
                  "Location",
                  query.get("redirect_uri")
                      + "?"
                      + SignedPasskeyClient.form(
                          Map.of("code", code, "state", query.get("state"))));
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        } else if (path.endsWith("/token")) {
          tokens.incrementAndGet();
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          var form = SignedPasskeyClient.query("https://localhost/?" + body);
          var request = codes.remove(form.get("code"));
          if (request == null
              || !SignedPasskeyClient.encoded(
                      SignedPasskeyClient.hash(
                          form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)))
                  .equals(request.get("code_challenge"))) {
            json(exchange, 400, Map.of("error", "invalid_grant"));
            return;
          }
          Instant now = Instant.now();
          var claims =
              new JWTClaimsSet.Builder()
                  .issuer(mode.equals("issuer") ? "https://other.invalid" : issuer())
                  .subject("boundary-subject")
                  .audience(
                      mode.equals("audience")
                          ? List.of("other-client")
                          : mode.equals("azp") || mode.equals("multi-audience")
                              ? List.of("boundary-client", "other-client")
                              : List.of("boundary-client"))
                  .issueTime(Date.from(mode.equals("future-issued") ? now.plusSeconds(600) : now))
                  .expirationTime(
                      Date.from(
                          mode.equals("expired") ? now.minusSeconds(600) : now.plusSeconds(600)))
                  .claim("nonce", mode.equals("nonce") ? "unrelated-nonce" : request.get("nonce"))
                  .claim("auth_time", now.getEpochSecond());
          if (mode.equals("azp")) claims.claim("azp", "other-client");
          if (mode.equals("multi-audience")) claims.claim("azp", "boundary-client");
          SignedJWT token;
          if (mode.equals("hmac-algorithm")) {
            token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            token.sign(new MACSigner(new byte[32]));
          } else {
            token =
                new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(mode.equals("unknown-kid") ? "unknown-key" : signingKey.getKeyID())
                        .build(),
                    claims.build());
            token.sign(
                new RSASSASigner(
                    mode.equals("signature") ? new RSAKeyGenerator(2048).generate() : signingKey));
          }
          json(
              exchange,
              200,
              Map.of(
                  "access_token",
                  "fixture-upstream-access",
                  "token_type",
                  "Bearer",
                  "expires_in",
                  600,
                  "scope",
                  "openid profile",
                  "id_token",
                  token.serialize()));
        } else if (path.endsWith("/userinfo")) {
          userInfos.incrementAndGet();
          json(
              exchange,
              200,
              Map.of(
                  "sub",
                  mode.equals("userinfo-subject") ? "other-subject" : "boundary-subject",
                  "name",
                  "Untrusted display name",
                  "roles",
                  List.of("admin")));
        } else json(exchange, 404, Map.of("error", "not_found"));
      } catch (Exception failure) {
        json(exchange, 500, Map.of("error", failure.getClass().getSimpleName()));
      }
    }

    private static void json(HttpExchange exchange, int status, Object value)
        throws java.io.IOException {
      byte[] bytes = SignedPasskeyClient.JSON.writeValueAsBytes(value);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}
