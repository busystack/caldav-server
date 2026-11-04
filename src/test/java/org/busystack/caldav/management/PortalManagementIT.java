package org.busystack.caldav.management;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.session.SessionSecurity;
import org.busystack.caldav.sharing.GroupService;
import org.busystack.caldav.storage.*;
import org.busystack.caldav.testing.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.*;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.*;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL and HTTPS; factor fixtures test policy, not WebAuthn cryptography. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "busystack.public-base-url=https://localhost/team",
      "busystack.issuer=https://localhost/team"
    })
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PortalManagementIT {
  @Container static final PostgreSQLContainer POSTGRES = LoopbackContainers.postgres();

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("server.ssl.enabled", () -> true);
    registry.add("server.ssl.key-store", () -> TestTls.instance().keyStore());
    registry.add("server.ssl.key-store-password", () -> TestTls.instance().password());
  }

  @Autowired PortalService portal;
  @Autowired ManagementService management;
  @Autowired AdministrationService administration;
  @Autowired SharingManagementService sharing;
  @Autowired PortabilityService portability;
  @Autowired PortabilityStorage portabilityStorage;
  @Autowired org.busystack.caldav.calendar.CalendarInterchange interchange;
  @Autowired AccountService accounts;
  @Autowired CalendarStore calendars;
  @Autowired DavStore dav;
  @Autowired GroupService groups;
  @Autowired org.busystack.caldav.config.OperationalProblems problems;
  @Autowired JdbcTemplate jdbc;
  @Autowired SessionRepository<? extends Session> repository;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @LocalServerPort int port;
  UUID admin, owner, reader, other, collection;
  final String hostile = "<img src=x onerror=alert(1)>";

  @BeforeEach
  void prepare() {
    FixtureDatabase.reset(
        jdbc,
        "TRUNCATE spring_session,security_audit RESTART IDENTITY CASCADE",
        "TRUNCATE accounts CASCADE");
    admin =
        accounts.provision("portal-admin", "Administrator", "correct horse battery staple", true);
    owner =
        accounts.provision(
            "portal-owner", "Owner " + hostile, "correct horse battery staple", false);
    reader = accounts.provision("portal-reader", "Reader", "correct horse battery staple", false);
    other = accounts.provision("portal-other", "Unrelated", "correct horse battery staple", false);
    collection =
        portal.create(
            auth(owner, true),
            "Private " + hostile,
            "VEVENT",
            "Description " + hostile,
            "#126A5EFF");
  }

  @Test
  void exportSnapshotCommitFailureReleasesCompletedFileAndReservation() throws Exception {
    dav.putResource(
        owner, collection, "commit.ics", event("export-commit"), WriteCondition.create());
    long before = portabilityStorage.reservedBytes();
    var completed = new java.util.concurrent.atomic.AtomicReference<PortabilityService.Export>();
    jdbc.execute(
        "CREATE FUNCTION qualification_export_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='EXPORT_COMMIT_TEST' THEN RAISE EXCEPTION 'Qualification export commit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER qualification_export_commit_failure AFTER INSERT ON security_audit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION qualification_export_commit_failure()");
    try {
      var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
      tx.setIsolationLevel(
          org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
      assertThatThrownBy(
              () ->
                  tx.execute(
                      status -> {
                        try {
                          completed.set(portability.export(auth(owner, true), collection));
                        } catch (java.io.IOException failure) {
                          throw new java.io.UncheckedIOException(failure);
                        }
                        assertThat(completed.get().path()).isRegularFile();
                        jdbc.update(
                            "INSERT INTO security_audit(account_id,action,outcome) VALUES (?,'EXPORT_COMMIT_TEST','SUCCESS')",
                            owner);
                        return completed.get();
                      }))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("Qualification export commit failure");
      assertThat(completed.get()).isNotNull();
      assertThat(completed.get().path()).doesNotExist();
      assertThat(portabilityStorage.reservedBytes()).isLessThanOrEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM security_audit WHERE action='EXPORT_COMMIT_TEST'",
                  Long.class))
          .isZero();
    } finally {
      jdbc.execute("DROP TRIGGER qualification_export_commit_failure ON security_audit");
      jdbc.execute("DROP FUNCTION qualification_export_commit_failure()");
      if (completed.get() != null) completed.get().close();
    }
    try (var successful = portability.export(auth(owner, true), collection)) {
      assertThat(successful.path()).isRegularFile();
      assertThat(successful.length()).isPositive();
    }
    assertThat(portabilityStorage.reservedBytes()).isLessThanOrEqualTo(before);
  }

  @Test
  void invalidRowFormsRetainTheirPageAndMemberFilter() throws Exception {
    var names = new HashSet<String>();
    for (int n = 0; n < 51; n++) {
      String username = "paged-%02d".formatted(n);
      accounts.provision(username, "Paged member " + n, "correct horse battery staple", false);
      management.share(auth(owner, true), collection, username, "READ");
      names.add(username);
    }
    UUID group = groups.create(auth(admin, true), "Paged group", names);
    var user = browser(owner, true);
    var sharingPage = get(user, "/collections/" + collection + "/sharing?directPage=1");
    assertThat(sharingPage.body()).contains("name=\"directPage\" value=\"1\"");
    fieldError(
        post(
            user,
            "/shares",
            Map.of(
                "_csrf",
                csrf(sharingPage.body()),
                "collection",
                collection.toString(),
                "username",
                "paged-50",
                "permission",
                "INVALID",
                "expected",
                "READ",
                "directPage",
                "1")),
        400,
        "direct-grant-paged-50",
        "permission");
    var administrator = browser(admin, true);
    var members = get(administrator, "/admin/groups/" + group + "?page=1");
    UUID last = accounts.find("paged-50").id();
    fieldError(
        post(
            administrator,
            "/admin/groups/" + group + "/members/" + last + "/remove",
            Map.of("_csrf", csrf(members.body()), "memberPage", "1")),
        400,
        "member-remove-" + last,
        "confirmed");
    var filtered =
        post(
            administrator,
            "/admin/groups/" + group + "/members/" + last + "/remove",
            Map.of("_csrf", csrf(members.body()), "memberQuery", "paged-50"));
    fieldError(filtered, 400, "member-remove-" + last, "confirmed");
    assertThat(filtered.body()).contains("name=\"q\" value=\"paged-50\"");
  }

  @Test
  void selectedImportDestinationRemainsEditableBeyondFirstListingPage() throws Exception {
    for (int n = 0; n < 51; n++) portal.create(auth(owner, true), "A first " + n, "VEVENT", "", "");
    assertThat(portal.collections(auth(owner, true), "all", "", 0).rows())
        .noneMatch(c -> c.id().equals(collection));
    var response = get(browser(owner, true), "/portability?destination=" + collection);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("value=\"" + collection + "\"", "Validate and preview import", "open=\"open\"");
    assertThat(get(browser(other, true), "/portability?destination=" + collection).statusCode())
        .isIn(403, 404);
  }

  @Test
  void targetedAuditRetainsExistingCountAndAgePruning() {
    var audit = new org.busystack.caldav.security.audit.SecurityAudit(jdbc);
    jdbc.update(
        "INSERT INTO security_audit(account_id,action,outcome,target_type,target_id,occurred_at) SELECT ?, 'ACCOUNT_PROFILE_UPDATED','SUCCESS','ACCOUNT',?,now()-interval '31 days' FROM generate_series(1,10005)",
        admin,
        owner);
    audit.targeted(
        admin,
        org.busystack.caldav.security.audit.SecurityAudit.Action.ACCOUNT_PROFILE_UPDATED,
        org.busystack.caldav.security.audit.SecurityAudit.Outcome.SUCCESS,
        org.busystack.caldav.security.audit.SecurityAudit.TargetType.ACCOUNT,
        owner,
        null);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM security_audit", Long.class))
        .isLessThanOrEqualTo(10000);
    audit.prune();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM security_audit WHERE occurred_at < now()-interval '30 days'",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM security_audit WHERE target_id=? AND occurred_at > now()-interval '1 minute'",
                Long.class,
                owner))
        .isEqualTo(1);
  }

  @Test
  void metadataUsesDavPropertiesAndRetainsPathsBytesAndTokens() {
    var original = portal.collection(auth(owner, true), collection);
    byte[] bytes = event("one");
    calendars.put(owner, collection, "one.ics", "one", bytes, WriteCondition.create());
    String token = calendars.currentSyncToken(owner, collection);
    portal.edit(
        auth(owner, true), collection, original.state(), "Renamed", "New description", "#123456");
    var changed = portal.collection(auth(owner, true), collection);
    assertThat(changed.address())
        .isEqualTo(original.address())
        .startsWith("https://localhost/team/dav/");
    assertThat(changed.component()).isEqualTo("VEVENT");
    assertThat(changed.description()).isEqualTo("New description");
    assertThat(dav.deadProperty(owner, collection, null, PortalService.COLOR)).contains("#123456");
    assertThat(calendars.get(owner, collection, "one.ics").content()).isEqualTo(bytes);
    assertThat(calendars.currentSyncToken(owner, collection)).isEqualTo(token);
    denied(() -> portal.edit(auth(owner, true), collection, original.state(), "Stale", "", ""));
  }

  @Test
  void readWriteRecipientsHaveMetadataButNeverOwnerControls() {
    management.share(auth(owner, true), collection, "portal-reader", "READ");
    var original = portal.collection(auth(reader, true), collection);
    denied(() -> portal.edit(auth(reader, true), collection, original.state(), "Denied", "", ""));
    management.share(auth(owner, true), collection, "portal-reader", "WRITE");
    portal.edit(auth(reader, true), collection, original.state(), "Shared edit", "", "#123456");
    denied(() -> portal.owner(auth(reader, true), collection));
    denied(
        () ->
            portal.delete(
                auth(reader, true),
                collection,
                portal.collection(auth(reader, true), collection).deletionState(),
                true));
    denied(() -> portal.collection(auth(admin, true), collection));
    denied(() -> portal.collection(auth(other, true), collection));
  }

  @Test
  void deletionIsConfirmedRecheckedAndCascadesAllBindingsAndTokens() {
    var g = groups.create(auth(admin, true), "Shared", Set.of("portal-reader"));
    groups.grant(auth(owner, true), collection, g, "READ");
    management.share(auth(owner, true), collection, "portal-other", "WRITE");
    calendars.put(owner, collection, "one.ics", "one", event("one"), WriteCondition.create());
    var old = portal.collection(auth(owner, true), collection);
    denied(() -> portal.delete(auth(owner, true), collection, old.deletionState(), false));
    calendars.put(owner, collection, "two.ics", "two", event("two"), WriteCondition.create());
    denied(() -> portal.delete(auth(owner, true), collection, old.deletionState(), true));
    String token = calendars.sync(reader, collection, "", 1).token();
    portal.delete(
        auth(owner, true),
        collection,
        portal.collection(auth(owner, true), collection).deletionState(),
        true);
    for (String table :
        List.of(
            "dav_bindings",
            "calendar_resources",
            "collection_members",
            "collection_group_members",
            "sync_continuations",
            "calendar_resource_versions")) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM " + table + " WHERE collection_id=?",
                  Long.class,
                  collection))
          .isZero();
    }
    denied(() -> calendars.sync(reader, collection, token, 10));
  }

  @Test
  void effectivePermissionsStayAuthoritativeAfterEachRevocation() {
    UUID g = groups.create(auth(admin, true), "Contributors", Set.of("portal-reader"));
    sharing.direct(auth(owner, true), collection, "portal-reader", "READ", "NONE");
    sharing.group(auth(owner, true), collection, g, "WRITE", "NONE");
    assertThat(portal.effective(auth(owner, false), collection, 0).rows())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.permission())
                  .isEqualTo(dav.permission(reader, collection))
                  .isEqualTo("WRITE");
              assertThat(m.direct()).isTrue();
              assertThat(m.groups()).isEqualTo(1);
            });
    sharing.direct(auth(owner, true), collection, "portal-reader", "REVOKE", "READ");
    assertThat(dav.permission(reader, collection)).isEqualTo("WRITE");
    denied(() -> sharing.direct(auth(owner, true), collection, "portal-reader", "WRITE", "READ"));
    denied(() -> portal.effective(auth(reader, true), collection, 0));
    denied(() -> portal.effective(auth(admin, true), collection, 0));
    groups.changeMember(auth(admin, true), g, null, reader, false, true);
    denied(() -> portal.collection(auth(reader, true), collection));
  }

  @Test
  void structuredMembershipDoesNotReplaceOtherMembersAndDisabledMembershipCanBeRemoved() {
    UUID g = groups.create(auth(admin, true), "Team", Set.of("portal-reader"));
    denied(() -> groups.changeMember(auth(owner, true), g, "portal-other", null, true, true));
    denied(() -> groups.changeMember(auth(admin, true), g, "portal-other", null, true, false));
    groups.changeMember(auth(admin, true), g, "portal-other", null, true, true);
    assertThat(groups.detail(auth(admin, true), g).members()).isEqualTo(2);
    accounts.disable(reader);
    groups.changeMember(auth(admin, true), g, null, reader, false, true);
    assertThat(groups.members(auth(admin, true), g, "", 0).rows())
        .singleElement()
        .extracting(GroupService.MemberView::account)
        .isEqualTo(other);
    groups.rename(auth(admin, true), g, "Team", "New team");
    denied(() -> groups.rename(auth(admin, true), g, "Team", "Stale"));
  }

  @Test
  void accountProfileCannotChangeIdentityRolesOrMappings() {
    var original = accounts.find(owner);
    administration.edit(auth(admin, true), owner, original.displayName(), "Updated owner");
    var changed = accounts.find(owner);
    assertThat(changed.id()).isEqualTo(original.id());
    assertThat(changed.username()).isEqualTo(original.username());
    assertThat(changed.administrator()).isFalse();
    assertThat(changed.passwordHash()).isEqualTo(original.passwordHash());
    denied(() -> administration.edit(auth(owner, true), other, "Unrelated", "Denied"));
    denied(() -> administration.edit(auth(admin, true), owner, original.displayName(), "Stale"));
    denied(() -> administration.disable(auth(admin, true), owner, false));
    administration.disable(auth(admin, true), owner, true);
    assertThat(accounts.find(owner).enabled()).isFalse();
    denied(() -> portal.setup(authFor(original, true)));
    assertThat(administration.accounts(auth(admin, true), "portal-owner", "disabled", 0).rows())
        .hasSize(1);
  }

  @Test
  void collectionAndAccountPagesAreBoundedSearchableAndIsolated() {
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    tx.executeWithoutResult(
        s -> {
          for (int i = 0; i < 55; i++) calendars.createCollection(owner, "Page " + i, "VTODO");
        });
    assertThat(portal.collections(auth(owner, false), "owned", "", 0).rows()).hasSize(50);
    assertThat(portal.collections(auth(owner, false), "owned", "", 0).hasNext()).isTrue();
    assertThat(portal.collections(auth(owner, false), "owned", "", 1).rows()).hasSize(6);
    assertThat(portal.collections(auth(other, false), "all", "", 0).rows()).isEmpty();
    assertThat(portal.collections(auth(admin, false), "all", "", 0).rows()).isEmpty();
    denied(() -> portal.collections(auth(owner, true), "owned", "", -1));
    denied(() -> administration.accounts(auth(owner, true), "", "all", 0));
    assertThat(administration.accounts(auth(admin, true), "portal-owner", "local", 0).rows())
        .hasSize(1);
  }

  @Test
  void sensitiveOperationsRequireBothRecentFactorsAndCurrentAccount() {
    denied(() -> portal.create(auth(owner, false), "Denied", "VEVENT", "", ""));
    denied(() -> portal.create(null, "Denied", "VEVENT", "", ""));
    denied(() -> management.issue(auth(owner, false), "Denied", 1));
    denied(() -> management.issue(auth(owner, true), "", 1));
    denied(() -> management.issue(auth(owner, true), "Valid", 366));
    var credential = management.issue(auth(owner, true), "Device", 1);
    denied(() -> management.revoke(auth(other, true), credential.id()));
    management.revoke(auth(owner, true), credential.id());
    assertThat(accounts.verifyDav("portal-owner", credential.secret())).isFalse();
    assertThat(portal.credentials(auth(owner, false), 0).rows())
        .singleElement()
        .extracting(ManagementService.Credential::status)
        .isEqualTo("Revoked");
  }

  @Test
  void importPreviewConfirmationDuplicateConflictAndRoundTripAreAccurate() throws Exception {
    byte[] file = (calendarBody("a") + calendarBody("b")).getBytes(StandardCharsets.UTF_8);
    var preview = portability.preview(auth(owner, true), collection, file);
    assertThat(preview.ready()).isEqualTo(2);
    assertThat(calendars.metadataBatch(owner, collection, "", 10)).isEmpty();
    denied(() -> portability.confirm(auth(owner, true), preview.token(), false));
    denied(() -> portability.confirm(auth(other, true), preview.token(), true));
    var result = portability.confirm(auth(owner, true), preview.token(), true);
    assertThat(result.imported()).isEqualTo(2);
    assertThat(result.conflicts()).isZero();
    denied(() -> portability.confirm(auth(owner, true), preview.token(), true));
    var duplicate = portability.preview(auth(owner, true), collection, file);
    assertThat(duplicate.skipped()).isEqualTo(2);
    var conflict =
        portability.preview(
            auth(owner, true),
            collection,
            calendarBody("a")
                .replace("DTSTART:20261028T000000Z", "DTSTART:20261029T000000Z")
                .getBytes(StandardCharsets.UTF_8));
    assertThat(conflict.conflicts()).isEqualTo(1);
    var exported = portability.export(auth(owner, true), collection);
    assertThat(exported.stream()).isFalse();
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(exported.path());
      assertThat(new String(bytes, StandardCharsets.UTF_8).split("BEGIN:VCALENDAR", -1)).hasSize(2);
    } finally {
      exported.close();
    }
    UUID destination = portal.create(auth(owner, true), "Imported", "VEVENT", "", "");
    var roundtrip =
        portability.confirm(
            auth(owner, true),
            portability.preview(auth(owner, true), destination, bytes).token(),
            true);
    assertThat(roundtrip.imported()).isEqualTo(2);
    for (var m : calendars.metadataBatch(owner, destination, "", 10))
      assertThat(calendars.get(owner, destination, m.name()).content()).isEqualTo(event(m.uid()));
  }

  @Test
  void incompatibleCalendarContextsExportLosslesslyWithoutCombiningTimezoneDefinitions()
      throws Exception {
    String zone =
        "BEGIN:VTIMEZONE\r\nTZID:Private/Fixed\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0200\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n";
    byte[] a =
        calendarBody("zone-a")
            .replace("BEGIN:VEVENT", zone + "BEGIN:VEVENT")
            .replace("DTSTART:20261028T000000Z", "DTSTART;TZID=Private/Fixed:20261028T000000")
            .getBytes(StandardCharsets.UTF_8);
    byte[] b =
        new String(a, StandardCharsets.UTF_8)
            .replace("zone-a", "zone-b")
            .replace("+0200", "+0300")
            .getBytes(StandardCharsets.UTF_8);
    portability.confirm(
        auth(owner, true), portability.preview(auth(owner, true), collection, a).token(), true);
    portability.confirm(
        auth(owner, true), portability.preview(auth(owner, true), collection, b).token(), true);
    var export = portability.export(auth(owner, true), collection);
    try {
      assertThat(export.stream()).isTrue();
      var items = new java.util.ArrayList<org.busystack.caldav.calendar.CalendarInterchange.Item>();
      try (var index = portabilityStorage.create(PortabilityStorage.INDEX_BYTES);
          var file = interchange.open(export.path(), index.path())) {
        for (int n = 0; n < file.size(); n++) items.add(interchange.item(file, n, "VEVENT"));
      }
      assertThat(items).hasSize(2).allSatisfy(i -> assertThat(i.rejection()).isEmpty());
      assertThat(items)
          .extracting(i -> new String(i.content(), StandardCharsets.UTF_8))
          .containsExactlyInAnyOrder(
              new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8));
    } finally {
      export.close();
    }
  }

  @Test
  void importRechecksWritePermissionAndReportsRejectedItemsWithoutMutation() throws Exception {
    management.share(auth(owner, true), collection, "portal-reader", "WRITE");
    var preview = portability.preview(auth(reader, true), collection, event("safe"));
    var client = browser(reader, true);
    String csrf = csrf(get(client, "/portability").body());
    management.share(auth(owner, true), collection, "portal-reader", "READ");
    denied(() -> portability.confirm(auth(reader, true), preview.token(), true));
    assertThat(
            post(
                    client,
                    "/portability/import/" + preview.token(),
                    Map.of("_csrf", csrf, "confirmed", "true"))
                .statusCode())
        .isEqualTo(403);
    management.share(auth(owner, true), collection, "portal-reader", "REVOKE");
    var hidden = get(client, "/portability/import/" + preview.token());
    assertThat(hidden.statusCode()).isIn(403, 404);
    assertThat(hidden.body()).doesNotContain("New UID series", "DTSTART", "Untitled item");
    assertThat(get(client, "/portability").body())
        .doesNotContain("/portability/import/" + preview.token());
    denied(() -> portability.preview(auth(reader, true), collection, event("safe")));
    denied(() -> portability.export(auth(admin, true), collection));
    byte[] malformed =
        calendarBody("invalid")
            .replace("DTSTART:20261028T000000Z", "DTSTART:bad")
            .getBytes(StandardCharsets.UTF_8);
    var invalid = portability.preview(auth(owner, true), collection, malformed);
    assertThat(invalid.rejected()).isEqualTo(1);
    assertThat(calendars.metadataBatch(owner, collection, "", 10)).isEmpty();
    denied(
        () ->
            portability.preview(
                auth(owner, true), collection, "not a calendar".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void importRollbackNeverLeavesPartialResources() throws Exception {
    var preview =
        portability.preview(
            auth(owner, true),
            collection,
            (calendarBody("a") + calendarBody("b")).getBytes(StandardCharsets.UTF_8));
    jdbc.execute(
        "CREATE FUNCTION portal_fail_second() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.uid='b' THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER portal_fail_second BEFORE INSERT ON calendar_resources FOR EACH ROW EXECUTE FUNCTION portal_fail_second()");
    try {
      assertThatThrownBy(() -> portability.confirm(auth(owner, true), preview.token(), true))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      jdbc.execute("DROP TRIGGER portal_fail_second ON calendar_resources");
      jdbc.execute("DROP FUNCTION portal_fail_second()");
    }
    assertThat(calendars.metadataBatch(owner, collection, "", 10)).isEmpty();
    assertThat(portability.confirm(auth(owner, true), preview.token(), true).imported())
        .isEqualTo(2);
    denied(() -> portability.confirm(auth(owner, true), preview.token(), true));
    assertThat(calendars.metadataBatch(owner, collection, "", 10)).hasSize(2);
  }

  private static byte[] mediumStreamBody(int n, String type) {
    String zone =
        "BEGIN:VTIMEZONE\r\nTZID:Same/Local\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:"
            + (n % 2 == 0 ? "+0200" : "+0300")
            + "\r\nTZOFFSETTO:"
            + (n % 2 == 0 ? "+0200" : "+0300")
            + "\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n";
    String uid = "medium-" + n;
    String value =
        calendarBody(uid)
            .replace("BEGIN:VEVENT", zone + "BEGIN:VEVENT")
            .replace("DTSTART:20261028T000000Z", "DTSTART;TZID=Same/Local:20261028T000000")
            .replace("X-CLIENT:preserve", "X-CLIENT:" + "p".repeat(8500))
            .replace("VERSION:2.0", "VERSION:2.0\r\nX-CALENDAR-CONTEXT:" + n);
    if (type.equals("VTODO"))
      value =
          value.replace("VEVENT", "VTODO").replace("SUMMARY:", "STATUS:NEEDS-ACTION\r\nSUMMARY:");
    else if (n % 100 == 0) {
      String exception =
          calendarBody(uid)
              .substring(
                  calendarBody(uid).indexOf("BEGIN:VEVENT"),
                  calendarBody(uid).indexOf("END:VCALENDAR"))
              .replace(
                  "DTSTART:20261028T000000Z",
                  "RECURRENCE-ID;TZID=Same/Local:20261029T000000\r\nDTSTART;TZID=Same/Local:20261029T020000");
      value =
          value
              .replace("X-CLIENT:", "RRULE:FREQ=DAILY;COUNT=3\r\nX-CLIENT:")
              .replace("END:VCALENDAR", exception + "END:VCALENDAR");
    }
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void differentCalendarClosingsPreserveResourceLimitOnHttpRoundTrip() throws Exception {
    var client = browser(owner, true);
    String csrf = csrf(get(client, "/portability").body());
    int scenario = 0;
    for (String ending : List.of("\r\n", "\n", "", "\r")) {
      for (int order = 0; order < 3; order++) {
        boolean neighbor = order != 0;
        UUID source =
            portal.create(
                auth(owner, true), "Closing boundary source " + scenario, "VEVENT", "", "");
        UUID target =
            portal.create(
                auth(owner, true), "Closing boundary target " + scenario++, "VEVENT", "", "");
        byte[] small = event("closing-small");
        String text =
            calendarBody("closing-limit").replace("END:VCALENDAR\r\n", "END:VCALENDAR" + ending);
        int padding =
            org.busystack.caldav.calendar.CalendarContent.MAX_BYTES
                - text.getBytes(StandardCharsets.UTF_8).length;
        byte[] large =
            text.replace("X-CLIENT:preserve", "X-CLIENT:preserve" + "x".repeat(padding))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(large).hasSize(org.busystack.caldav.calendar.CalendarContent.MAX_BYTES);
        assertThat(org.busystack.caldav.calendar.CalendarContent.parse(large, "VEVENT").uid())
            .isEqualTo("closing-limit");
        calendars.put(
            owner,
            source,
            order == 1 ? "a-limit.ics" : "b-limit.ics",
            "closing-limit",
            large,
            WriteCondition.create());
        if (neighbor)
          calendars.put(
              owner,
              source,
              order == 1 ? "b-small.ics" : "a-small.ics",
              "closing-small",
              small,
              WriteCondition.create());
        var response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(base() + "/portability/collections/" + source + "/export"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        boolean stream = neighbor && !ending.equals("\r\n");
        assertThat(response.headers().firstValue("Content-Disposition"))
            .hasValue(
                "attachment; filename=\"busystack-collection"
                    + (stream ? "-stream" : "")
                    + ".ics\"");
        var preview = upload(client, csrf, target, "closing-boundary.ics", response.body());
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).doesNotContain("UID series exceeds the 1 MiB resource limit");
        var matcher =
            java.util.regex.Pattern.compile("/portability/import/([a-f0-9-]{36})")
                .matcher(preview.body());
        assertThat(matcher.find()).isTrue();
        var result =
            post(
                client,
                "/portability/import/" + matcher.group(1),
                Map.of("_csrf", csrf, "confirmed", "true"));
        assertThat(result.statusCode()).isEqualTo(200);
        var resources = calendars.metadataBatch(owner, target, "", 10);
        assertThat(resources).hasSize(neighbor ? 2 : 1);
        for (var resource : resources) {
          byte[] stored = calendars.get(owner, target, resource.name()).content();
          assertThat(stored.length)
              .isLessThanOrEqualTo(org.busystack.caldav.calendar.CalendarContent.MAX_BYTES);
          assertThat(org.busystack.caldav.calendar.CalendarContent.parse(stored, "VEVENT").uid())
              .isEqualTo(resource.uid());
          byte[] expected = resource.uid().equals("closing-limit") ? large : small;
          if (resource.uid().equals("closing-limit") && ending.equals("\r"))
            expected = java.util.Arrays.copyOf(expected, expected.length - 1);
          assertThat(stored).isEqualTo(expected);
        }
      }
    }
  }

  @Test
  void realHttpMediumStreamsRoundTripDistinctTimezonesMetadataExceptionsAndTasks()
      throws Exception {
    for (String type : List.of("VEVENT", "VTODO")) {
      UUID source = portal.create(auth(owner, true), "Medium source " + type, type, "", "");
      UUID target = portal.create(auth(owner, true), "Medium target " + type, type, "", "");
      var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
      tx.executeWithoutResult(
          status -> {
            for (int n = 0; n < 1100; n++)
              calendars.put(
                  owner,
                  source,
                  "medium-%04d.ics".formatted(n),
                  "medium-" + n,
                  mediumStreamBody(n, type),
                  WriteCondition.create());
          });
      var client = browser(owner, true);
      String csrf = csrf(get(client, "/portability").body());
      var downloaded = Files.createTempFile("busystack-medium-client-", ".ics");
      try {
        var response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(base() + "/portability/collections/" + source + "/export"))
                    .timeout(Duration.ofSeconds(660))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofFile(downloaded));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition"))
            .hasValue("attachment; filename=\"busystack-collection-stream.ics\"");
        assertThat(Files.size(downloaded)).isGreaterThan(8L * 1024 * 1024);
        var preview = uploadFile(client, csrf, target, downloaded);
        assertThat(preview.statusCode()).isEqualTo(200);
        var matcher =
            java.util.regex.Pattern.compile("/portability/import/([a-f0-9-]{36})")
                .matcher(preview.body());
        assertThat(matcher.find()).isTrue();
        String token = matcher.group(1);
        assertThat(get(client, "/portability/import/" + token + "?page=21").body())
            .contains("medium-1099");
        var result =
            post(
                client, "/portability/import/" + token, Map.of("_csrf", csrf, "confirmed", "true"));
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM calendar_resources WHERE collection_id=?",
                    Long.class,
                    target))
            .isEqualTo(1100);
        for (var batch = calendars.resourceBatch(owner, target, ""); !batch.isEmpty(); ) {
          for (var resource : batch) {
            var parsed =
                org.busystack.caldav.calendar.CalendarContent.parse(resource.content(), type);
            int n = Integer.parseInt(parsed.uid().substring("medium-".length()));
            assertThat(resource.content()).isEqualTo(mediumStreamBody(n, type));
          }
          batch = calendars.resourceBatch(owner, target, batch.getLast().name());
        }
      } finally {
        Files.deleteIfExists(downloaded);
      }
    }
  }

  @Test
  void realHttpLargeRoundTripHasPagedPreviewAndPreservesAllResourceBytes() throws Exception {
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    String padding = "x".repeat(6400);
    tx.executeWithoutResult(
        status -> {
          for (int n = 0; n < 10000; n++) {
            byte[] content =
                calendarBody("large-" + n)
                    .replace("X-CLIENT:preserve", "X-CLIENT:" + padding)
                    .getBytes(StandardCharsets.UTF_8);
            dav.putResource(
                owner, collection, "large-%04d.ics".formatted(n), content, WriteCondition.create());
          }
        });
    UUID target = portal.create(auth(owner, true), "Large migration", "VEVENT", "", "");
    var browser = browser(owner, true);
    java.nio.file.Path downloadFile = Files.createTempFile("busystack-qualification-", ".ics");
    var peakDisk = new java.util.concurrent.atomic.AtomicLong();
    var peakReserved = new java.util.concurrent.atomic.AtomicLong();
    var peakHeap = new java.util.concurrent.atomic.AtomicLong();
    var peakRss = new java.util.concurrent.atomic.AtomicLong();
    var sampler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    sampler.scheduleAtFixedRate(
        () -> {
          try {
            for (String line : Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))) {
              if (line.startsWith("VmRSS:"))
                peakRss.accumulateAndGet(
                    Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024, Math::max);
            }
            peakDisk.accumulateAndGet(portabilityStorage.usedBytes(), Math::max);
          } catch (java.io.IOException ignored) {
          }
          peakReserved.accumulateAndGet(portabilityStorage.reservedBytes(), Math::max);
          peakHeap.accumulateAndGet(
              java.lang.management.ManagementFactory.getMemoryMXBean()
                  .getHeapMemoryUsage()
                  .getUsed(),
              Math::max);
        },
        0,
        100,
        java.util.concurrent.TimeUnit.MILLISECONDS);
    try {
      var download =
          browser.send(
              HttpRequest.newBuilder(
                      URI.create(base() + "/portability/collections/" + collection + "/export"))
                  .timeout(Duration.ofSeconds(660))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofFile(downloadFile));
      assertThat(download.statusCode()).isEqualTo(200);
      assertThat(Files.size(download.body()))
          .isGreaterThan(60L * 1024 * 1024)
          .isLessThanOrEqualTo(64L * 1024 * 1024);
      String csrf = csrf(get(browser, "/portability").body());
      var preview = uploadFile(browser, csrf, target, download.body());
      assertThat(preview.statusCode()).isEqualTo(200);
      assertThat(preview.body()).contains("Next", "10000").doesNotContain("large-9999");
      var matcher =
          java.util.regex.Pattern.compile("/portability/import/([a-f0-9-]{36})")
              .matcher(preview.body());
      assertThat(matcher.find()).isTrue();
      String token = matcher.group(1);
      assertThat(get(browser, "/portability/import/" + token + "?page=199").body())
          .contains("large-9999");
      // A retained maximum-size preview and real overlapping downloads share one ledger.
      var concurrentOne = Files.createTempFile("busystack-qualification-export-", ".ics");
      var concurrentTwo = Files.createTempFile("busystack-qualification-export-", ".ics");
      try (var downloads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var request =
            HttpRequest.newBuilder(
                    URI.create(base() + "/portability/collections/" + collection + "/export"))
                .timeout(Duration.ofSeconds(660))
                .GET()
                .build();
        var one =
            downloads.submit(
                () -> browser.send(request, HttpResponse.BodyHandlers.ofFile(concurrentOne)));
        long waitUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        long retainedSize = Files.size(downloadFile);
        while (portabilityStorage.reservedBytes() < retainedSize + PortabilityStorage.FILE_BYTES
            && System.nanoTime() < waitUntil) Thread.sleep(10);
        assertThat(portabilityStorage.reservedBytes())
            .isGreaterThanOrEqualTo(retainedSize + PortabilityStorage.FILE_BYTES);
        var busy = upload(browser, csrf, target, "busy.ics", event("capacity-denied"));
        assertThat(busy.statusCode()).isEqualTo(503);
        assertThat(busy.headers().firstValue("Retry-After")).isPresent();
        var two =
            downloads.submit(
                () -> browser.send(request, HttpResponse.BodyHandlers.ofFile(concurrentTwo)));
        assertThat(one.get(660, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(two.get(660, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(Files.mismatch(downloadFile, concurrentOne)).isEqualTo(-1);
        assertThat(Files.mismatch(downloadFile, concurrentTwo)).isEqualTo(-1);
        assertThat(get(browser, "/portability/import/" + token).statusCode()).isEqualTo(200);
      } finally {
        Files.deleteIfExists(concurrentOne);
        Files.deleteIfExists(concurrentTwo);
      }
      var outcome =
          post(browser, "/portability/import/" + token, Map.of("_csrf", csrf, "confirmed", "true"));
      assertThat(outcome.statusCode()).isEqualTo(200);
      assertThat(outcome.body())
          .contains("10000", "All reported new items were committed together");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM calendar_resources WHERE collection_id=?",
                  Long.class,
                  target))
          .isEqualTo(10000);
      for (var batch = calendars.resourceBatch(owner, target, ""); !batch.isEmpty(); ) {
        for (var resource : batch) {
          String uid =
              org.busystack.caldav.calendar.CalendarContent.parse(resource.content(), "VEVENT")
                  .uid();
          assertThat(resource.content())
              .isEqualTo(
                  calendarBody(uid)
                      .replace("X-CLIENT:preserve", "X-CLIENT:" + padding)
                      .getBytes(StandardCharsets.UTF_8));
        }
        batch = calendars.resourceBatch(owner, target, batch.getLast().name());
      }
      var replayBrowser = browser(owner, true);
      String replayCsrf = csrf(get(replayBrowser, "/portability").body());
      var resultPage = get(replayBrowser, "/portability/import/" + token + "?page=199");
      assertThat(resultPage.statusCode()).isEqualTo(200);
      assertThat(resultPage.body()).contains("Imported", "10000");
      assertThat(
              post(
                      replayBrowser,
                      "/portability/import/" + token,
                      Map.of("_csrf", replayCsrf, "confirmed", "true"))
                  .statusCode())
          .isEqualTo(412);
    } finally {
      sampler.shutdownNow();
      sampler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
      Files.deleteIfExists(downloadFile);
      var output = java.nio.file.Path.of("target/qualification/portability");
      Files.createDirectories(output);
      Files.writeString(
          output.resolve("large-http.json"),
          "{\"items\":10000,\"heapLimit\":268435456,\"peakHeap\":"
              + peakHeap.get()
              + ",\"peakTemporaryDisk\":"
              + peakDisk.get()
              + ",\"peakRss\":"
              + peakRss.get()
              + ",\"peakReservation\":"
              + peakReserved.get()
              + "}\n");
    }
  }

  @Test
  void concurrentHttpConfirmationsCommitOnceAndFailedReplacementRetainsOriginal() throws Exception {
    var browser = browser(owner, true);
    var preview = portability.preview(auth(owner, true), collection, event("single-use"));
    denied(() -> portability.preview(auth(owner, true), collection, new byte[] {1}));
    String csrf = csrf(get(browser, "/portability").body());
    var failedUpload =
        upload(
            browser,
            csrf,
            collection,
            "invalid.ics",
            "invalid calendar".getBytes(StandardCharsets.UTF_8));
    assertThat(failedUpload.statusCode()).isEqualTo(400);
    assertThat(failedUpload.body())
        .contains(
            "href=\"/team/portability/import/" + preview.token() + "\"",
            "Review your latest import preview or results");
    assertThat(get(browser, "/portability/import/" + preview.token()).statusCode()).isEqualTo(200);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var operation =
          (java.util.concurrent.Callable<Integer>)
              () -> {
                start.await();
                return post(
                        browser,
                        "/portability/import/" + preview.token(),
                        Map.of("_csrf", csrf, "confirmed", "true"))
                    .statusCode();
              };
      var one = executor.submit(operation);
      var two = executor.submit(operation);
      start.countDown();
      assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 412);
    }
    assertThat(calendars.metadataBatch(owner, collection, "", 10)).hasSize(1);
  }

  @Test
  void httpRollbackRetainsEditableConfirmationBeforeAndAfterResourceWrites() throws Exception {
    var browser = browser(owner, true);
    String csrf = csrf(get(browser, "/portability").body());
    for (String failUid : List.of("a", "b")) {
      UUID target = portal.create(auth(owner, true), "Rollback " + failUid, "VEVENT", "", "");
      var preview =
          portability.preview(
              auth(owner, true),
              target,
              (calendarBody("a") + calendarBody("b")).getBytes(StandardCharsets.UTF_8));
      jdbc.execute(
          "CREATE FUNCTION reject_http_import() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.uid='"
              + failUid
              + "' THEN RAISE EXCEPTION 'injected persistence failure'; END IF; RETURN NEW; END $$");
      jdbc.execute(
          "CREATE TRIGGER reject_http_import BEFORE INSERT ON calendar_resources FOR EACH ROW EXECUTE FUNCTION reject_http_import()");
      try {
        var failed =
            post(
                browser,
                "/portability/import/" + preview.token(),
                Map.of("_csrf", csrf, "confirmed", "true"));
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(failed.body())
            .contains("href=\"/team/portability/import/" + preview.token() + "\"")
            .doesNotContain("injected persistence failure", "DTSTART");
      } finally {
        jdbc.execute("DROP TRIGGER reject_http_import ON calendar_resources");
        jdbc.execute("DROP FUNCTION reject_http_import()");
      }
      assertThat(calendars.metadataBatch(owner, target, "", 10)).isEmpty();
      assertThat(get(browser, "/portability/import/" + preview.token()).statusCode())
          .isEqualTo(200);
      assertThat(
              post(
                      browser,
                      "/portability/import/" + preview.token(),
                      Map.of("_csrf", csrf, "confirmed", "true"))
                  .statusCode())
          .isEqualTo(200);
      assertThat(calendars.metadataBatch(owner, target, "", 10)).hasSize(2);
    }
  }

  @Test
  void pendingPreviewsRejectDisabledAccountsAndChangedSecurityGenerations() throws Exception {
    var old = auth(owner, true);
    var preview = portability.preview(old, collection, event("epoch"));
    var staleClient = browser(owner, true);
    String staleCsrf = csrf(get(staleClient, "/portability").body());
    jdbc.update("UPDATE accounts SET security_epoch=gen_random_uuid() WHERE id=?", owner);
    denied(() -> portability.confirm(old, preview.token(), true));
    denied(() -> portability.confirm(auth(owner, true), preview.token(), true));
    assertThat(
            post(
                    staleClient,
                    "/portability/import/" + preview.token(),
                    Map.of("_csrf", staleCsrf, "confirmed", "true"))
                .statusCode())
        .isEqualTo(403);
    var fresh = auth(owner, true);
    var valid = portability.preview(fresh, collection, event("disabled"));
    var disabledClient = browser(owner, true);
    String disabledCsrf = csrf(get(disabledClient, "/portability").body());
    accounts.disable(owner);
    denied(() -> portability.confirm(fresh, valid.token(), true));
    assertThat(
            post(
                    disabledClient,
                    "/portability/import/" + valid.token(),
                    Map.of("_csrf", disabledCsrf, "confirmed", "true"))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM calendar_resources WHERE collection_id=?",
                Long.class,
                collection))
        .isZero();
  }

  @Test
  void ordinaryErrorsReturnEditableFormsWithAssociatedFieldsAndPreservedNonsecretValues()
      throws Exception {
    var administrator = browser(admin, true);
    var user = browser(owner, true);
    String adminCsrf = csrf(get(administrator, "/admin/accounts/new").body());
    String userCsrf = csrf(get(user, "/collections/new").body());
    var accountError =
        post(
            administrator,
            "/admin/accounts",
            Map.of(
                "_csrf",
                adminCsrf,
                "username",
                "invalid username",
                "displayName",
                "Retain display",
                "password",
                "Never redisplay this password"));
    fieldError(accountError, 400, "account-create", "username");
    assertThat(accountError.body())
        .contains("value=\"Retain display\"", "value=\"invalid username\"")
        .doesNotContain("Never redisplay this password");
    fieldError(
        post(
            administrator,
            "/admin/accounts",
            Map.of(
                "_csrf",
                adminCsrf,
                "username",
                "portal-owner",
                "displayName",
                "Retain display",
                "password",
                "Never redisplay this password")),
        409,
        "account-create",
        "username");
    fieldError(
        post(
            administrator,
            "/admin/accounts",
            Map.of(
                "_csrf",
                adminCsrf,
                "username",
                "new-user",
                "displayName",
                "",
                "password",
                "short")),
        400,
        "account-create",
        "password");
    fieldError(
        post(
            administrator,
            "/admin/accounts/" + owner + "/edit",
            Map.of(
                "_csrf",
                adminCsrf,
                "expected",
                accounts.find(owner).displayName(),
                "displayName",
                "")),
        400,
        "account-edit",
        "displayName");
    var collectionError =
        post(
            user,
            "/collections",
            Map.of(
                "_csrf",
                userCsrf,
                "name",
                "Retain name",
                "description",
                "Retain description",
                "color",
                "invalid",
                "component",
                "VTODO"));
    fieldError(collectionError, 400, "collection-create", "color");
    assertThat(collectionError.body())
        .contains(
            "value=\"Retain name\"",
            ">Retain description</textarea>",
            "value=\"VTODO\" selected=\"selected\"");
    fieldError(
        post(
            user,
            "/collections/" + collection + "/edit",
            Map.of(
                "_csrf",
                userCsrf,
                "state",
                portal.collection(auth(owner, true), collection).state(),
                "name",
                "",
                "description",
                "Retain description")),
        400,
        "collection-edit",
        "name");
    fieldError(
        post(administrator, "/admin/groups", Map.of("_csrf", adminCsrf, "name", "")),
        400,
        "group-create",
        "name");
    UUID group = groups.create(auth(admin, true), "Form group", Set.of());
    fieldError(
        post(administrator, "/admin/groups", Map.of("_csrf", adminCsrf, "name", "Form group")),
        409,
        "group-create",
        "name");
    fieldError(
        post(
            administrator,
            "/admin/groups/" + group,
            Map.of("_csrf", adminCsrf, "expected", "Form group", "name", "x".repeat(201))),
        400,
        "group-rename",
        "name");
    fieldError(
        post(
            administrator,
            "/admin/groups/" + group + "/members",
            Map.of("_csrf", adminCsrf, "username", "missing-account", "confirmed", "true")),
        400,
        "member-add",
        "username");
    fieldError(
        post(
            administrator,
            "/admin/groups/" + group + "/members",
            Map.of("_csrf", adminCsrf, "username", "portal-reader")),
        400,
        "member-add",
        "confirmed");
    fieldError(
        post(
            user,
            "/shares",
            Map.of(
                "_csrf",
                userCsrf,
                "collection",
                collection.toString(),
                "username",
                "missing-account",
                "permission",
                "READ",
                "expected",
                "NONE")),
        400,
        "direct-add",
        "username");
    fieldError(
        post(
            user,
            "/credentials",
            Map.of("_csrf", userCsrf, "label", "Retain device", "days", "366")),
        400,
        "credential-create",
        "days");
    fieldError(
        post(
            user,
            "/credentials",
            Map.of("_csrf", userCsrf, "label", "Retain device", "days", "abc")),
        400,
        "credential-create",
        "days");
    fieldError(
        post(user, "/credentials", Map.of("_csrf", userCsrf, "label", "", "days", "90")),
        400,
        "credential-create",
        "label");
    var preview = portability.preview(auth(owner, true), collection, event("confirmation"));
    fieldError(
        post(user, "/portability/import/" + preview.token(), Map.of("_csrf", userCsrf)),
        400,
        "import-confirm",
        "confirmed");
    var primaryOnly = browser(owner, false);
    assertThat(
            post(
                    primaryOnly,
                    "/collections",
                    Map.of(
                        "_csrf",
                        csrf(get(primaryOnly, "/collections").body()),
                        "name",
                        "",
                        "component",
                        "VEVENT"))
                .statusCode())
        .isEqualTo(403);
  }

  private static void fieldError(
      HttpResponse<String> response, int status, String form, String field) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.body())
        .contains(
            "data-error-summary",
            "id=\"" + form + "-" + field + "\"",
            "aria-describedby=\"" + form + "-" + field + "-error\"",
            "id=\"" + form + "-" + field + "-error\"",
            "aria-invalid=\"true\"");
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
  }

  @Test
  void administrativeAuditUsesOpaqueTargetsAndMembershipContextWithoutPrivateData()
      throws Exception {
    UUID created =
        administration.create(
            auth(admin, true),
            "audit-created",
            "Private personal label",
            "Never record this password");
    administration.edit(
        auth(admin, true), created, "Private personal label", "Another private label");
    administration.disable(auth(admin, true), created, true);
    UUID group = groups.create(auth(admin, true), "Private group label", Set.of());
    groups.rename(auth(admin, true), group, "Private group label", "Another group label");
    groups.changeMember(auth(admin, true), group, "portal-reader", null, true, true);
    groups.changeMember(auth(admin, true), group, null, reader, false, true);
    groups.delete(auth(admin, true), group);
    for (String action :
        List.of(
            "ACCOUNT_CREATED",
            "ACCOUNT_PROFILE_UPDATED",
            "ACCOUNT_DISABLED",
            "GROUP_CREATED",
            "GROUP_UPDATED",
            "GROUP_MEMBER_ADDED",
            "GROUP_MEMBER_REMOVED",
            "GROUP_DELETED")) {
      var event =
          administration.audit(auth(admin, true), action, "SUCCESS", "portal-admin", 0).rows();
      assertThat(event).hasSize(1);
      var entry = event.getFirst();
      assertThat(entry.actor()).isEqualTo(admin);
      assertThat(entry.targetType()).isEqualTo(action.startsWith("ACCOUNT") ? "ACCOUNT" : "GROUP");
      assertThat(entry.targetId()).isEqualTo(action.startsWith("ACCOUNT") ? created : group);
      assertThat(entry.time()).isNotNull();
      assertThat(entry.relatedAccount())
          .isEqualTo(action.startsWith("GROUP_MEMBER_") ? reader : null);
    }
    var historical =
        administration.audit(auth(admin, true), "COLLECTION_CREATED", "", "", 0).rows().getFirst();
    assertThat(historical.targetType()).isNull();
    assertThat(historical.targetId()).isNull();
    var response = get(browser(admin, true), "/admin/audit");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains(
            created.toString(), group.toString(), "Not recorded (historical or untargeted event)")
        .doesNotContain(
            "Never record this password",
            "Private personal label",
            "Another private label",
            "Private group label",
            "Another group label",
            hostile,
            "DTSTART",
            collection.toString());
    assertThat(get(browser(owner, true), "/admin/audit").statusCode()).isEqualTo(403);
  }

  @Test
  void targetedAuditFailureRollsBackTheAdministrativeMutation() {
    var original = accounts.find(owner);
    jdbc.execute(
        "CREATE FUNCTION reject_admin_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='ACCOUNT_PROFILE_UPDATED' THEN RAISE EXCEPTION 'injected audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_admin_audit BEFORE INSERT ON security_audit FOR EACH ROW EXECUTE FUNCTION reject_admin_audit()");
    try {
      assertThatThrownBy(
              () ->
                  administration.edit(
                      auth(admin, true), owner, original.displayName(), "Must roll back"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(accounts.find(owner).displayName()).isEqualTo(original.displayName());
      assertThat(
              administration.audit(auth(admin, true), "ACCOUNT_PROFILE_UPDATED", "", "", 0).rows())
          .isEmpty();
    } finally {
      jdbc.execute("DROP TRIGGER reject_admin_audit ON security_audit");
      jdbc.execute("DROP FUNCTION reject_admin_audit()");
    }
  }

  @Test
  void allPortalTemplatesRenderOverHttpsAndEscapeUntrustedText() throws Exception {
    var browser = browser(owner, true);
    for (String path :
        List.of(
            "/",
            "/collections",
            "/collections/new",
            "/collections/" + collection,
            "/collections/" + collection + "/delete",
            "/collections/" + collection + "/sharing",
            "/connections",
            "/security",
            "/security/credentials",
            "/portability",
            "/security/recovery",
            "/security/password",
            "/security/sessions",
            "/oauth-grants",
            "/security/passkeys",
            "/login")) {
      var response = get(browser, path);
      assertThat(response.statusCode()).as(path).isEqualTo(200);
      assertThat(response.body())
          .as(path)
          .contains("/team/assets/account.css")
          .doesNotContain(hostile);
      assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
      if (path.equals("/connections")) {
        assertThat(response.body())
            .contains("id=\"gnome-address\"", "value=\"https://portal-owner@localhost/team/dav/\"");
      }
    }
    var adminBrowser = browser(admin, true);
    UUID g = groups.create(auth(admin, true), "Group " + hostile, Set.of());
    for (String path :
        List.of(
            "/admin",
            "/admin/accounts",
            "/admin/accounts/new",
            "/admin/accounts/" + owner,
            "/admin/groups",
            "/admin/groups/" + g,
            "/admin/audit",
            "/admin/diagnostics",
            "/admin/oauth-clients")) {
      var response = get(adminBrowser, path);
      assertThat(response.statusCode()).as(path).isEqualTo(200);
      assertThat(response.body()).as(path).doesNotContain(hostile);
    }
    assertThat(get(browser, "/admin/accounts").statusCode()).isEqualTo(403);
    assertThat(get(adminBrowser, "/collections/" + collection).statusCode()).isEqualTo(403);
  }

  @Test
  void browserMutationsRequireCsrfAndServerConfirmation() throws Exception {
    var browser = browser(owner, true);
    assertThat(
            post(browser, "/collections", Map.of("name", "CSRF denied", "component", "VEVENT"))
                .statusCode())
        .isEqualTo(403);
    String csrf = csrf(get(browser, "/collections/" + collection + "/delete").body());
    var deleted =
        post(
            browser,
            "/collections/" + collection + "/delete",
            Map.of(
                "_csrf",
                csrf,
                "state",
                portal.collection(auth(owner, true), collection).deletionState()));
    assertThat(deleted.statusCode()).isEqualTo(400);
    assertThat(calendars.collection(owner, collection)).isNotNull();
    var created =
        post(
            browser,
            "/collections",
            Map.of("_csrf", csrf, "name", "Browser collection", "component", "VTODO"));
    assertThat(created.statusCode()).isEqualTo(302);
    assertThat(created.headers().firstValue("Location").orElse("")).contains("/team/collections/");
  }

  @Test
  void multipartUploadsAreRejectedOutsideTheReservedPortabilityRoute() throws Exception {
    var client = browser(owner, true);
    String boundary = "BusyStackOutsideRoute";
    String body =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"unused.ics\"\r\n\r\nprivate fixture\r\n--"
            + boundary
            + "--\r\n";
    for (String route :
        List.of("/login", "/collections", "/credentials", "/dav/calendars/unused.ics")) {
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create(base() + route))
                  .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(415);
      assertThat(response.body()).doesNotContain("private fixture");
    }
  }

  @Test
  void multipartAdmissionChecksRealBytesSizeAndDestinationWithoutLeakingContent() throws Exception {
    var browser = browser(owner, true);
    String csrf = csrf(get(browser, "/portability").body());
    var invalid =
        upload(
            browser,
            csrf,
            collection,
            "../../unsafe.ics",
            "<script>not a calendar</script>".getBytes(StandardCharsets.UTF_8));
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(invalid.body())
        .contains("/team/assets/account.css")
        .doesNotContain("<script>not a calendar</script>", "../../unsafe.ics");
    var oversized = upload(browser, csrf, collection, "large.ics", new byte[64 * 1024 * 1024 + 1]);
    assertThat(oversized.statusCode()).isEqualTo(413);
    assertThat(oversized.body()).contains("/team/assets/account.css", "Upload too large");
    assertThat(oversized.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    var oversizedForm =
        upload(browser, csrf, collection, "large.ics", new byte[64 * 1024 * 1024 + 1], false);
    assertThat(oversizedForm.statusCode()).isEqualTo(413);
    assertThat(oversizedForm.body()).contains("/team/assets/account.css", "Upload too large");
    assertThat(problems.recent())
        .noneMatch(
            p ->
                p.requestId()
                    .equals(oversizedForm.headers().firstValue("X-Request-ID").orElseThrow()));
    assertThat(
            upload(
                    browser,
                    csrf,
                    portal.create(auth(other, true), "Other private", "VEVENT", "", ""),
                    "calendar.ics",
                    event("denied"))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            upload(browser, csrf, collection, "../../unsafe.ics", event("accepted")).statusCode())
        .isEqualTo(200);
    assertThat(calendars.metadataBatch(owner, collection, "", 10)).isEmpty();
    assertThat(
            get(browser(admin, true), "/portability/collections/" + collection + "/export")
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void exportHttpHeadersAndAuditViewerNeverRevealSecrets() throws Exception {
    var ownerBrowser = browser(owner, true);
    assertThat(
            get(browser(owner, false), "/portability/collections/" + collection + "/export")
                .statusCode())
        .isEqualTo(403);
    var empty = get(ownerBrowser, "/portability/collections/" + collection + "/export");
    assertThat(empty.statusCode()).isEqualTo(302);
    assertThat(empty.headers().firstValue("Location").orElse(""))
        .endsWith("/team/collections/" + collection);
    assertThat(get(ownerBrowser, "/collections/" + collection).body())
        .contains("This export snapshot contained no calendar or task items");
    calendars.put(owner, collection, "one.ics", "one", event("one"), WriteCondition.create());
    calendars.share(owner, collection, reader, "READ");
    assertThat(
            get(browser(reader, true), "/portability/collections/" + collection + "/export").body())
        .isEqualTo(calendarBody("one"));
    var credential = management.issue(auth(owner, true), "Private label", 1);
    var response = get(browser(owner, true), "/portability/collections/" + collection + "/export");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(calendarBody("one"));
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("text/calendar");
    assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
        .contains("attachment")
        .doesNotContain(hostile);
    assertThat(get(browser(admin, true), "/admin/audit").body())
        .doesNotContain(credential.secret())
        .doesNotContain("DTSTART");
    denied(() -> administration.audit(auth(owner, true), "", "", "", 0));
    assertThat(
            administration
                .audit(auth(admin, true), "COLLECTION_CREATED", "SUCCESS", "portal-owner", 0)
                .rows())
        .hasSize(1);
  }

  @Test
  void nativeCalendarAndTaskFixturesRoundTripWithoutByteChanges() throws Exception {
    for (String type : List.of("VEVENT", "VTODO")) {
      String name = type.equals("VEVENT") ? "thunderbird-event.ics" : "thunderbird-task.ics";
      byte[] original;
      try (var input = getClass().getResourceAsStream("/calendar/clients/" + name)) {
        assertThat(input).isNotNull();
        original = input.readAllBytes();
      }
      UUID source = portal.create(auth(owner, true), "Native " + type, type, "", "");
      var first = portability.preview(auth(owner, true), source, original);
      assertThat(first.ready()).isEqualTo(1);
      assertThat(portability.confirm(auth(owner, true), first.token(), true).imported())
          .isEqualTo(1);
      var export = portability.export(auth(owner, true), source);
      byte[] exported;
      try {
        exported = Files.readAllBytes(export.path());
      } finally {
        export.close();
      }
      assertThat(exported).isEqualTo(original);
      UUID target = portal.create(auth(owner, true), "Migrated " + type, type, "", "");
      var second = portability.preview(auth(owner, true), target, exported);
      assertThat(portability.confirm(auth(owner, true), second.token(), true).imported())
          .isEqualTo(1);
      var metadata = calendars.metadataBatch(owner, target, "", 10);
      assertThat(metadata).hasSize(1);
      var parsed = org.busystack.caldav.calendar.CalendarContent.parse(original, type);
      assertThat(metadata.getFirst().uid()).isEqualTo(parsed.uid());
      assertThat(calendars.get(owner, target, metadata.getFirst().name()).content())
          .isEqualTo(original);
    }
  }

  @Test
  void supportedDirectoryCapacityUsesBoundedPagesAndSingleMemberAdmission() {
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    tx.executeWithoutResult(
        state -> {
          jdbc.update(
              "INSERT INTO accounts(id,username,display_name,password_hash) SELECT gen_random_uuid(),'capacity-user-'||lpad(n::text,4,'0'),'Capacity user '||n,? FROM generate_series(1,1000) n",
              accounts.find(owner).passwordHash());
          jdbc.update(
              "INSERT INTO sharing_groups(id,name,created_by) SELECT gen_random_uuid(),'Capacity group '||lpad(n::text,4,'0'),? FROM generate_series(1,1000) n",
              admin);
        });
    UUID group = groups.search(auth(admin, true), "Capacity group 0001", 0).rows().getFirst().id();
    jdbc.update(
        "INSERT INTO sharing_group_members(group_id,account_id) SELECT ?,id FROM accounts WHERE username LIKE 'capacity-user-%'",
        group);
    long start = System.nanoTime();
    var accountPage = administration.accounts(auth(admin, true), "capacity-user-", "local", 0);
    long accountsMillis = (System.nanoTime() - start) / 1000000;
    assertThat(accountPage.rows()).hasSize(50);
    assertThat(accountPage.hasNext()).isTrue();
    var last = administration.accounts(auth(admin, true), "capacity-user-", "local", 19);
    assertThat(last.rows()).hasSize(50);
    assertThat(last.hasNext()).isFalse();
    start = System.nanoTime();
    var groupPage = groups.search(auth(admin, true), "Capacity group ", 0);
    long groupsMillis = (System.nanoTime() - start) / 1000000;
    assertThat(groupPage.rows()).hasSize(50);
    assertThat(groupPage.hasNext()).isTrue();
    start = System.nanoTime();
    var members = groups.members(auth(admin, true), group, "", 19);
    long membersMillis = (System.nanoTime() - start) / 1000000;
    assertThat(members.rows()).hasSize(50);
    assertThat(members.hasNext()).isFalse();
    denied(() -> groups.changeMember(auth(admin, true), group, "portal-reader", null, true, true));
    groups.changeMember(
        auth(admin, true), group, null, members.rows().getFirst().account(), false, true);
    groups.changeMember(auth(admin, true), group, "portal-reader", null, true, true);
    assertThat(groups.detail(auth(admin, true), group).members()).isEqualTo(1000);
    System.out.printf(
        "PORTAL_CAPACITY accounts=1000 groups=1000 members=1000 account_page_ms=%d group_page_ms=%d member_page_ms=%d%n",
        accountsMillis, groupsMillis, membersMillis);
  }

  private Authentication auth(UUID id, boolean mfa) {
    return authFor(accounts.find(id), mfa);
  }

  private Authentication authFor(AccountService.Account a, boolean mfa) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority(a.administrator() ? "ROLE_ADMIN" : "ROLE_USER"));
    authorities.add(SessionSecurity.authority(a.securityEpoch()));
    authorities.add(
        FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY));
    if (mfa)
      authorities.add(
          FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY));
    return UsernamePasswordAuthenticationToken.authenticated(a.username(), null, authorities);
  }

  private <S extends Session> String persist(SessionRepository<S> repo, Authentication auth) {
    S session = repo.createSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(auth));
    repo.save(session);
    return session.getId();
  }

  private HttpClient browser(UUID id, boolean mfa) throws Exception {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    String encoded =
        Base64.getEncoder()
            .encodeToString(persist(repository, auth(id, mfa)).getBytes(StandardCharsets.UTF_8));
    cookies.put(
        URI.create(base()),
        Map.of(
            "Set-Cookie",
            List.of("JSESSIONID=" + encoded + "; Path=/team; Secure; HttpOnly; SameSite=Lax")));
    return HttpClient.newBuilder()
        .sslContext(TestTls.instance().context())
        .cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
    return browser.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(Duration.ofSeconds(660))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(HttpClient browser, String path, Map<String, String> fields)
      throws Exception {
    String form =
        fields.entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    return browser.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(Duration.ofSeconds(660))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> upload(
      HttpClient browser, String csrf, UUID destination, String filename, byte[] content)
      throws Exception {
    return upload(browser, csrf, destination, filename, content, true);
  }

  private HttpResponse<String> upload(
      HttpClient browser,
      String csrf,
      UUID destination,
      String filename,
      byte[] content,
      boolean header)
      throws Exception {
    String boundary = "BusyStack" + UUID.randomUUID().toString().replace("-", "");
    var body = new java.io.ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n"
                + csrf
                + "\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"destination\"\r\n\r\n"
                + destination
                + "\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename
                + "\"\r\nContent-Type: text/calendar\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(URI.create(base() + "/portability/preview"))
            .timeout(Duration.ofSeconds(660))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Accept", "text/html")
            .POST(
                HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()),
                    HttpRequest.BodyPublishers.ofByteArray(content),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")));
    if (header) request.header("X-CSRF-TOKEN", csrf);
    return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> uploadFile(
      HttpClient browser, String csrf, UUID destination, java.nio.file.Path file) throws Exception {
    String boundary = "BusyStack" + UUID.randomUUID().toString().replace("-", "");
    String head =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"destination\"\r\n\r\n"
            + destination
            + "\r\n--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"export.ics\"\r\nContent-Type: text/calendar\r\n\r\n";
    return browser.send(
        HttpRequest.newBuilder(URI.create(base() + "/portability/preview"))
            .timeout(Duration.ofSeconds(660))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("X-CSRF-TOKEN", csrf)
            .POST(
                HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString(head),
                    HttpRequest.BodyPublishers.ofFile(file),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String base() {
    return "https://localhost:" + port + "/team";
  }

  private static String csrf(String html) {
    var matcher =
        java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private static String calendarBody(String uid) {
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Portal fixture//EN\r\nBEGIN:VEVENT\r\nUID:"
        + uid
        + "\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261028T000000Z\r\nX-CLIENT:preserve\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
  }

  private static byte[] event(String uid) {
    return calendarBody(uid).getBytes(StandardCharsets.UTF_8);
  }

  private interface Checked {
    void run() throws Exception;
  }

  private static void denied(Checked operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfAny(
            IllegalArgumentException.class, StoreException.class, AccessDeniedException.class);
  }
}
