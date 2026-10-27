package org.busystack.caldav.storage;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "busystack.public-base-url=https://calendar.example",
      "busystack.issuer=https://calendar.example"
    })
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DavStoreIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired DavStore store;
  @Autowired CalendarStore calendars;
  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  UUID owner, member, administrator, calendar;
  static final QName COLOR = new QName("urn:independent-client:settings", "color");
  static final String BLUE =
      "<s:color xmlns:s=\"urn:independent-client:settings\">blå &amp; blue</s:color>";

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    owner = accounts.provision("owner", "Owner", "correct horse battery staple", false);
    member = accounts.provision("member", "Member", "correct horse battery staple", false);
    administrator =
        accounts.provision("administrator", "Administrator", "correct horse battery staple", true);
    calendar = store.create(owner, owner, "work", "Work", "VEVENT", Map.of()).collection().id();
  }

  private byte[] event(String uid) {
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Independent fixture//EN\r\nBEGIN:VEVENT\r\nUID:"
            + uid
            + "\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261028T000000Z\r\nX-UNKNOWN;X-PARAM=\"a^'b\":保留\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private CalendarStore.Resource put(UUID id, String name, String uid) {
    return store.putResource(owner, id, name, event(uid), WriteCondition.create()).resource();
  }

  private void fails(Runnable action, StoreException.Kind kind) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(kind);
  }

  @Test
  void persistentBindingsFollowExistingCreationSharingAndStablePrincipalIdentity() {
    UUID direct = calendars.createCollection(owner, "Existing API", "VTODO");
    assertThat(store.resolve(owner, owner, direct.toString()).collection().id()).isEqualTo(direct);
    calendars.share(owner, calendar, member, "READ");
    assertThat(store.resolve(member, member, calendar.toString()).collection().id())
        .isEqualTo(calendar);
    jdbc.update(
        "UPDATE accounts SET username='renamed-owner',display_name='新しい名前' WHERE id=?", owner);
    var restarted = new DavStore(jdbc, calendars);
    assertThat(restarted.resolve(member, member, calendar.toString()).collection().owner())
        .isEqualTo(owner);
    assertThat(restarted.children(owner, owner, "")).hasSize(2);
  }

  @Test
  void aclReplacementProtectsOwnerAndAdminAndRevokesDiscoveryAndSync() {
    var original = calendars.sync(owner, calendar, null, 100).token();
    store.replaceAcl(owner, calendar, List.of(new DavStore.Member(member, "READ")));
    assertThat(store.permission(member, calendar)).isEqualTo("READ");
    fails(
        () ->
            store.putResource(
                member, calendar, "forbidden.ics", event("blocked"), WriteCondition.create()),
        StoreException.Kind.FORBIDDEN);
    fails(() -> store.members(member, calendar), StoreException.Kind.FORBIDDEN);
    fails(() -> store.resolve(administrator, owner, "work"), StoreException.Kind.FORBIDDEN);
    assertThat(store.principals(member))
        .extracting(DavStore.Principal::id)
        .containsExactlyInAnyOrder(owner, member);
    fails(() -> calendars.sync(member, calendar, original, 100), StoreException.Kind.INVALID_TOKEN);
    store.replaceAcl(owner, calendar, List.of(new DavStore.Member(member, "WRITE")));
    store.putResource(member, calendar, "shared.ics", event("shared"), WriteCondition.create());
    String token = calendars.sync(member, calendar, null, 100).token();
    store.replaceAcl(owner, calendar, List.of());
    fails(() -> store.resolve(member, member, calendar.toString()), StoreException.Kind.NOT_FOUND);
    fails(() -> calendars.sync(member, calendar, token, 100), StoreException.Kind.FORBIDDEN);
    assertThatThrownBy(
            () -> store.replaceAcl(owner, calendar, List.of(new DavStore.Member(owner, "READ"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.permission(owner, calendar)).isEqualTo("OWNER");
  }

  @Test
  void namespacesHandleUnicodePercentSignsAndMoveWithoutChangingCollectionIdentity() {
    store.create(owner, owner, "📅 équipe %", "Folder", null, Map.of(COLOR, BLUE));
    assertThat(
            store.transfer(
                owner, owner, "work", owner, "📅 équipe %/旅行", true, false, WriteCondition.none()))
        .isTrue();
    assertThat(store.resolve(owner, owner, "📅 équipe %/旅行").collection().id()).isEqualTo(calendar);
    assertThat(store.children(owner, owner, "📅 équipe %"))
        .extracting(item -> item.binding().path())
        .containsExactly("📅 équipe %/旅行");
    assertThat(
            store.transfer(
                owner, owner, "📅 équipe %", owner, "🚀 moved", true, false, WriteCondition.none()))
        .isTrue();
    assertThat(store.resolve(owner, owner, "🚀 moved/旅行").collection().id()).isEqualTo(calendar);
    assertThat(store.snapshot(owner, owner, "🚀 moved").properties())
        .contains(new DavStore.Property(COLOR.getNamespaceURI(), COLOR.getLocalPart(), BLUE));
    fails(() -> store.resolve(owner, owner, "work"), StoreException.Kind.NOT_FOUND);
  }

  @Test
  void resourceCopyMovePreserveBytesPropertiesAndCommitSafeChanges() {
    UUID target =
        store.create(owner, owner, "target", "Target", "VEVENT", Map.of()).collection().id();
    var source = put(calendar, "a %.ics", "source");
    store.patch(owner, owner, "work", "a %.ics", Map.of(COLOR, BLUE), Set.of());
    String sourceToken = calendars.sync(owner, calendar, null, 100).token();
    assertThat(
            store.transferResource(
                owner,
                calendar,
                "a %.ics",
                target,
                "copied.ics",
                false,
                false,
                WriteCondition.match(source.etag())))
        .isTrue();
    assertThat(calendars.get(owner, target, "copied.ics").content()).isEqualTo(source.content());
    assertThat(store.deadProperties(owner, target, "copied.ics")).containsEntry(COLOR, BLUE);
    fails(
        () ->
            store.transferResource(
                owner,
                calendar,
                "a %.ics",
                calendar,
                "duplicate.ics",
                false,
                false,
                WriteCondition.none()),
        StoreException.Kind.UID_CONFLICT);
    assertThat(
            store.transferResource(
                owner,
                calendar,
                "a %.ics",
                calendar,
                "renamed.ics",
                true,
                false,
                WriteCondition.match(source.etag())))
        .isTrue();
    assertThat(calendars.get(owner, calendar, "a %.ics")).isNull();
    assertThat(store.deadProperties(owner, calendar, "renamed.ics")).containsEntry(COLOR, BLUE);
    assertThat(calendars.sync(owner, calendar, sourceToken, 100).changes())
        .hasSize(2)
        .anySatisfy(
            change -> {
              assertThat(change.name()).isEqualTo("a %.ics");
              assertThat(change.deleted()).isTrue();
            });
    assertThat(calendars.get(owner, calendar, "renamed.ics").etag()).isEqualTo(source.etag());
  }

  @Test
  void staleSourceDestinationCollisionAndComponentMismatchNeverPartiallyMutate() {
    UUID target =
        store.create(owner, owner, "target", "Target", "VEVENT", Map.of()).collection().id();
    UUID tasks = store.create(owner, owner, "tasks", "Tasks", "VTODO", Map.of()).collection().id();
    var source = put(calendar, "source.ics", "source");
    var destination = put(target, "occupied.ics", "destination");
    fails(
        () ->
            store.transferResource(
                owner,
                calendar,
                "source.ics",
                target,
                "occupied.ics",
                true,
                false,
                WriteCondition.none()),
        StoreException.Kind.PRECONDITION);
    fails(
        () ->
            store.transferResource(
                owner,
                calendar,
                "source.ics",
                target,
                "occupied.ics",
                true,
                true,
                WriteCondition.match("\"stale\"")),
        StoreException.Kind.PRECONDITION);
    assertThatThrownBy(
            () ->
                store.transferResource(
                    owner,
                    calendar,
                    "source.ics",
                    tasks,
                    "task.ics",
                    true,
                    false,
                    WriteCondition.none()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(calendars.get(owner, calendar, "source.ics").etag()).isEqualTo(source.etag());
    assertThat(calendars.get(owner, target, "occupied.ics").etag()).isEqualTo(destination.etag());
  }

  @Test
  void depthZeroAndDeepCopiesUseNewCollectionsAndNeverCopySharing() {
    put(calendar, "one.ics", "one");
    store.patch(owner, owner, "work", null, Map.of(COLOR, BLUE), Set.of());
    calendars.share(owner, calendar, member, "WRITE");
    store.transfer(
        owner, owner, "work", owner, "empty", false, false, WriteCondition.none(), false);
    var empty = store.snapshot(owner, owner, "empty");
    assertThat(empty.entry().collection().id()).isNotEqualTo(calendar);
    assertThat(empty.resources()).isEmpty();
    assertThat(empty.properties()).hasSize(1);
    store.transfer(owner, owner, "work", owner, "deep", false, false, WriteCondition.none());
    var deep = store.snapshot(owner, owner, "deep");
    assertThat(deep.resources()).hasSize(1);
    assertThat(store.members(owner, deep.entry().collection().id())).isEmpty();
    fails(() -> store.resolve(member, owner, "deep"), StoreException.Kind.FORBIDDEN);
  }

  @Test
  void recursiveFolderDeletionRemovesAuthoritativeCollectionsSharesAndOldTokens() {
    store.create(owner, owner, "folder", "Folder", null, Map.of());
    store.transfer(owner, owner, "work", owner, "folder/work", true, false, WriteCondition.none());
    calendars.share(owner, calendar, member, "READ");
    String token = calendars.sync(member, calendar, null, 100).token();
    store.deleteBinding(owner, owner, "folder", new WriteCondition(false, true, Set.of()));
    fails(() -> store.resolve(member, member, calendar.toString()), StoreException.Kind.NOT_FOUND);
    fails(() -> calendars.sync(member, calendar, token, 100), StoreException.Kind.NOT_FOUND);
    UUID recreated =
        store
            .create(owner, owner, "folder", "New collection", "VEVENT", Map.of())
            .collection()
            .id();
    assertThat(recreated).isNotEqualTo(calendar);
    fails(() -> calendars.sync(owner, recreated, token, 100), StoreException.Kind.INVALID_TOKEN);
  }

  @Test
  void propertyUpdatesAreAtomicAndRetainAcceptedXmlWithoutRewriting() {
    QName display = new QName("DAV:", "displayname");
    store.patch(
        owner,
        owner,
        "work",
        null,
        Map.of(COLOR, BLUE, display, "<D:displayname xmlns:D=\"DAV:\">新しい名前</D:displayname>"),
        Set.of());
    assertThat(store.resolve(owner, owner, "work").binding().displayName()).isEqualTo("新しい名前");
    assertThat(store.deadProperties(owner, calendar, null))
        .containsEntry(COLOR, BLUE)
        .containsKey(display)
        .hasSize(2);
    fails(
        () ->
            store.patch(
                owner,
                owner,
                "work",
                null,
                Map.of(
                    COLOR,
                    "<s:color xmlns:s=\"urn:independent-client:settings\">changed</s:color>",
                    new QName("DAV:", "getetag"),
                    "<D:getetag xmlns:D=\"DAV:\">evil</D:getetag>"),
                Set.of()),
        StoreException.Kind.INVALID_PROPERTY);
    assertThat(store.deadProperties(owner, calendar, null)).containsEntry(COLOR, BLUE);
    fails(
        () ->
            store.patch(
                owner,
                owner,
                "work",
                null,
                Map.of(
                    COLOR,
                    "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><s:color xmlns:s='urn:independent-client:settings'>&e;</s:color>"),
                Set.of()),
        StoreException.Kind.INVALID_PROPERTY);
    assertThat(store.deadProperties(owner, calendar, null)).containsEntry(COLOR, BLUE);
  }

  @Test
  void databaseFailureRollsBackPropertyMetadataAndDisplayNameTogether() {
    QName display = new QName("DAV:", "displayname");
    QName blocked = new QName("urn:test", "blocked");
    jdbc.execute(
        "CREATE FUNCTION dav_reject_property() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated property persistence failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER dav_reject_property BEFORE INSERT ON dav_collection_properties FOR EACH ROW EXECUTE FUNCTION dav_reject_property()");
    try {
      assertThatThrownBy(
              () ->
                  store.patch(
                      owner,
                      owner,
                      "work",
                      null,
                      Map.of(
                          display,
                          "<D:displayname xmlns:D='DAV:'>must roll back</D:displayname>",
                          blocked,
                          "<b:blocked xmlns:b='urn:test'>value</b:blocked>"),
                      Set.of()))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(store.resolve(owner, owner, "work").binding().displayName()).isEqualTo("Work");
      assertThat(store.deadProperties(owner, calendar, null)).isEmpty();
    } finally {
      jdbc.execute("DROP TRIGGER dav_reject_property ON dav_collection_properties");
      jdbc.execute("DROP FUNCTION dav_reject_property()");
    }
  }

  @Test
  void snapshotPreflightsAggregateBytesBeforeLoadingBodies() {
    jdbc.update(
        "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) SELECT ?,value::text||'.ics',value::text,decode(repeat('41',1048576),'hex'),'\"budget-fixture\"',value FROM generate_series(1,33) value",
        calendar);
    fails(() -> store.snapshot(owner, owner, "work"), StoreException.Kind.LIMIT);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM calendar_resources WHERE collection_id=?",
                Long.class,
                calendar))
        .isEqualTo(33);
  }

  @Test
  void crossResourceConditionLockPreventsAWriteBetweenValidationAndMutation() throws Exception {
    var original = put(calendar, "atomic.ics", "atomic");
    byte[] replacement =
        new String(original.content(), StandardCharsets.UTF_8)
            .replace("保留", "winner")
            .getBytes(StandardCharsets.UTF_8);
    var validated = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    var holderPid = new java.util.concurrent.atomic.AtomicInteger();
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var conditional =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            DavMutationLock.exclusive(jdbc);
                            holderPid.set(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            assertThat(calendars.get(owner, calendar, "atomic.ics").etag())
                                .isEqualTo(original.etag());
                            validated.countDown();
                            try {
                              if (!finish.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("Condition coordination timed out");
                            } catch (InterruptedException failure) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(failure);
                            }
                            return store.putResource(
                                owner,
                                calendar,
                                "atomic.ics",
                                replacement,
                                WriteCondition.match(original.etag()));
                          }),
              workers);
      try {
        assertThat(validated.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var ordinary =
            java.util.concurrent.CompletableFuture.supplyAsync(
                () ->
                    store.putResource(
                        owner,
                        calendar,
                        "atomic.ics",
                        event("atomic"),
                        WriteCondition.match(original.etag())),
                workers);
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(10))
            .untilAsserted(
                () ->
                    assertThat(
                            jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                                Boolean.class,
                                holderPid.get()))
                        .isTrue());
        finish.countDown();
        assertThat(conditional.get(15, java.util.concurrent.TimeUnit.SECONDS).resource().content())
            .isEqualTo(replacement);
        assertThatThrownBy(() -> ordinary.get(15, java.util.concurrent.TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .cause()
            .isInstanceOf(StoreException.class)
            .extracting("kind")
            .isEqualTo(StoreException.Kind.PRECONDITION);
      } finally {
        finish.countDown();
      }
    }
    assertThat(calendars.get(owner, calendar, "atomic.ics").content()).isEqualTo(replacement);
  }

  @Test
  void writeSharesModifyOneAuthoritativeResourceButNeverOwnershipOrAcl() {
    put(calendar, "shared.ics", "shared");
    store.replaceAcl(owner, calendar, List.of(new DavStore.Member(member, "WRITE")));
    store.patch(member, member, calendar.toString(), "shared.ics", Map.of(COLOR, BLUE), Set.of());
    assertThat(store.deadProperties(owner, calendar, "shared.ics")).containsEntry(COLOR, BLUE);
    fails(() -> store.replaceAcl(member, calendar, List.of()), StoreException.Kind.FORBIDDEN);
    fails(
        () -> store.deleteBinding(member, member, calendar.toString(), WriteCondition.none()),
        StoreException.Kind.FORBIDDEN);
    assertThat(store.resolve(owner, owner, "work").collection().owner()).isEqualTo(owner);
    assertThat(calendars.get(owner, calendar, "shared.ics")).isNotNull();
  }
}
