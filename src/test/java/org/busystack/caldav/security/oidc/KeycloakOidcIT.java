package org.busystack.caldav.security.oidc;

import static org.assertj.core.api.Assertions.*;

import java.io.StringReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.testing.TestTls;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/** Actual Spring OAuth2 Login code/nonce/PKCE exchange with disposable Keycloak; no mock issuer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(KeycloakOidcIT.ProbeConfiguration.class)
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KeycloakOidcIT {
  private static final int APP_PORT = reservePort();
  private static final String BASE = "https://localhost:" + APP_PORT;
  private static final String ALICE = "d0880a2f-bb8e-4f40-84dd-f0707fe08c77";
  private static final String BOB = "4695db38-f030-4724-be1b-6e45a204bc65";
  private static final String USER_PASSWORD = "disposable-keycloak-user-password";
  private static final String ADMIN_PASSWORD = "disposable-keycloak-admin-password";
  private static final SSLContext PREVIOUS_SSL_CONTEXT = defaultSslContext();
  private static final SSLSocketFactory PREVIOUS_SOCKET_FACTORY =
      HttpsURLConnection.getDefaultSSLSocketFactory();

  static {
    // Standard OIDC discovery/token/JWK clients use JVM trust. This isolated fork
    // trusts only the disposable test CA; hostname verification remains enabled.
    SSLContext.setDefault(TestTls.instance().context());
    HttpsURLConnection.setDefaultSSLSocketFactory(TestTls.instance().context().getSocketFactory());
  }

  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @Container
  static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>(
              new ImageFromDockerfile("busystack-keycloak-test:26.8.1", false)
                  .withDockerfile(java.nio.file.Path.of("qualification/keycloak/Dockerfile")))
          .withExposedPorts(8443)
          .withCreateContainerCmdModifier(
              cmd -> org.busystack.caldav.testing.LoopbackContainers.bind(cmd, 8443))
          .withLabel("org.busystack.caldav.test", "true")
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "fixture-admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
          .withEnv("JAVA_OPTS_KC_HEAP", "-Xms128m -Xmx512m")
          .withCopyToContainer(
              Transferable.of(realm()), "/opt/keycloak/data/import/busystack-fixture.json")
          .withCopyToContainer(
              MountableFile.forHostPath(
                  java.nio.file.Path.of(URI.create(TestTls.instance().keyStore())), 0444),
              "/opt/keycloak/conf/test-server.p12")
          .withCommand(
              "start-dev",
              "--import-realm",
              "--hostname-strict=false",
              "--https-port=8443",
              "--https-key-store-file=/opt/keycloak/conf/test-server.p12",
              "--https-key-store-password=" + TestTls.instance().password())
          .waitingFor(
              Wait.forHttp("/realms/busystack-fixture/.well-known/openid-configuration")
                  .forPort(8443)
                  .usingTls()
                  .forStatusCode(200))
          // Augmentation and schema import exceeded three minutes on the shared legacy CPU.
          // The qualification host needed just over five minutes under build contention.
          // Readiness is still probed; this is a bounded deadline, not a fixed sleep.
          .withStartupTimeout(Duration.ofMinutes(8));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("server.port", () -> APP_PORT);
    registry.add("server.ssl.enabled", () -> true);
    registry.add("server.ssl.key-store", () -> TestTls.instance().keyStore());
    registry.add("server.ssl.key-store-password", () -> TestTls.instance().password());
    registry.add("busystack.public-base-url", () -> BASE);
    registry.add("busystack.issuer", () -> BASE);
    registry.add("busystack.oidc.enabled", () -> true);
    registry.add("busystack.oidc.issuer", KeycloakOidcIT::issuer);
    registry.add("busystack.oidc.client-id", () -> "busystack-upstream");
    registry.add("busystack.oidc.client-secret", () -> "disposable-upstream-client-secret");
    registry.add("busystack.oidc.allowed-users[0].subject", () -> ALICE);
    registry.add("busystack.oidc.allowed-users[0].username", () -> "internal-alice");
    registry.add("busystack.oidc.allowed-users[0].display-name", () -> "Internal Alice");
  }

  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;
  @Autowired SessionRepository<? extends Session> sessions;
  @Autowired org.busystack.caldav.storage.CalendarStore calendars;

  @Autowired
  org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
      registrations;

  private final JsonMapper json = JsonMapper.builder().build();
  private String lastLoginStage;

  @AfterAll
  static void restoreJvmTrust() {
    SSLContext.setDefault(PREVIOUS_SSL_CONTEXT);
    HttpsURLConnection.setDefaultSSLSocketFactory(PREVIOUS_SOCKET_FACTORY);
  }

  @BeforeEach
  void reset() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    if (registrations.findById("fixture-client") == null)
      registrations.save(
          org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId(
                  "fixture-client")
              .clientId("fixture-client")
              .clientAuthenticationMethod(
                  org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
              .authorizationGrantType(
                  org.springframework.security.oauth2.core.AuthorizationGrantType
                      .AUTHORIZATION_CODE)
              .authorizationGrantType(
                  org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN)
              .redirectUri("http://127.0.0.1:34567/callback")
              .scope("dav")
              .clientSettings(
                  org.springframework.security.oauth2.server.authorization.settings.ClientSettings
                      .builder()
                      .requireProofKey(true)
                      .requireAuthorizationConsent(true)
                      .build())
              .tokenSettings(
                  org.springframework.security.oauth2.server.authorization.settings.TokenSettings
                      .builder()
                      .accessTokenFormat(
                          org.springframework.security.oauth2.server.authorization.settings
                              .OAuth2TokenFormat.REFERENCE)
                      .reuseRefreshTokens(false)
                      .build())
              .build());
  }

  @Test
  @Order(1)
  void verifiedRealmSubjectMapsToNonAdministratorAndUpstreamTokensCannotAuthenticateDav()
      throws Exception {
    var client = browser();
    var result = login(client, "realm-alice");
    assertThat(result.statusCode())
        .as("Login failed at %s: %s", lastLoginStage, visibleText(result.body()))
        .isEqualTo(302);
    assertThat(result.headers().firstValue("Location").orElse("")).doesNotContain("error");
    var probe = client.send(get(BASE + "/probe/oidc"), HttpResponse.BodyHandlers.ofString());
    assertThat(probe.statusCode()).isEqualTo(200);
    var principal = json.readTree(probe.body());
    assertThat(principal.path("name").asString()).isEqualTo("internal-alice");
    assertThat(principal.path("authorities").toString())
        .contains("FACTOR_AUTHORIZATION_CODE", "BUSYSTACK_TRUSTED_UPSTREAM")
        .doesNotContain("ROLE_ADMIN", "FACTOR_PASSWORD");
    var account = accounts.find("internal-alice");
    assertThat(account.administrator()).isFalse();
    assertThat(account.passwordHash()).isNull();
    var dav =
        client.send(
            HttpRequest.newBuilder(URI.create(BASE + "/dav/missing"))
                .header("Authorization", "Bearer " + principal.path("idToken").asString())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(dav.statusCode()).isEqualTo(401);
    var localFallback = browser();
    var page = localFallback.send(get(BASE + "/login"), HttpResponse.BodyHandlers.ofString());
    String csrf = parse(page.body()).hidden().get("_csrf");
    var password =
        post(
            localFallback,
            BASE + "/login",
            Map.of("_csrf", csrf, "username", "internal-alice", "password", USER_PASSWORD));
    assertThat(password.statusCode()).isEqualTo(302);
    assertThat(password.headers().firstValue("Location").orElse("")).contains("error");
  }

  @Test
  @Order(2)
  void unknownRealmUserDoesNotAutoLinkAnExistingLocalUsername() throws Exception {
    var localId =
        accounts.provision("realm-bob", "Local owner", "correct horse battery staple", true);
    var result = login(browser(), "realm-bob");
    assertThat(result.statusCode())
        .as("Login failed at %s: %s", lastLoginStage, visibleText(result.body()))
        .isEqualTo(302);
    assertThat(result.headers().firstValue("Location").orElse("")).contains("error");
    assertThat(accounts.find("realm-bob").id()).isEqualTo(localId);
    assertThat(accounts.find("realm-bob").passwordHash()).isNotNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM external_identities", Long.class))
        .isZero();
  }

  @Test
  @Order(3)
  void localDisablingRejectsAnAlreadyAuthenticatedFederatedBrowser() throws Exception {
    var client = browser();
    var loggedIn = login(client, "realm-alice");
    assertThat(loggedIn.statusCode())
        .as("Login failed at %s: %s", lastLoginStage, visibleText(loggedIn.body()))
        .isEqualTo(302);
    assertThat(loggedIn.headers().firstValue("Location").orElse("")).doesNotContain("error");
    var account = accounts.find("internal-alice");
    accounts.disable(account.id());
    var revoked = client.send(get(BASE + "/probe/oidc"), HttpResponse.BodyHandlers.ofString());
    assertThat(revoked.statusCode()).isEqualTo(302);
    assertThat(URI.create(BASE).resolve(revoked.headers().firstValue("Location").orElseThrow()))
        .isEqualTo(URI.create(BASE + "/login"));
    var result = login(browser(), "realm-alice");
    assertThat(result.headers().firstValue("Location").orElse("")).contains("error");
  }

  @Test
  @Order(4)
  void explicitMfaLinkingKeepsOwnershipAndReplacesTheOldSecurityContext() throws Exception {
    // The initial MFA session and old OAuth rows are fixtures, not WebAuthn qualification.
    // Linking, CSRF, provider password/code/PKCE, signed ID-token verification,
    // callback, and replacement JDBC session exercise the real HTTP application.
    var ownerId =
        accounts.provision(
            "linked-owner", "Existing local administrator", "correct horse battery staple", true);
    var original = accounts.find(ownerId);
    var calendarId = calendars.createCollection(ownerId, "Owned calendar", "VEVENT");
    var credential =
        accounts.issueDavCredential(ownerId, "Before linking", Instant.now().plusSeconds(3600));
    jdbc.update(
        "INSERT INTO oauth2_authorization(id,registered_client_id,principal_name,authorization_grant_type) VALUES ('link-fixture','fixture-client',?,'authorization_code')",
        original.username());
    jdbc.update(
        "INSERT INTO oauth2_authorization_consent(registered_client_id,principal_name,authorities) VALUES ('fixture-client',?,'SCOPE_dav')",
        original.username());
    jdbc.update(
        "INSERT INTO oauth_refresh_families(authorization_id,account_id,client_id,recovery_epoch,expires_at) SELECT 'link-fixture',?,'fixture-client',recovery_epoch,now()+interval '1 hour' FROM system_state WHERE id=1",
        ownerId);
    String oldSession = persistMfaSession(sessions, original);
    var client = browserFor(oldSession);
    var dashboard = client.send(get(BASE + "/"), HttpResponse.BodyHandlers.ofString());
    assertThat(dashboard.statusCode()).isEqualTo(200);
    String csrf = parse(dashboard.body()).hidden().get("_csrf");
    assertThat(csrf).isNotBlank();
    assertThat(post(client, BASE + "/security/identities/link", Map.of()).statusCode())
        .isEqualTo(403);
    URI unrelatedAuthorization = beginAuthorization(client);
    var begin = post(client, BASE + "/security/identities/link", Map.of("_csrf", csrf));
    assertThat(begin.statusCode()).isEqualTo(302);
    assertThat(URI.create(BASE).resolve(begin.headers().firstValue("Location").orElseThrow()))
        .isEqualTo(URI.create(BASE + "/oauth2/authorization/upstream"));
    URI linkAuthorization = beginAuthorization(client);
    // Complete an earlier parallel request first. Its callback must leave the account
    // untouched and must not consume the linking request's state-bound intent.
    var unrelated = completeLogin(client, unrelatedAuthorization, "realm-bob");
    assertThat(unrelated.statusCode()).isEqualTo(302);
    assertThat(unrelated.headers().firstValue("Location").orElse("")).contains("error");
    assertThat(accounts.find(ownerId).passwordHash()).isNotNull();
    assertThat(accounts.find(ownerId).securityEpoch()).isEqualTo(original.securityEpoch());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM external_identities", Long.class))
        .isZero();
    assertThat(accounts.verifyDav(original.username(), credential.secret())).isTrue();
    var linked = completeLogin(client, linkAuthorization, "realm-alice");
    assertThat(linked.statusCode())
        .as("Linking failed at %s: %s", lastLoginStage, visibleText(linked.body()))
        .isEqualTo(302);
    assertThat(linked.headers().firstValue("Location").orElse("")).doesNotContain("error");
    var current = accounts.find(ownerId);
    assertThat(current.username()).isEqualTo(original.username());
    assertThat(current.administrator()).isTrue();
    assertThat(current.passwordHash()).isNull();
    assertThat(current.securityEpoch()).isNotEqualTo(original.securityEpoch());
    assertThat(calendars.collection(ownerId, calendarId).owner()).isEqualTo(ownerId);
    assertThat(accounts.find("internal-alice")).isNull();
    assertThat(accounts.verifyDav(current.username(), credential.secret())).isFalse();
    assertThat(sessions.findById(oldSession)).isNull();
    var oldBrowser = browserFor(oldSession);
    var denied = oldBrowser.send(get(BASE + "/"), HttpResponse.BodyHandlers.ofString());
    assertThat(denied.statusCode()).isEqualTo(302);
    assertThat(URI.create(BASE).resolve(denied.headers().firstValue("Location").orElseThrow()))
        .isEqualTo(URI.create(BASE + "/login"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE id='link-fixture'", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization_consent WHERE principal_name=?",
                Long.class,
                current.username()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM oauth_refresh_families WHERE authorization_id='link-fixture'",
                Boolean.class))
        .isTrue();
    var probe = client.send(get(BASE + "/probe/oidc"), HttpResponse.BodyHandlers.ofString());
    assertThat(probe.statusCode()).isEqualTo(200);
    var principal = json.readTree(probe.body());
    assertThat(principal.path("name").asString()).isEqualTo(original.username());
    assertThat(principal.path("authorities").toString())
        .contains("ROLE_ADMIN", "BUSYSTACK_TRUSTED_UPSTREAM", "FACTOR_AUTHORIZATION_CODE")
        .doesNotContain("FACTOR_PASSWORD", "FACTOR_WEBAUTHN");
  }

  @Test
  @Order(5)
  void providerDisablingAndOutageHaveTheDocumentedBoundedRevocationBoundary() throws Exception {
    var client = browser();
    var loggedIn = login(client, "realm-alice");
    assertThat(loggedIn.statusCode())
        .as("Login failed at %s: %s", lastLoginStage, visibleText(loggedIn.body()))
        .isEqualTo(302);
    assertThat(loggedIn.headers().firstValue("Location").orElse("")).doesNotContain("error");
    var account = accounts.find("internal-alice");
    var credential =
        accounts.issueDavCredential(
            account.id(), "OIDC boundary fixture", Instant.now().plusSeconds(3600));
    var configured =
        KEYCLOAK.execInContainer(
            "/opt/keycloak/bin/kcadm.sh",
            "config",
            "credentials",
            "--server",
            "http://localhost:8080",
            "--realm",
            "master",
            "--user",
            "fixture-admin",
            "--password",
            ADMIN_PASSWORD);
    assertThat(configured.getExitCode()).isZero();
    var disabled =
        KEYCLOAK.execInContainer(
            "/opt/keycloak/bin/kcadm.sh",
            "update",
            "users/" + ALICE,
            "-r",
            "busystack-fixture",
            "-s",
            "enabled=false");
    assertThat(disabled.getExitCode()).isZero();
    var refused = login(browser(), "realm-alice");
    assertThat(refused.statusCode()).isEqualTo(200);
    assertThat(refused.body().toLowerCase(java.util.Locale.ROOT))
        .containsAnyOf("invalid username or password", "account is disabled");
    // OIDC login is not a real-time deprovisioning event for independent credentials.
    assertThat(accounts.verifyDav(account.username(), credential.secret())).isTrue();
    KEYCLOAK.stop();
    assertThat(accounts.verifyDav(account.username(), credential.secret())).isTrue();
    jdbc.update(
        "UPDATE external_identities SET last_verified_at=now()-interval '25 hours' WHERE account_id=?",
        account.id());
    assertThat(accounts.verifyDav(account.username(), credential.secret())).isFalse();
    accounts.provision("local-survivor", "Local survivor", "correct horse battery staple", false);
    var local = browser();
    var page = local.send(get(BASE + "/login"), HttpResponse.BodyHandlers.ofString());
    var localLogin =
        post(
            local,
            BASE + "/login",
            Map.of(
                "_csrf",
                parse(page.body()).hidden().get("_csrf"),
                "username",
                "local-survivor",
                "password",
                "correct horse battery staple"));
    assertThat(localLogin.statusCode()).isEqualTo(302);
    assertThat(localLogin.headers().firstValue("Location").orElse("")).doesNotContain("error");
  }

  private HttpResponse<String> login(HttpClient client, String username) throws Exception {
    return completeLogin(client, beginAuthorization(client), username);
  }

  private URI beginAuthorization(HttpClient client) throws Exception {
    var start =
        client.send(
            get(BASE + "/oauth2/authorization/upstream"), HttpResponse.BodyHandlers.ofString());
    assertThat(start.statusCode()).isEqualTo(302);
    var authorization = URI.create(start.headers().firstValue("Location").orElseThrow());
    assertThat(authorization.toString())
        .contains("code_challenge_method=S256", "prompt=login", "max_age=0");
    return authorization;
  }

  private HttpResponse<String> completeLogin(HttpClient client, URI authorization, String username)
      throws Exception {
    var login = client.send(get(authorization.toString()), HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(200);
    var form = parse(login.body());
    var values = new LinkedHashMap<>(form.hidden());
    values.put("username", username);
    values.put("password", USER_PASSWORD);
    values.put("credentialId", "");
    lastLoginStage = "Keycloak login form POST";
    var submitted = post(client, form.action(), values);
    if (submitted.statusCode() != 302) return submitted;
    String callback = submitted.headers().firstValue("Location").orElseThrow();
    assertThat(callback).startsWith(BASE + "/login/oauth2/code/upstream");
    lastLoginStage = "application OIDC callback";
    return client.send(get(callback), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(HttpClient client, String url, Map<String, String> values)
      throws Exception {
    String form =
        values.entrySet().stream()
            .map(
                entry ->
                    URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpClient browser() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .sslContext(TestTls.instance().context())
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  private HttpClient browserFor(String sessionId) throws java.io.IOException {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    String encoded = Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
    cookies.put(
        URI.create(BASE),
        Map.of(
            "Set-Cookie",
            List.of("JSESSIONID=" + encoded + "; Path=/; Secure; HttpOnly; SameSite=Lax")));
    return HttpClient.newBuilder()
        .cookieHandler(cookies)
        .sslContext(TestTls.instance().context())
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  private static <S extends Session> String persistMfaSession(
      SessionRepository<S> sessions, AccountService.Account account) {
    var principal =
        User.withUsername(account.username())
            .password(account.passwordHash())
            .authorities("ROLE_ADMIN")
            .build();
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principal,
            null,
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_ADMIN"),
                org.busystack.caldav.security.session.SessionSecurity.authority(
                    account.securityEpoch()),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)));
    S session = sessions.createSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(authentication));
    sessions.save(session);
    return session.getId();
  }

  private HttpRequest get(String uri) {
    return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(20)).GET().build();
  }

  private record Form(String action, Map<String, String> hidden) {}

  private String visibleText(String html) throws Exception {
    var text = new StringBuilder();
    new ParserDelegator()
        .parse(
            new StringReader(html),
            new HTMLEditorKit.ParserCallback() {
              @Override
              public void handleText(char[] value, int position) {
                if (text.length() < 500) text.append(value).append(' ');
              }
            },
            true);
    return text.substring(0, Math.min(text.length(), 500));
  }

  private Form parse(String html) throws Exception {
    var action = new java.util.concurrent.atomic.AtomicReference<String>();
    var fields = new LinkedHashMap<String, String>();
    new ParserDelegator()
        .parse(
            new StringReader(html),
            new HTMLEditorKit.ParserCallback() {
              @Override
              public void handleStartTag(
                  HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.FORM
                    && (action.get() == null
                        || "kc-form-login".equals(attributes.getAttribute(HTML.Attribute.ID))))
                  action.set((String) attributes.getAttribute(HTML.Attribute.ACTION));
              }

              @Override
              public void handleSimpleTag(
                  HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.INPUT
                    && attributes.getAttribute(HTML.Attribute.NAME) != null
                    && "hidden".equals(attributes.getAttribute(HTML.Attribute.TYPE)))
                  fields.put(
                      (String) attributes.getAttribute(HTML.Attribute.NAME),
                      String.valueOf(attributes.getAttribute(HTML.Attribute.VALUE)));
              }
            },
            true);
    return new Form(action.get(), fields);
  }

  private static int reservePort() {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String issuer() {
    return "https://localhost:" + KEYCLOAK.getMappedPort(8443) + "/realms/busystack-fixture";
  }

  private static SSLContext defaultSslContext() {
    try {
      return SSLContext.getDefault();
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static byte[] realm() {
    Map<String, Object> client =
        Map.ofEntries(
            Map.entry("clientId", "busystack-upstream"),
            Map.entry("secret", "disposable-upstream-client-secret"),
            Map.entry("protocol", "openid-connect"),
            Map.entry("publicClient", false),
            Map.entry("standardFlowEnabled", true),
            Map.entry("directAccessGrantsEnabled", false),
            Map.entry("implicitFlowEnabled", false),
            Map.entry("serviceAccountsEnabled", false),
            Map.entry("redirectUris", List.of(BASE + "/login/oauth2/code/upstream")),
            Map.entry("webOrigins", List.of(BASE)),
            Map.entry("attributes", Map.of("pkce.code.challenge.method", "S256")));
    var users =
        List.of(
            Map.of(
                "id",
                ALICE,
                "username",
                "realm-alice",
                "enabled",
                true,
                "email",
                "alice@example.test",
                "emailVerified",
                true,
                "firstName",
                "Realm",
                "lastName",
                "Alice",
                "realmRoles",
                List.of("admin"),
                "credentials",
                List.of(Map.of("type", "password", "value", USER_PASSWORD, "temporary", false))),
            Map.of(
                "id",
                BOB,
                "username",
                "realm-bob",
                "enabled",
                true,
                "email",
                "bob@example.test",
                "emailVerified",
                true,
                "firstName",
                "Realm",
                "lastName",
                "Bob",
                "credentials",
                List.of(Map.of("type", "password", "value", USER_PASSWORD, "temporary", false))));
    return JsonMapper.builder()
        .build()
        .writeValueAsBytes(
            Map.of(
                "realm",
                "busystack-fixture",
                "enabled",
                true,
                "sslRequired",
                "none",
                "roles",
                Map.of("realm", List.of(Map.of("name", "admin"))),
                "clients",
                List.of(client),
                "users",
                users));
  }

  @TestConfiguration
  static class ProbeConfiguration {
    @Bean
    ProbeController oidcProbe() {
      return new ProbeController();
    }
  }

  @RestController
  static class ProbeController {
    @GetMapping("/probe/oidc")
    Map<String, Object> principal(Authentication authentication) {
      var user = (OidcUser) authentication.getPrincipal();
      return Map.of(
          "name",
          authentication.getName(),
          "authorities",
          authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList(),
          "idToken",
          user.getIdToken().getTokenValue());
    }
  }
}
