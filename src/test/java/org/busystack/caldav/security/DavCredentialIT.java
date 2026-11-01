package org.busystack.caldav.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.busystack.caldav.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "busystack.public-base-url=https://calendar.example",
      "busystack.issuer=https://calendar.example"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class DavCredentialIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean PasswordEncoder passwords;
  UUID account;
  AccountService.IssuedCredential issued;

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    account = accounts.provision("dav-user", "DAV user", "a secure human account password", false);
    issued = accounts.issueDavCredential(account, "Device", Instant.now().plusSeconds(3600));
  }

  private String stored() {
    return jdbc.queryForObject(
        "SELECT verifier FROM dav_credentials WHERE id=?", String.class, issued.id());
  }

  @Test
  void issuanceUsesRandomTokenVerifierWhileHumanPasswordsStayAdaptiveAndRevocationIsImmediate() {
    assertThat(accounts.find(account).passwordHash()).startsWith("$2");
    assertThat(stored())
        .startsWith(DavSecretVerifier.PREFIX)
        .doesNotContain(issued.secret().substring(37));
    assertThat(accounts.verifyDav("dav-user", issued.secret())).isTrue();
    assertThat(accounts.verifyDav("dav-user", issued.id() + "." + "x".repeat(43))).isFalse();
    accounts.revokeDav(account, issued.id());
    assertThat(accounts.verifyDav("dav-user", issued.secret())).isFalse();
  }

  @Test
  void validLegacyBcryptCredentialUpgradesOnlyItsVerifier() {
    String legacy = passwords.encode(issued.secret().substring(37));
    jdbc.update("UPDATE dav_credentials SET verifier=? WHERE id=?", legacy, issued.id());
    assertThat(accounts.verifyDav("dav-user", issued.id() + "." + "x".repeat(43))).isFalse();
    assertThat(stored()).isEqualTo(legacy);
    assertThat(accounts.verifyDav("dav-user", issued.secret())).isTrue();
    assertThat(stored()).startsWith(DavSecretVerifier.PREFIX);
    assertThat(accounts.verifyDav("dav-user", issued.secret())).isTrue();
  }

  @Test
  void revocationDuringLegacyVerificationCannotBeUndoneByVerifierUpgrade() throws Exception {
    String secret = issued.secret().substring(37), legacy = passwords.encode(secret);
    jdbc.update("UPDATE dav_credentials SET verifier=? WHERE id=?", legacy, issued.id());
    var verified = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              boolean result = (boolean) invocation.callRealMethod();
              verified.countDown();
              if (!proceed.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Legacy verification barrier timed out");
              return result;
            })
        .when(passwords)
        .matches(secret, legacy);
    var attempt =
        CompletableFuture.supplyAsync(() -> accounts.verifyDav("dav-user", issued.secret()));
    try {
      assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();
      accounts.revokeDav(account, issued.id());
    } finally {
      proceed.countDown();
    }
    assertThat(attempt.get(10, TimeUnit.SECONDS)).isFalse();
    assertThat(stored()).isEqualTo(legacy);
    assertThat(
            jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM dav_credentials WHERE id=?",
                Boolean.class,
                issued.id()))
        .isTrue();
  }
}
