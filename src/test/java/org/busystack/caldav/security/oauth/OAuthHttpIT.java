package org.busystack.caldav.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.busystack.caldav.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds completed interactive grants; tests actual HTTP token/DAV boundaries, not WebAuthn login.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "busystack.public-base-url=https://calendar.example",
      "busystack.issuer=https://calendar.example",
      "server.servlet.session.cookie.secure=false"
    })
@Import(OAuthHttpIT.ProbeConfiguration.class)
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OAuthHttpIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  private static final String CALLBACK = "http://127.0.0.1:34567/callback";
  private static final String VERIFIER =
      "valid-code-verifier-for-native-client-01234567890123456789";
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final JsonMapper json = JsonMapper.builder().build();
  @Autowired AccountService accounts;
  @Autowired OAuthClientService clients;
  @Autowired RegisteredClientRepository registrations;
  @Autowired OAuth2AuthorizationService authorizations;
  @Autowired RefreshFamilyStore families;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired org.busystack.caldav.security.session.ClientAccessRevocation clientAccess;
  @LocalServerPort int port;
  UUID administrator, account;

  @BeforeEach
  void prepare() {
    jdbc.execute(
        "TRUNCATE oauth2_authorization_consent,oauth2_authorization,oauth2_registered_client CASCADE");
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    administrator =
        accounts.provision(
            "oauth-admin", "OAuth administrator", "correct horse battery staple", true);
    account = accounts.provision("oauth-user", "OAuth user", "correct horse battery staple", false);
    clients.registerNative(administrator, "native-http", "Native HTTP test", Set.of(CALLBACK));
  }

  @Test
  void publicPkceIssuesScopedOpaqueTokensAndRefreshReplayRevokesDescendants() throws Exception {
    var initial = exchange(seedCode());
    assertThat(initial.statusCode()).isEqualTo(200);
    assertThat(initial.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(initial.headers().firstValue("Pragma")).hasValue("no-cache");
    assertThat(initial.headers().firstValue("Content-Type").orElseThrow())
        .startsWith("application/json");
    JsonNode first = json.readTree(initial.body());
    assertThat(first.path("token_type").asString()).isEqualTo("Bearer");
    assertThat(first.path("expires_in").asLong()).isPositive();
    String firstAccess = first.path("access_token").asString();
    String firstRefresh = first.path("refresh_token").asString();
    assertThat(firstAccess).doesNotContain(".");
    assertThat(dav(firstAccess).statusCode()).isEqualTo(200);
    assertThat(dav(firstAccess).body()).isEqualTo(account.toString());
    var refreshed = refresh(firstRefresh);
    assertThat(refreshed.statusCode()).isEqualTo(200);
    JsonNode second = json.readTree(refreshed.body());
    String secondRefresh = second.path("refresh_token").asString();
    String secondAccess = second.path("access_token").asString();
    assertThat(secondRefresh).isNotEqualTo(firstRefresh);
    assertThat(dav(firstAccess).statusCode()).isEqualTo(401);
    assertThat(dav(secondAccess).statusCode()).isEqualTo(200);
    var replay = refresh(firstRefresh);
    assertThat(replay.statusCode()).isEqualTo(400);
    assertThat(replay.body()).contains("invalid_grant");
    assertThat(dav(secondAccess).statusCode()).isEqualTo(401);
    assertThat(refresh(secondRefresh).statusCode()).isEqualTo(400);
  }

  @Test
  void emptyOptionalSecretRefreshesAndRejectsInvalidGrantsWithoutBrowserRedirects()
      throws Exception {
    var softly = new org.assertj.core.api.SoftAssertions();
    for (String suffix :
        List.of(
            "",
            "&client_secret=",
            "&client_secret=&unrecognized-extension=one&unrecognized-extension=two")) {
      var issuedResponse = exchange(seedCode());
      assertThat(issuedResponse.statusCode()).isEqualTo(200);
      JsonNode issued = json.readTree(issuedResponse.body());
      String oldAccess = issued.path("access_token").asString();
      String oldRefresh = issued.path("refresh_token").asString();
      var refreshed =
          post(
              "/oauth2/token",
              form(
                      Map.of(
                          "grant_type",
                          "refresh_token",
                          "client_id",
                          "native-http",
                          "refresh_token",
                          oldRefresh))
                  + suffix);
      softly.assertThat(refreshed.statusCode()).as("valid refresh %s", suffix).isEqualTo(200);
      softly.assertThat(refreshed.headers().firstValue("Location")).isEmpty();
      softly
          .assertThat(refreshed.headers().firstValue("Content-Type").orElse(""))
          .startsWith("application/json");
      if (refreshed.statusCode() == 200) {
        JsonNode successor = json.readTree(refreshed.body());
        softly.assertThat(successor.path("token_type").asString()).isEqualTo("Bearer");
        softly.assertThat(successor.path("refresh_token").asString().equals(oldRefresh)).isFalse();
        softly.assertThat(successor.path("refresh_token").asString()).isNotBlank();
        softly.assertThat(successor.path("access_token").asString()).isNotBlank();
        softly.assertThat(dav(oldAccess).statusCode()).isEqualTo(401);
        softly
            .assertThat(dav(successor.path("access_token").asString()).statusCode())
            .isEqualTo(200);
        softly
            .assertThat(refreshed.headers().firstValue("Cache-Control").orElse(""))
            .contains("no-store");
        softly.assertThat(refreshed.headers().firstValue("Pragma")).hasValue("no-cache");
      }
      var invalid =
          post(
              "/oauth2/token",
              form(
                      Map.of(
                          "grant_type",
                          "refresh_token",
                          "client_id",
                          "native-http",
                          "refresh_token",
                          "independent-invalid-control"))
                  + suffix);
      softly.assertThat(invalid.statusCode()).as("invalid refresh %s", suffix).isEqualTo(400);
      softly.assertThat(invalid.headers().firstValue("Location")).isEmpty();
      softly
          .assertThat(invalid.headers().firstValue("Content-Type").orElse(""))
          .startsWith("application/json");
      if (invalid.statusCode() == 400)
        softly
            .assertThat(json.readTree(invalid.body()).path("error").asString())
            .isEqualTo("invalid_grant");
    }
    softly.assertAll();
  }

  @Test
  void nonemptyAndDuplicateSecretsReturnOAuthErrorsWithoutConsumingRefreshGrants()
      throws Exception {
    for (var control :
        Map.ofEntries(
                Map.entry("&client_secret=unsupported-secret", "invalid_client"),
                Map.entry("&client_secret=%20", "invalid_client"),
                Map.entry("&client_secret=&client_secret=", "invalid_request"),
                Map.entry("&client_secret=&client_secret=unsupported-secret", "invalid_request"),
                Map.entry("&client_secret=unsupported-secret&client_secret=", "invalid_request"),
                Map.entry("&client_secret=one&client_secret=two", "invalid_request"),
                Map.entry("&client_secret=&client_id=native-http", "invalid_request"),
                Map.entry("&client_secret=&grant_type=refresh_token", "invalid_request"),
                Map.entry("&client_secret=&refresh_token=duplicate-control", "invalid_request"),
                Map.entry("&client_secret=&scope=dav&scope=dav", "invalid_request"))
            .entrySet()) {
      var issuedResponse = exchange(seedCode());
      assertThat(issuedResponse.statusCode()).isEqualTo(200);
      JsonNode issued = json.readTree(issuedResponse.body());
      String token = issued.path("refresh_token").asString();
      for (String candidate : List.of(token, "independent-invalid-control")) {
        var rejected =
            post(
                "/oauth2/token",
                form(
                        Map.of(
                            "grant_type",
                            "refresh_token",
                            "client_id",
                            "native-http",
                            "refresh_token",
                            candidate))
                    + control.getKey());
        assertThat(rejected.statusCode())
            .as("refresh control %s", control.getKey())
            .isEqualTo(control.getValue().equals("invalid_client") ? 401 : 400);
        assertThat(rejected.headers().firstValue("Location")).isEmpty();
        assertThat(rejected.headers().firstValue("Content-Type").orElseThrow())
            .startsWith("application/json");
        assertThat(json.readTree(rejected.body()).path("error").asString())
            .isEqualTo(control.getValue());
      }
      assertThat(dav(issued.path("access_token").asString()).statusCode()).isEqualTo(200);
      var refreshed = refresh(token);
      assertThat(refreshed.statusCode()).isEqualTo(200);
      assertThat(dav(json.readTree(refreshed.body()).path("access_token").asString()).statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  void emptyOptionalSecretRevokesWithoutAdmittingNonemptyOrDuplicateSecrets() throws Exception {
    for (String suffix : List.of("", "&client_secret=")) {
      var issued = json.readTree(exchange(seedCode()).body());
      var revoked =
          post(
              "/oauth2/revoke",
              form(
                      Map.of(
                          "client_id",
                          "native-http",
                          "token",
                          issued.path("refresh_token").asString()))
                  + suffix);
      assertThat(revoked.statusCode()).as("revoke %s", suffix).isEqualTo(200);
      assertThat(revoked.headers().firstValue("Location")).isEmpty();
      assertThat(dav(issued.path("access_token").asString()).statusCode()).isEqualTo(401);
    }
    for (var control :
        Map.of(
                "&client_secret=unsupported-secret", "invalid_client",
                "&client_secret=%20", "invalid_client",
                "&client_secret=&client_secret=", "invalid_request",
                "&client_secret=&client_secret=unsupported-secret", "invalid_request")
            .entrySet()) {
      var issued = json.readTree(exchange(seedCode()).body());
      var rejected =
          post(
              "/oauth2/revoke",
              form(
                      Map.of(
                          "client_id",
                          "native-http",
                          "token",
                          issued.path("refresh_token").asString()))
                  + control.getKey());
      assertThat(rejected.statusCode())
          .as("revoke control %s", control.getKey())
          .isEqualTo(control.getValue().equals("invalid_client") ? 401 : 400);
      assertThat(rejected.headers().firstValue("Location")).isEmpty();
      assertThat(rejected.headers().firstValue("Content-Type").orElseThrow())
          .startsWith("application/json");
      assertThat(json.readTree(rejected.body()).path("error").asString())
          .isEqualTo(control.getValue());
      assertThat(dav(issued.path("access_token").asString()).statusCode()).isEqualTo(200);
      assertThat(refresh(issued.path("refresh_token").asString()).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void rootBearerChallengeAndAuthorizationRequestBoundariesMatchTheSelectedProfile()
      throws Exception {
    var invalid =
        http.send(
            HttpRequest.newBuilder(URI.create(base() + "/dav/"))
                .header("Authorization", "Bearer invalid-boundary-fixture")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(invalid.statusCode()).isEqualTo(401);
    assertThat(invalid.headers().firstValue("WWW-Authenticate").orElseThrow())
        .startsWith("Bearer ")
        .contains("invalid_token")
        .doesNotContain("resource_metadata");
    String common =
        form(
            Map.of(
                "response_type",
                "code",
                "client_id",
                "native-http",
                "redirect_uri",
                CALLBACK,
                "scope",
                "dav",
                "code_challenge",
                "012345678901234567890123456789012345678901234",
                "code_challenge_method",
                "S256"));
    for (String query :
        List.of(
            common.replace("client_id=native-http", "client_id=unknown-audit-client"),
            common.replace(
                java.net.URLEncoder.encode(CALLBACK, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(
                    "https://attacker.invalid/callback", StandardCharsets.UTF_8)),
            common + "&client_id=native-http",
            common + "&response_type=code")) {
      var rejected =
          http.send(
              HttpRequest.newBuilder(URI.create(base() + "/oauth2/authorize?" + query))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(400);
      assertThat(rejected.headers().firstValue("Location")).isEmpty();
    }
    var ordinary =
        http.send(
            HttpRequest.newBuilder(URI.create(base() + "/oauth2/authorize?" + common))
                .header("Accept", "text/html")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var extension =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        base()
                            + "/oauth2/authorize?"
                            + common
                            + "&unrecognized-audit-extension=opaque"))
                .header("Accept", "text/html")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(ordinary.statusCode()).isEqualTo(302);
    assertThat(extension.statusCode()).isEqualTo(ordinary.statusCode());
    assertThat(extension.headers().firstValue("Location"))
        .isEqualTo(ordinary.headers().firstValue("Location"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Long.class))
        .isZero();
    String omittedResponse =
        java.util.Arrays.stream(common.split("&"))
            .filter(parameter -> !parameter.startsWith("response_type="))
            .collect(java.util.stream.Collectors.joining("&"));
    for (String query :
        List.of(omittedResponse, common.replace("response_type=code", "response_type="))) {
      var rejected =
          http.send(
              HttpRequest.newBuilder(URI.create(base() + "/oauth2/authorize?" + query))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(rejected.statusCode()).isEqualTo(400);
      assertThat(rejected.headers().firstValue("Location")).isEmpty();
    }
    for (String body :
        List.of(
            "grant_type=authorization_code&grant_type=refresh_token&client_id=native-http&code=unused&code_verifier="
                + VERIFIER,
            "grant_type=authorization_code&client_id=native-http&client_id=native-http&code=unused&code_verifier="
                + VERIFIER)) {
      var rejected =
          http.send(
              HttpRequest.newBuilder(URI.create(base() + "/oauth2/token"))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(400);
      assertThat(rejected.body()).contains("invalid_request");
      assertThat(rejected.body()).doesNotContain("access_token", "refresh_token");
    }
    for (var malformed :
        Map.of(
                "client_id=native-http",
                "invalid_request",
                "grant_type=&client_id=native-http",
                "invalid_request",
                "grant_type=authorization_code&client_id=native-http",
                "invalid_request",
                "grant_type=authorization_code&client_id=native-http&code=unused",
                "invalid_grant",
                "grant_type=authorization_code&code=unused&code_verifier=" + VERIFIER,
                "invalid_request",
                "grant_type=unsupported-audit-grant&client_id=native-http",
                "unsupported_grant_type")
            .entrySet()) {
      var rejected =
          http.send(
              HttpRequest.newBuilder(URI.create(base() + "/oauth2/token"))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(malformed.getKey()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(400);
      assertThat(rejected.headers().firstValue("Location")).isEmpty();
      assertThat(json.readTree(rejected.body()).path("error").asString())
          .isEqualTo(malformed.getValue());
      assertThat(rejected.body()).doesNotContain("access_token", "refresh_token");
    }
  }

  @Test
  void codeReplayRevokesIssuedGrantAndWrongVerifierCannotExchange() throws Exception {
    String code = seedCode();
    var wrong =
        post(
            "/oauth2/token",
            Map.of(
                "grant_type",
                "authorization_code",
                "client_id",
                "native-http",
                "redirect_uri",
                CALLBACK,
                "code",
                code,
                "code_verifier",
                "wrong-code-verifier-012345678901234567890123456789"));
    assertThat(wrong.statusCode()).isEqualTo(400);
    var issued = exchange(code);
    assertThat(issued.statusCode()).isEqualTo(200);
    String access = json.readTree(issued.body()).path("access_token").asString();
    assertThat(dav(access).statusCode()).isEqualTo(200);
    assertThat(exchange(code).statusCode()).isEqualTo(400);
    assertThat(dav(access).statusCode()).isEqualTo(401);
  }

  @Test
  void unsupportedClientAuthenticationReturnsOAuthErrorsWithoutConsumingTheCode() throws Exception {
    String code = seedCode();
    for (boolean basic : List.of(true, false)) {
      var parameters =
          new java.util.HashMap<>(
              Map.of(
                  "grant_type",
                  "authorization_code",
                  "client_id",
                  "native-http",
                  "redirect_uri",
                  CALLBACK,
                  "code",
                  code,
                  "code_verifier",
                  VERIFIER));
      var request =
          HttpRequest.newBuilder(URI.create(base() + "/oauth2/token"))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/x-www-form-urlencoded");
      if (basic)
        request.header(
            "Authorization",
            "Basic "
                + Base64.getEncoder()
                    .encodeToString(
                        "native-http:unsupported-public-client-secret"
                            .getBytes(StandardCharsets.UTF_8)));
      else parameters.put("client_secret", "unsupported-public-client-secret");
      var rejected =
          http.send(
              request.POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(rejected.statusCode()).isEqualTo(401);
      assertThat(rejected.headers().firstValue("Content-Type").orElseThrow())
          .startsWith("application/json");
      assertThat(rejected.body()).contains("invalid_client");
    }
    assertThat(exchange(code).statusCode()).isEqualTo(200);
  }

  @Test
  void concurrentRefreshCannotCreateTwoUsableSuccessorChains() throws Exception {
    JsonNode issued = json.readTree(exchange(seedCode()).body());
    String refresh = issued.path("refresh_token").asString();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var first = CompletableFuture.supplyAsync(() -> concurrentRefresh(refresh, ready, start));
    var second = CompletableFuture.supplyAsync(() -> concurrentRefresh(refresh, ready, start));
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    var a = first.get(15, TimeUnit.SECONDS);
    var b = second.get(15, TimeUnit.SECONDS);
    assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(200, 400);
    JsonNode winner = json.readTree((a.statusCode() == 200 ? a : b).body());
    assertThat(dav(winner.path("access_token").asString()).statusCode()).isEqualTo(401);
    assertThat(refresh(winner.path("refresh_token").asString()).statusCode()).isEqualTo(400);
  }

  @Test
  void revocationAccountDisablingAndRecoveryInvalidateBearerAccess() throws Exception {
    JsonNode issued = json.readTree(exchange(seedCode()).body());
    String access = issued.path("access_token").asString();
    var revoked =
        post(
            "/oauth2/revoke",
            Map.of("client_id", "native-http", "token", issued.path("refresh_token").asString()));
    assertThat(revoked.statusCode()).isEqualTo(200);
    assertThat(dav(access).statusCode()).isEqualTo(401);
    issued = json.readTree(exchange(seedCode()).body());
    access = issued.path("access_token").asString();
    accounts.disable(account);
    assertThat(dav(access).statusCode()).isEqualTo(401);
    assertThat(refresh(issued.path("refresh_token").asString()).statusCode()).isEqualTo(400);
  }

  @Test
  void revocationAndAccountResetUseOneLockOrderWithoutDeadlockOrRetry() throws Exception {
    JsonNode issued = json.readTree(exchange(seedCode()).body());
    String access = issued.path("access_token").asString();
    String refresh = issued.path("refresh_token").asString();
    String authorization =
        authorizations
            .findByToken(
                access,
                org.springframework.security.oauth2.server.authorization.OAuth2TokenType
                    .ACCESS_TOKEN)
            .getId();
    CountDownLatch resetHoldingRows = new CountDownLatch(1);
    CountDownLatch finishReset = new CountDownLatch(1);
    var resetPid = new java.util.concurrent.atomic.AtomicInteger();
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var reset =
          CompletableFuture.runAsync(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            jdbc.query(
                                "SELECT id FROM accounts WHERE id=? FOR UPDATE", rs -> {}, account);
                            jdbc.update(
                                "UPDATE oauth_refresh_families SET revoked_at=now() WHERE authorization_id=?",
                                authorization);
                            resetPid.set(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            resetHoldingRows.countDown();
                            try {
                              if (!finishReset.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Reset coordination timed out");
                            } catch (InterruptedException failure) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(failure);
                            }
                            clientAccess.revoke(account, "oauth-user");
                          }),
              workers);
      try {
        assertThat(resetHoldingRows.await(10, TimeUnit.SECONDS)).isTrue();
        var revoke =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return post(
                        "/oauth2/revoke", Map.of("client_id", "native-http", "token", refresh));
                  } catch (Exception failure) {
                    throw new AssertionError(failure);
                  }
                },
                workers);
        org.awaitility.Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(
                () ->
                    assertThat(
                            jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                                Boolean.class,
                                resetPid.get()))
                        .isTrue());
        finishReset.countDown();
        reset.get(15, TimeUnit.SECONDS);
        assertThat(revoke.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      } finally {
        finishReset.countDown();
      }
    }
    assertThat(authorizations.findById(authorization)).isNull();
    assertThat(families.isActive(authorization)).isFalse();
    assertThat(dav(access).statusCode()).isEqualTo(401);
  }

  @Test
  void expiryRecoveryAndAudienceChecksApplyOnEveryDavRequest() throws Exception {
    JsonNode issued = json.readTree(exchange(seedCode()).body());
    String access = issued.path("access_token").asString();
    var grant =
        authorizations.findByToken(
            access,
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
    var claims = new java.util.HashMap<>(grant.getAccessToken().getClaims());
    claims.put("aud", new java.util.ArrayList<>(List.of("https://attacker.invalid/dav")));
    authorizations.save(
        OAuth2Authorization.from(grant)
            .token(
                grant.getAccessToken().getToken(),
                metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claims))
            .build());
    assertThat(dav(access).statusCode()).isEqualTo(401);
    issued = json.readTree(exchange(seedCode()).body());
    access = issued.path("access_token").asString();
    grant =
        authorizations.findByToken(
            access,
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
    var wrongIssuer = new java.util.HashMap<>(grant.getAccessToken().getClaims());
    wrongIssuer.put("iss", "https://attacker.invalid");
    authorizations.save(
        OAuth2Authorization.from(grant)
            .token(
                grant.getAccessToken().getToken(),
                metadata ->
                    metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, wrongIssuer))
            .build());
    assertThat(dav(access).statusCode()).isEqualTo(401);
    issued = json.readTree(exchange(seedCode()).body());
    access = issued.path("access_token").asString();
    jdbc.update(
        "UPDATE oauth2_authorization SET access_token_issued_at=now()-interval '2 minutes',access_token_expires_at=now()-interval '1 minute'");
    assertThat(dav(access).statusCode()).isEqualTo(401);
    issued = json.readTree(exchange(seedCode()).body());
    access = issued.path("access_token").asString();
    jdbc.update("UPDATE system_state SET recovery_epoch=gen_random_uuid() WHERE id=1");
    assertThat(dav(access).statusCode()).isEqualTo(401);
    assertThat(refresh(issued.path("refresh_token").asString()).statusCode()).isEqualTo(400);
  }

  @Test
  void metadataUsesConfiguredIssuerAndAdministratorControlsRegistration() throws Exception {
    var passwordOnly =
        UsernamePasswordAuthenticationToken.authenticated(
            "oauth-admin",
            null,
            List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)));
    assertThatThrownBy(
            () -> clients.registerNative(passwordOnly, "not-yet", "Requires MFA", Set.of(CALLBACK)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(registrations.findByClientId("not-yet")).isNull();
    var metadata =
        http.send(
            HttpRequest.newBuilder(URI.create(base() + "/.well-known/oauth-authorization-server"))
                .header("Forwarded", "proto=http;host=attacker.invalid")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(metadata.statusCode()).isEqualTo(200);
    assertThat(json.readTree(metadata.body()).path("issuer").asString())
        .isEqualTo("https://calendar.example");
    assertThat(json.readTree(metadata.body()).path("response_modes_supported").toString())
        .isEqualTo("[\"query\"]");
    assertThat(metadata.body())
        .contains("S256", "authorization_code", "refresh_token")
        .doesNotContain(
            "client_credentials",
            "password",
            "implicit",
            "attacker.invalid",
            "jwks_uri",
            "tls_client_certificate_bound_access_tokens",
            "dpop_signing_alg_values_supported");
    assertThatThrownBy(
            () -> clients.registerNative(account, "untrusted", "Untrusted", Set.of(CALLBACK)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                clients.registerNative(
                    administrator,
                    "redirect",
                    "Bad redirect",
                    Set.of("https://attacker.invalid/callback#fragment")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            registrations
                .findByClientId("native-http")
                .getClientSettings()
                .isRequireAuthorizationConsent())
        .isTrue();
    assertThat(
            post(
                    "/oauth2/token",
                    Map.of(
                        "grant_type",
                        "password",
                        "client_id",
                        "native-http",
                        "username",
                        "oauth-user",
                        "password",
                        "correct horse battery staple"))
                .statusCode())
        .isNotEqualTo(200);
  }

  @Test
  void passwordOnlyBrowserSessionMustCompleteMfaBeforeClientAuthorization() throws Exception {
    HttpClient browser =
        HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var login =
        browser.send(
            HttpRequest.newBuilder(URI.create(base() + "/login"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(200);
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
    String credentials =
        form(
            Map.of(
                "username",
                "oauth-user",
                "password",
                "correct horse battery staple",
                "_csrf",
                csrf.get()));
    var authenticated =
        browser.send(
            HttpRequest.newBuilder(URI.create(base() + "/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(credentials))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(authenticated.statusCode()).isEqualTo(302);
    String authorization =
        form(
            Map.of(
                "response_type",
                "code",
                "client_id",
                "native-http",
                "redirect_uri",
                CALLBACK,
                "scope",
                "dav",
                "state",
                UUID.randomUUID().toString(),
                "code_challenge",
                "unused-because-step-up-is-required",
                "code_challenge_method",
                "S256"));
    var denied =
        browser.send(
            HttpRequest.newBuilder(URI.create(base() + "/oauth2/authorize?" + authorization))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "text/html")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(denied.statusCode()).isEqualTo(302);
    assertThat(denied.headers().firstValue("Location").orElseThrow())
        .contains("/login?factor=required");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Long.class))
        .isZero();
  }

  @Test
  void oldInteractiveProofCannotIssueTokensAfterAccountReset() throws Exception {
    UUID captured =
        jdbc.queryForObject("SELECT security_epoch FROM accounts WHERE id=?", UUID.class, account);
    jdbc.update("UPDATE accounts SET security_epoch=gen_random_uuid() WHERE id=?", account);
    String code = seedCode(captured);
    var rejected = exchange(code);
    assertThat(rejected.statusCode()).isEqualTo(400);
    assertThat(rejected.body()).contains("invalid_grant");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_refresh_families", Long.class))
        .isZero();
  }

  @Test
  void concurrentRegistrationHasOneWinnerAndDatabaseUniqueness() throws Exception {
    var start = new CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      java.util.concurrent.Callable<Boolean> register =
          () -> {
            if (!start.await(10, TimeUnit.SECONDS))
              throw new AssertionError("Registration start timed out");
            try {
              clients.registerNative(
                  administrator, "racing-client", "Concurrent client", Set.of(CALLBACK));
              return true;
            } catch (IllegalArgumentException duplicate) {
              return false;
            }
          };
      var first = workers.submit(register);
      var second = workers.submit(register);
      start.countDown();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_registered_client WHERE client_id='racing-client'",
                Long.class))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO oauth2_registered_client(id,client_id,client_name,client_authentication_methods,authorization_grant_types,scopes,client_settings,token_settings) SELECT ?,client_id,client_name,client_authentication_methods,authorization_grant_types,scopes,client_settings,token_settings FROM oauth2_registered_client WHERE client_id=?",
                    UUID.randomUUID().toString(),
                    "racing-client"))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  @Test
  void lateAuthorizationSaveCannotRecreateRemovedOrReplacedClientState() throws Exception {
    String code = seedCode();
    var pending =
        authorizations.findByToken(
            code,
            new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("code"));
    clients.remove(administrator, "native-http");
    assertThatThrownBy(() -> authorizations.save(pending))
        .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
    clients.registerNative(
        administrator, "native-http", "Replacement registration", Set.of(CALLBACK));
    assertThat(registrations.findByClientId("native-http").getId())
        .isNotEqualTo(pending.getRegisteredClientId());
    assertThatThrownBy(() -> authorizations.save(pending))
        .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Long.class))
        .isZero();
    assertThat(exchange(code).statusCode()).isEqualTo(400);
  }

  @Test
  void clientRemovalSerializesWithAnInFlightCodeExchange() throws Exception {
    removalWaitsForGrantMutation(false);
  }

  @Test
  void clientRemovalSerializesWithAnInFlightRefresh() throws Exception {
    removalWaitsForGrantMutation(true);
  }

  private void removalWaitsForGrantMutation(boolean refreshGrant) throws Exception {
    String code = seedCode();
    String presented =
        refreshGrant ? json.readTree(exchange(code).body()).path("refresh_token").asString() : code;
    var authorization =
        authorizations.findByToken(
            presented,
            new org.springframework.security.oauth2.server.authorization.OAuth2TokenType(
                refreshGrant ? "refresh_token" : "code"));
    jdbc.execute(
        "CREATE TABLE oauth_mutation_pause(authorization_id text PRIMARY KEY,lock_key bigint NOT NULL)");
    jdbc.update("INSERT INTO oauth_mutation_pause VALUES (?,?)", authorization.getId(), 731092811L);
    jdbc.execute(
        "CREATE FUNCTION oauth_pause_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE gate bigint; BEGIN SELECT lock_key INTO gate FROM oauth_mutation_pause WHERE authorization_id=NEW.id; IF FOUND THEN PERFORM pg_advisory_xact_lock(gate); END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER oauth_pause_mutation BEFORE UPDATE ON oauth2_authorization FOR EACH ROW EXECUTE FUNCTION oauth_pause_mutation()");
    CountDownLatch holdingGate = new CountDownLatch(1);
    CountDownLatch releaseGate = new CountDownLatch(1);
    var gatePid = new java.util.concurrent.atomic.AtomicInteger();
    var removalPid = new java.util.concurrent.atomic.AtomicInteger();
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var gate =
          CompletableFuture.runAsync(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> {}, 731092811L);
                            gatePid.set(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            holdingGate.countDown();
                            try {
                              if (!releaseGate.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Mutation gate timed out");
                            } catch (InterruptedException failure) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(failure);
                            }
                          }),
              workers);
      try {
        assertThat(holdingGate.await(10, TimeUnit.SECONDS)).isTrue();
        var mutation =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return refreshGrant ? refresh(presented) : exchange(presented);
                  } catch (Exception failure) {
                    throw new AssertionError(failure);
                  }
                },
                workers);
        org.awaitility.Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(
                () ->
                    assertThat(
                            jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                                Boolean.class,
                                gatePid.get()))
                        .isTrue());
        var removal =
            CompletableFuture.runAsync(
                () ->
                    new org.springframework.transaction.support.TransactionTemplate(transactions)
                        .executeWithoutResult(
                            status -> {
                              removalPid.set(
                                  jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                              clients.remove(administrator, "native-http");
                            }),
                workers);
        org.awaitility.Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(
                () ->
                    assertThat(
                            jdbc.queryForObject(
                                "SELECT cardinality(pg_blocking_pids(?))>0",
                                Boolean.class,
                                removalPid.get()))
                        .isTrue());
        releaseGate.countDown();
        gate.get(15, TimeUnit.SECONDS);
        var response = mutation.get(15, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        removal.get(15, TimeUnit.SECONDS);
        assertThat(registrations.findByClientId("native-http")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Long.class))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_refresh_families", Long.class))
            .isZero();
        assertThat(dav(json.readTree(response.body()).path("access_token").asString()).statusCode())
            .isEqualTo(401);
      } finally {
        releaseGate.countDown();
      }
    } finally {
      jdbc.execute("DROP TRIGGER oauth_pause_mutation ON oauth2_authorization");
      jdbc.execute("DROP FUNCTION oauth_pause_mutation()");
      jdbc.execute("DROP TABLE oauth_mutation_pause");
    }
  }

  private String seedCode() throws Exception {
    return seedCode(
        jdbc.queryForObject("SELECT security_epoch FROM accounts WHERE id=?", UUID.class, account));
  }

  @Test
  void authorizationAndRegistrationReadUseOneSnapshotDuringClientRemoval() throws Exception {
    String code = seedCode();
    String access = json.readTree(exchange(code).body()).path("access_token").asString();
    String registration = registrations.findByClientId("native-http").getId();
    var readingRegistration = new CountDownLatch(1);
    var continueRead = new CountDownLatch(1);
    var interleaving =
        new RegisteredClientRepository() {
          @Override
          public void save(
              org.springframework.security.oauth2.server.authorization.client.RegisteredClient
                  value) {
            registrations.save(value);
          }

          @Override
          public org.springframework.security.oauth2.server.authorization.client.RegisteredClient
              findByClientId(String id) {
            return registrations.findByClientId(id);
          }

          @Override
          public org.springframework.security.oauth2.server.authorization.client.RegisteredClient
              findById(String id) {
            if (id.equals(registration)) {
              readingRegistration.countDown();
              try {
                if (!continueRead.await(10, TimeUnit.SECONDS))
                  throw new AssertionError("Registration read coordination timed out");
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
              }
            }
            return registrations.findById(id);
          }
        };
    var reader =
        new PrincipalOAuthAuthorizationService(
            OAuthJdbcSerialization.service(jdbc, interleaving),
            accounts,
            new OAuthClientLock(jdbc),
            transactions);
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var result =
          CompletableFuture.supplyAsync(
              () ->
                  reader.findByToken(
                      access,
                      org.springframework.security.oauth2.server.authorization.OAuth2TokenType
                          .ACCESS_TOKEN),
              workers);
      try {
        assertThat(readingRegistration.await(10, TimeUnit.SECONDS)).isTrue();
        clients.remove(administrator, "native-http");
        assertThat(registrations.findById(registration)).isNull();
        continueRead.countDown();
        assertThat(result.get(15, TimeUnit.SECONDS).getRegisteredClientId())
            .isEqualTo(registration);
      } finally {
        continueRead.countDown();
      }
    }
    assertThat(
            reader.findByToken(
                access,
                org.springframework.security.oauth2.server.authorization.OAuth2TokenType
                    .ACCESS_TOKEN))
        .isNull();
    assertThat(dav(access).statusCode()).isEqualTo(401);
  }

  @Test
  void frameworkWebAuthnPrincipalPersistsWithoutAllowingUnrelatedPolymorphicTypes()
      throws Exception {
    String code = seedCode();
    var pending =
        authorizations.findByToken(
            code,
            new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("code"));
    org.springframework.security.core.Authentication original =
        pending.getAttribute(Principal.class.getName());
    var user =
        org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity
            .builder()
            .id(org.springframework.security.web.webauthn.api.Bytes.random())
            .name("oauth-user")
            .displayName("OAuth user")
            .build();
    var webauthn =
        new org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication(
            user, original.getAuthorities());
    authorizations.save(
        OAuth2Authorization.from(pending).attribute(Principal.class.getName(), webauthn).build());
    org.springframework.security.core.Authentication restored =
        authorizations.findById(pending.getId()).getAttribute(Principal.class.getName());
    assertThat(restored)
        .isInstanceOf(
            org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication.class);
    assertThat(restored.getName()).isEqualTo("oauth-user");
    assertThat(
            new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>(
                restored.getAuthorities()))
        .containsExactlyElementsOf(webauthn.getAuthorities());
    org.busystack.caldav.security.RecentAuthentication.requireMfa(restored);
    assertThat(exchange(code).statusCode()).isEqualTo(200);
    jdbc.update(
        "UPDATE oauth2_authorization SET attributes=replace(attributes,?,?) WHERE id=?",
        org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication.class
            .getName(),
        "java.lang.ProcessBuilder",
        pending.getId());
    assertThatThrownBy(() -> authorizations.findById(pending.getId()))
        .hasRootCauseInstanceOf(tools.jackson.databind.exc.InvalidTypeIdException.class);
  }

  private String seedCode(UUID capturedEpoch) throws Exception {
    var client = registrations.findByClientId("native-http");
    var user =
        User.withUsername("oauth-user")
            .password(accounts.find(account).passwordHash())
            .roles("USER")
            .build();
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            user,
            null,
            List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                org.busystack.caldav.security.session.SessionSecurity.authority(capturedEpoch),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)));
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    var request =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://calendar.example/oauth2/authorize")
            .clientId("native-http")
            .redirectUri(CALLBACK)
            .scopes(Set.of("dav"))
            .additionalParameters(
                Map.of("code_challenge", challenge, "code_challenge_method", "S256"))
            .build();
    var code =
        new OAuth2AuthorizationCode(
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32).generateKey(),
            Instant.now(),
            Instant.now().plusSeconds(60));
    authorizations.save(
        OAuth2Authorization.withRegisteredClient(client)
            .principalName("oauth-user")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of("dav"))
            .attribute(Principal.class.getName(), authentication)
            .attribute(OAuth2AuthorizationRequest.class.getName(), request)
            .token(code)
            .build());
    return code.getTokenValue();
  }

  private HttpResponse<String> exchange(String code) throws Exception {
    return post(
        "/oauth2/token",
        Map.of(
            "grant_type",
            "authorization_code",
            "client_id",
            "native-http",
            "redirect_uri",
            CALLBACK,
            "code",
            code,
            "code_verifier",
            VERIFIER));
  }

  private HttpResponse<String> refresh(String value) throws Exception {
    return post(
        "/oauth2/token",
        Map.of("grant_type", "refresh_token", "client_id", "native-http", "refresh_token", value));
  }

  private HttpResponse<String> dav(String value) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(base() + "/dav/oauth-security-probe"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + value)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, Map<String, String> parameters) throws Exception {
    return post(path, form(parameters));
  }

  private HttpResponse<String> post(String path, String form) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> concurrentRefresh(
      String token, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(10, TimeUnit.SECONDS))
        throw new AssertionError("Refresh start latch timed out");
      return refresh(token);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    } catch (Exception ex) {
      throw new AssertionError(ex);
    }
  }

  private static String form(Map<String, String> parameters) {
    return parameters.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  private String base() {
    return "http://127.0.0.1:" + port;
  }

  @TestConfiguration
  static class ProbeConfiguration {
    @Bean
    ProbeController probe() {
      return new ProbeController();
    }
  }

  @RestController
  static class ProbeController {
    @GetMapping("/dav/oauth-security-probe")
    String principal(Principal principal) {
      return principal.getName();
    }
  }
}
