package org.busystack.caldav.security.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.oauth.OAuthClientService;
import org.busystack.caldav.security.session.SessionSecurity;
import org.busystack.caldav.testing.SignedPasskeyClient;
import org.busystack.caldav.testing.TestTls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real signatures, CSRF/session ceremonies and persistent state; no seeded authentication grants.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "busystack.public-base-url=https://localhost/team",
      "busystack.issuer=https://localhost/team"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class PasskeyHttpIT {
  private static final String PASSWORD = "Independent signed fixture password";
  private static final String CALLBACK = "http://127.0.0.1:34567/callback";

  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("server.ssl.enabled", () -> true);
    registry.add("server.ssl.key-store", () -> TestTls.instance().keyStore());
    registry.add("server.ssl.key-store-password", () -> TestTls.instance().password());
  }

  @LocalServerPort int port;
  @Autowired AccountService accounts;
  @Autowired AccountPasskeyCredentials credentials;
  @Autowired OAuthClientService clients;
  @Autowired JdbcTemplate jdbc;
  SignedPasskeyClient transport;
  UUID owner;
  UUID recipient;
  UUID administrator;

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    jdbc.execute("TRUNCATE passkey_revocations");
    // This class owns the disposable database, including its hashed account and loopback buckets.
    jdbc.execute("TRUNCATE login_throttle");
    owner = accounts.provision("signed-owner", "Signed owner", PASSWORD, false);
    recipient = accounts.provision("signed-recipient", "Signed recipient", PASSWORD, false);
    administrator = accounts.provision("signed-admin", "Signed administrator", PASSWORD, true);
    transport = new SignedPasskeyClient(port, "/team", "https://localhost");
  }

  @AfterEach
  void closeBrowsers() {
    if (transport != null) transport.close();
  }

  private CredentialRecord saved(SignedPasskeyClient.Credential credential) {
    return credentials.findByCredentialId(Bytes.fromBase64(credential.id()));
  }

  @Test
  void registrationChecksClientDataFlagsAndSessionAccountBindingBeforeSaving() throws Exception {
    var browser = transport.passwordLogin("signed-owner", PASSWORD);
    for (String mode :
        List.of(
            "wrong-origin",
            "wrong-challenge",
            "wrong-rpidhash",
            "no-up",
            "no-uv",
            "impossible-backup")) {
      var rejected = transport.registration(browser, mode);
      assertThat(rejected.response().statusCode()).as(mode).isEqualTo(400);
      assertThat(saved(rejected.credential())).isNull();
    }
    var prepared = transport.registration(browser, "prepare");
    String csrf = transport.csrf(browser, "/login");
    assertThat(
            transport
                .request(
                    browser,
                    "/login",
                    "POST",
                    csrf,
                    SignedPasskeyClient.form(
                        Map.of("username", "signed-recipient", "password", PASSWORD)))
                .statusCode())
        .isEqualTo(302);
    var swapped =
        transport.request(
            browser,
            "/webauthn/register",
            "POST",
            transport.csrf(browser, "/login"),
            prepared.body());
    assertThat(swapped.statusCode()).isEqualTo(400);
    assertThat(saved(prepared.credential())).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credentials", Long.class)).isZero();
    var registered = transport.registration(browser, "correct");
    assertThat(registered.response().statusCode()).isEqualTo(200);
    var key = registered.credential();
    var record = saved(key);
    assertThat(
            jdbc.queryForObject(
                "SELECT account_id FROM user_entities WHERE id=?",
                UUID.class,
                record.getUserEntityUserId().toBase64UrlString()))
        .isEqualTo(recipient);
    assertThat(
            transport
                .assertCredential(transport.options(browser), key, "correct", 0x0d)
                .statusCode())
        .isEqualTo(200);
    record = saved(key);
    var fresh = transport.registration(browser, "prepare");
    var duplicateBody =
        SignedPasskeyClient.JSON.readTree(
            SignedPasskeyClient.JSON.writeValueAsString(registered.body()));
    var freshBody =
        SignedPasskeyClient.JSON.readTree(
            SignedPasskeyClient.JSON.writeValueAsString(fresh.body()));
    ((tools.jackson.databind.node.ObjectNode)
            duplicateBody.path("publicKey").path("credential").path("response"))
        .set(
            "clientDataJSON",
            freshBody.path("publicKey").path("credential").path("response").path("clientDataJSON"));
    var duplicate =
        transport.request(
            browser,
            "/webauthn/register",
            "POST",
            transport.csrf(browser, "/login"),
            duplicateBody);
    assertUnchanged(record, saved(key));
    var afterDuplicate = transport.get(browser, "/security/passkeys");
    assertThat(duplicate.statusCode())
        .as(
            "body=%s headers=%s protected-page-status=%s",
            duplicate.body(), duplicate.headers().map(), afterDuplicate.statusCode())
        .isEqualTo(400);
    assertThat(duplicate.headers().firstValue("Location")).isEmpty();
    assertThat(afterDuplicate.statusCode()).isEqualTo(200);
  }

  @Test
  void anonymousHandleAndVerifiedBackupChangesReachJdbcAndRejectedAssertionsNeverUpdateState()
      throws Exception {
    var browser = transport.passwordLogin("signed-owner", PASSWORD);
    var key = transport.enroll(browser);
    assertThat(saved(key).isBackupEligible()).isTrue();
    assertThat(saved(key).isBackupState()).isFalse();
    for (String mode : List.of("wrong", "absent")) {
      var before = saved(key);
      var anonymous = transport.browser();
      var denied = transport.assertCredential(transport.options(anonymous), key, mode, 0x1d);
      assertThat(denied.statusCode()).as(mode).isEqualTo(401);
      assertUnchanged(before, saved(key));
      assertThat(transport.get(anonymous, "/security/sessions").statusCode()).isEqualTo(302);
    }
    for (int flags : List.of(0x0d, 0x1d, 0x0d)) {
      var accepted =
          transport.assertCredential(transport.options(transport.browser()), key, "correct", flags);
      assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
      var persisted =
          new AccountPasskeyCredentials(jdbc).findByCredentialId(Bytes.fromBase64(key.id()));
      assertThat(persisted.isBackupState()).isEqualTo((flags & 0x10) != 0);
      assertThat(persisted.isBackupEligible()).isTrue();
      assertThat(persisted.isUvInitialized()).isTrue();
      assertThat(persisted.getSignatureCount()).isPositive();
    }
  }

  @Test
  void verifiedUvInitializationChangesOnlyAfterTheSameAcceptedCredentialAssertion()
      throws Exception {
    var key = transport.enroll(transport.passwordLogin("signed-owner", PASSWORD));
    // Exercise the conditional record transition without adding any credential-import API or
    // weakening this deployment's required-UV registration policy.
    jdbc.update("UPDATE user_credentials SET uv_initialized=false WHERE credential_id=?", key.id());
    var before = saved(key);
    assertThat(before.isUvInitialized()).isFalse();
    var rejected =
        transport.assertCredential(transport.options(transport.browser()), key, "wrong", 0x1d);
    assertThat(rejected.statusCode()).isEqualTo(401);
    assertUnchanged(before, saved(key));
    var accepted =
        transport.assertCredential(transport.options(transport.browser()), key, "correct", 0x0d);
    assertThat(accepted.statusCode()).isEqualTo(200);
    assertThat(saved(key).isUvInitialized()).isTrue();
  }

  @Test
  void everyAdvertisedSignatureAlgorithmRegistersAndVerifiesRealAssertions() throws Exception {
    for (var variant :
        Map.of("signed-owner", "ES256", "signed-recipient", "RS256", "signed-admin", "EdDSA")
            .entrySet()) {
      var key =
          transport.enroll(transport.passwordLogin(variant.getKey(), PASSWORD), variant.getValue());
      assertThat(
              transport
                  .assertCredential(transport.options(transport.browser()), key, "correct", 0x0d)
                  .statusCode())
          .as(variant.getValue())
          .isEqualTo(200);
      var before = saved(key);
      assertThat(
              transport
                  .assertCredential(
                      transport.options(transport.browser()), key, "invalid-signature", 0x1d)
                  .statusCode())
          .as(variant.getValue())
          .isEqualTo(401);
      assertUnchanged(before, saved(key));
    }
  }

  @Test
  void identifiedAccountAndAllowListBindingRejectCrossAccountAndMismatchedHandle()
      throws Exception {
    var ownerBrowser = transport.passwordLogin("signed-owner", PASSWORD);
    var ownerKey = transport.enroll(ownerBrowser);
    var recipientBrowser = transport.passwordLogin("signed-recipient", PASSWORD);
    var recipientKey = transport.enroll(recipientBrowser);
    var before = saved(ownerKey);
    var cross =
        transport.assertCredential(transport.options(recipientBrowser), ownerKey, "correct", 0x1d);
    assertThat(cross.statusCode()).isEqualTo(401);
    assertUnchanged(before, saved(ownerKey));
    assertThat(saved(recipientKey).getSignatureCount()).isZero();
    var mismatch =
        transport.assertCredential(transport.options(ownerBrowser), ownerKey, "wrong", 0x1d);
    assertThat(mismatch.statusCode()).isEqualTo(401);
    assertUnchanged(before, saved(ownerKey));
    // An identified account's absent handle is allowed; its server-side allow-list binds the key.
    var accepted =
        transport.assertCredential(transport.options(ownerBrowser), ownerKey, "absent", 0x0d);
    assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
    var noKey = transport.passwordLogin("signed-admin", PASSWORD);
    assertThat(
            transport
                .request(
                    noKey,
                    "/webauthn/authenticate/options",
                    "POST",
                    transport.csrf(noKey, "/login"),
                    null)
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void signedCryptographicAndAuthenticatorBoundaryFailuresDoNotPersistAnyFlagsOrCounter()
      throws Exception {
    var key = transport.enroll(transport.passwordLogin("signed-owner", PASSWORD));
    for (String mode :
        List.of(
            "wrong-origin",
            "wrong-challenge",
            "invalid-signature",
            "wrong-type",
            "wrong-rpidhash",
            "cross-origin",
            "untrusted-top-origin")) {
      var before = saved(key);
      var rejected =
          transport.assertCredential(transport.options(transport.browser()), key, mode, 0x1d);
      assertThat(rejected.statusCode()).as(mode).isEqualTo(401);
      assertUnchanged(before, saved(key));
    }
    for (int flags : List.of(0x1c, 0x19, 0x15, 0x05)) {
      var before = saved(key);
      var rejected =
          transport.assertCredential(transport.options(transport.browser()), key, "correct", flags);
      assertThat(rejected.statusCode()).as("flags %s", flags).isEqualTo(401);
      assertUnchanged(before, saved(key));
    }
  }

  @Test
  void challengeExpirySingleUseRevocationAndDisabledAccountsFailBeforeStateMutation()
      throws Exception {
    var key = transport.enroll(transport.passwordLogin("signed-owner", PASSWORD));
    var ceremony = transport.options(transport.browser());
    var payload = key.assertion(ceremony.options(), "https://localhost", "correct", 0x1d);
    assertThat(
            transport
                .request(ceremony.browser(), "/login/webauthn", "POST", ceremony.csrf(), payload)
                .statusCode())
        .isEqualTo(200);
    var before = saved(key);
    assertThat(
            transport
                .request(ceremony.browser(), "/login/webauthn", "POST", ceremony.csrf(), payload)
                .statusCode())
        .isEqualTo(401);
    assertUnchanged(before, saved(key));
    var expired = transport.options(transport.browser());
    jdbc.update(
        "UPDATE webauthn_ceremonies SET created_at=now()-interval '2 minutes',expires_at=now()-interval '1 minute'");
    assertThat(transport.assertCredential(expired, key, "correct", 0x0d).statusCode())
        .isEqualTo(401);
    assertUnchanged(before, saved(key));
    var revoked = transport.options(transport.browser());
    credentials.delete(Bytes.fromBase64(key.id()));
    assertThat(transport.assertCredential(revoked, key, "correct", 0x0d).statusCode())
        .isEqualTo(401);
    assertThat(saved(key)).isNull();
    var other = transport.enroll(transport.passwordLogin("signed-recipient", PASSWORD));
    var disabled = transport.options(transport.browser());
    // Keep the key row to prove the relying-party's current-account check, not key absence alone.
    jdbc.update("UPDATE accounts SET enabled=false WHERE id=?", recipient);
    var otherBefore = saved(other);
    assertThat(transport.assertCredential(disabled, other, "correct", 0x1d).statusCode())
        .isEqualTo(401);
    assertUnchanged(otherBefore, saved(other));
  }

  @Test
  void actualPasswordPasskeyConsentPkceTokenRefreshAndRevocationJourneyUsesOnePrincipal()
      throws Exception {
    var admin = accounts.find(administrator);
    var adminProof =
        UsernamePasswordAuthenticationToken.authenticated(
            admin.username(),
            null,
            List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                SessionSecurity.authority(admin.securityEpoch()),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)));
    clients.registerNative(adminProof, "signed-native", "Signed consent client", Set.of(CALLBACK));
    var browser = transport.passwordLogin("signed-owner", PASSWORD);
    var key = transport.enroll(browser);
    assertThat(
            transport
                .assertCredential(transport.options(browser), key, "correct", 0x0d)
                .statusCode())
        .isEqualTo(200);
    String verifier = "independent-real-browser-code-verifier-012345678901234567890123456789";
    String challenge =
        SignedPasskeyClient.encoded(
            SignedPasskeyClient.hash(verifier.getBytes(StandardCharsets.US_ASCII)));
    String state = UUID.randomUUID().toString();
    String query =
        SignedPasskeyClient.form(
            Map.of(
                "response_type",
                "code",
                "client_id",
                "signed-native",
                "redirect_uri",
                CALLBACK,
                "scope",
                "dav",
                "state",
                state,
                "code_challenge",
                challenge,
                "code_challenge_method",
                "S256"));
    var pending = transport.get(browser, "/oauth2/authorize?" + query);
    assertThat(pending.statusCode()).as(pending.body()).isEqualTo(302);
    String consentLocation = pending.headers().firstValue("Location").orElseThrow();
    assertThat(consentLocation).contains("/team/oauth/consent?");
    var consent = transport.get(browser, transport.localPath(consentLocation));
    assertThat(consent.statusCode()).as(consent.body()).isEqualTo(200);
    String consentState = SignedPasskeyClient.query(consentLocation).get("state");
    String body =
        SignedPasskeyClient.form(
            Map.of("client_id", "signed-native", "state", consentState, "scope", "dav"));
    assertThat(transport.request(browser, "/oauth2/authorize", "POST", null, body).statusCode())
        .isEqualTo(403);
    var granted =
        transport.request(
            browser, "/oauth2/authorize", "POST", SignedPasskeyClient.csrf(consent.body()), body);
    assertThat(granted.statusCode()).as(granted.body()).isEqualTo(302);
    String redirect = granted.headers().firstValue("Location").orElseThrow();
    assertThat(redirect).startsWith(CALLBACK + "?");
    var parameters = SignedPasskeyClient.query(redirect);
    assertThat(parameters.get("state")).isEqualTo(state);
    var token =
        transport.request(
            transport.browser(),
            "/oauth2/token",
            "POST",
            null,
            SignedPasskeyClient.form(
                Map.of(
                    "grant_type",
                    "authorization_code",
                    "client_id",
                    "signed-native",
                    "redirect_uri",
                    CALLBACK,
                    "code",
                    parameters.get("code"),
                    "code_verifier",
                    verifier)));
    assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
    assertThat(token.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(token.headers().firstValue("Pragma")).hasValue("no-cache");
    var issued = SignedPasskeyClient.JSON.readTree(token.body());
    assertThat(issued.path("token_type").asString()).isEqualTo("Bearer");
    assertThat(issued.path("expires_in").asLong()).isPositive();
    String refresh = issued.path("refresh_token").asString();
    var rotated =
        transport.request(
            transport.browser(),
            "/oauth2/token",
            "POST",
            null,
            SignedPasskeyClient.form(
                Map.of(
                    "grant_type",
                    "refresh_token",
                    "client_id",
                    "signed-native",
                    "refresh_token",
                    refresh)));
    assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(200);
    String rotatedRefresh =
        SignedPasskeyClient.JSON.readTree(rotated.body()).path("refresh_token").asString();
    var emptyScope =
        transport.request(
            transport.browser(),
            "/oauth2/token",
            "POST",
            null,
            SignedPasskeyClient.form(
                Map.of(
                    "grant_type",
                    "refresh_token",
                    "client_id",
                    "signed-native",
                    "refresh_token",
                    rotatedRefresh,
                    "scope",
                    "",
                    "unknown-audit-parameter",
                    "opaque")));
    assertThat(emptyScope.statusCode()).as(emptyScope.body()).isEqualTo(200);
    assertThat(SignedPasskeyClient.JSON.readTree(emptyScope.body()).path("scope").asString())
        .isEqualTo("dav");
    String access =
        SignedPasskeyClient.JSON.readTree(emptyScope.body()).path("access_token").asString();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE attributes::jsonb->>'busystack.account_id'=?",
                Long.class,
                owner.toString()))
        .isEqualTo(1);
    assertThat(transport.bearer("/dav/", "GET", access, "").statusCode()).isEqualTo(200);
    var forbidden =
        transport.bearer(
            "/dav/calendars/" + recipient + "/missing/event.ics",
            "PUT",
            access,
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Boundary//EN\r\nBEGIN:VEVENT\r\nUID:blocked\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261028T000000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
    assertThat(forbidden.statusCode()).isEqualTo(403);
    assertThat(forbidden.headers().allValues("WWW-Authenticate").toString())
        .doesNotContain("resource_metadata");
    var revoked =
        transport.request(
            transport.browser(),
            "/oauth2/revoke",
            "POST",
            null,
            SignedPasskeyClient.form(
                Map.of(
                    "client_id",
                    "signed-native",
                    "token",
                    access,
                    "token_type_hint",
                    "access_token")));
    assertThat(revoked.statusCode()).isEqualTo(200);
    assertThat(
            transport
                .request(
                    transport.browser(),
                    "/oauth2/revoke",
                    "POST",
                    null,
                    SignedPasskeyClient.form(
                        Map.of("client_id", "signed-native", "token", "unknown-independent-token")))
                .statusCode())
        .isEqualTo(200);
    var denied = transport.bearer("/dav/", "GET", access, "");
    assertThat(denied.statusCode()).isEqualTo(401);
    assertThat(denied.headers().firstValue("WWW-Authenticate").orElseThrow())
        .startsWith("Bearer ")
        .contains("invalid_token")
        .doesNotContain("resource_metadata");
    assertThat(transport.get(browser, "/security/sessions").statusCode()).isEqualTo(200);
  }

  private static void assertUnchanged(CredentialRecord before, CredentialRecord after) {
    assertThat(after.isBackupState()).isEqualTo(before.isBackupState());
    assertThat(after.isUvInitialized()).isEqualTo(before.isUvInitialized());
    assertThat(after.getSignatureCount()).isEqualTo(before.getSignatureCount());
    assertThat(after.getLastUsed()).isEqualTo(before.getLastUsed());
  }
}
