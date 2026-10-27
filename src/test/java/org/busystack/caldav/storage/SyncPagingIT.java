package org.busystack.caldav.storage;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.namespace.QName;
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
class SyncPagingIT {
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
  @Autowired DavStore dav;
  @Autowired SyncContinuationCleanup cleanup;
  @Autowired SyncContinuationStore continuations;
  UUID owner, other, collection;

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    owner = accounts.provision("owner", "Owner", "correct horse battery staple", true);
    other = accounts.provision("other", "Other", "correct horse battery staple", false);
    collection = store.createCollection(owner, "Calendar", "VEVENT");
  }

  private byte[] content(String uid, String summary) {
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Independent sync fixture//EN\r\nBEGIN:VEVENT\r\nUID:"
            + uid
            + "\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261028T000000Z\r\nSUMMARY:"
            + summary
            + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private void put(String name, String uid, String summary) {
    store.put(owner, collection, name, uid, content(uid, summary), WriteCondition.none());
  }

  @Test
  void initialSmallPageReturnsContinuationRatherThanRejectingAcceptedCollection() {
    put("a.ics", "a", "one");
    put("b.ics", "b", "two");
    var first = store.sync(owner, collection, null, 1);
    assertThat(first.changes()).hasSize(1);
    var second = store.sync(owner, collection, first.token(), 1);
    assertThat(second.changes()).hasSize(1);
    assertThat(Set.of(first.changes().getFirst().name(), second.changes().getFirst().name()))
        .containsExactlyInAnyOrder("a.ics", "b.ics");
  }

  @Test
  void initialSyncExceedsFormerRowAndBodyThresholdsWithoutMissingSubsequentChanges() {
    // SQL constructs independently valid resources in the production PostgreSQL schema without
    // spending this capacity regression's runtime on 10,037 identical parser invocations.
    int count = 10037;
    String text = new String(content("fixture-UID", "x".repeat(3400)), StandardCharsets.UTF_8);
    jdbc.update(
        "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) SELECT ?, 'item-'||lpad(i::text,6,'0')||'.ics','fixture-'||i,convert_to(replace(?,'fixture-UID','fixture-'||i),'UTF8'),'\"etag-'||i||'\"',i FROM generate_series(1,?) i",
        collection,
        text,
        count);
    jdbc.update(
        "INSERT INTO collection_changes(collection_id,revision,name,deleted) SELECT collection_id,revision,name,false FROM calendar_resources WHERE collection_id=?",
        collection);
    jdbc.update("UPDATE collections SET revision=? WHERE id=?", count, collection);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(octet_length(content)) FROM calendar_resources WHERE collection_id=?",
                Long.class,
                collection))
        .isGreaterThan(32L * 1024 * 1024);
    Set<String> metadataNames = new HashSet<>();
    String metadataToken = null;
    do {
      var metadataPage =
          store.sync(owner, collection, metadataToken, 137, false, change -> 256, 137L * 256);
      for (var item : metadataPage.changes()) {
        assertThat(item.resource()).isNull();
        assertThat(metadataNames.add(item.name())).isTrue();
      }
      metadataToken = metadataPage.token();
      if (!metadataPage.truncated()) break;
    } while (true);
    assertThat(metadataNames).hasSize(count);
    Set<String> names = new HashSet<>();
    String token = null;
    int pages = 0;
    do {
      var page = store.sync(owner, collection, token, 137);
      for (var item : page.changes()) {
        assertThat(names.add(item.name())).isTrue();
        assertThat(new String(item.resource().content(), StandardCharsets.UTF_8))
            .contains("UID:" + item.metadata().uid() + "\r\n");
      }
      token = page.token();
      pages++;
      if (!page.truncated()) break;
      assertThat(pages).isLessThan(100);
    } while (true);
    assertThat(names).hasSize(count);
    put("after.ics", "after", "later");
    assertThat(store.sync(owner, collection, token, 137).changes())
        .singleElement()
        .extracting(CalendarStore.Change::name)
        .isEqualTo("after.ics");

    String beforeBulkChanges = store.currentSyncToken(owner, collection);
    long bulkHead =
        new TransactionTemplate(transactions)
            .execute(
                status -> {
                  long base =
                      jdbc.queryForObject(
                          "SELECT revision FROM collections WHERE id=? FOR UPDATE",
                          Long.class,
                          collection);
                  // Equivalent to individually accepted no-op PUTs, with the same collection lock
                  // and
                  // one atomic commit. Triggers retain every new immutable resource representation.
                  int updated =
                      jdbc.update(
                          "WITH numbered AS (SELECT name,?+row_number() OVER(ORDER BY name COLLATE \"C\") AS next_revision FROM calendar_resources WHERE collection_id=? AND name LIKE 'item-%') UPDATE calendar_resources r SET revision=n.next_revision FROM numbered n WHERE r.collection_id=? AND r.name=n.name",
                          base, collection, collection);
                  assertThat(updated).isEqualTo(count);
                  jdbc.update(
                      "INSERT INTO collection_changes(collection_id,revision,name,deleted) SELECT collection_id,revision,name,false FROM calendar_resources WHERE collection_id=? AND revision>?",
                      collection,
                      base);
                  jdbc.update(
                      "UPDATE collections SET revision=? WHERE id=?", base + count, collection);
                  return base + count;
                });
    Set<String> incrementalMetadata = new HashSet<>();
    metadataToken = beforeBulkChanges;
    do {
      var page =
          store.sync(owner, collection, metadataToken, 137, false, change -> 256, 137L * 256);
      for (var item : page.changes()) {
        assertThat(item.resource()).isNull();
        assertThat(incrementalMetadata.add(item.name())).isTrue();
      }
      metadataToken = page.token();
      if (!page.truncated()) break;
    } while (true);
    assertThat(incrementalMetadata).containsExactlyInAnyOrderElementsOf(names);
    Set<String> incrementalBodies = new HashSet<>();
    token = beforeBulkChanges;
    do {
      var page = store.sync(owner, collection, token, 137);
      for (var item : page.changes()) {
        assertThat(incrementalBodies.add(item.name())).isTrue();
        assertThat(new String(item.resource().content(), StandardCharsets.UTF_8))
            .contains("UID:" + item.metadata().uid() + "\r\n");
      }
      token = page.token();
      if (!page.truncated()) break;
    } while (true);
    assertThat(incrementalBodies).containsExactlyInAnyOrderElementsOf(names);
    assertThat(token).isEqualTo(metadataToken).endsWith(":" + bulkHead);
    assertThat(store.sync(owner, collection, token, 137).changes()).isEmpty();
  }

  private Map<String, CalendarStore.Change> drain(CalendarStore.SyncPage first, int limit) {
    Map<String, CalendarStore.Change> result = new HashMap<>();
    var page = first;
    int pages = 0;
    while (true) {
      for (var change : page.changes()) assertThat(result.put(change.name(), change)).isNull();
      if (!page.truncated()) break;
      assertThat(page.changes()).isNotEmpty();
      assertThat(++pages).isLessThan(100);
      page = store.sync(owner, collection, page.token(), limit);
    }
    return result;
  }

  @Test
  void initialWatermarkSurvivesUpdatesDeletesRecreationUnicodeAndRetriedRequests() {
    put("a.ics", "a", "original-a");
    put("b.ics", "b", "original-b");
    put("c.ics", "c", "original-c");
    put("é % 保留.ics", "unicode", "original-unicode");
    var first = store.sync(owner, collection, null, 1);
    assertThat(first.truncated()).isTrue();
    put("b.ics", "b", "changed-b-one");
    put("b.ics", "b", "changed-b-two");
    store.delete(owner, collection, "c.ics", WriteCondition.none());
    put("c.ics", "recreated-c", "new-c");
    store.delete(owner, collection, "é % 保留.ics", WriteCondition.none());
    put("0-before-cursor.ics", "before", "new-before");
    put("z-after-cursor.ics", "after", "new-after");
    var second = store.sync(owner, collection, first.token(), 1);
    var retry = store.sync(owner, collection, first.token(), 1);
    assertThat(retry.changes().getFirst().name()).isEqualTo(second.changes().getFirst().name());
    assertThat(retry.changes().getFirst().resource().content())
        .isEqualTo(second.changes().getFirst().resource().content());
    assertThat(new String(second.changes().getFirst().resource().content(), StandardCharsets.UTF_8))
        .contains("original-b");
    Map<String, CalendarStore.Change> snapshot = new HashMap<>();
    snapshot.put(first.changes().getFirst().name(), first.changes().getFirst());
    var page = second;
    String finalToken;
    while (true) {
      for (var item : page.changes()) assertThat(snapshot.put(item.name(), item)).isNull();
      if (!page.truncated()) {
        finalToken = page.token();
        break;
      }
      page = store.sync(owner, collection, page.token(), 1);
    }
    assertThat(snapshot).containsOnlyKeys("a.ics", "b.ics", "c.ics", "é % 保留.ics");
    assertThat(new String(snapshot.get("c.ics").resource().content(), StandardCharsets.UTF_8))
        .contains("original-c");
    Map<String, CalendarStore.Change> delta =
        drain(store.sync(owner, collection, finalToken, 1), 1);
    assertThat(delta)
        .containsOnlyKeys(
            "b.ics", "c.ics", "é % 保留.ics", "0-before-cursor.ics", "z-after-cursor.ics");
    assertThat(delta.get("é % 保留.ics").deleted()).isTrue();
    assertThat(new String(delta.get("b.ics").resource().content(), StandardCharsets.UTF_8))
        .contains("changed-b-two");
    assertThat(delta.get("c.ics").metadata().uid()).isEqualTo("recreated-c");
  }

  @Test
  void incrementalWatermarkReturnsOneFinalStatePerNameThenLaterMutations() {
    String empty = store.currentSyncToken(owner, collection);
    put("a.ics", "a", "one");
    put("b.ics", "b", "one");
    put("c.ics", "c", "one");
    put("gone.ics", "gone", "one");
    store.delete(owner, collection, "gone.ics", WriteCondition.none());
    var first = store.sync(owner, collection, empty, 1);
    put("b.ics", "b", "after-watermark");
    store.delete(owner, collection, "c.ics", WriteCondition.none());
    var page = first;
    Map<String, CalendarStore.Change> initial = new HashMap<>();
    String finalToken;
    while (true) {
      for (var item : page.changes()) assertThat(initial.put(item.name(), item)).isNull();
      if (!page.truncated()) {
        finalToken = page.token();
        break;
      }
      page = store.sync(owner, collection, page.token(), 1);
    }
    assertThat(initial).containsOnlyKeys("a.ics", "b.ics", "c.ics", "gone.ics");
    assertThat(initial.get("gone.ics").deleted()).isTrue();
    assertThat(initial.get("c.ics").deleted()).isFalse();
    assertThat(new String(initial.get("b.ics").resource().content(), StandardCharsets.UTF_8))
        .contains("SUMMARY:one");
    var delta = drain(store.sync(owner, collection, finalToken, 1), 1);
    assertThat(delta).containsOnlyKeys("b.ics", "c.ics");
    assertThat(delta.get("c.ics").deleted()).isTrue();
  }

  @Test
  void metadataAndActualSelectedByteBudgetsAreIndependentOfStoredBodySize() {
    put("amp&unicode保留.ics", "a", "&".repeat(10000));
    put("b.ics", "b", "x".repeat(10000));
    var first =
        store.sync(
            owner,
            collection,
            null,
            100,
            false,
            change -> {
              assertThat(change.resource()).isNull();
              return change.name().getBytes(StandardCharsets.UTF_8).length + 80;
            },
            110);
    assertThat(first.changes()).hasSize(1);
    assertThat(first.truncated()).isTrue();
    assertThat(first.changes().getFirst().metadata().contentLength()).isGreaterThan(10000);
    var last = store.sync(owner, collection, first.token(), 100, false, change -> 100, 110);
    assertThat(last.changes()).hasSize(1);
    assertThat(last.truncated()).isFalse();
    long cursors = jdbc.queryForObject("SELECT count(*) FROM sync_continuations", Long.class);
    assertThatThrownBy(() -> store.sync(owner, collection, null, 100, true, change -> 111, 110))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.LIMIT);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_continuations", Long.class))
        .isEqualTo(cursors);
    assertThat(store.sync(owner, collection, null, 100, true, change -> 111, 222).truncated())
        .isFalse();
  }

  @Test
  void continuationIsDurableScopedAndRejectsExpiryRetentionRecoveryAndRevokedPermissions() {
    put("a.ics", "a", "a");
    put("b.ics", "b", "b");
    put("c.ics", "c", "c");
    String continuation = store.sync(owner, collection, null, 1).token();
    // A new service instance has no process-local continuation data. Execute it in a fresh real
    // database transaction, as a restarted application's service proxy would do.
    var restarted =
        new TransactionTemplate(transactions)
            .execute(
                status ->
                    new CalendarStore(jdbc, continuations)
                        .sync(owner, collection, continuation, 1));
    assertThat(restarted.changes())
        .singleElement()
        .extracting(CalendarStore.Change::name)
        .isEqualTo("b.ics");
    UUID another = store.createCollection(owner, "Other", "VEVENT");
    assertThatThrownBy(() -> store.sync(owner, another, continuation, 1))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.INVALID_TOKEN);
    store.share(owner, collection, other, "READ");
    assertThatThrownBy(() -> store.sync(owner, collection, continuation, 1))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.INVALID_TOKEN);
    String reader = store.sync(other, collection, null, 1).token();
    assertThatThrownBy(() -> store.sync(owner, collection, reader, 1))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.INVALID_TOKEN);
    store.share(owner, collection, other, null);
    assertThatThrownBy(() -> store.sync(other, collection, reader, 1))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.FORBIDDEN);
    String current = store.sync(owner, collection, null, 1).token();
    final String beforeExpiry = current;
    jdbc.update("UPDATE sync_continuations SET expires_at=now()-interval '1 second'");
    assertThatThrownBy(() -> store.sync(owner, collection, beforeExpiry, 1))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.INVALID_TOKEN);
    current = store.sync(owner, collection, null, 1).token();
    final String beforePrune = current;
    store.pruneHistory(collection, 2);
    assertThatThrownBy(() -> store.sync(owner, collection, beforePrune, 1))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.INVALID_TOKEN);
    assertThat(drain(store.sync(owner, collection, null, 1), 1)).hasSize(3);
    final String beforeRestore = store.sync(owner, collection, null, 1).token();
    jdbc.update("UPDATE system_state SET recovery_epoch=gen_random_uuid() WHERE id=1");
    assertThatThrownBy(() -> store.sync(owner, collection, beforeRestore, 1))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.INVALID_TOKEN);
    final String beforeDisable = store.sync(owner, collection, null, 1).token();
    accounts.disable(owner);
    assertThatThrownBy(() -> store.sync(owner, collection, beforeDisable, 1))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.FORBIDDEN);
  }

  @Test
  void versionedPropertiesRemainFrozenAndCopyCapturesDeferredProperties() {
    var binding = dav.create(owner, owner, "calendar", "Calendar", "VEVENT", Map.of());
    collection = binding.collection().id();
    put("a.ics", "a", "a");
    put("b.ics", "b", "b");
    QName title = new QName("urn:sync-fixture", "title");
    dav.patch(
        owner,
        owner,
        "calendar",
        "b.ics",
        Map.of(title, "<x:title xmlns:x=\"urn:sync-fixture\">before</x:title>"),
        Set.of(),
        WriteCondition.none());
    var first = store.sync(owner, collection, null, 1);
    long version = store.get(owner, collection, "b.ics").revision();
    dav.patch(
        owner,
        owner,
        "calendar",
        "b.ics",
        Map.of(title, "<x:title xmlns:x=\"urn:sync-fixture\">after</x:title>"),
        Set.of(),
        WriteCondition.none());
    var second = store.sync(owner, collection, first.token(), 1);
    assertThat(second.changes().getFirst().metadata().revision()).isEqualTo(version);
    assertThat(
            store
                .historicalProperties(owner, collection, "b.ics", version, Set.of(title))
                .get(title))
        .contains("before")
        .doesNotContain("after");
    assertThat(store.historicalPropertyNames(owner, collection, "b.ics", version)).contains(title);
    var target = dav.create(owner, owner, "copied", "Copied", "VEVENT", Map.of());
    dav.transferResource(
        owner,
        collection,
        "b.ics",
        target.collection().id(),
        "copy.ics",
        false,
        false,
        WriteCondition.none());
    long copied = store.get(owner, target.collection().id(), "copy.ics").revision();
    assertThat(
            store
                .historicalProperties(
                    owner, target.collection().id(), "copy.ics", copied, Set.of(title))
                .get(title))
        .contains("after");
  }

  @Test
  void forwardMigrationFromReviewedSchemaPreservesOldTokensAndAuthoritativeData() {
    String schema = "sync_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var properties = new java.util.Properties();
    properties.setProperty("currentSchema", schema);
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setConnectionProperties(properties);
    var migrated = new JdbcTemplate(source);
    try {
      org.flywaydb.core.Flyway.configure()
          .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .target("9")
          .load()
          .migrate();
      UUID actor = UUID.randomUUID(), id = UUID.randomUUID();
      migrated.update(
          "INSERT INTO accounts(id,username,display_name) VALUES (?,'upgrade','Upgrade')", actor);
      migrated.update(
          "INSERT INTO collections(id,owner_id,name,component,revision) VALUES (?,?,'Retained','VEVENT',2)",
          id,
          actor);
      byte[] authoritative = content("retained", "Unicode 保留");
      migrated.update(
          "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) VALUES (?,'retained.ics','retained',?,'\"retained\"',1)",
          id,
          authoritative);
      migrated.update(
          "INSERT INTO collection_changes(collection_id,revision,name,deleted) VALUES (?,1,'retained.ics',false),(?,2,'removed.ics',true)",
          id,
          id);
      migrated.update(
          "INSERT INTO dav_resource_properties(collection_id,resource_name,namespace,name,xml) VALUES (?,'retained.ics','urn:upgrade','language','<x:language xmlns:x=\"urn:upgrade\" xml:lang=\"fr\"> a <x:b/> b </x:language>')",
          id);
      UUID generation =
          migrated.queryForObject("SELECT generation FROM collections WHERE id=?", UUID.class, id);
      UUID epoch =
          migrated.queryForObject("SELECT recovery_epoch FROM system_state WHERE id=1", UUID.class);
      String oldToken = "urn:busystack:sync:" + epoch + ":" + id + ":" + generation + ":0";
      org.flywaydb.core.Flyway.configure()
          .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .load()
          .migrate();
      org.flywaydb.core.Flyway.configure()
          .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .load()
          .validate();
      var upgradedTransactions =
          new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
      var transaction = new TransactionTemplate(upgradedTransactions);
      var upgraded =
          new CalendarStore(migrated, new SyncContinuationStore(migrated, upgradedTransactions));
      var delta = transaction.execute(status -> upgraded.sync(actor, id, oldToken, 100));
      assertThat(delta.changes()).hasSize(2);
      assertThat(delta.changes())
          .filteredOn(CalendarStore.Change::deleted)
          .singleElement()
          .extracting(CalendarStore.Change::name)
          .isEqualTo("removed.ics");
      assertThat(upgraded.get(actor, id, "retained.ics").content()).isEqualTo(authoritative);
      assertThat(
              upgraded
                  .historicalProperties(
                      actor, id, "retained.ics", 1, Set.of(new QName("urn:upgrade", "language")))
                  .values())
          .singleElement()
          .asString()
          .contains("xml:lang=\"fr\"", " a ", " b ");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void projectedContentPagesAlsoBoundRetainedUnprojectedBodies() {
    String summary = "x".repeat(900_000);
    for (int i = 0; i < 7; i++) put("large-" + i + ".ics", "large-" + i, summary);
    var metadata = store.sync(owner, collection, null, 100, false, change -> 100, 10000);
    assertThat(metadata.truncated()).isFalse();
    assertThat(metadata.changes()).hasSize(7);
    var projected = store.sync(owner, collection, null, 100, true, change -> 100, 10000);
    assertThat(projected.truncated()).isTrue();
    assertThat(projected.changes()).hasSize(4);
    assertThat(projected.changes().stream().mapToLong(c -> c.metadata().contentLength()).sum())
        .isLessThanOrEqualTo(4L * 1024 * 1024);
    var remaining =
        store.sync(owner, collection, projected.token(), 100, true, change -> 100, 10000);
    assertThat(remaining.truncated()).isFalse();
    assertThat(remaining.changes()).hasSize(3);
  }

  @Test
  void committedWriteDuringPageConstructionStaysAboveFrozenWatermark() throws Exception {
    put("a.ics", "a", "before-a");
    put("b.ics", "b", "before-b");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    var first =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () ->
                store.sync(
                    owner,
                    collection,
                    null,
                    1,
                    true,
                    change -> {
                      entered.countDown();
                      try {
                        if (!finish.await(15, java.util.concurrent.TimeUnit.SECONDS))
                          throw new AssertionError("synchronization callback release timed out");
                      } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                      }
                      return 100;
                    },
                    1000));
    try {
      assertThat(entered.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      put("b.ics", "b", "committed-during-page");
      finish.countDown();
      var page = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(page.truncated()).isTrue();
      var second = store.sync(owner, collection, page.token(), 1);
      assertThat(second.truncated()).isFalse();
      assertThat(
              new String(second.changes().getFirst().resource().content(), StandardCharsets.UTF_8))
          .contains("SUMMARY:before-b")
          .doesNotContain("committed-during-page");
      var later = store.sync(owner, collection, second.token(), 1);
      assertThat(later.changes())
          .singleElement()
          .satisfies(
              change ->
                  assertThat(new String(change.resource().content(), StandardCharsets.UTF_8))
                      .contains("committed-during-page"));
    } finally {
      finish.countDown();
    }
  }

  @Test
  void boundedReadCommittedCleanupSkipsAnotherWorkersLockedExpiredState() throws Exception {
    put("a.ics", "a", "a");
    put("b.ics", "b", "b");
    store.sync(owner, collection, null, 1);
    jdbc.update(
        "INSERT INTO sync_continuations(id,collection_id,account_id,generation,recovery_epoch,lower_revision,upper_revision,retention_floor,initial_sync,last_name,expires_at) SELECT gen_random_uuid(),collection_id,account_id,generation,recovery_epoch,lower_revision,upper_revision,retention_floor,initial_sync,last_name,now()-interval '1 second' FROM sync_continuations CROSS JOIN generate_series(1,2) WHERE collection_id=?",
        collection);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var worker =
        java.util.concurrent.CompletableFuture.runAsync(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        status -> {
                          jdbc.queryForObject(
                              "SELECT id FROM sync_continuations WHERE expires_at<now() ORDER BY expires_at,id LIMIT 1 FOR UPDATE",
                              UUID.class);
                          locked.countDown();
                          try {
                            if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                              throw new AssertionError("cleanup lock release timed out");
                          } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(ex);
                          }
                        }));
    try {
      assertThat(locked.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      cleanup.pruneExpired();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sync_continuations WHERE expires_at<now()", Long.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sync_continuations WHERE expires_at>now()", Long.class))
          .isEqualTo(1);
    } finally {
      release.countDown();
      worker.get(15, java.util.concurrent.TimeUnit.SECONDS);
    }
    cleanup.pruneExpired();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_continuations", Long.class))
        .isEqualTo(1);
  }

  @Test
  void cursorPersistenceRechecksPermissionRevokedDuringPageConstruction() throws Exception {
    put("a.ics", "a", "a");
    put("b.ics", "b", "b");
    store.share(owner, collection, other, "READ");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    var pending =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () ->
                store.sync(
                    other,
                    collection,
                    null,
                    1,
                    false,
                    change -> {
                      entered.countDown();
                      try {
                        if (!finish.await(15, java.util.concurrent.TimeUnit.SECONDS))
                          throw new AssertionError("revocation release timed out");
                      } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                      }
                      return 100;
                    },
                    1000));
    try {
      assertThat(entered.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      store.share(owner, collection, other, null);
      finish.countDown();
      assertThatThrownBy(() -> pending.get(15, java.util.concurrent.TimeUnit.SECONDS))
          .hasCauseInstanceOf(StoreException.class)
          .satisfies(
              error ->
                  assertThat(((StoreException) error.getCause()).kind())
                      .isEqualTo(StoreException.Kind.FORBIDDEN));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sync_continuations WHERE account_id=?", Long.class, other))
          .isZero();
    } finally {
      finish.countDown();
    }
  }
}
