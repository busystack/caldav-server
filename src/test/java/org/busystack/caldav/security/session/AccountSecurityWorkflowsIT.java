package org.busystack.caldav.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.security.audit.SecurityHashes;
import org.busystack.caldav.security.oauth.RefreshFamilyStore;
import org.busystack.caldav.security.passkey.AccountPasskeyCredentials;
import org.busystack.caldav.security.passkey.AccountPasskeyUsers;
import org.busystack.caldav.security.passkey.CeremonyStore;
import org.busystack.caldav.security.password.AccountPasswordService;
import org.busystack.caldav.security.recovery.RecoveryService;
import org.busystack.caldav.security.throttle.LoginThrottleService;
import org.busystack.caldav.testing.LoopbackContainers;
import org.busystack.caldav.testing.TestTls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.webauthn.api.*;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real JDBC/HTTPS security workflows; seeded factor contexts do not qualify WebAuthn cryptography.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "busystack.public-base-url=https://calendar.example",
      "busystack.issuer=https://calendar.example"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@org.springframework.context.annotation.Import(
    AccountSecurityWorkflowsIT.SlowRequestConfiguration.class)
class AccountSecurityWorkflowsIT {
  @Container static final PostgreSQLContainer POSTGRES = LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("server.ssl.enabled", () -> true);
    registry.add("server.ssl.key-store", () -> TestTls.instance().keyStore());
    registry.add("server.ssl.key-store-password", () -> TestTls.instance().password());
  }

  @Autowired AccountService accounts;
  @Autowired SessionSecurity security;
  @Autowired SessionService sessions;
  @Autowired SessionRepository<? extends Session> repository;
  @Autowired RecoveryService recovery;
  @Autowired AccountPasswordService passwords;
  @Autowired PasswordEncoder encoder;
  @Autowired AccountPasskeyUsers passkeyUsers;
  @Autowired AccountPasskeyCredentials passkeys;
  @Autowired RefreshFamilyStore families;

  @Autowired
  org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
      registrations;

  @Autowired CeremonyStore ceremonies;
  @Autowired LoginThrottleService throttle;
  @Autowired SecurityAudit audit;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired SlowRequestHarness slowRequests;
  @LocalServerPort int port;
  UUID owner, other;

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.reset(
        jdbc,
        "TRUNCATE spring_session,login_throttle,security_audit,passkey_revocations,oauth2_authorization,oauth2_authorization_consent,oauth2_registered_client RESTART IDENTITY CASCADE",
        "TRUNCATE accounts CASCADE");
    owner =
        accounts.provision("session-owner", "Session owner", "correct horse battery staple", true);
    other =
        accounts.provision(
            "session-other", "Another account", "correct horse battery staple", false);
  }

  @Test
  void realPasswordLoginPersistsSessionFixationAndSurvivesARepositoryRestart() throws Exception {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient browser = browser(cookies);
    String csrf = csrf(browser, "/login");
    String before =
        cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("JSESSIONID"))
            .findFirst()
            .orElseThrow()
            .getValue();
    var loggedIn =
        post(
            browser,
            "/login",
            Map.of(
                "username",
                "session-owner",
                "password",
                "correct horse battery staple",
                "_csrf",
                csrf));
    assertThat(loggedIn.statusCode()).isEqualTo(302);
    String after =
        cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("JSESSIONID"))
            .findFirst()
            .orElseThrow()
            .getValue();
    assertThat(after).isNotEqualTo(before);
    var page = get(browser, "/security/sessions");
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("This browser");
    String id =
        jdbc.queryForObject(
            "SELECT session_id FROM spring_session WHERE principal_name='session-owner'",
            String.class);
    var restarted = new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactions));
    Session persisted = restarted.findById(id);
    SecurityContext context =
        persisted.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(context.getAuthentication().getName()).isEqualTo("session-owner");
    assertThat(security.assertCurrent(context.getAuthentication()).id()).isEqualTo(owner);
    assertThat(context.getAuthentication().getAuthorities())
        .anySatisfy(
            authority ->
                assertThat(authority.getAuthority())
                    .isEqualTo(FactorGrantedAuthority.PASSWORD_AUTHORITY));
    assertThat(loggedIn.headers().allValues("Set-Cookie"))
        .anySatisfy(
            cookie ->
                assertThat(cookie).contains("JSESSIONID=", "Secure", "HttpOnly", "SameSite=Lax"));
  }

  @Test
  void staleAnonymousRequestCannotRestoreThePreLoginCookie() throws Exception {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient browser = browser(cookies);
    String csrf = csrf(browser, "/login");
    String oldCookie =
        cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("JSESSIONID"))
            .findFirst()
            .orElseThrow()
            .getValue();
    String oldId = new String(Base64.getDecoder().decode(oldCookie), StandardCharsets.UTF_8);
    HttpClient oldBrowser = browserFor(oldId);
    try (var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var delayed =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return get(oldBrowser, "/login?fixtureSlow=true");
                } catch (Exception ex) {
                  throw new AssertionError(ex);
                }
              },
              workers);
      try {
        assertThat(slowRequests.entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(oldId.equals(slowRequests.loadedId)).isTrue();
        var login =
            post(
                browser,
                "/login",
                Map.of(
                    "username",
                    "session-owner",
                    "password",
                    "correct horse battery staple",
                    "_csrf",
                    csrf));
        assertThat(login.statusCode()).isEqualTo(302);
      } finally {
        slowRequests.release.countDown();
      }
      assertThat(delayed.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
    assertThat(repository.findById(oldId)).isNull();
    assertThat(get(browser, "/security/sessions").statusCode()).isEqualTo(200);
    assertThat(get(oldBrowser, "/security/sessions").statusCode()).isEqualTo(302);
  }

  @Test
  void onlyRecentMfaCanRevokeOwnedSessionsAndRevocationIsDurable() {
    String owned = persist(auth(owner, true));
    String foreign = persist(auth(other, true));
    UUID ownedPrimary = primaryId(owned), foreignPrimary = primaryId(foreign);
    assertThatThrownBy(() -> sessions.revoke(auth(owner, false), ownedPrimary, owned, "127.0.0.1"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(sessions.revoke(auth(owner, true), foreignPrimary, owned, "127.0.0.1")).isFalse();
    assertThat(repository.findById(foreign)).isNotNull();
    assertThat(sessions.revoke(auth(owner, true), ownedPrimary, owned, "127.0.0.1")).isTrue();
    assertThat(repository.findById(owned)).isNull();
    Session restored =
        new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactions))
            .findById(owned);
    assertThat(restored).isNull();
  }

  @Test
  void selfSessionRevocationWorksOverHttpsAndRequiresCsrf() throws Exception {
    String id = persist(auth(owner, true));
    HttpClient browser = browserFor(id);
    UUID primary = primaryId(id);
    assertThat(post(browser, "/security/sessions/" + primary + "/revoke", Map.of()).statusCode())
        .isEqualTo(403);
    String csrf = csrf(browser, "/security/sessions");
    assertThat(
            post(browser, "/security/sessions/" + primary + "/revoke", Map.of("_csrf", csrf))
                .statusCode())
        .isEqualTo(302);
    assertThat(repository.findById(id)).isNull();
    assertThat(get(browser, "/security/sessions").statusCode()).isEqualTo(302);
  }

  @Test
  void oldCredentialSnapshotCannotCreateAValidSessionAfterReset() throws Exception {
    Authentication old = auth(owner, true);
    security.invalidateAll(owner);
    String stale = persist(old);
    assertThat(get(browserFor(stale), "/security/sessions").statusCode()).isEqualTo(403);
    assertThat(repository.findById(stale)).isNull();
    assertThatThrownBy(() -> security.assertCurrent(old)).isInstanceOf(AccessDeniedException.class);
    assertThat(get(browserFor(persist(auth(owner, true))), "/security/sessions").statusCode())
        .isEqualTo(200);
  }

  @Test
  void disablingEndsSessionsAndReenablingDoesNotReviveTheirAuthority() {
    Authentication old = auth(owner, true);
    String session = persist(old);
    accounts.disable(owner);
    assertThat(repository.findById(session)).isNull();
    jdbc.update("UPDATE accounts SET enabled=true WHERE id=?", owner);
    assertThatThrownBy(() -> security.assertCurrent(old)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void recoveryCodesAppearOnceAndConsumeAtomicallyWithCompleteAccessReset() {
    Authentication full = auth(owner, true);
    var issued = recovery.issue(full, "127.0.0.1");
    assertThat(issued.codes())
        .hasSize(10)
        .doesNotHaveDuplicates()
        .allSatisfy(code -> assertThat(code).hasSize(80));
    assertThat(recovery.status(full).remaining()).isEqualTo(10);
    assertThat(jdbc.queryForList("SELECT token_hash FROM account_recovery_codes", String.class))
        .allSatisfy(hash -> assertThat(hash).hasSize(64).isNotIn(issued.codes()));
    var key = key(owner);
    String session = persist(full);
    var dav =
        accounts.issueDavCredential(owner, "Recovery fixture", Instant.now().plusSeconds(3600));
    String grant = UUID.randomUUID().toString();
    registrations.save(
        org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId(
                "recovery-client")
            .clientId("recovery-client")
            .clientAuthenticationMethod(
                org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
            .authorizationGrantType(
                org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://127.0.0.1:34567/callback")
            .scope("dav")
            .build());
    families.register(
        new RefreshFamilyStore.Family(
            grant, owner, "recovery-client", Instant.now().plusSeconds(3600)),
        "recovery-refresh-token-fixture",
        Instant.now().plusSeconds(3600),
        () -> {});
    recovery.recover(auth(owner, false), issued.codes().getFirst(), "127.0.0.1");
    assertThat(repository.findById(session)).isNull();
    assertThat(accounts.verifyDav("session-owner", dav.secret())).isFalse();
    assertThat(families.isActive(grant)).isFalse();
    assertThat(passkeys.findByCredentialId(key.getCredentialId())).isNull();
    assertThatThrownBy(() -> passkeys.save(key)).isInstanceOf(BadCredentialsException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_recovery_codes WHERE used_at IS NOT NULL",
                Long.class))
        .isEqualTo(1);
    assertThatThrownBy(
            () -> recovery.recover(auth(owner, false), issued.codes().getFirst(), "127.0.0.1"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(recovery.status(auth(owner, false)).remaining()).isEqualTo(9);
    assertThat(
            jdbc.queryForList(
                "SELECT row_to_json(security_audit)::text FROM security_audit", String.class))
        .allSatisfy(
            value ->
                assertThat(value)
                    .doesNotContain(
                        issued.codes().getFirst(), "correct horse battery staple", dav.secret()));
  }

  @Test
  void recoveryRequiresRecentPrimaryAndRejectsCrossAccountWrongSecretExpiryAndRestore() {
    var issued = recovery.issue(auth(owner, true), "127.0.0.1");
    assertThatThrownBy(() -> recovery.issue(auth(owner, false), "127.0.0.1"))
        .isInstanceOf(AccessDeniedException.class);
    String original = issued.codes().getFirst();
    assertThatThrownBy(() -> recovery.recover(auth(other, false), original, "127.0.0.1"))
        .isInstanceOf(AccessDeniedException.class);
    String wrong = original.substring(0, 79) + (original.endsWith("A") ? "B" : "A");
    assertThatThrownBy(() -> recovery.recover(auth(owner, false), wrong, "127.0.0.1"))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update(
        "UPDATE account_recovery_codes SET created_at=now()-interval '2 years',expires_at=now()-interval '1 year'");
    assertThatThrownBy(() -> recovery.recover(auth(owner, false), original, "127.0.0.1"))
        .isInstanceOf(AccessDeniedException.class);
    String restored = recovery.issue(auth(owner, true), "127.0.0.1").codes().getFirst();
    jdbc.update("UPDATE system_state SET recovery_epoch=gen_random_uuid() WHERE id=1");
    assertThatThrownBy(() -> recovery.recover(auth(owner, false), restored, "127.0.0.1"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_recovery_codes WHERE used_at IS NOT NULL",
                Long.class))
        .isZero();
  }

  @Test
  void concurrentRecoveryUsesOneCodeOnlyOnce() throws Exception {
    String code = recovery.issue(auth(owner, true), "127.0.0.1").codes().getFirst();
    Authentication primary = auth(owner, false);
    CountDownLatch start = new CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          CompletableFuture.supplyAsync(() -> recoverWhenStarted(start, primary, code), workers);
      var second =
          CompletableFuture.supplyAsync(() -> recoverWhenStarted(start, primary, code), workers);
      start.countDown();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM account_recovery_codes WHERE used_at IS NOT NULL",
                  Long.class))
          .isEqualTo(1);
    }
  }

  @Test
  void failedRecoveryTransactionDoesNotConsumeTheCodeOrRevokeAccess() {
    String code = recovery.issue(auth(owner, true), "127.0.0.1").codes().getFirst();
    Authentication before = auth(owner, false);
    jdbc.execute(
        "CREATE FUNCTION fail_security_reset() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture reset failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_security_reset BEFORE UPDATE OF security_epoch ON accounts FOR EACH ROW EXECUTE FUNCTION fail_security_reset()");
    try {
      assertThatThrownBy(() -> recovery.recover(before, code, "127.0.0.1"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM account_recovery_codes WHERE used_at IS NOT NULL",
                  Long.class))
          .isZero();
      assertThat(security.assertCurrent(before).id()).isEqualTo(owner);
    } finally {
      jdbc.execute("DROP TRIGGER fail_security_reset ON accounts");
      jdbc.execute("DROP FUNCTION fail_security_reset()");
    }
    recovery.recover(before, code, "127.0.0.1");
  }

  @Test
  void passwordChangeRetainsPasskeysAndRevokesAllOtherAccess() {
    Authentication before = auth(owner, true);
    var key = key(owner);
    String session = persist(before);
    var dav =
        accounts.issueDavCredential(owner, "Password fixture", Instant.now().plusSeconds(3600));
    recovery.issue(before, "127.0.0.1");
    passwords.change(before, "a replacement password for testing", "127.0.0.1");
    assertThat(
            encoder.matches(
                "a replacement password for testing", accounts.find(owner).passwordHash()))
        .isTrue();
    assertThat(encoder.matches("correct horse battery staple", accounts.find(owner).passwordHash()))
        .isFalse();
    assertThat(passkeys.findByCredentialId(key.getCredentialId())).isNotNull();
    assertThat(repository.findById(session)).isNull();
    assertThat(accounts.verifyDav("session-owner", dav.secret())).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_recovery_codes WHERE account_id=?",
                Long.class,
                owner))
        .isZero();
    assertThatThrownBy(() -> security.assertCurrent(before))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void passwordChangeRequiresMfaAndNeverCreatesAnExternalAccountPassword() {
    assertThatThrownBy(
            () ->
                passwords.change(
                    auth(owner, false), "a replacement password for testing", "127.0.0.1"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> passwords.change(auth(owner, true), "界".repeat(25), "127.0.0.1"))
        .isInstanceOf(IllegalArgumentException.class);
    jdbc.update(
        "INSERT INTO external_identities(issuer,subject,account_id,source,last_verified_at) VALUES ('https://issuer.invalid','subject',?,'LINKED',now())",
        owner);
    assertThatThrownBy(
            () ->
                passwords.change(
                    auth(owner, true), "a replacement password for testing", "127.0.0.1"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void hashUpgradeKeepsEpochButStaleHashUpgradeCannotOverwriteAChangedPassword() {
    String oldHash = new BCryptPasswordEncoder(10).encode("correct horse battery staple");
    jdbc.update("UPDATE accounts SET password_hash=? WHERE id=?", oldHash, owner);
    UserDetails snapshot = user(owner);
    UUID epoch = epoch(owner);
    String stronger = encoder.encode("correct horse battery staple");
    UserDetails upgraded = passwords.updatePassword(snapshot, stronger);
    assertThat(upgraded.getPassword()).isEqualTo(stronger);
    assertThat(epoch(owner)).isEqualTo(epoch);
    assertThat(accounts.find(owner).passwordHash()).isEqualTo(stronger);
    UserDetails stale = user(owner);
    passwords.change(auth(owner, true), "a replacement password for testing", "127.0.0.1");
    assertThatThrownBy(
            () -> passwords.updatePassword(stale, encoder.encode("correct horse battery staple")))
        .isInstanceOf(CredentialsExpiredException.class);
    assertThat(
            encoder.matches(
                "a replacement password for testing", accounts.find(owner).passwordHash()))
        .isTrue();
  }

  @Test
  void actualDaoLoginUpgradesBcryptWorkFactorWithoutRotatingEpoch() throws Exception {
    jdbc.update(
        "UPDATE accounts SET password_hash=? WHERE id=?",
        new BCryptPasswordEncoder(10).encode("correct horse battery staple"),
        owner);
    UUID epoch = epoch(owner);
    HttpClient browser = browser(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    String csrf = csrf(browser, "/login");
    assertThat(
            post(
                    browser,
                    "/login",
                    Map.of(
                        "username",
                        "session-owner",
                        "password",
                        "correct horse battery staple",
                        "_csrf",
                        csrf))
                .statusCode())
        .isEqualTo(302);
    assertThat(accounts.find(owner).passwordHash()).startsWith("$2a$12$");
    assertThat(epoch(owner)).isEqualTo(epoch);
    assertThat(get(browser, "/security/sessions").statusCode()).isEqualTo(200);
  }

  @Test
  void durableCeremoniesBindSessionPurposeGenerationAndSingleUse() throws Exception {
    String session = UUID.randomUUID().toString(),
        hash = SecurityHashes.sha256(UUID.randomUUID().toString());
    ceremonies.issue(session, CeremonyStore.Purpose.REGISTER, hash, auth(owner, false));
    assertThat(
            ceremonies.consume(UUID.randomUUID().toString(), CeremonyStore.Purpose.REGISTER, hash))
        .isFalse();
    assertThat(ceremonies.consume(session, CeremonyStore.Purpose.AUTHENTICATE, hash)).isFalse();
    CountDownLatch start = new CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          CompletableFuture.supplyAsync(() -> consumeWhenStarted(start, session, hash), workers);
      var second =
          CompletableFuture.supplyAsync(() -> consumeWhenStarted(start, session, hash), workers);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    ceremonies.issue(session, CeremonyStore.Purpose.REGISTER, hash, auth(owner, false));
    security.invalidateAll(owner);
    assertThat(ceremonies.consume(session, CeremonyStore.Purpose.REGISTER, hash)).isFalse();
    ceremonies.issue(session, CeremonyStore.Purpose.AUTHENTICATE, hash, null);
    jdbc.update(
        "UPDATE webauthn_ceremonies SET created_at=now()-interval '2 minutes',expires_at=now()-interval '1 minute'");
    assertThat(ceremonies.consume(session, CeremonyStore.Purpose.AUTHENTICATE, hash)).isFalse();
  }

  @Test
  void frameworkOptionsAreRecordedBeforeResponseAndOneAttemptConsumesChallengeOverHttps()
      throws Exception {
    HttpClient browser = browser(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    String csrf = csrf(browser, "/login");
    var options = post(browser, "/webauthn/authenticate/options", Map.of("_csrf", csrf));
    assertThat(options.statusCode()).isEqualTo(200);
    assertThat(options.body()).contains("challenge");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM webauthn_ceremonies", Long.class))
        .isEqualTo(1);
    assertThat(post(browser, "/login/webauthn", Map.of("_csrf", csrf)).statusCode()).isEqualTo(401);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM webauthn_ceremonies", Long.class))
        .isZero();
    assertThat(post(browser, "/login/webauthn", Map.of("_csrf", csrf)).statusCode()).isEqualTo(401);
  }

  @Test
  void boundedTemporaryThrottleDoesNotExtendLockoutOrDisableAccounts() {
    for (int count = 0; count < 5; count++)
      assertThat(throttle.reserve("session-owner", "test-peer").allowed()).isTrue();
    assertThat(throttle.reserve("session-owner", "test-peer").allowed()).isFalse();
    Instant blocked =
        jdbc.queryForObject(
            "SELECT max(blocked_until) FROM login_throttle",
            (rs, row) -> rs.getTimestamp(1).toInstant());
    for (int count = 0; count < 5; count++)
      assertThat(throttle.reserve("SESSION-OWNER", "test-peer").allowed()).isFalse();
    Instant unchanged =
        jdbc.queryForObject(
            "SELECT max(blocked_until) FROM login_throttle",
            (rs, row) -> rs.getTimestamp(1).toInstant());
    assertThat(unchanged).isEqualTo(blocked);
    assertThat(accounts.find(owner).enabled()).isTrue();
    jdbc.update(
        "UPDATE login_throttle SET blocked_until=now()-interval '1 second' WHERE blocked_until IS NOT NULL");
    assertThat(throttle.reserve("session-owner", "test-peer").allowed()).isTrue();
    throttle.result("session-owner", "test-peer", SecurityAudit.Action.PASSWORD_LOGIN, true);
    assertThat(throttle.reserve("session-owner", "test-peer").allowed()).isTrue();
  }

  @Test
  void throttleCapacityAndAuditRetentionAreBounded() {
    jdbc.update(
        "INSERT INTO login_throttle(bucket_key,window_started,attempts,expires_at) SELECT lpad(to_hex(value),64,'0'),now(),0,now()+interval '1 hour' FROM generate_series(1,10000) AS value");
    assertThat(throttle.reserve("another-name", "another-peer").allowed()).isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM login_throttle", Long.class))
        .isEqualTo(10000);
    jdbc.update("UPDATE login_throttle SET expires_at=now()-interval '1 second'");
    assertThat(throttle.reserve("another-name", "another-peer").allowed()).isTrue();
    jdbc.update(
        "INSERT INTO security_audit(action,outcome) SELECT 'PASSWORD_LOGIN','FAILURE' FROM generate_series(1,10050)");
    audit.record(
        owner, SecurityAudit.Action.PASSWORD_LOGIN, SecurityAudit.Outcome.SUCCESS, "127.0.0.1");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM security_audit", Long.class))
        .isLessThanOrEqualTo(10000);
    jdbc.update("UPDATE security_audit SET occurred_at=now()-interval '31 days'");
    audit.prune();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM security_audit", Long.class)).isZero();
  }

  @Test
  void realLoginFailuresThrottleAndForwardedHeadersCannotChoosePeer() throws Exception {
    HttpClient browser = browser(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    String csrf = csrf(browser, "/login");
    for (int count = 0; count < 5; count++)
      assertThat(
              post(
                      browser,
                      "/login",
                      Map.of(
                          "username",
                          "session-owner",
                          "password",
                          "invalid-password",
                          "_csrf",
                          csrf))
                  .statusCode())
          .isEqualTo(302);
    var denied =
        post(
            browser,
            "/login",
            Map.of(
                "username",
                "session-owner",
                "password",
                "correct horse battery staple",
                "_csrf",
                csrf));
    assertThat(denied.statusCode()).isEqualTo(429);
    assertThat(Long.parseLong(denied.headers().firstValue("Retry-After").orElseThrow()))
        .isBetween(1L, 60L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM security_audit WHERE outcome='FAILURE'", Long.class))
        .isEqualTo(5);
    assertThat(accounts.find(owner).enabled()).isTrue();
    String fields =
        form(
            Map.of(
                "username",
                "session-owner",
                "password",
                "correct horse battery staple",
                "_csrf",
                csrf));
    var forwarded =
        browser.send(
            HttpRequest.newBuilder(URI.create(base() + "/login"))
                .timeout(Duration.ofSeconds(15))
                .header("X-Forwarded-For", "203.0.113.99")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(fields))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(forwarded.statusCode()).isEqualTo(429);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM security_audit WHERE peer_hash=?",
                Long.class,
                SecurityHashes.sha256("203.0.113.99")))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM security_audit WHERE peer_hash=?",
                Long.class,
                SecurityHashes.sha256("127.0.0.1")))
        .isGreaterThan(0);
  }

  private Authentication auth(UUID account, boolean mfa) {
    UserDetails user = user(account);
    var authorities =
        new ArrayList<org.springframework.security.core.GrantedAuthority>(user.getAuthorities());
    authorities.add(
        FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY));
    if (mfa)
      authorities.add(
          FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY));
    return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
  }

  private UserDetails user(UUID account) {
    var current = accounts.find(account);
    return User.withUsername(current.username())
        .password(current.passwordHash())
        .authorities("ROLE_USER", SessionSecurity.authority(epoch(account)).getAuthority())
        .build();
  }

  private UUID epoch(UUID account) {
    return jdbc.queryForObject(
        "SELECT security_epoch FROM accounts WHERE id=?", UUID.class, account);
  }

  private String persist(Authentication authentication) {
    return persist(repository, authentication);
  }

  private static <S extends Session> String persist(
      SessionRepository<S> repository, Authentication authentication) {
    S session = repository.createSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(authentication));
    repository.save(session);
    return session.getId();
  }

  private UUID primaryId(String session) {
    return UUID.fromString(
        jdbc.queryForObject(
            "SELECT primary_id FROM spring_session WHERE session_id=?", String.class, session));
  }

  private CredentialRecord key(UUID account) {
    var user =
        ImmutablePublicKeyCredentialUserEntity.builder()
            .id(Bytes.random())
            .name(accounts.find(account).username())
            .displayName("Recovery fixture")
            .build();
    passkeyUsers.save(user);
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var key =
        ImmutableCredentialRecord.builder()
            .credentialId(Bytes.random())
            .userEntityUserId(user.getId())
            .publicKey(new ImmutablePublicKeyCose(new byte[] {1, 2, 3}))
            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .signatureCount(1)
            .uvInitialized(true)
            .backupEligible(false)
            .backupState(false)
            .transports(Set.of(AuthenticatorTransport.INTERNAL))
            .label("Recovery fixture")
            .created(now)
            .lastUsed(now)
            .build();
    passkeys.save(key);
    return key;
  }

  private boolean recoverWhenStarted(
      CountDownLatch start, Authentication authentication, String code) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Recovery latch timed out");
      recovery.recover(authentication, code, "127.0.0.1");
      return true;
    } catch (AccessDeniedException ex) {
      return false;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
  }

  private boolean consumeWhenStarted(CountDownLatch start, String session, String hash) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Ceremony latch timed out");
      return ceremonies.consume(session, CeremonyStore.Purpose.REGISTER, hash);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
  }

  private HttpClient browser(CookieManager cookies) {
    return HttpClient.newBuilder()
        .sslContext(TestTls.instance().context())
        .cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  private HttpClient browserFor(String id) throws java.io.IOException {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    String encoded = Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    cookies.put(
        URI.create(base()),
        Map.of(
            "Set-Cookie",
            List.of("JSESSIONID=" + encoded + "; Path=/; Secure; HttpOnly; SameSite=Lax")));
    return browser(cookies);
  }

  private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
    return browser.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(HttpClient browser, String path, Map<String, String> fields)
      throws Exception {
    return browser.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String csrf(HttpClient browser, String path) throws Exception {
    var page = get(browser, path);
    assertThat(page.statusCode()).isEqualTo(200);
    var token = new java.util.concurrent.atomic.AtomicReference<String>();
    new javax.swing.text.html.parser.ParserDelegator()
        .parse(
            new java.io.StringReader(page.body()),
            new javax.swing.text.html.HTMLEditorKit.ParserCallback() {
              @Override
              public void handleSimpleTag(
                  javax.swing.text.html.HTML.Tag tag,
                  javax.swing.text.MutableAttributeSet attributes,
                  int position) {
                if (tag == javax.swing.text.html.HTML.Tag.INPUT
                    && "_csrf"
                        .equals(attributes.getAttribute(javax.swing.text.html.HTML.Attribute.NAME)))
                  token.set(
                      (String) attributes.getAttribute(javax.swing.text.html.HTML.Attribute.VALUE));
              }
            },
            true);
    assertThat(token.get()).isNotBlank();
    return token.get();
  }

  private static String form(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  private String base() {
    return "https://127.0.0.1:" + port;
  }

  static final class SlowRequestHarness {
    volatile String loadedId;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class SlowRequestConfiguration {
    @org.springframework.context.annotation.Bean
    SlowRequestHarness slowRequestHarness() {
      return new SlowRequestHarness();
    }

    @org.springframework.context.annotation.Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> slowRequest(
        SlowRequestHarness harness) {
      var filter =
          new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();
      filter.setOrder(
          org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER + 1);
      filter.setFilter(
          (request, response, chain) -> {
            var http = (jakarta.servlet.http.HttpServletRequest) request;
            if ("GET".equals(http.getMethod())
                && "/login".equals(http.getServletPath())
                && "true".equals(http.getParameter("fixtureSlow"))) {
              var session = http.getSession();
              harness.loadedId = session.getId();
              harness.entered.countDown();
              try {
                if (!harness.release.await(15, TimeUnit.SECONDS))
                  throw new AssertionError("Delayed request latch timed out");
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new jakarta.servlet.ServletException(ex);
              }
              session.setMaxInactiveInterval(session.getMaxInactiveInterval());
              session.setAttribute("concurrent-fixture", "completed");
              response.getWriter().write("Delayed request completed");
            } else chain.doFilter(request, response);
          });
      return filter;
    }
  }
}
