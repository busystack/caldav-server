package org.busystack.caldav.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.busystack.caldav.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "busystack.public-base-url=https://calendar.example",
      "busystack.issuer=https://calendar.example"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RefreshFamilyStoreIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired RefreshFamilyStore families;
  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired OAuthClientLock clients;

  @Autowired
  org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
      registrations;

  private final Base64StringKeyGenerator tokens =
      new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);
  private UUID account;
  private String authorization, initial;
  private Instant expiry;

  @BeforeEach
  void prepare() {
    if (registrations.findByClientId("native-client") == null)
      registrations.save(
          org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId(
                  "ledger-client")
              .clientId("native-client")
              .clientAuthenticationMethod(
                  org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
              .authorizationGrantType(
                  org.springframework.security.oauth2.core.AuthorizationGrantType
                      .AUTHORIZATION_CODE)
              .redirectUri("http://127.0.0.1:34567/callback")
              .scope("dav")
              .build());
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    account =
        accounts.provision("native-user", "Native user", "correct horse battery staple", false);
    authorization = UUID.randomUUID().toString();
    initial = tokens.generateKey();
    expiry = Instant.now().plusSeconds(3600);
    families.register(
        new RefreshFamilyStore.Family(authorization, account, "native-client", expiry),
        initial,
        expiry,
        () -> {});
  }

  @Test
  void rotatesAndCommitsFamilyRevocationOnSpentTokenReplay() {
    String successor = rotate(initial);
    assertThat(successor).isNotEqualTo(initial);
    assertThat(families.isActive(authorization)).isTrue();
    assertRejected(() -> rotate(initial), RefreshRejectedException.Reason.REPLAY);
    assertThat(families.isActive(authorization)).isFalse();
    assertRejected(() -> rotate(successor), RefreshRejectedException.Reason.REVOKED);
    assertThat(jdbc.queryForList("SELECT token_hash FROM oauth_refresh_tokens", String.class))
        .allSatisfy(
            hash -> assertThat(hash).hasSize(64).isNotEqualTo(initial).isNotEqualTo(successor));
  }

  @Test
  void replayRevocationSurvivesAnOuterTransactionRollbackAndStoreRestart() {
    rotate(initial);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              assertRejected(() -> rotate(initial), RefreshRejectedException.Reason.REPLAY);
              status.setRollbackOnly();
            });
    var restarted = new RefreshFamilyStore(jdbc, transactions, accounts, clients);
    assertThat(restarted.isActive(authorization)).isFalse();
  }

  @Test
  void callbackFailureRollsBackConsumptionAndAuthorizationMutation() {
    assertThatThrownBy(
            () ->
                families.refresh(
                    initial,
                    "native-client",
                    family -> {
                      jdbc.update(
                          "UPDATE accounts SET display_name='must roll back' WHERE id=?", account);
                      throw new IllegalStateException("Simulated grant persistence failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(accounts.find(account).displayName()).isEqualTo("Native user");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth_refresh_tokens WHERE consumed_at IS NOT NULL",
                Long.class))
        .isZero();
    assertThat(rotate(initial)).isNotBlank();
  }

  @Test
  void initialAuthorizationAndFamilyRegistrationAreAtomic() {
    String failedId = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                families.register(
                    new RefreshFamilyStore.Family(failedId, account, "native-client", expiry),
                    tokens.generateKey(),
                    expiry,
                    () -> {
                      jdbc.update(
                          "UPDATE accounts SET display_name='must roll back' WHERE id=?", account);
                      throw new IllegalStateException("Simulated initial authorization failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(families.isActive(failedId)).isFalse();
    assertThat(accounts.find(account).displayName()).isEqualTo("Native user");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_refresh_families", Long.class))
        .isEqualTo(1);
  }

  @Test
  void initialFamilyRegistrationJoinsTheCodeExchangeTransaction() {
    String rolledBackId = UUID.randomUUID().toString();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              families.register(
                  new RefreshFamilyStore.Family(rolledBackId, account, "native-client", expiry),
                  tokens.generateKey(),
                  expiry,
                  () ->
                      jdbc.update(
                          "UPDATE accounts SET display_name='must roll back' WHERE id=?", account));
              status.setRollbackOnly();
            });
    assertThat(families.isActive(rolledBackId)).isFalse();
    assertThat(accounts.find(account).displayName()).isEqualTo("Native user");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_refresh_families", Long.class))
        .isEqualTo(1);
  }

  @Test
  void wrongClientAndGuessedTokensCannotRevokeAnExistingFamily() {
    AtomicBoolean called = new AtomicBoolean();
    assertRejected(
        () ->
            families.refresh(
                initial,
                "another-client",
                family -> {
                  called.set(true);
                  return new RefreshFamilyStore.Rotation<>(
                      "unexpected", tokens.generateKey(), expiry);
                }),
        RefreshRejectedException.Reason.INVALID);
    assertRejected(() -> rotate(tokens.generateKey()), RefreshRejectedException.Reason.INVALID);
    assertThat(called).isFalse();
    assertThat(families.isActive(authorization)).isTrue();
    assertThat(rotate(initial)).isNotBlank();
  }

  @Test
  void enforcesAccountStateRevocationExpiryAndRecoveryEpoch() {
    families.revoke(UUID.randomUUID(), authorization);
    assertThat(families.isActive(authorization)).isTrue();
    accounts.disable(account);
    assertThat(families.isActive(authorization)).isFalse();
    assertRejected(() -> rotate(initial), RefreshRejectedException.Reason.DISABLED);
  }

  @Test
  void enforcesExpiryAndRecoveryEpoch() {
    jdbc.update("UPDATE oauth_refresh_tokens SET expires_at=now()-interval '1 minute'");
    assertRejected(() -> rotate(initial), RefreshRejectedException.Reason.EXPIRED);
    jdbc.update("UPDATE oauth_refresh_tokens SET expires_at=now()+interval '1 hour'");
    jdbc.update("UPDATE system_state SET recovery_epoch=gen_random_uuid() WHERE id=1");
    assertThat(families.isActive(authorization)).isFalse();
    assertRejected(() -> rotate(initial), RefreshRejectedException.Reason.REVOKED);
  }

  @Test
  void concurrentRefreshSerializesAndDetectsTheLosingAttemptAsReplay() throws Exception {
    CountDownLatch mutationEntered = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      CompletableFuture<String> first =
          CompletableFuture.supplyAsync(
              () ->
                  families.refresh(
                      initial,
                      "native-client",
                      family -> {
                        mutationEntered.countDown();
                        try {
                          if (!allowCommit.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("Refresh commit latch timed out");
                        } catch (InterruptedException ex) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(ex);
                        }
                        String successor = tokens.generateKey();
                        return new RefreshFamilyStore.Rotation<>(successor, successor, expiry);
                      }),
              workers);
      assertThat(mutationEntered.await(10, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<String> second =
          CompletableFuture.supplyAsync(
              () -> {
                secondStarted.countDown();
                return rotate(initial);
              },
              workers);
      try {
        assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
        org.awaitility.Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(
                () ->
                    assertThat(
                            jdbc.queryForObject(
                                "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",
                                Long.class))
                        .isGreaterThan(0));
        allowCommit.countDown();
        String successor = first.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(RefreshRejectedException.class);
        assertThat(families.isActive(authorization)).isFalse();
        assertRejected(() -> rotate(successor), RefreshRejectedException.Reason.REVOKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_refresh_tokens", Long.class))
            .isEqualTo(2);
      } finally {
        allowCommit.countDown();
      }
    }
  }

  private String rotate(String token) {
    return families.refresh(
        token,
        "native-client",
        family -> {
          String successor = tokens.generateKey();
          return new RefreshFamilyStore.Rotation<>(successor, successor, expiry);
        });
  }

  private static void assertRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      RefreshRejectedException.Reason reason) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            RefreshRejectedException.class,
            failure -> assertThat(failure.reason()).isEqualTo(reason));
  }
}
