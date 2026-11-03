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
      Files.deleteIfExists(exported.path());
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
      var items =
          new org.busystack.caldav.calendar.CalendarInterchange(
                  new org.busystack.caldav.calendar.CalendarAdmission(
                      new org.busystack.caldav.config.DavCompatibilityProperties(true, true)))
              .read(Files.readAllBytes(export.path()), "VEVENT");
      assertThat(items).hasSize(2).allSatisfy(i -> assertThat(i.rejection()).isEmpty());
      assertThat(items)
          .extracting(i -> new String(i.content(), StandardCharsets.UTF_8))
          .containsExactlyInAnyOrder(
              new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8));
    } finally {
      Files.deleteIfExists(export.path());
    }
  }

  @Test
  void importRechecksWritePermissionAndReportsRejectedItemsWithoutMutation() throws Exception {
    management.share(auth(owner, true), collection, "portal-reader", "WRITE");
    var preview = portability.preview(auth(reader, true), collection, event("safe"));
    management.share(auth(owner, true), collection, "portal-reader", "READ");
    denied(() -> portability.confirm(auth(reader, true), preview.token(), true));
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
    var oversized = upload(browser, csrf, collection, "large.ics", new byte[8 * 1024 * 1024 + 1]);
    assertThat(oversized.statusCode()).isEqualTo(413);
    assertThat(oversized.body()).contains("/team/assets/account.css", "Upload too large");
    assertThat(oversized.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    var oversizedForm =
        upload(browser, csrf, collection, "large.ics", new byte[8 * 1024 * 1024 + 1], false);
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
        Files.deleteIfExists(export.path());
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
            .timeout(Duration.ofSeconds(30))
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
            .timeout(Duration.ofSeconds(30))
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
    body.write(content);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(URI.create(base() + "/portability/preview"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Accept", "text/html")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
    if (header) request.header("X-CSRF-TOKEN", csrf);
    return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
