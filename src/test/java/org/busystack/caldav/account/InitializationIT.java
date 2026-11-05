package org.busystack.caldav.account;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.Executors;
import org.busystack.caldav.testing.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "busystack.public-base-url=https://localhost/team",
      "busystack.issuer=https://localhost/team"
    })
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class InitializationIT {
  @Container static final PostgreSQLContainer POSTGRES = LoopbackContainers.postgres();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("server.ssl.enabled", () -> true);
    r.add("server.ssl.key-store", () -> TestTls.instance().keyStore());
    r.add("server.ssl.key-store-password", () -> TestTls.instance().password());
  }

  @Autowired InitializationService setup;
  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;
  @LocalServerPort int port;
  SignedPasskeyClient http;
  static final String PASSWORD = "Synthetic initialization password";

  @BeforeEach
  void reset() {
    FixtureDatabase.clearAccounts(jdbc);
    jdbc.update(
        "UPDATE installation_state SET initialized=false,code_verifier=NULL,expires_at=NULL,failed_attempts=0,initial_administrator=NULL,welcome_completed_at=NULL");
    http = new SignedPasskeyClient(port, "/team", "https://localhost");
  }

  @AfterEach
  void close() {
    http.close();
  }

  @Test
  void freshHttpsSetupRequiresCodeAndCsrfAndNeverEchoesSecrets() throws Exception {
    var browser = http.browser();
    assertThat(
            http.get(browser, "/oauth2/authorization/upstream")
                .headers()
                .firstValue("Location")
                .orElseThrow())
        .endsWith("/setup");
    assertThat(http.get(browser, "/login").headers().firstValue("Location").orElseThrow())
        .endsWith("/setup");
    var page = http.get(browser, "/setup");
    assertThat(page.body())
        .contains("Set up BusyStack", "scripts/initialize.sh")
        .doesNotContain("name=\"password\"");
    var form = http.get(browser, "/setup/administrator");
    assertThat(form.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
    assertThat(form.headers().firstValue("Referrer-Policy").orElseThrow()).isEqualTo("no-referrer");
    String code = setup.issueCode();
    var values =
        Map.of(
            "code",
            code,
            "username",
            "fixture-admin",
            "password",
            PASSWORD,
            "confirmation",
            PASSWORD);
    assertThat(
            http.request(
                    browser, "/setup/administrator", "POST", null, SignedPasskeyClient.form(values))
                .statusCode())
        .isEqualTo(403);
    var wrong =
        http.request(
            browser,
            "/setup/administrator",
            "POST",
            SignedPasskeyClient.csrf(form.body()),
            SignedPasskeyClient.form(
                Map.of(
                    "code",
                    "wrong-code",
                    "username",
                    "fixture-admin",
                    "password",
                    PASSWORD,
                    "confirmation",
                    PASSWORD)));
    assertThat(wrong.statusCode()).isEqualTo(400);
    assertThat(wrong.body())
        .contains("Initialization code is unavailable", "fixture-admin")
        .doesNotContain(PASSWORD, "wrong-code");
    var result =
        http.request(
            browser,
            "/setup/administrator",
            "POST",
            SignedPasskeyClient.csrf(wrong.body()),
            SignedPasskeyClient.form(values));
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(result.body())
        .contains("Administrator account created", "Sign in")
        .doesNotContain(code, PASSWORD);
    assertThat(http.get(browser, "/security").statusCode())
        .isEqualTo(302); // Setup never authenticates.
    assertThat(accounts.find("fixture-admin").administrator()).isTrue();
    assertThat(setup.available()).isFalse();
    assertThat(setup.initialize(code, "other", PASSWORD, PASSWORD)).containsKey("code");
  }

  @Test
  void randomReplacementExpiryAndBoundedAttempts() {
    String first = setup.issueCode();
    String second = setup.issueCode();
    assertThat(first).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(second);
    assertThat(java.util.Base64.getUrlDecoder().decode(first)).hasSize(32);
    assertThat(
            jdbc.queryForObject(
                "SELECT encode(code_verifier,'hex') FROM installation_state", String.class))
        .doesNotContain(first, second);
    assertThat(setup.initialize(first, "admin", PASSWORD, PASSWORD)).containsKey("code");
    jdbc.update("UPDATE installation_state SET expires_at=now()-interval '1 second'");
    assertThat(setup.initialize(second, "admin", PASSWORD, PASSWORD)).containsKey("code");
    String active = setup.issueCode();
    for (int n = 0; n < 5; n++)
      assertThat(setup.initialize("incorrect", "admin", PASSWORD, PASSWORD)).containsKey("code");
    assertThat(setup.initialize(active, "admin", PASSWORD, PASSWORD)).containsKey("code");
    assertThat(jdbc.queryForObject("SELECT failed_attempts FROM installation_state", Integer.class))
        .isEqualTo(5);
    String replacement = setup.issueCode();
    assertThat(setup.initialize(replacement, "admin", PASSWORD, PASSWORD)).isEmpty();
  }

  @Test
  void fieldValidationAndRollbackLeaveTheCodeUsable() {
    String code = setup.issueCode();
    assertThat(setup.initialize(code, "bad user", "short", "different"))
        .containsKeys("username", "password", "confirmation");
    assertThat(setup.initialize(code, "admin", "é".repeat(37), "é".repeat(37)))
        .containsKey("password");
    assertThat(setup.available()).isTrue();
    assertThat(setup.initialize(code, "admin", PASSWORD, PASSWORD)).isEmpty();
  }

  @Test
  void concurrentClaimsHaveExactlyOneWinner() throws Exception {
    String code = setup.issueCode();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                start.await();
                return setup.initialize(code, "first", PASSWORD, PASSWORD).isEmpty();
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return setup.initialize(code, "second", PASSWORD, PASSWORD).isEmpty();
              });
      start.countDown();
      assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
    }
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM accounts WHERE administrator", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void administratorJourneyKeepsRegistrationSeparateFromVerification() throws Exception {
    String code = setup.issueCode();
    assertThat(setup.initialize(code, "journey-admin", PASSWORD, PASSWORD)).isEmpty();
    var browser = http.passwordLogin("journey-admin", PASSWORD);
    var enrollment = http.get(browser, "/auth/continue?next=/team/connections?client=gnome");
    assertThat(enrollment.body())
        .contains("Secure your account", "Create passkey")
        .doesNotContain("Registered passkeys", "id=\"authenticate-passkey\"");
    String csrf = SignedPasskeyClient.csrf(enrollment.body());
    assertThat(
            http.request(
                    browser,
                    "/credentials",
                    "POST",
                    csrf,
                    SignedPasskeyClient.form(Map.of("label", "Denied", "days", "1")))
                .statusCode())
        .isEqualTo(403);
    var key = http.enroll(browser);
    assertThat(http.get(browser, "/auth/continue").body())
        .contains("Verify your passkey")
        .doesNotContain("Secure your account");
    assertThat(http.get(browser, "/security/passkeys").statusCode()).isEqualTo(302);
    assertThat(
            http.request(
                    browser,
                    "/credentials",
                    "POST",
                    http.csrf(browser, "/auth/continue"),
                    SignedPasskeyClient.form(Map.of("label", "Still denied", "days", "1")))
                .statusCode())
        .isEqualTo(403);
    assertThat(http.assertCredential(http.options(browser), key, "correct", 0x0d).statusCode())
        .isEqualTo(200);
    var complete = http.get(browser, "/auth/continue");
    assertThat(complete.body()).contains("Authentication successful");
    assertThat(http.get(browser, "/").body())
        .contains("Welcome to BusyStack", "Set up my calendar", "Manage users");
    assertThat(
            http.request(
                    browser,
                    "/welcome",
                    "POST",
                    http.csrf(browser, "/"),
                    SignedPasskeyClient.form(Map.of("activity", "users")))
                .statusCode())
        .isEqualTo(302);
    assertThat(http.get(browser, "/").body()).doesNotContain("<h1>Welcome to BusyStack</h1>");
    var issued =
        http.request(
            browser,
            "/credentials",
            "POST",
            http.csrf(browser, "/security/credentials"),
            SignedPasskeyClient.form(
                Map.of("label", "Synthetic GNOME", "days", "1", "client", "gnome")));
    assertThat(issued.statusCode()).isEqualTo(200);
    assertThat(issued.body())
        .contains("Application password created", "Synthetic GNOME", "client=gnome");
  }

  @Test
  void failedAccountWriteRollsBackConsumptionAndThePermanentLatch() {
    String code = setup.issueCode();
    jdbc.execute(
        "CREATE FUNCTION reject_initialization_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Deliberate account write failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_initialization_fixture BEFORE INSERT ON accounts FOR EACH ROW EXECUTE FUNCTION reject_initialization_fixture()");
    try {
      assertThatThrownBy(() -> setup.initialize(code, "rollback-admin", PASSWORD, PASSWORD))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(setup.available()).isTrue();
    } finally {
      jdbc.execute("DROP TRIGGER reject_initialization_fixture ON accounts");
      jdbc.execute("DROP FUNCTION reject_initialization_fixture()");
    }
    assertThat(setup.initialize(code, "rollback-admin", PASSWORD, PASSWORD)).isEmpty();
  }

  @Test
  void upgradingAnExistingDisabledAdministratorClosesSetup() {
    jdbc.execute("CREATE SCHEMA initialization_upgrade");
    try {
      var source = jdbc.getDataSource();
      org.flywaydb.core.Flyway.configure()
          .dataSource(source)
          .defaultSchema("initialization_upgrade")
          .schemas("initialization_upgrade")
          .target("14")
          .load()
          .migrate();
      jdbc.update(
          "INSERT INTO initialization_upgrade.accounts(id,username,display_name,password_hash,administrator,enabled) VALUES (gen_random_uuid(),'legacy-admin','Legacy administrator','non-authenticating-fixture',true,false)");
      org.flywaydb.core.Flyway.configure()
          .dataSource(source)
          .defaultSchema("initialization_upgrade")
          .schemas("initialization_upgrade")
          .load()
          .migrate();
      assertThat(
              jdbc.queryForObject(
                  "SELECT initialized FROM initialization_upgrade.installation_state",
                  Boolean.class))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM initialization_upgrade.accounts WHERE NOT enabled",
                  Integer.class))
          .isEqualTo(1);
    } finally {
      jdbc.execute("DROP SCHEMA initialization_upgrade CASCADE");
    }
  }

  @Test
  void offlineProvisionDisableAndAccountDeletionCannotReopenInitialization() {
    accounts.provision("existing", "Existing", PASSWORD, true);
    assertThat(setup.available()).isFalse();
    assertThatThrownBy(setup::issueCode).isInstanceOf(IllegalStateException.class);
    accounts.disable(accounts.find("existing").id());
    assertThat(setup.available()).isFalse();
    jdbc.update("DELETE FROM accounts");
    assertThat(setup.available()).isFalse();
  }
}
