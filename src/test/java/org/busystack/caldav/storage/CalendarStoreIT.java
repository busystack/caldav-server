package org.busystack.caldav.storage;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
class CalendarStoreIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired CalendarStore store;
  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  UUID owner, other, collection;

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    owner = accounts.provision("owner", "Owner", "correct horse battery staple", true);
    other = accounts.provision("other", "Other", "correct horse battery staple", false);
    collection = store.createCollection(owner, "Calendar", "VEVENT");
  }

  private CalendarStore.Resource put(String name, String uid, WriteCondition condition) {
    return store.put(
        owner,
        collection,
        name,
        uid,
        ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack tests//EN\r\nBEGIN:VEVENT\r\nUID:"
                + uid
                + "\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261028T000000Z\r\nX-UNKNOWN;X-PARAM=\"a^'b\":保留\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
            .getBytes(StandardCharsets.UTF_8),
        condition);
  }

  @Test
  void preservesBytesEtagsAndFailedPreconditions() {
    var created = put("event.ics", "one", WriteCondition.create());
    assertThat(store.get(owner, collection, "event.ics").content()).isEqualTo(created.content());
    String token = store.sync(owner, collection, null, 100).token();
    assertThatThrownBy(() -> put("event.ics", "two", WriteCondition.create()))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.PRECONDITION);
    assertThatThrownBy(() -> put("event.ics", "two", WriteCondition.match("\"stale\"")))
        .isInstanceOf(StoreException.class);
    assertThat(store.sync(owner, collection, token, 100).changes()).isEmpty();
    var replacement =
        store.put(
            owner,
            collection,
            "event.ics",
            "one",
            new String(created.content(), StandardCharsets.UTF_8)
                .replace("保留", "updated")
                .getBytes(StandardCharsets.UTF_8),
            WriteCondition.match(created.etag()));
    assertThat(replacement.etag()).isNotEqualTo(created.etag());
    assertThatThrownBy(
            () ->
                store.delete(owner, collection, "event.ics", WriteCondition.match(created.etag())))
        .isInstanceOf(StoreException.class);
    store.delete(owner, collection, "event.ics", WriteCondition.match(replacement.etag()));
    assertThat(store.sync(owner, collection, token, 100).changes())
        .singleElement()
        .satisfies(change -> assertThat(change.deleted()).isTrue());
  }

  @Test
  void enforcesUidUniquenessAndRollsBackRevision() {
    put("first.ics", "same", WriteCondition.create());
    String token = store.sync(owner, collection, null, 100).token();
    assertThatThrownBy(() -> put("second.ics", "same", WriteCondition.create()))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.UID_CONFLICT);
    assertThat(store.sync(owner, collection, token, 100).token()).isEqualTo(token);
    assertThat(store.get(owner, collection, "second.ics")).isNull();
  }

  @Test
  void sharesRespectPrivilegesAndRevocation() {
    put("event.ics", "one", WriteCondition.create());
    assertThatThrownBy(() -> store.get(other, collection, "event.ics"))
        .isInstanceOf(StoreException.class);
    store.share(owner, collection, other, "READ");
    assertThat(store.collections(other)).hasSize(1);
    assertThat(store.get(other, collection, "event.ics")).isNotNull();
    assertThatThrownBy(() -> store.delete(other, collection, "event.ics", WriteCondition.none()))
        .isInstanceOf(StoreException.class);
    String token = store.sync(other, collection, null, 100).token();
    store.share(owner, collection, other, "WRITE");
    assertThatThrownBy(() -> store.sync(other, collection, token, 100))
        .isInstanceOf(StoreException.class);
    store.delete(other, collection, "event.ics", WriteCondition.none());
    store.share(owner, collection, other, null);
    assertThat(store.collections(other)).isEmpty();
    assertThatThrownBy(() -> store.sync(other, collection, null, 100))
        .isInstanceOf(StoreException.class);
  }

  @Test
  void invalidatesExpiredAndRestoredTokensAndDoesNotTruncateSilently() {
    String initial = store.sync(owner, collection, null, 100).token();
    put("one.ics", "one", WriteCondition.create());
    String one = store.sync(owner, collection, null, 100).token();
    put("two.ics", "two", WriteCondition.create());
    assertThatThrownBy(() -> store.sync(owner, collection, null, 1))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.LIMIT);
    store.pruneHistory(collection, 1);
    assertThatThrownBy(() -> store.sync(owner, collection, initial, 100))
        .isInstanceOf(StoreException.class);
    assertThat(store.sync(owner, collection, one, 100).changes()).hasSize(1);
    jdbc.update("UPDATE system_state SET recovery_epoch=gen_random_uuid() WHERE id=1");
    assertThatThrownBy(() -> store.sync(owner, collection, one, 100))
        .isInstanceOf(StoreException.class);
    UUID another = store.createCollection(owner, "Other", "VTODO");
    assertThatThrownBy(() -> store.sync(owner, another, one, 100))
        .isInstanceOf(StoreException.class);
  }

  @Test
  void serializesCommitOrderWhileReportsUseCommittedSnapshots() throws Exception {
    var firstWritten = new CountDownLatch(1);
    var allowCommit = new CountDownLatch(1);
    var first =
        CompletableFuture.runAsync(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        tx -> {
                          put("one.ics", "one", WriteCondition.create());
                          firstWritten.countDown();
                          try {
                            if (!allowCommit.await(10, TimeUnit.SECONDS))
                              throw new AssertionError("commit latch timed out");
                          } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(ex);
                          }
                        }));
    assertThat(firstWritten.await(10, TimeUnit.SECONDS)).isTrue();
    var second =
        CompletableFuture.supplyAsync(() -> put("two.ics", "two", WriteCondition.create()));
    try {
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%collections%FOR UPDATE%'",
                              Long.class))
                      .isGreaterThan(0));
      var during = store.sync(owner, collection, null, 100);
      assertThat(during.changes()).isEmpty();
      allowCommit.countDown();
      first.get(10, TimeUnit.SECONDS);
      assertThat(second.get(10, TimeUnit.SECONDS).revision()).isEqualTo(2);
      assertThat(store.sync(owner, collection, during.token(), 100).changes()).hasSize(2);
    } finally {
      allowCommit.countDown();
    }
  }

  @Test
  void davCredentialsAreScopedRevocableAndAccountDisablingIsImmediate() {
    var credential =
        accounts.issueDavCredential(owner, "Test application", Instant.now().plusSeconds(3600));
    assertThat(accounts.verifyDav("owner", credential.secret())).isTrue();
    assertThat(accounts.verifyDav("other", credential.secret())).isFalse();
    assertThat(accounts.verifyDav("owner", "correct horse battery staple")).isFalse();
    accounts.revokeDav(owner, credential.id());
    assertThat(accounts.verifyDav("owner", credential.secret())).isFalse();
    var another =
        accounts.issueDavCredential(owner, "Other device", Instant.now().plusSeconds(3600));
    accounts.disable(owner);
    assertThat(accounts.verifyDav("owner", another.secret())).isFalse();
    assertThatThrownBy(() -> store.get(owner, collection, "any.ics"))
        .isInstanceOf(StoreException.class);
  }

  @Test
  void simultaneousCreatesProduceOneSuccessAndOnePreconditionFailure() throws Exception {
    var start = new CountDownLatch(1);
    java.util.function.Supplier<String> create =
        () -> {
          try {
            if (!start.await(5, TimeUnit.SECONDS))
              throw new AssertionError("start latch timed out");
            put("same.ics", "same", WriteCondition.create());
            return "created";
          } catch (StoreException ex) {
            return ex.kind().name();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
          }
        };
    var first = CompletableFuture.supplyAsync(create);
    var second = CompletableFuture.supplyAsync(create);
    start.countDown();
    assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
        .containsExactlyInAnyOrder("created", "PRECONDITION");
    assertThat(store.collection(owner, collection).revision()).isEqualTo(1);
    assertThat(store.sync(owner, collection, null, 100).changes()).hasSize(1);
  }

  @Test
  void usernameChangesDoNotChangeOwnershipOrResourceIdentity() {
    var original = put("preserved.ics", "preserved", WriteCondition.create());
    jdbc.update("UPDATE accounts SET username='renamed' WHERE id=?", owner);
    assertThat(accounts.find("renamed").id()).isEqualTo(owner);
    assertThat(store.collections(owner))
        .singleElement()
        .extracting(CalendarStore.Collection::id)
        .isEqualTo(collection);
    assertThat(store.get(owner, collection, "preserved.ics").etag()).isEqualTo(original.etag());
  }

  @Test
  void overwritingAnExistingResourceCannotReplaceItsUid() {
    var original = put("original.ics", "retained-uid", WriteCondition.create());
    String token = store.sync(owner, collection, null, 100).token();
    long revision = store.collection(owner, collection).revision();
    assertThatThrownBy(
            () -> put("original.ics", "different-uid", WriteCondition.match(original.etag())))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.UID_CONFLICT);
    assertThat(store.get(owner, collection, "original.ics").content())
        .isEqualTo(original.content());
    assertThat(store.get(owner, collection, "original.ics").etag()).isEqualTo(original.etag());
    assertThat(store.collection(owner, collection).revision()).isEqualTo(revision);
    assertThat(store.sync(owner, collection, token, 100).changes()).isEmpty();
  }
}
