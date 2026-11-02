package org.busystack.caldav.protocol;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.busystack.caldav.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.w3c.dom.Element;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "busystack.public-base-url=https://calendar.example/team",
      "busystack.issuer=https://calendar.example/team"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class DavHttpIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("server.ssl.enabled", () -> true);
    registry.add(
        "server.ssl.key-store", () -> org.busystack.caldav.testing.TestTls.instance().keyStore());
    registry.add(
        "server.ssl.key-store-password",
        () -> org.busystack.caldav.testing.TestTls.instance().password());
  }

  @LocalServerPort int port;
  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  UUID alice, bob;
  String aliceAuth, bobAuth, home;
  final HttpClient http =
      HttpClient.newBuilder()
          .sslContext(org.busystack.caldav.testing.TestTls.instance().context())
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    alice = accounts.provision("alice", "Alice & Calendar", "a secure Alice password", false);
    bob = accounts.provision("bob", "Bob Tasks", "a secure Bob password", false);
    aliceAuth =
        basic(
            "alice",
            accounts.issueDavCredential(alice, "client", Instant.now().plusSeconds(3600)).secret());
    bobAuth =
        basic(
            "bob",
            accounts.issueDavCredential(bob, "client", Instant.now().plusSeconds(3600)).secret());
    home = "/calendars/" + alice + "/";
  }

  private String basic(String user, String secret) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
  }

  private HttpRequest.Builder request(String path, String auth) {
    return HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/team/dav" + path))
        .header("Authorization", auth);
  }

  private HttpResponse<String> sendAs(
      String method, String path, String body, String auth, String... headers) throws Exception {
    var builder =
        request(path, auth)
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (headers.length > 0) builder.headers(headers);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> send(String method, String path, String body, String... headers)
      throws Exception {
    return sendAs(method, path, body, aliceAuth, headers);
  }

  private String create(String name, String component) throws Exception {
    String path = home + name + "/";
    assertThat(
            send(
                    "MKCALENDAR",
                    path,
                    "<C:mkcalendar xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:set><D:prop><D:displayname>"
                        + name
                        + "</D:displayname><C:supported-calendar-component-set><C:comp name='"
                        + component
                        + "'/></C:supported-calendar-component-set></D:prop></D:set></C:mkcalendar>")
                .statusCode())
        .isEqualTo(201);
    return path;
  }

  private static String event(String uid, String summary) {
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack protocol fixture//EN\r\nBEGIN:VEVENT\r\nUID:"
        + uid
        + "\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20261027T100000Z\r\nDTEND:20261027T110000Z\r\nSUMMARY:"
        + summary
        + "\r\nX-CLIENT-PROPERTY;X-PARAM=kept:preserve me\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
  }

  private static String query(String filter) {
    return "<C:calendar-query xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:prop><D:getetag/><C:calendar-data/></D:prop><C:filter><C:comp-filter name='VCALENDAR'>"
        + filter
        + "</C:comp-filter></C:filter></C:calendar-query>";
  }

  private static String sync(String token, int limit) {
    return "<D:sync-collection xmlns:D='DAV:'><D:sync-token>"
        + token
        + "</D:sync-token><D:sync-level>1</D:sync-level><D:limit><D:nresults>"
        + limit
        + "</D:nresults></D:limit><D:prop><D:getetag/></D:prop></D:sync-collection>";
  }

  private static Element xml(HttpResponse<String> response) {
    return DavXml.parse(response.body().getBytes(StandardCharsets.UTF_8));
  }

  private static String text(HttpResponse<String> response, String namespace, String local) {
    return xml(response).getElementsByTagNameNS(namespace, local).item(0).getTextContent();
  }

  @Test
  void discoveryUsesStablePrincipalAndRealComponentCapabilities() throws Exception {
    var root =
        send(
            "PROPFIND",
            "/",
            "<D:propfind xmlns:D='DAV:'><D:prop><D:current-user-principal/><D:principal-collection-set/></D:prop></D:propfind>",
            "Depth",
            "0");
    assertThat(root.statusCode()).isEqualTo(207);
    assertThat(root.body()).contains("https://calendar.example/team/dav/principals/" + alice + "/");
    var principal =
        send(
            "PROPFIND",
            "/principals/" + alice + "/",
            "<D:propfind xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav'><D:prop><C:calendar-home-set/><D:displayname/></D:prop></D:propfind>",
            "Depth",
            "0");
    assertThat(principal.statusCode()).isEqualTo(207);
    assertThat(principal.body())
        .contains("Alice &amp; Calendar", "https://calendar.example/team/dav" + home);
    String path = create("tasks", "VTODO");
    var props = send("PROPFIND", path, "", "Depth", "0");
    assertThat(props.statusCode()).isEqualTo(207);
    assertThat(props.body())
        .contains("VTODO")
        .doesNotContain("calendar-schedule", "calendarserver-sharing");
    var options = send("OPTIONS", path, "");
    assertThat(options.statusCode()).isEqualTo(200);
    assertThat(options.headers().firstValue("DAV").orElseThrow())
        .doesNotContain("schedule", "2", "extended-mkcol");
  }

  @Test
  void acceptedOctetsStrongValidatorsUnicodeAndLiteralPlusRoundTrip() throws Exception {
    String path = create("events", "VEVENT") + "a+b-%E9%9B%AA.ics",
        data = event("unicode-uid", "Café 雪 & <XML>");
    var put =
        send(
            "PUT",
            path,
            data,
            "Content-Type",
            "text/calendar; charset=utf-8",
            "If-None-Match",
            "*");
    assertThat(put.statusCode()).isEqualTo(201);
    String etag = put.headers().firstValue("ETag").orElseThrow();
    assertThat(etag).startsWith("\"").endsWith("\"");
    var get = send("GET", path, "");
    assertThat(get.statusCode()).isEqualTo(200);
    assertThat(get.body()).isEqualTo(data);
    assertThat(get.headers().firstValue("ETag")).hasValue(etag);
    var head = send("HEAD", path, "");
    assertThat(head.statusCode()).isEqualTo(200);
    assertThat(head.body()).isEmpty();
    assertThat(head.headers().firstValue("Content-Length"))
        .hasValue(Integer.toString(data.getBytes(StandardCharsets.UTF_8).length));
    assertThat(send("GET", path, "", "If-None-Match", "W/" + etag).statusCode()).isEqualTo(304);
    assertThat(send("PUT", path, data, "If-Match", "W/" + etag).statusCode()).isEqualTo(412);
    String report =
        "<C:calendar-multiget xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:prop><C:calendar-data/><D:getetag/></D:prop><D:href>https://calendar.example/team/dav"
            + path
            + "</D:href></C:calendar-multiget>";
    var multiget = send("REPORT", path.substring(0, path.lastIndexOf('/') + 1), report);
    assertThat(multiget.statusCode()).isEqualTo(207);
    assertThat(text(multiget, DavXml.CAL, "calendar-data")).isEqualTo(data);
  }

  @Test
  void createUpdateDeletePreconditionsAreEvaluatedInsideMutation() throws Exception {
    String path = create("events", "VEVENT") + "race.ics", data = event("race-uid", "Race");
    HttpRequest first =
        request(path, aliceAuth)
            .header("If-None-Match", "*")
            .PUT(HttpRequest.BodyPublishers.ofString(data))
            .build();
    var a = http.sendAsync(first, HttpResponse.BodyHandlers.ofString());
    var b = http.sendAsync(first, HttpResponse.BodyHandlers.ofString());
    CompletableFuture.allOf(a, b).join();
    assertThat(List.of(a.join().statusCode(), b.join().statusCode()))
        .containsExactlyInAnyOrder(201, 412);
    String old = send("GET", path, "").headers().firstValue("ETag").orElseThrow();
    assertThat(send("PUT", path, event("race-uid", "Changed"), "If-Match", old).statusCode())
        .isEqualTo(204);
    assertThat(send("DELETE", path, "", "If-Match", old).statusCode()).isEqualTo(412);
    assertThat(send("GET", path, "").body()).contains("SUMMARY:Changed");
    String current = send("GET", path, "").headers().firstValue("ETag").orElseThrow();
    assertThat(send("PUT", path, data, "If-None-Match", "W/" + current).statusCode())
        .isEqualTo(412);
    assertThat(send("DELETE", path, "", "If-Match", current).statusCode()).isEqualTo(204);
    assertThat(send("GET", path, "").statusCode()).isEqualTo(404);
  }

  @Test
  void queryMultigetAndFreeBusyUseProtocolBodiesAndBoundedRanges() throws Exception {
    String calendar = create("events", "VEVENT");
    send("PUT", calendar + "one.ics", event("one", "Snow meeting"));
    send("PUT", calendar + "two.ics", event("two", "Other"));
    String filter =
        "<C:comp-filter name='VEVENT'><C:time-range start='20261027T103000Z' end='20261027T113000Z'/><C:prop-filter name='SUMMARY'><C:text-match collation='i;ascii-casemap'>snow</C:text-match></C:prop-filter></C:comp-filter>";
    var query = send("REPORT", calendar, query(filter), "Depth", "1");
    assertThat(query.statusCode()).isEqualTo(207);
    assertThat(query.body()).contains("one.ics").doesNotContain("two.ics");
    var busy =
        send(
            "REPORT",
            calendar,
            "<C:free-busy-query xmlns:C='urn:ietf:params:xml:ns:caldav'><C:time-range start='20261027T000000Z' end='20261028T000000Z'/></C:free-busy-query>",
            "Depth",
            "1");
    assertThat(busy.statusCode()).isEqualTo(200);
    assertThat(busy.body()).contains("BEGIN:VFREEBUSY", "FREEBUSY").doesNotContain("Snow meeting");
    var missing =
        send(
            "REPORT",
            calendar,
            "<C:calendar-multiget xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:prop><D:getetag/></D:prop><D:href>https://calendar.example/team/dav"
                + calendar
                + "absent.ics</D:href></C:calendar-multiget>");
    assertThat(missing.statusCode()).isEqualTo(207);
    assertThat(missing.body()).contains("404 Not Found");
    assertThat(
            send(
                    "REPORT",
                    calendar,
                    query(
                        "<C:comp-filter name='VEVENT'><C:prop-filter name='SUMMARY'><C:text-match collation='evil'>x</C:text-match></C:prop-filter></C:comp-filter>"),
                    "Depth",
                    "1")
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void initialIncrementalDeletedExpiredAndLimitedSyncAreExplicit() throws Exception {
    String calendar = create("events", "VEVENT");
    assertThat(send("REPORT", calendar, sync("", 100), "Depth", "1").statusCode()).isEqualTo(207);
    assertThat(
            send(
                    "REPORT",
                    calendar,
                    sync("", 100)
                        .replace(
                            "<D:sync-level>1</D:sync-level>",
                            "<D:sync-level>infinite</D:sync-level>"),
                    "Depth",
                    "1")
                .statusCode())
        .isEqualTo(400);
    assertThat(send("REPORT", calendar, sync("", 100), "Depth", "infinity").statusCode())
        .isEqualTo(400);
    assertThat(send("REPORT", calendar, sync("", 100), "Depth", "0", "Depth", "1").statusCode())
        .isEqualTo(400);
    send("PUT", calendar + "one.ics", event("one", "One"));
    var initial = send("REPORT", calendar, sync("", 100), "Depth", "0");
    assertThat(initial.statusCode()).isEqualTo(207);
    String token = text(initial, DavXml.DAV, "sync-token");
    send("PUT", calendar + "two.ics", event("two", "Two"));
    send("DELETE", calendar + "one.ics", "");
    var delta = send("REPORT", calendar, sync(token, 100));
    assertThat(delta.statusCode()).isEqualTo(207);
    assertThat(delta.body()).contains("one.ics", "two.ics", "404 Not Found");
    var partial = send("REPORT", calendar, sync(token, 1));
    assertThat(partial.statusCode()).isEqualTo(207);
    assertThat(partial.body()).contains("HTTP/1.1 507 Insufficient Storage");
    var remaining = send("REPORT", calendar, sync(text(partial, DavXml.DAV, "sync-token"), 1));
    assertThat(remaining.statusCode()).isEqualTo(207);
    assertThat(remaining.body()).doesNotContain("507 Insufficient Storage");
    assertThat(send("REPORT", calendar, sync("urn:invalid", 100)).statusCode()).isEqualTo(403);
    String another = create("another", "VEVENT");
    assertThat(send("REPORT", another, sync(token, 100)).statusCode()).isEqualTo(403);
  }

  @Test
  void propertyUpdateIsAtomicAndUnknownXmlPropertiesRemainNamespaced() throws Exception {
    String calendar = create("events", "VEVENT");
    var failed =
        send(
            "PROPPATCH",
            calendar,
            "<D:propertyupdate xmlns:D='DAV:'><D:set><D:prop><D:displayname>Must not persist</D:displayname><D:getetag>invalid</D:getetag></D:prop></D:set></D:propertyupdate>");
    assertThat(failed.statusCode()).isEqualTo(207);
    assertThat(failed.body()).contains("403 Forbidden", "424 Failed Dependency");
    assertThat(send("PROPFIND", calendar, "", "Depth", "0").body())
        .contains(">events<")
        .doesNotContain("Must not persist");
    var changed =
        send(
            "PROPPATCH",
            calendar,
            "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:client'><D:set><D:prop><D:displayname>New name</D:displayname><X:metadata xml:lang='ja'><X:value>雪 &amp; ice</X:value></X:metadata></D:prop></D:set></D:propertyupdate>");
    assertThat(changed.statusCode()).isEqualTo(207);
    var props =
        send(
            "PROPFIND",
            calendar,
            "<D:propfind xmlns:D='DAV:' xmlns:X='urn:client'><D:prop><D:displayname/><X:metadata/><X:absent/></D:prop></D:propfind>",
            "Depth",
            "0");
    assertThat(props.statusCode()).isEqualTo(207);
    assertThat(props.body()).contains("New name", "雪 &amp; ice", "404 Not Found", "urn:client");
  }

  private String acl(String permission) {
    return "<D:acl xmlns:D='DAV:'><D:ace><D:principal><D:href>https://calendar.example/team/dav/principals/"
        + bob
        + "/</D:href></D:principal><D:grant><D:privilege><D:read/></D:privilege>"
        + (permission.equals("WRITE") ? "<D:privilege><D:write/></D:privilege>" : "")
        + "</D:grant></D:ace></D:acl>";
  }

  @Test
  void sharingUsesOneCollectionAndAllReadWriteAndRevocationPathsCheckAcl() throws Exception {
    String calendar = create("shared", "VEVENT");
    send("PUT", calendar + "secret.ics", event("secret", "Shared secret"));
    assertThat(send("ACL", calendar, acl("READ")).statusCode()).isEqualTo(200);
    UUID id =
        jdbc.queryForObject(
            "SELECT collection_id FROM dav_bindings WHERE home_id=? AND path='shared'",
            UUID.class,
            alice);
    String shared = "/calendars/" + bob + "/" + id + "/";
    assertThat(sendAs("PROPFIND", "/calendars/" + bob + "/", "", bobAuth, "Depth", "1").body())
        .contains(id.toString());
    assertThat(sendAs("GET", shared + "secret.ics", "", bobAuth).body()).contains("Shared secret");
    assertThat(
            sendAs("PUT", shared + "secret.ics", event("secret", "Forbidden"), bobAuth)
                .statusCode())
        .isEqualTo(403);
    assertThat(sendAs("GET", calendar + "secret.ics", "", bobAuth).statusCode()).isEqualTo(403);
    assertThat(send("ACL", calendar, acl("WRITE")).statusCode()).isEqualTo(200);
    assertThat(
            sendAs("PUT", shared + "secret.ics", event("secret", "Written by Bob"), bobAuth)
                .statusCode())
        .isEqualTo(204);
    assertThat(send("GET", calendar + "secret.ics", "").body()).contains("Written by Bob");
    String token = text(sendAs("REPORT", shared, sync("", 100), bobAuth), DavXml.DAV, "sync-token");
    assertThat(send("ACL", calendar, "<D:acl xmlns:D='DAV:'/>").statusCode()).isEqualTo(200);
    assertThat(sendAs("GET", shared + "secret.ics", "", bobAuth).statusCode()).isIn(403, 404);
    assertThat(sendAs("REPORT", shared, sync(token, 100), bobAuth).statusCode()).isIn(403, 404);
  }

  @Test
  void copyMoveResourcesAndCollectionsRespectCollisionsAndSyncHistory() throws Exception {
    String source = create("source", "VEVENT"), destination = create("destination", "VEVENT");
    send("PUT", source + "one.ics", event("one", "Original"));
    assertThat(
            send(
                    "COPY",
                    source + "one.ics",
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + destination + "copy.ics",
                    "Overwrite",
                    "F")
                .statusCode())
        .isEqualTo(201);
    assertThat(
            send(
                    "COPY",
                    source + "one.ics",
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + destination + "other.ics",
                    "Overwrite",
                    "F")
                .statusCode())
        .isEqualTo(403);
    assertThat(
            send(
                    "MOVE",
                    destination + "copy.ics",
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + destination + "moved.ics")
                .statusCode())
        .isEqualTo(201);
    assertThat(send("GET", destination + "copy.ics", "").statusCode()).isEqualTo(404);
    assertThat(
            send(
                    "COPY",
                    source,
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + home + "empty/",
                    "Depth",
                    "0")
                .statusCode())
        .isEqualTo(201);
    assertThat(send("GET", home + "empty/one.ics", "").statusCode()).isEqualTo(404);
    assertThat(
            send(
                    "MOVE",
                    source,
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + home + "renamed/",
                    "Depth",
                    "infinity")
                .statusCode())
        .isEqualTo(201);
    assertThat(send("GET", home + "renamed/one.ics", "").body()).contains("Original");
    assertThat(send("GET", source + "one.ics", "").statusCode()).isEqualTo(404);
  }

  @Test
  void foldersAndRequiredPrincipalReportsHaveActualResponseSemantics() throws Exception {
    assertThat(send("MKCOL", home + "folder/", "").statusCode()).isEqualTo(201);
    String calendar = create("folder/events", "VEVENT");
    var expanded =
        send(
            "REPORT",
            calendar,
            "<D:expand-property xmlns:D='DAV:'><D:property name='owner'><D:property name='displayname'/></D:property></D:expand-property>");
    assertThat(expanded.statusCode()).isEqualTo(207);
    assertThat(expanded.body()).contains("Alice &amp; Calendar", "D:response");
    var search =
        send(
            "REPORT",
            "/principals/",
            "<D:principal-property-search xmlns:D='DAV:'><D:property-search><D:prop><D:displayname/></D:prop><D:match>Alice</D:match></D:property-search><D:prop><D:displayname/></D:prop></D:principal-property-search>");
    assertThat(search.statusCode()).isEqualTo(207);
    assertThat(search.body()).contains("Alice &amp; Calendar").doesNotContain("Bob Tasks");
    var searchable =
        send("REPORT", "/principals/", "<D:principal-search-property-set xmlns:D='DAV:'/>");
    assertThat(searchable.statusCode()).isEqualTo(200);
    assertThat(searchable.body()).contains("principal-search-property");
    var principals =
        send(
            "REPORT",
            calendar,
            "<D:acl-principal-prop-set xmlns:D='DAV:'><D:prop><D:displayname/></D:prop></D:acl-principal-prop-set>");
    assertThat(principals.statusCode()).isEqualTo(207);
    assertThat(principals.body()).contains("Alice &amp; Calendar");
  }

  @Test
  void undatedTasksAreStoredWithoutInventingEventDates() throws Exception {
    String calendar = create("tasks", "VTODO"),
        data =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Independent raw task fixture//EN\r\nBEGIN:VTODO\r\nUID:task-1\r\nDTSTAMP:20261027T000000Z\r\nSUMMARY:Undated task\r\nPERCENT-COMPLETE:50\r\nPRIORITY:2\r\nEND:VTODO\r\nEND:VCALENDAR\r\n";
    assertThat(send("PUT", calendar + "task.ics", data).statusCode()).isEqualTo(201);
    assertThat(send("GET", calendar + "task.ics", "").body()).isEqualTo(data);
    assertThat(send("PUT", calendar + "event.ics", event("wrong", "Event")).statusCode())
        .isEqualTo(403);
    var report =
        send(
            "REPORT",
            calendar,
            query(
                "<C:comp-filter name='VTODO'><C:time-range start='20261027T000000Z' end='20261028T000000Z'/></C:comp-filter>"),
            "Depth",
            "1");
    assertThat(report.statusCode()).isEqualTo(207);
    assertThat(report.body()).contains("task.ics");
  }

  @Test
  void metadataAclAndCollectionCreationHonorHttpPreconditions() throws Exception {
    String calendar = create("conditions", "VEVENT"), path = calendar + "one.ics";
    var created = send("PUT", path, event("condition-uid", "Before"));
    String etag = created.headers().firstValue("ETag").orElseThrow();
    String update =
        "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:test'><D:set><D:prop><X:metadata>accepted</X:metadata></D:prop></D:set></D:propertyupdate>";
    assertThat(send("PROPPATCH", path, update, "If-Match", "\"stale\"").statusCode())
        .isEqualTo(412);
    assertThat(send("PROPFIND", path, "", "Depth", "0").body()).doesNotContain("accepted");
    assertThat(send("PROPPATCH", path, update, "If-Match", etag).statusCode()).isEqualTo(207);
    assertThat(send("ACL", calendar, acl("READ"), "If-None-Match", "*").statusCode())
        .isEqualTo(412);
    assertThat(sendAs("PROPFIND", "/calendars/" + bob + "/", "", bobAuth, "Depth", "1").body())
        .doesNotContain("conditions");
    assertThat(send("MKCOL", home + "absent/", "", "If-Match", "*").statusCode()).isEqualTo(412);
    assertThat(send("GET", home + "absent/", "").statusCode()).isEqualTo(404);
    assertThat(send("MKCALENDAR", home + "new/", "", "If-Match", "*").statusCode()).isEqualTo(412);
  }

  @Test
  void taggedDavConditionsCheckOtherResourcesWithoutAWindowForBlindWrites() throws Exception {
    String calendar = create("tagged", "VEVENT"),
        target = calendar + "target.ics",
        gate = calendar + "gate.ics";
    send("PUT", target, event("target-uid", "Original"));
    String gateTag =
        send("PUT", gate, event("gate-uid", "Gate")).headers().firstValue("ETag").orElseThrow();
    String condition = "<https://calendar.example/team/dav" + gate + "> ([" + gateTag + "])";
    assertThat(send("PUT", target, event("target-uid", "Permitted"), "If", condition).statusCode())
        .isEqualTo(204);
    send("PUT", gate, event("gate-uid", "Changed gate"));
    assertThat(
            send("PUT", target, event("target-uid", "Must not persist"), "If", condition)
                .statusCode())
        .isEqualTo(412);
    assertThat(send("GET", target, "").body())
        .contains("Permitted")
        .doesNotContain("Must not persist");
  }

  @Test
  void hostileInputAndCrossOriginDestinationsFailWithoutPartialWrites() throws Exception {
    String calendar = create("events", "VEVENT");
    assertThat(
            send(
                    "PROPFIND",
                    calendar,
                    "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><D:propfind xmlns:D='DAV:'><D:prop><D:displayname>&e;</D:displayname></D:prop></D:propfind>")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "PROPFIND",
                    calendar,
                    "<D:propfind xmlns:D='DAV:'>"
                        + "<D:a>".repeat(40)
                        + "</D:a>".repeat(40)
                        + "</D:propfind>")
                .statusCode())
        .isEqualTo(400);
    assertThat(send("PUT", calendar + "large.ics", "x".repeat(1048577)).statusCode())
        .isEqualTo(413);
    var chunked =
        request(calendar + "chunked.ics", aliceAuth)
            .PUT(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(new byte[1048577])))
            .build();
    assertThat(http.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(413);
    assertThat(send("PUT", calendar + "bad.ics", "malformed").statusCode()).isEqualTo(403);
    assertThat(
            send("PUT", calendar + "compressed.ics", "data", "Content-Encoding", "gzip")
                .statusCode())
        .isEqualTo(415);
    assertThat(
            send("COPY", calendar, "", "Destination", "https://attacker.invalid/dav/calendars/")
                .statusCode())
        .isEqualTo(403);
    assertThat(send("PROPFIND", calendar + "%2e%2e/", "", "Depth", "0").statusCode())
        .isIn(400, 404);
    assertThat(send("GET", calendar + "bad.ics", "").statusCode()).isEqualTo(404);
  }

  @Test
  void principalMatchDepthZeroSearchesMembersRecursivelyAndKeepsRequestScope() throws Exception {
    assertThat(send("MKCOL", home + "nested/", "").statusCode()).isEqualTo(201);
    String calendar = create("nested/events", "VEVENT");
    assertThat(send("PUT", calendar + "one.ics", event("principal-match", "Member")).statusCode())
        .isEqualTo(201);
    String body =
        "<D:principal-match xmlns:D='DAV:'><D:principal-property><D:owner/></D:principal-property><D:prop><D:displayname/></D:prop></D:principal-match>";
    var matched = send("REPORT", home, body, "Depth", "0");
    assertThat(matched.statusCode()).isEqualTo(207);
    assertThat(principalReportHrefs(matched))
        .contains(
            "https://calendar.example/team/dav" + home + "nested/",
            "https://calendar.example/team/dav" + calendar,
            "https://calendar.example/team/dav" + calendar + "one.ics")
        .doesNotContain("https://calendar.example/team/dav" + home);
    assertThat(send("REPORT", home, body, "Depth", "1").statusCode()).isEqualTo(400);
    assertThat(send("REPORT", home, body, "Depth", "infinity").statusCode()).isEqualTo(400);
    String self =
        "<D:principal-match xmlns:D='DAV:'><D:self/><D:prop><D:displayname/></D:prop></D:principal-match>";
    var principals = send("REPORT", "/principals/", self, "Depth", "0");
    assertThat(principals.statusCode()).isEqualTo(207);
    assertThat(principalReportHrefs(principals))
        .containsExactly("https://calendar.example/team/dav/principals/" + alice + "/");
    assertThat(principalReportHrefs(send("REPORT", home, self, "Depth", "0"))).isEmpty();
  }

  @Test
  void principalSearchAndsAllPropertiesAndUnsearchablePropertiesDoNotMatch() throws Exception {
    String positive =
        "<D:principal-property-search xmlns:D='DAV:'><D:property-search><D:prop><D:displayname/></D:prop><D:match>Alice</D:match></D:property-search><D:prop><D:displayname/></D:prop></D:principal-property-search>";
    assertThat(principalReportHrefs(send("REPORT", "/principals/", positive, "Depth", "0")))
        .containsExactly("https://calendar.example/team/dav/principals/" + alice + "/");
    String mixed =
        "<D:principal-property-search xmlns:D='DAV:' xmlns:X='urn:unindexed'><D:property-search><D:prop><D:displayname/><X:department/></D:prop><D:match>Alice</D:match></D:property-search><D:prop><D:displayname/></D:prop></D:principal-property-search>";
    var unmatched = send("REPORT", "/principals/", mixed, "Depth", "0");
    assertThat(unmatched.statusCode()).isEqualTo(207);
    assertThat(principalReportHrefs(unmatched)).isEmpty();
  }

  @Test
  void effectivePrivilegesAreSpecificToResourceTypeAndCoarseGrantChildrenAreAbstract()
      throws Exception {
    String calendar = create("privileges", "VEVENT");
    assertThat(send("PUT", calendar + "one.ics", event("privileges", "One")).statusCode())
        .isEqualTo(201);
    String current =
        "<D:propfind xmlns:D='DAV:'><D:prop><D:current-user-privilege-set/></D:prop></D:propfind>";
    for (String path : List.of("/", "/principals/", "/principals/" + alice + "/")) {
      var response = send("PROPFIND", path, current, "Depth", "0");
      assertThat(response.statusCode()).isEqualTo(207);
      assertThat(xml(response).getElementsByTagNameNS("DAV:", "write").getLength()).isZero();
      assertThat(xml(response).getElementsByTagNameNS("DAV:", "write-acl").getLength()).isZero();
    }
    var resource = send("PROPFIND", calendar + "one.ics", current, "Depth", "0");
    assertThat(xml(resource).getElementsByTagNameNS("DAV:", "write-content").getLength()).isZero();
    assertThat(xml(resource).getElementsByTagNameNS("DAV:", "write-acl").getLength()).isZero();
    assertThat(xml(resource).getElementsByTagNameNS("DAV:", "bind").getLength()).isZero();
    var collection = send("PROPFIND", calendar, current, "Depth", "0");
    assertThat(xml(collection).getElementsByTagNameNS("DAV:", "write-acl").getLength())
        .isEqualTo(1);
    var supported =
        send(
            "PROPFIND",
            calendar,
            "<D:propfind xmlns:D='DAV:'><D:prop><D:supported-privilege-set/></D:prop></D:propfind>",
            "Depth",
            "0");
    var privileges = xml(supported).getElementsByTagNameNS("DAV:", "supported-privilege");
    boolean checked = false;
    for (int index = 0; index < privileges.getLength(); index++) {
      var privilege = (Element) privileges.item(index);
      if (DavXml.child(DavXml.required(privilege, "DAV:", "privilege"), "DAV:", "write-properties")
          != null) {
        assertThat(DavXml.child(privilege, "DAV:", "abstract")).isNotNull();
        checked = true;
      }
    }
    assertThat(checked).isTrue();
  }

  private static List<String> principalReportHrefs(HttpResponse<String> response) {
    var nodes = xml(response).getElementsByTagNameNS("DAV:", "response");
    return java.util.stream.IntStream.range(0, nodes.getLength())
        .mapToObj(
            index -> DavXml.required((Element) nodes.item(index), "DAV:", "href").getTextContent())
        .toList();
  }

  @Autowired org.busystack.caldav.sharing.GroupService groupDirectory;
  @Autowired org.busystack.caldav.storage.DavStore propertyStore;

  private org.springframework.security.core.Authentication groupAdministrator() {
    var account = accounts.find("group-administrator");
    if (account == null)
      account =
          accounts.find(
              accounts.provision(
                  "group-administrator",
                  "Group administrator",
                  "correct horse battery staple",
                  true));
    return org.springframework.security.authentication.UsernamePasswordAuthenticationToken
        .authenticated(
            account.username(),
            null,
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_ADMIN"),
                org.busystack.caldav.security.session.SessionSecurity.authority(
                    account.securityEpoch()),
                org.springframework.security.core.authority.FactorGrantedAuthority.fromAuthority(
                    org.springframework.security.core.authority.FactorGrantedAuthority
                        .PASSWORD_AUTHORITY),
                org.springframework.security.core.authority.FactorGrantedAuthority.fromAuthority(
                    org.springframework.security.core.authority.FactorGrantedAuthority
                        .WEBAUTHN_AUTHORITY)));
  }

  @Test
  void groupAcesUseStandardPrincipalUrlsAndOneSharedCalendarAuthority() throws Exception {
    String calendar = create("group-events", "VEVENT");
    var administrator = groupAdministrator();
    UUID group = groupDirectory.create(administrator, "Calendar editors", java.util.Set.of("bob"));
    String groupHref = "https://calendar.example/team/dav/principals/" + group + "/";
    String acl =
        "<D:acl xmlns:D='DAV:'><D:ace><D:principal><D:href>"
            + groupHref
            + "</D:href></D:principal><D:grant><D:privilege><D:read/></D:privilege><D:privilege><D:write/></D:privilege></D:grant></D:ace></D:acl>";
    assertThat(send("ACL", calendar, acl).statusCode()).isEqualTo(200);
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='group-events'",
            UUID.class,
            alice);
    String shared = "/calendars/" + bob + "/" + id + "/";
    assertThat(
            sendAs(
                    "PUT",
                    shared + "shared.ics",
                    event("group-shared", "Written by member"),
                    bobAuth)
                .statusCode())
        .isEqualTo(201);
    assertThat(send("GET", calendar + "shared.ics", "").body()).contains("Written by member");
    var principal =
        sendAs(
            "PROPFIND",
            "/principals/" + group + "/",
            "<D:propfind xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav'><D:prop><D:displayname/><D:group-member-set/><C:calendar-home-set/></D:prop></D:propfind>",
            bobAuth,
            "Depth",
            "0");
    assertThat(principal.statusCode()).isEqualTo(207);
    assertThat(principal.body())
        .contains("Calendar editors", "/principals/" + bob + "/", "404 Not Found");
    var membership =
        sendAs(
            "PROPFIND",
            "/principals/" + bob + "/",
            "<D:propfind xmlns:D='DAV:'><D:prop><D:group-membership/></D:prop></D:propfind>",
            bobAuth,
            "Depth",
            "0");
    assertThat(membership.body()).contains(groupHref);
    String self =
        "<D:principal-match xmlns:D='DAV:'><D:self/><D:prop><D:displayname/></D:prop></D:principal-match>";
    assertThat(
            principalReportHrefs(
                sendAs("REPORT", "/principals/" + group + "/", self, bobAuth, "Depth", "0")))
        .containsExactly(groupHref);
    assertThat(principalReportHrefs(sendAs("REPORT", "/principals/", self, bobAuth, "Depth", "0")))
        .contains(groupHref, "https://calendar.example/team/dav/principals/" + bob + "/");
    var aclPrincipals =
        send(
            "REPORT",
            calendar,
            "<D:acl-principal-prop-set xmlns:D='DAV:'><D:prop><D:displayname/></D:prop></D:acl-principal-prop-set>");
    assertThat(principalReportHrefs(aclPrincipals)).contains(groupHref);
    groupDirectory.update(administrator, group, "Calendar editors", java.util.Set.of());
    assertThat(sendAs("GET", shared + "shared.ics", "", bobAuth).statusCode()).isIn(403, 404);
    assertThat(send("GET", calendar + "shared.ics", "").body()).contains("Written by member");
  }

  @Test
  void aclPrincipalReportsRequireReadAclAndReturnActualGroupOwner() throws Exception {
    String calendar = create("acl-principal-permissions", "VEVENT");
    var administrator = groupAdministrator();
    UUID group =
        groupDirectory.create(administrator, "Restricted directory", java.util.Set.of("bob"));
    String report =
        "<D:acl-principal-prop-set xmlns:D='DAV:'><D:prop><D:displayname/></D:prop></D:acl-principal-prop-set>";
    var deniedGroupReport = sendAs("REPORT", "/principals/" + group + "/", report, bobAuth);
    assertThat(deniedGroupReport.statusCode()).isEqualTo(403);
    var need = DavXml.required(xml(deniedGroupReport), "DAV:", "need-privileges");
    var failing = DavXml.required(need, "DAV:", "resource");
    assertThat(DavXml.required(failing, "DAV:", "href").getTextContent())
        .isEqualTo("https://calendar.example/team/dav/principals/" + group + "/");
    assertThat(DavXml.child(DavXml.required(failing, "DAV:", "privilege"), "DAV:", "read-acl"))
        .isNotNull();
    assertThat(send("ACL", calendar, acl("READ")).statusCode()).isEqualTo(200);
    assertThat(send("REPORT", "/principals/" + bob + "/", report).statusCode()).isEqualTo(403);
    UUID calendarId =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='acl-principal-permissions'",
            UUID.class,
            alice);
    String shared = "/calendars/" + bob + "/" + calendarId + "/";
    var deniedWrite =
        sendAs("PUT", shared + "not-writable.ics", event("not-writable", "Denied"), bobAuth);
    assertThat(deniedWrite.statusCode()).isEqualTo(403);
    var writeFailure =
        DavXml.required(
            DavXml.required(xml(deniedWrite), "DAV:", "need-privileges"), "DAV:", "resource");
    assertThat(DavXml.required(writeFailure, "DAV:", "href").getTextContent())
        .isEqualTo("https://calendar.example/team/dav" + shared);
    assertThat(DavXml.child(DavXml.required(writeFailure, "DAV:", "privilege"), "DAV:", "write"))
        .isNotNull();
    var deniedOwnership = sendAs("DELETE", shared, "", bobAuth);
    assertThat(deniedOwnership.statusCode()).isEqualTo(403);
    var ownerFailure =
        DavXml.required(
            DavXml.required(xml(deniedOwnership), "DAV:", "need-privileges"), "DAV:", "resource");
    assertThat(
            DavXml.child(DavXml.required(ownerFailure, "DAV:", "privilege"), "DAV:", "write-acl"))
        .isNotNull();
    UUID administratorId = accounts.find(administrator.getName()).id();
    String administratorAuth =
        basic(
            administrator.getName(),
            accounts
                .issueDavCredential(
                    administratorId, "group owner report", Instant.now().plusSeconds(3600))
                .secret());
    var ownerReport = sendAs("REPORT", "/principals/" + group + "/", report, administratorAuth);
    assertThat(ownerReport.statusCode()).isEqualTo(207);
    assertThat(principalReportHrefs(ownerReport))
        .containsExactly("https://calendar.example/team/dav/principals/" + administratorId + "/");
    assertThat(principalReportHrefs(sendAs("REPORT", "/principals/" + bob + "/", report, bobAuth)))
        .containsExactly("https://calendar.example/team/dav/principals/" + bob + "/");
  }

  @Test
  void partialGroupWritePrivilegeCannotEscalateReadOnlySharing() throws Exception {
    String calendar = create("group-read-only", "VEVENT");
    UUID group = groupDirectory.create(groupAdministrator(), "Readers", java.util.Set.of("bob"));
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='group-read-only'",
            UUID.class,
            alice);
    String principal =
        "<D:principal><D:href>https://calendar.example/team/dav/principals/"
            + group
            + "/</D:href></D:principal>";
    String read =
        "<D:acl xmlns:D='DAV:'><D:ace>"
            + principal
            + "<D:grant><D:privilege><D:read/></D:privilege></D:grant></D:ace></D:acl>";
    assertThat(send("ACL", calendar, read).statusCode()).isEqualTo(200);
    String partial =
        "<D:acl xmlns:D='DAV:'><D:ace>"
            + principal
            + "<D:grant><D:privilege><D:read/></D:privilege><D:privilege><D:write-properties/></D:privilege></D:grant></D:ace></D:acl>";
    assertThat(send("ACL", calendar, partial).statusCode()).isEqualTo(403);
    assertThat(
            sendAs(
                    "PUT",
                    "/calendars/" + bob + "/" + id + "/forbidden.ics",
                    event("forbidden", "Forbidden"),
                    bobAuth)
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void displayNamePreservesXmlLanguageAndResourceMetadataDoesNotRenameOrRewriteContent()
      throws Exception {
    String calendar = home + "language/";
    assertThat(
            send(
                    "MKCALENDAR",
                    calendar,
                    "<C:mkcalendar xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:set><D:prop><D:displayname xml:lang='fr'>Vacances</D:displayname></D:prop></D:set></C:mkcalendar>")
                .statusCode())
        .isEqualTo(201);
    String prop = "<D:propfind xmlns:D='DAV:'><D:prop><D:displayname/></D:prop></D:propfind>";
    var name =
        (Element)
            xml(send("PROPFIND", calendar, prop, "Depth", "0"))
                .getElementsByTagNameNS("DAV:", "displayname")
                .item(0);
    assertThat(name.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")).isEqualTo("fr");
    String content = event("language-event", "Original content");
    var put = send("PUT", calendar + "original.ics", content);
    String etag = put.headers().firstValue("ETag").orElseThrow();
    String patch =
        "<D:propertyupdate xmlns:D='DAV:'><D:set><D:prop><D:displayname xml:lang='ja'>予定</D:displayname></D:prop></D:set></D:propertyupdate>";
    assertThat(send("PROPPATCH", calendar + "original.ics", patch).statusCode()).isEqualTo(207);
    var resourceName =
        (Element)
            xml(send("PROPFIND", calendar + "original.ics", prop, "Depth", "0"))
                .getElementsByTagNameNS("DAV:", "displayname")
                .item(0);
    assertThat(resourceName.getTextContent()).isEqualTo("予定");
    assertThat(resourceName.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
        .isEqualTo("ja");
    var get = send("GET", calendar + "original.ics", "");
    assertThat(get.body()).isEqualTo(content);
    assertThat(get.headers().firstValue("ETag").orElseThrow()).isEqualTo(etag);
    assertThat(
            send(
                    "PROPPATCH",
                    calendar + "original.ics",
                    "<D:propertyupdate xmlns:D='DAV:'><D:remove><D:prop><D:displayname/></D:prop></D:remove></D:propertyupdate>")
                .statusCode())
        .isEqualTo(207);
    assertThat(
            text(
                send("PROPFIND", calendar + "original.ics", prop, "Depth", "0"),
                "DAV:",
                "displayname"))
        .isEqualTo("original.ics");
    propertyStore.renameDisplayName(alice, alice, "language", "Renamed");
    var renamed =
        (Element)
            xml(send("PROPFIND", calendar, prop, "Depth", "0"))
                .getElementsByTagNameNS("DAV:", "displayname")
                .item(0);
    assertThat(renamed.getTextContent()).isEqualTo("Renamed");
    assertThat(renamed.hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")).isFalse();
  }

  @Test
  void collectionGetAndHeadEvaluatePreconditionsWithoutInventingValidators() throws Exception {
    String calendar = create("collection-conditions", "VEVENT");
    for (String collection : List.of(home, calendar)) {
      for (String method : List.of("GET", "HEAD")) {
        assertThat(send(method, collection, "", "If-Match", "*").statusCode()).isEqualTo(200);
        var none = send(method, collection, "", "If-None-Match", "*");
        assertThat(none.statusCode()).isEqualTo(304);
        assertThat(none.body()).isEmpty();
        assertThat(none.headers().firstValue("ETag")).isEmpty();
        assertThat(send(method, collection, "", "If-Match", "\"unknown\"").statusCode())
            .isEqualTo(412);
        assertThat(send(method, collection, "", "If-None-Match", "W/\"unknown\"").statusCode())
            .isEqualTo(200);
      }
      assertThat(send("GET", collection, "", "If-None-Match", "malformed").statusCode())
          .isEqualTo(400);
    }
    var redirect =
        send("GET", calendar.substring(0, calendar.length() - 1), "", "If-None-Match", "*");
    assertThat(redirect.statusCode()).isEqualTo(308);
    assertThat(redirect.headers().firstValue("Location"))
        .contains("https://calendar.example/team/dav" + calendar);
    assertThat(sendAs("GET", calendar, "", bobAuth, "If-None-Match", "*").statusCode())
        .isEqualTo(403);
  }

  @Test
  void freeBusyReportsAreCollectionOnlyAndConcealAnUnreadableRequestTarget() throws Exception {
    String calendar = create("freebusy-contract", "VEVENT");
    assertThat(
            send("PUT", calendar + "event.ics", event("freebusy-contract", "Visible")).statusCode())
        .isEqualTo(201);
    String report =
        "<C:free-busy-query xmlns:C='urn:ietf:params:xml:ns:caldav'><C:time-range start='20261027T000000Z' end='20261028T000000Z'/></C:free-busy-query>";
    assertThat(send("REPORT", calendar, report, "Depth", "1").statusCode()).isEqualTo(200);
    assertThat(send("REPORT", calendar + "event.ics", report).statusCode()).isEqualTo(403);
    var supported =
        send(
            "PROPFIND",
            calendar + "event.ics",
            "<D:propfind xmlns:D='DAV:'><D:prop><D:supported-report-set/></D:prop></D:propfind>",
            "Depth",
            "0");
    assertThat(supported.statusCode()).isEqualTo(207);
    assertThat(supported.body())
        .contains("calendar-query", "calendar-multiget")
        .doesNotContain("free-busy-query");
    assertThat(sendAs("REPORT", calendar, report, bobAuth, "Depth", "1").statusCode())
        .isEqualTo(404);
    assertThat(sendAs("REPORT", calendar, query(""), bobAuth, "Depth", "1").statusCode())
        .isEqualTo(403);
  }

  @Test
  void calendarValidationPreconditionsAreDistinctAndRejectedWritesPreserveBothCollections()
      throws Exception {
    String events = create("validation-events", "VEVENT"),
        tasks = create("validation-tasks", "VTODO");
    String original = event("validation-master", "Original"), resource = events + "original.ics";
    var created = send("PUT", resource, original);
    assertThat(created.statusCode()).isEqualTo(201);
    String originalEtag = created.headers().firstValue("ETag").orElseThrow();
    String task =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Independent validation fixture//EN\r\nBEGIN:VTODO\r\nUID:task-validation\r\nDTSTAMP:20261027T000000Z\r\nSUMMARY:Valid undated task\r\nEND:VTODO\r\nEND:VCALENDAR\r\n";
    assertThat(send("PUT", tasks + "task.ics", task).statusCode()).isEqualTo(201);
    String eventToken = text(send("REPORT", events, sync("", 100)), DavXml.DAV, "sync-token");
    String taskToken = text(send("REPORT", tasks, sync("", 100)), DavXml.DAV, "sync-token");
    String method = original.replace("VERSION:2.0\r\n", "VERSION:2.0\r\nMETHOD:PUBLISH\r\n");
    String mixed =
        original.replace(
            "END:VCALENDAR\r\n",
            "BEGIN:VTODO\r\nUID:validation-master\r\nDTSTAMP:20261027T000000Z\r\nEND:VTODO\r\nEND:VCALENDAR\r\n");
    String mixedUid =
        original.replace(
            "END:VCALENDAR\r\n",
            "BEGIN:VEVENT\r\nUID:different-uid\r\nDTSTAMP:20261027T000000Z\r\nRECURRENCE-ID:20261028T100000Z\r\nDTSTART:20261028T100000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
    String missingZone =
        original
            .replace("DTSTART:20261027T100000Z", "DTSTART;TZID=America/New_York:20261027T100000")
            .replace("DTEND:20261027T110000Z", "DTEND;TZID=America/New_York:20261027T110000");
    for (String invalid : List.of(method, mixed, mixedUid, missingZone)) {
      var rejected = send("PUT", resource, invalid, "If-Match", originalEtag);
      assertThat(rejected.statusCode()).isEqualTo(403);
      assertThat(DavXml.child(xml(rejected), DavXml.CAL, "valid-calendar-object-resource"))
          .isNotNull();
    }
    for (String invalid : List.of("not iCalendar", "")) {
      var malformed = send("PUT", resource, invalid, "If-Match", originalEtag);
      assertThat(malformed.statusCode()).isEqualTo(403);
      assertThat(DavXml.child(xml(malformed), DavXml.CAL, "valid-calendar-data")).isNotNull();
    }
    var unsupported = send("PUT", resource, task, "If-Match", originalEtag);
    assertThat(unsupported.statusCode()).isEqualTo(403);
    assertThat(DavXml.child(xml(unsupported), DavXml.CAL, "supported-calendar-component"))
        .isNotNull();
    for (String operation : List.of("COPY", "MOVE")) {
      var rejected =
          send(
              operation,
              tasks + "task.ics",
              "",
              "Destination",
              "https://calendar.example/team/dav" + events + "task.ics");
      assertThat(rejected.statusCode()).isEqualTo(403);
      assertThat(DavXml.child(xml(rejected), DavXml.CAL, "supported-calendar-component"))
          .isNotNull();
      assertThat(send("GET", tasks + "task.ics", "").body()).isEqualTo(task);
      assertThat(send("GET", events + "task.ics", "").statusCode()).isEqualTo(404);
    }
    var current = send("GET", resource, "");
    assertThat(current.body()).isEqualTo(original);
    assertThat(current.headers().firstValue("ETag")).contains(originalEtag);
    assertThat(text(send("REPORT", events, sync("", 100)), DavXml.DAV, "sync-token"))
        .isEqualTo(eventToken);
    assertThat(text(send("REPORT", tasks, sync("", 100)), DavXml.DAV, "sync-token"))
        .isEqualTo(taskToken);
    String zoned =
        event("embedded-zone", "Embedded zone")
            .replace(
                "BEGIN:VEVENT\r\n",
                "BEGIN:VTIMEZONE\r\nTZID:Custom/Zone\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0200\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\n")
            .replace("DTSTART:20261027T100000Z", "DTSTART;TZID=Custom/Zone:20261027T100000")
            .replace("DTEND:20261027T110000Z", "DTEND;TZID=Custom/Zone:20261027T110000");
    assertThat(send("PUT", events + "embedded-zone.ics", zoned).statusCode()).isEqualTo(201);
    assertThat(send("GET", events + "embedded-zone.ics", "").body()).isEqualTo(zoned);
  }

  private String currentCollectionToken(String collection) throws Exception {
    var response =
        send(
            "PROPFIND",
            collection,
            "<D:propfind xmlns:D='DAV:'><D:prop><D:sync-token/></D:prop></D:propfind>",
            "Depth",
            "0");
    assertThat(response.statusCode()).isEqualTo(207);
    return text(response, DavXml.DAV, "sync-token");
  }

  @Test
  void residualCalendarGrammarFailuresRejectCreationAndConditionalReplacementAtomically()
      throws Exception {
    for (String name :
        List.of(
            "invalid-leading-token",
            "invalid-unicode-leading-token",
            "invalid-unicode-bom-leading-token",
            "invalid-zero-byday",
            "invalid-plus-zero-byday",
            "invalid-minus-zero-byday",
            "invalid-weeks-days")) {
      String source =
          name.equals("invalid-minus-zero-byday")
              ? "invalid-zero-byday"
              : name.contains("unicode") ? "invalid-leading-token" : name;
      String invalid = independentAdmissionFixture(source);
      if (name.equals("invalid-minus-zero-byday"))
        invalid = invalid.replace("BYDAY=0MO", "BYDAY=-0MO");
      if (name.contains("unicode"))
        invalid =
            invalid.replace(
                "XBEGIN:VCALENDAR",
                (name.contains("-bom-") ? "\uFEFF" : "") + "beg\u0131n:vcalendar");
      String uid =
          invalid
              .lines()
              .filter(line -> line.startsWith("UID:"))
              .findFirst()
              .orElseThrow()
              .substring(4);
      String calendar = create(name, "VEVENT"), resource = calendar + "resource.ics";
      UUID collection = datedCollection(name);
      var empty = admissionHistoryState(collection);
      String emptyToken = currentCollectionToken(calendar);
      var creation =
          send("PUT", resource, invalid, "Content-Type", "text/calendar", "If-None-Match", "*");
      assertThat(creation.statusCode()).as(name + " creation").isEqualTo(403);
      assertThat(DavXml.child(xml(creation), DavXml.CAL, "valid-calendar-data")).isNotNull();
      assertThat(send("GET", resource, "").statusCode()).isEqualTo(404);
      assertThat(admissionHistoryState(collection)).isEqualTo(empty);
      assertThat(empty.get("resources")).isEqualTo(0L);
      assertThat(empty.get("versions")).isEqualTo(0L);
      assertThat(empty.get("changes")).isEqualTo(0L);
      assertThat(currentCollectionToken(calendar)).isEqualTo(emptyToken);

      String previous = event(uid, "Preserved conditional-write control");
      assertThat(send("PUT", resource, previous, "If-None-Match", "*").statusCode()).isEqualTo(201);
      var before =
          http.send(
              request(resource, aliceAuth).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      assertThat(before.statusCode()).isEqualTo(200);
      assertThat(before.body()).isEqualTo(previous.getBytes(StandardCharsets.UTF_8));
      String etag = before.headers().firstValue("ETag").orElseThrow();
      var state = admissionHistoryState(collection);
      String token = currentCollectionToken(calendar);
      var replacement =
          send("PUT", resource, invalid, "Content-Type", "text/calendar", "If-Match", etag);
      assertThat(replacement.statusCode()).as(name + " conditional replacement").isEqualTo(403);
      assertThat(DavXml.child(xml(replacement), DavXml.CAL, "valid-calendar-data")).isNotNull();
      var after =
          http.send(
              request(resource, aliceAuth).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      assertThat(after.statusCode()).isEqualTo(200);
      assertThat(after.body()).isEqualTo(before.body());
      assertThat(after.headers().firstValue("ETag")).contains(etag);
      assertThat(admissionHistoryState(collection)).isEqualTo(state);
      assertThat(currentCollectionToken(calendar)).isEqualTo(token);
      var incremental = send("REPORT", calendar, sync(token, 100), "Depth", "0");
      assertThat(incremental.statusCode()).isEqualTo(207);
      assertThat(xml(incremental).getElementsByTagNameNS(DavXml.DAV, "response").getLength())
          .isZero();
    }
  }

  @Test
  void residualCalendarLegalNeighborsAndInitialCaseBomVariantsKeepExactGetBytes() throws Exception {
    String calendar = create("lexical-legal-neighbors", "VEVENT");
    for (String name :
        List.of("valid-first-weekday", "valid-last-weekday", "valid-one-week", "valid-nine-days")) {
      String resource = calendar + name + ".ics", body = independentAdmissionFixture(name);
      assertThat(send("PUT", resource, body, "If-None-Match", "*").statusCode()).isEqualTo(201);
      var get =
          http.send(
              request(resource, aliceAuth).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      assertThat(get.statusCode()).isEqualTo(200);
      assertThat(get.body()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }
    int ordinal = 0;
    for (String boundary :
        List.of(
            "BEGIN:VCALENDAR",
            "begin:vcalendar",
            "bEgIn:vCaLeNdAr",
            "\uFEFFBEGIN:VCALENDAR",
            "\uFEFFbEgIn:vCaLeNdAr")) {
      String resource = calendar + "boundary-" + ordinal + ".ics";
      String body =
          event("boundary-" + ordinal++, "CaseSensitive TEXT and BOM control")
              .replaceFirst("BEGIN:VCALENDAR", boundary);
      assertThat(send("PUT", resource, body, "If-None-Match", "*").statusCode()).isEqualTo(201);
      var get =
          http.send(
              request(resource, aliceAuth).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      assertThat(get.statusCode()).isEqualTo(200);
      assertThat(get.body()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String independentAdmissionFixture(String name) throws Exception {
    try (var input =
        DavHttpIT.class.getResourceAsStream(
            "/calendar/independent-verification/" + name + ".ics")) {
      if (input == null) throw new IllegalStateException("Missing independent admission fixture");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private java.util.Map<String, Object> admissionHistoryState(UUID collection) {
    return jdbc.queryForMap(
        "SELECT c.revision, (SELECT count(*) FROM calendar_resources r WHERE r.collection_id=c.id) AS resources,"
            + " (SELECT coalesce(max(r.revision),0) FROM calendar_resources r WHERE r.collection_id=c.id) AS resource_revision,"
            + " (SELECT count(*) FROM calendar_resource_versions v WHERE v.collection_id=c.id) AS versions,"
            + " (SELECT count(*) FROM collection_changes h WHERE h.collection_id=c.id) AS changes"
            + " FROM collections c WHERE c.id=?",
        collection);
  }

  @Test
  void syncStateTokensWorkInTaggedAndUntaggedDavIfWithNegationAndCollectionScoping()
      throws Exception {
    String calendar = create("sync-state", "VEVENT"), other = create("sync-state-other", "VEVENT");
    String href = "https://calendar.example/team/dav" + calendar;
    String initial = currentCollectionToken(calendar);
    assertThat(send("GET", calendar, "", "If", "(<" + initial + ">)").statusCode()).isEqualTo(200);
    String first = calendar + "first.ics";
    assertThat(
            send(
                    "PUT",
                    first,
                    event("sync-first", "First"),
                    "If",
                    "<" + href + "> (<" + initial + ">)")
                .statusCode())
        .isEqualTo(201);
    assertThat(
            send(
                    "PUT",
                    calendar + "stale.ics",
                    event("sync-stale", "Denied"),
                    "If",
                    "<" + href + "> (<" + initial + ">)")
                .statusCode())
        .isEqualTo(412);
    String current = currentCollectionToken(calendar);
    assertThat(current).isNotEqualTo(initial);
    assertThat(
            send(
                    "PUT",
                    calendar + "negated.ics",
                    event("sync-negated", "Negated"),
                    "If",
                    "<" + href + "> (Not <" + initial + ">)")
                .statusCode())
        .isEqualTo(201);
    current = currentCollectionToken(calendar);
    assertThat(
            send(
                    "PUT",
                    calendar + "denied.ics",
                    event("sync-denied", "Denied"),
                    "If",
                    "<" + href + "> (Not <" + current + ">)")
                .statusCode())
        .isEqualTo(412);
    assertThat(
            send(
                    "PUT",
                    calendar + "wrong-collection.ics",
                    event("sync-wrong", "Denied"),
                    "If",
                    "<" + href + "> (<" + currentCollectionToken(other) + ">)")
                .statusCode())
        .isEqualTo(412);
    String firstEtag = send("GET", first, "").headers().firstValue("ETag").orElseThrow();
    String mixed =
        "<"
            + href
            + "> (<"
            + current
            + ">) ([\"wrong\"]) <https://calendar.example/team/dav"
            + first
            + "> (["
            + firstEtag
            + "] Not <urn:uuid:unknown-lock>)";
    assertThat(send("GET", first, "", "If", mixed, "If-None-Match", firstEtag).statusCode())
        .isEqualTo(304);
    assertThat(send("PUT", first, event("sync-first", "Changed"), "If", mixed).statusCode())
        .isEqualTo(204);
    assertThat(send("GET", calendar + "stale.ics", "").statusCode()).isEqualTo(404);
    assertThat(send("GET", calendar + "denied.ics", "").statusCode()).isEqualTo(404);
  }

  @Test
  void syncStateConditionWaitsForAnInFlightMutationBeforeEvaluating() throws Exception {
    String calendar = create("sync-state-concurrent", "VEVENT");
    UUID collection =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='sync-state-concurrent'",
            UUID.class,
            alice);
    String before = currentCollectionToken(calendar);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    var holderPid = new java.util.concurrent.atomic.AtomicInteger();
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var writer =
          workers.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
                            holderPid.set(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            locked.countDown();
                            try {
                              if (!finish.await(15, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("Sync condition coordination timed out");
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(interrupted);
                            }
                            propertyStore.putResource(
                                alice,
                                collection,
                                "committed.ics",
                                event("sync-committed", "Committed first")
                                    .getBytes(StandardCharsets.UTF_8),
                                org.busystack.caldav.storage.WriteCondition.create());
                          }));
      try {
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var conditional =
            workers.submit(
                () ->
                    send(
                        "PUT",
                        calendar + "conditional.ics",
                        event("sync-conditional", "Must fail"),
                        "If",
                        "<https://calendar.example/team/dav" + calendar + "> (<" + before + ">)"));
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
        writer.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(conditional.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode())
            .isEqualTo(412);
      } finally {
        finish.countDown();
      }
    }
    assertThat(send("GET", calendar + "committed.ics", "").statusCode()).isEqualTo(200);
    assertThat(send("GET", calendar + "conditional.ics", "").statusCode()).isEqualTo(404);
    var delta = send("REPORT", calendar, sync(before, 100));
    assertThat(delta.statusCode()).isEqualTo(207);
    assertThat(delta.body()).contains("committed.ics").doesNotContain("conditional.ics");
  }

  @Test
  void syncTokenOnlyPropertiesAndConditionsDoNotLoadCollectionBodies() throws Exception {
    String calendar = create("metadata-token", "VEVENT");
    UUID collection =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='metadata-token'",
            UUID.class,
            alice);
    // Deliberately oversized database-only bodies isolate the metadata/report budget distinction.
    jdbc.update(
        "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) SELECT ?,value::text||'.ics',value::text,decode(repeat('41',1048576),'hex'),'\"budget-fixture\"',value FROM generate_series(1,33) value",
        collection);
    jdbc.update("UPDATE collections SET revision=33 WHERE id=?", collection);
    String token = currentCollectionToken(calendar);
    var metadata = send("REPORT", calendar, sync("", 100));
    assertThat(metadata.statusCode()).isEqualTo(207);
    assertThat(xml(metadata).getElementsByTagNameNS("DAV:", "getetag").getLength()).isEqualTo(33);
    assertThat(
            send(
                    "PUT",
                    calendar + "small.ics",
                    event("metadata-small", "Small"),
                    "If",
                    "<https://calendar.example/team/dav" + calendar + "> (<" + token + ">)")
                .statusCode())
        .isEqualTo(201);
    assertThat(currentCollectionToken(calendar)).isNotEqualTo(token);
  }

  @Test
  void resourceMultigetCardinalityAndUnsignedSyncLimitsHaveDistinctContracts() throws Exception {
    String calendar = create("report-contracts", "VEVENT"), resource = calendar + "one.ics";
    assertThat(send("PUT", resource, event("report-one", "One")).statusCode()).isEqualTo(201);
    String href = "<D:href>https://calendar.example/team/dav" + resource + "</D:href>";
    String prefix =
        "<C:calendar-multiget xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav'><D:prop><D:getetag/></D:prop>";
    assertThat(
            send("REPORT", resource, prefix + href + "</C:calendar-multiget>", "Depth", "infinity")
                .statusCode())
        .isEqualTo(207);
    assertThat(
            send("REPORT", resource, prefix + href + href + "</C:calendar-multiget>").statusCode())
        .isEqualTo(400);
    for (String invalid : List.of("-1", "+1", " 1", "1.0"))
      assertThat(
              send(
                      "REPORT",
                      calendar,
                      sync("", 1)
                          .replace(
                              "<D:nresults>1</D:nresults>",
                              "<D:nresults>" + invalid + "</D:nresults>"))
                  .statusCode())
          .isEqualTo(400);
    var zero = send("REPORT", calendar, sync("", 0));
    assertThat(zero.statusCode()).isEqualTo(507);
    assertThat(zero.body()).doesNotContain("sync-token");
    for (String ceiling : List.of("10001", "9".repeat(1000))) {
      var accepted =
          send(
              "REPORT",
              calendar,
              sync("", 1)
                  .replace(
                      "<D:nresults>1</D:nresults>", "<D:nresults>" + ceiling + "</D:nresults>"));
      assertThat(accepted.statusCode())
          .as("The client ceiling may exceed the server page size")
          .isEqualTo(207);
      var result = xml(accepted);
      assertThat(result.getElementsByTagNameNS("DAV:", "response").getLength()).isEqualTo(1);
      assertThat(result.getElementsByTagNameNS("DAV:", "getetag").getLength()).isEqualTo(1);
      assertThat(result.getElementsByTagNameNS("DAV:", "sync-token").getLength()).isEqualTo(1);
      assertThat(accepted.body()).contains("one.ics").doesNotContain(" 507 ");
    }
    assertThat(
            send(
                    "REPORT",
                    calendar,
                    sync("", 1)
                        .replace("<D:nresults>1</D:nresults>", "<D:nresults>00001</D:nresults>"))
                .statusCode())
        .isEqualTo(207);
  }

  @Test
  void multigetBoundsRepeatedResourceResolutionBeforeBuildingAnUnboundedResponse()
      throws Exception {
    String calendar = create("multiget-budget", "VEVENT"), resource = calendar + "large.ics";
    String content =
        event("large-report", "Large report")
            .replace("END:VEVENT\r\n", "X-BULK:" + "a".repeat(1047000) + "\r\nEND:VEVENT\r\n");
    assertThat(send("PUT", resource, content).statusCode()).isEqualTo(201);
    String href = "<D:href>https://calendar.example/team/dav" + resource + "</D:href>";
    String report =
        "<C:calendar-multiget xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav'><D:prop><D:getetag/></D:prop>"
            + href.repeat(34)
            + "</C:calendar-multiget>";
    var metadata = send("REPORT", calendar, report);
    assertThat(metadata.statusCode()).isEqualTo(207);
    assertThat(xml(metadata).getElementsByTagNameNS("DAV:", "getetag").getLength()).isEqualTo(34);
    assertThat(xml(metadata).getElementsByTagNameNS("DAV:", "response").getLength()).isEqualTo(34);
    var limited =
        send("REPORT", calendar, report.replace("<D:getetag/>", "<D:getetag/><C:calendar-data/>"));
    assertThat(limited.statusCode()).isEqualTo(507);
    assertThat(limited.body())
        .contains("number-of-matches-within-limits")
        .doesNotContain("multistatus", "calendar-data");
    assertThat(send("GET", resource, "").body()).isEqualTo(content);
  }

  private UUID datedCollection(String display) {
    return jdbc.queryForObject(
        "SELECT id FROM collections WHERE owner_id=? AND name=?", UUID.class, alice, display);
  }

  private void setModificationDate(UUID collection, String name, Instant modifiedAt) {
    jdbc.update(
        "UPDATE calendar_resources SET updated_at=? WHERE collection_id=? AND name=?",
        java.sql.Timestamp.from(modifiedAt),
        collection,
        name);
  }

  @Test
  void resourceModificationDatesRoundTripWithWholeSecondHttpPrecision() throws Exception {
    String calendar = create("http-dates", "VEVENT"), resource = calendar + "event.ics";
    String content = event("http-dates", "Unchanged representation");
    assertThat(send("PUT", resource, content).statusCode()).isEqualTo(201);
    Instant modified = Instant.parse("2020-01-01T12:00:00.987654Z");
    setModificationDate(datedCollection("http-dates"), "event.ics", modified);
    String date = HttpDateValues.format(modified);
    var get = send("GET", resource, "");
    String etag = get.headers().firstValue("ETag").orElseThrow();
    assertThat(get.statusCode()).isEqualTo(200);
    assertThat(get.body()).isEqualTo(content);
    assertThat(get.headers().firstValue("Last-Modified")).contains(date);
    assertThat(get.headers().firstValue("Date")).isPresent();
    var head = send("HEAD", resource, "");
    assertThat(head.statusCode()).isEqualTo(200);
    assertThat(head.body()).isEmpty();
    assertThat(head.headers().firstValue("Last-Modified")).contains(date);
    String properties =
        "<D:propfind xmlns:D='DAV:'><D:prop><D:getlastmodified/></D:prop></D:propfind>";
    assertThat(
            text(send("PROPFIND", resource, properties, "Depth", "0"), "DAV:", "getlastmodified"))
        .isEqualTo(date);
    for (String method : List.of("GET", "HEAD")) {
      var cached = send(method, resource, "", "If-Modified-Since", date);
      assertThat(cached.statusCode()).isEqualTo(304);
      assertThat(cached.body()).isEmpty();
      assertThat(cached.headers().firstValue("Last-Modified")).contains(date);
      assertThat(cached.headers().firstValue("ETag")).contains(etag);
      assertThat(
              send(
                      method,
                      resource,
                      "",
                      "If-Modified-Since",
                      HttpDateValues.format(modified.minusSeconds(1)))
                  .statusCode())
          .isEqualTo(200);
      assertThat(
              send(
                      method,
                      resource,
                      "",
                      "If-Unmodified-Since",
                      HttpDateValues.format(modified.minusSeconds(1)),
                      "If-None-Match",
                      etag)
                  .statusCode())
          .isEqualTo(412);
      assertThat(
              send(
                      method,
                      resource,
                      "",
                      "If-Match",
                      etag,
                      "If-Unmodified-Since",
                      HttpDateValues.format(modified.minusSeconds(1)))
                  .statusCode())
          .isEqualTo(200);
      assertThat(
              send(
                      method,
                      resource,
                      "",
                      "If-None-Match",
                      "\"different\"",
                      "If-Modified-Since",
                      date)
                  .statusCode())
          .isEqualTo(200);
    }
    assertThat(send("PUT", resource, content, "If-Unmodified-Since", date).statusCode())
        .isEqualTo(204);
    String patch =
        "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:client'><D:set><D:prop><X:color>blue</X:color></D:prop></D:set></D:propertyupdate>";
    assertThat(send("PROPPATCH", resource, patch, "If-Unmodified-Since", date).statusCode())
        .isEqualTo(207);
    assertThat(send("GET", resource, "").headers().firstValue("Last-Modified")).contains(date);
    assertThat(send("GET", resource, "").body()).isEqualTo(content);
    var protectedDate =
        send(
            "PROPPATCH",
            resource,
            "<D:propertyupdate xmlns:D='DAV:'><D:set><D:prop><D:getlastmodified>"
                + date
                + "</D:getlastmodified></D:prop></D:set></D:propertyupdate>");
    assertThat(protectedDate.statusCode()).isEqualTo(207);
    assertThat(protectedDate.body()).contains("403 Forbidden", "cannot-modify-protected-property");
  }

  @Test
  void staleDateMutationConditionsLeaveBytesPropertiesRevisionsAndSyncUnchanged() throws Exception {
    String calendar = create("date-writes", "VEVENT"), target = create("date-target", "VEVENT");
    String resource = calendar + "event.ics", original = event("date-write", "Original");
    assertThat(send("PUT", resource, original).statusCode()).isEqualTo(201);
    Instant modified = Instant.parse("2020-01-01T12:00:00.500Z");
    setModificationDate(datedCollection("date-writes"), "event.ics", modified);
    String stale = HttpDateValues.format(modified.minusSeconds(1));
    String token = currentCollectionToken(calendar), targetToken = currentCollectionToken(target);
    var before = send("GET", resource, "");
    String etag = before.headers().firstValue("ETag").orElseThrow();
    assertThat(
            send("PUT", resource, event("date-write", "Rejected"), "If-Unmodified-Since", stale)
                .statusCode())
        .isEqualTo(412);
    assertThat(send("DELETE", resource, "", "If-Unmodified-Since", stale).statusCode())
        .isEqualTo(412);
    for (String method : List.of("COPY", "MOVE"))
      assertThat(
              send(
                      method,
                      resource,
                      "",
                      "If-Unmodified-Since",
                      stale,
                      "Destination",
                      "https://calendar.example/team/dav" + target + "copied.ics")
                  .statusCode())
          .isEqualTo(412);
    String patch =
        "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:client'><D:set><D:prop><X:color>rejected</X:color></D:prop></D:set></D:propertyupdate>";
    assertThat(send("PROPPATCH", resource, patch, "If-Unmodified-Since", stale).statusCode())
        .isEqualTo(412);
    assertThat(send("GET", resource, "").body()).isEqualTo(original);
    assertThat(send("GET", resource, "").headers().firstValue("ETag")).contains(etag);
    assertThat(currentCollectionToken(calendar)).isEqualTo(token);
    assertThat(currentCollectionToken(target)).isEqualTo(targetToken);
    var color =
        send(
            "PROPFIND",
            resource,
            "<D:propfind xmlns:D='DAV:' xmlns:X='urn:client'><D:prop><X:color/></D:prop></D:propfind>",
            "Depth",
            "0");
    assertThat(color.body()).contains("404 Not Found").doesNotContain("rejected");
    assertThat(
            send(
                    "PUT",
                    resource,
                    event("date-write", "Accepted"),
                    "If-Match",
                    etag,
                    "If-Unmodified-Since",
                    stale)
                .statusCode())
        .isEqualTo(204);
    assertThat(
            send(
                    "DELETE",
                    resource,
                    "",
                    "If-Unmodified-Since",
                    HttpDateValues.format(Instant.now().plusSeconds(3600)))
                .statusCode())
        .isEqualTo(204);
  }

  @Test
  void legacyAndInvalidHttpDatesHaveTheirSpecifiedWireBehavior() throws Exception {
    String calendar = create("legacy-dates", "VEVENT"), resource = calendar + "event.ics";
    String content = event("legacy-date", "Dates");
    assertThat(send("PUT", resource, content).statusCode()).isEqualTo(201);
    setModificationDate(
        datedCollection("legacy-dates"), "event.ics", Instant.parse("1994-11-06T08:49:37.250Z"));
    for (String date :
        List.of(
            "Sun, 06 Nov 1994 08:49:37 GMT",
            "Sunday, 06-Nov-94 08:49:37 GMT",
            "Sun Nov  6 08:49:37 1994")) {
      assertThat(send("GET", resource, "", "If-Modified-Since", date).statusCode()).isEqualTo(304);
      assertThat(send("PUT", resource, content, "If-Unmodified-Since", date).statusCode())
          .isEqualTo(204);
    }
    for (String invalid :
        List.of(
            "invalid date",
            "Sun, 06 Nov 1994 08:49:37 GMT, Sun, 06 Nov 1994 08:49:37 GMT",
            "Sun, 06 Nov 1994 08:49:37 GMT trailing")) {
      assertThat(send("GET", resource, "", "If-Modified-Since", invalid).statusCode())
          .isEqualTo(200);
      assertThat(send("PUT", resource, content, "If-Unmodified-Since", invalid).statusCode())
          .isEqualTo(204);
    }
    assertThat(
            send(
                    "GET",
                    resource,
                    "",
                    "If-Modified-Since",
                    "Sun, 06 Nov 1994 08:49:37 GMT",
                    "If-Modified-Since",
                    "Sun, 06 Nov 1994 08:49:37 GMT")
                .statusCode())
        .isEqualTo(200);
    assertThat(
            send(
                    "PUT",
                    resource,
                    content,
                    "If-Unmodified-Since",
                    "Sun, 06 Nov 1993 08:49:37 GMT",
                    "If-Unmodified-Since",
                    "Sun, 06 Nov 1993 08:49:37 GMT")
                .statusCode())
        .isEqualTo(204);
    assertThat(
            send("PUT", resource, content, "If-Modified-Since", "Sun, 06 Nov 1994 08:49:37 GMT")
                .statusCode())
        .isEqualTo(204);
  }

  @Test
  void futureStoredDatesAreClampedToResponseDateForHeadersPropertiesAndConditions()
      throws Exception {
    String calendar = create("future-dates", "VEVENT"), resource = calendar + "event.ics";
    String content = event("future-date", "Restored future timestamp");
    assertThat(send("PUT", resource, content).statusCode()).isEqualTo(201);
    setModificationDate(
        datedCollection("future-dates"), "event.ics", Instant.parse("2100-01-01T00:00:00Z"));
    var get = send("GET", resource, "");
    assertThat(get.headers().firstValue("Last-Modified"))
        .isEqualTo(get.headers().firstValue("Date"));
    var prop =
        send(
            "PROPFIND",
            resource,
            "<D:propfind xmlns:D='DAV:'><D:prop><D:getlastmodified/></D:prop></D:propfind>",
            "Depth",
            "0");
    var responseDate =
        java.time.ZonedDateTime.parse(
                prop.headers().firstValue("Date").orElseThrow(),
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant();
    var propertyDate =
        java.time.ZonedDateTime.parse(
                text(prop, "DAV:", "getlastmodified"),
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant();
    assertThat(propertyDate).isBeforeOrEqualTo(responseDate);
    String effectiveUpperBound = HttpDateValues.format(Instant.now().plusSeconds(60));
    assertThat(send("GET", resource, "", "If-Modified-Since", effectiveUpperBound).statusCode())
        .isEqualTo(304);
    assertThat(
            send("PUT", resource, content, "If-Unmodified-Since", effectiveUpperBound).statusCode())
        .isEqualTo(204);
  }

  @Test
  void datelessCollectionsIgnoreDateConditionsAndPreserveRedirectAndAuthorizationPrecedence()
      throws Exception {
    String calendar = create("dateless-collections", "VEVENT");
    String past = "Sun, 06 Nov 1994 08:49:37 GMT", future = "Fri, 01 Jan 2100 00:00:00 GMT";
    for (String path : List.of(home, calendar)) {
      var get = send("GET", path, "", "If-Unmodified-Since", past, "If-Modified-Since", future);
      assertThat(get.statusCode()).isEqualTo(200);
      assertThat(get.headers().firstValue("Last-Modified")).isEmpty();
      var prop =
          send(
              "PROPFIND",
              path,
              "<D:propfind xmlns:D='DAV:'><D:prop><D:getlastmodified/></D:prop></D:propfind>",
              "Depth",
              "0");
      assertThat(prop.body()).contains("404 Not Found");
    }
    assertThat(
            send(
                    "GET",
                    calendar.substring(0, calendar.length() - 1),
                    "",
                    "If-Unmodified-Since",
                    past)
                .statusCode())
        .isEqualTo(308);
    assertThat(sendAs("GET", calendar, "", bobAuth, "If-Unmodified-Since", past).statusCode())
        .isEqualTo(403);
  }

  @Test
  void dateConditionalWriteWaitsForTheMutationLockThenChecksCommittedContent() throws Exception {
    String calendar = create("date-concurrent", "VEVENT"), resource = calendar + "event.ics";
    UUID collection = datedCollection("date-concurrent");
    String original = event("date-concurrent", "Original"),
        committed = event("date-concurrent", "Committed first");
    assertThat(send("PUT", resource, original).statusCode()).isEqualTo(201);
    Instant oldDate = Instant.parse("2020-01-01T12:00:00Z");
    setModificationDate(collection, "event.ics", oldDate);
    String before = currentCollectionToken(calendar);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    var holderPid = new java.util.concurrent.atomic.AtomicInteger();
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var writer =
          workers.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
                            holderPid.set(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            jdbc.queryForObject(
                                "SELECT id FROM collections WHERE id=? FOR UPDATE",
                                UUID.class,
                                collection);
                            locked.countDown();
                            try {
                              if (!finish.await(15, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("Date condition coordination timed out");
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(interrupted);
                            }
                            propertyStore.putResource(
                                alice,
                                collection,
                                "event.ics",
                                committed.getBytes(StandardCharsets.UTF_8),
                                org.busystack.caldav.storage.WriteCondition.none());
                          }));
      try {
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var conditional =
            workers.submit(
                () ->
                    send(
                        "PUT",
                        resource,
                        event("date-concurrent", "Must fail"),
                        "If-Unmodified-Since",
                        HttpDateValues.format(oldDate)));
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
        writer.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(conditional.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode())
            .isEqualTo(412);
      } finally {
        finish.countDown();
      }
    }
    assertThat(send("GET", resource, "").body()).isEqualTo(committed);
    var delta = send("REPORT", calendar, sync(before, 100));
    assertThat(delta.statusCode()).isEqualTo(207);
    assertThat(xml(delta).getElementsByTagNameNS("DAV:", "getetag").getLength()).isEqualTo(1);
  }

  @Test
  void multigetAtHomePreservesSuccessfulEntriesBesideMissingAncestors() throws Exception {
    String calendar = create("mixed", "VEVENT");
    String resource = calendar + "valid.ics";
    assertThat(send("PUT", resource, event("mixed-member", "Valid")).statusCode()).isEqualTo(201);
    String report =
        "<C:calendar-multiget xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'>"
            + "<D:prop><D:getetag/></D:prop><D:href>/team/dav"
            + resource
            + "</D:href>"
            + "<D:href>/team/dav"
            + home
            + "missing/leaf.ics</D:href>"
            + "<D:href>/team/dav"
            + calendar
            + "missing.ics</D:href></C:calendar-multiget>";
    var response = send("REPORT", home, report);
    assertThat(response.statusCode()).isEqualTo(207);
    assertThat(xml(response).getElementsByTagNameNS("DAV:", "response").getLength()).isEqualTo(3);
    assertThat(xml(response).getElementsByTagNameNS("DAV:", "getetag").getLength()).isEqualTo(1);
    assertThat(response.body()).contains("HTTP/1.1 404 Not Found");
  }

  @Test
  void nestedExpansionPreservesAvailablePropertiesBesideMissingAncestors() throws Exception {
    String calendar = create("expanded-mixed", "VEVENT"), resource = calendar + "valid.ics";
    assertThat(send("PUT", resource, event("expanded-member", "Valid")).statusCode())
        .isEqualTo(201);
    String patch =
        "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:test'><D:set><D:prop><X:link>"
            + "<D:href>/team/dav"
            + resource
            + "</D:href><D:href>/team/dav"
            + home
            + "absent/leaf.ics</D:href></X:link></D:prop></D:set></D:propertyupdate>";
    assertThat(send("PROPPATCH", calendar, patch).statusCode()).isEqualTo(207);
    var response =
        send(
            "REPORT",
            calendar,
            "<D:expand-property xmlns:D='DAV:'>"
                + "<D:property name='link' namespace='urn:test'><D:property name='getetag'/>"
                + "</D:property></D:expand-property>",
            "Depth",
            "0");
    assertThat(response.statusCode()).isEqualTo(207);
    assertThat(xml(response).getElementsByTagNameNS("DAV:", "getetag").getLength()).isEqualTo(1);
    assertThat(response.body()).contains("HTTP/1.1 404 Not Found");
  }

  @Test
  void selectiveQueriesAndFreeBusyScanBodiesInBatchesBeyondTheFormerAggregateLimit()
      throws Exception {
    String calendar = create("large-selective", "VEVENT");
    UUID id = datedCollection("large-selective");
    String body =
        event("{UID}", "Unrelated")
            .replace("END:VEVENT\r\n", "X-BULK:" + "a".repeat(1047000) + "\r\nEND:VEVENT\r\n");
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) SELECT ?,n::text||'.ics',n::text,convert_to(replace(?,'{UID}',n::text),'UTF8'),'\"large-fixture\"',n FROM generate_series(1,33) n",
                  id,
                  body);
              jdbc.update("UPDATE collections SET revision=33 WHERE id=?", id);
            });
    assertThat(send("PUT", calendar + "needle.ics", event("needle", "Only match")).statusCode())
        .isEqualTo(201);
    var result =
        send(
            "REPORT",
            calendar,
            query(
                "<C:comp-filter name='VEVENT'><C:prop-filter name='SUMMARY'><C:text-match>Only match</C:text-match></C:prop-filter></C:comp-filter>"),
            "Depth",
            "1");
    assertThat(result.statusCode()).isEqualTo(207);
    assertThat(xml(result).getElementsByTagNameNS("DAV:", "response").getLength()).isEqualTo(1);
    assertThat(result.body()).contains("needle.ics").doesNotContain("X-BULK");
    var busy =
        send(
            "REPORT",
            calendar,
            "<C:free-busy-query xmlns:C='urn:ietf:params:xml:ns:caldav'><C:time-range start='20261027T000000Z' end='20261028T000000Z'/></C:free-busy-query>",
            "Depth",
            "1");
    assertThat(busy.statusCode()).isEqualTo(200);
    assertThat(busy.body()).contains("BEGIN:VFREEBUSY");
    var metadata =
        send(
            "PROPFIND",
            calendar,
            "<D:propfind xmlns:D='DAV:'><D:prop><D:getetag/></D:prop></D:propfind>",
            "Depth",
            "1");
    assertThat(metadata.statusCode()).isEqualTo(207);
    assertThat(xml(metadata).getElementsByTagNameNS("DAV:", "getetag").getLength()).isEqualTo(35);
    assertThat(xml(metadata).getElementsByTagNameNS("DAV:", "response").getLength()).isEqualTo(35);
  }

  @Test
  void selectiveTimeRangesHandleMoreOriginalResourcesThanTheRecurrenceInstanceLimit()
      throws Exception {
    String calendar = create("many-originals", "VEVENT");
    UUID id = datedCollection("many-originals");
    String body = event("{UID}", "Cardinality needle");
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) SELECT ?,n::text||'.ics',n::text,convert_to(replace(CASE WHEN n=5000 THEN ? ELSE replace(?,'20261027','20000101') END,'{UID}',n::text),'UTF8'),'\"cardinality-fixture\"',n FROM generate_series(1,10037) n",
                  id,
                  body,
                  body);
              jdbc.update("UPDATE collections SET revision=10037 WHERE id=?", id);
            });
    var result =
        send(
            "REPORT",
            calendar,
            query(
                    "<C:comp-filter name='VEVENT'><C:time-range start='20261027T000000Z' end='20261028T000000Z'/></C:comp-filter>")
                .replace("<C:calendar-data/>", ""),
            "Depth",
            "1");
    assertThat(result.statusCode()).describedAs(result.body()).isEqualTo(207);
    assertThat(xml(result).getElementsByTagNameNS("DAV:", "response").getLength()).isEqualTo(1);
    assertThat(result.body()).contains("5000.ics");
    var busy =
        send(
            "REPORT",
            calendar,
            "<C:free-busy-query xmlns:C='urn:ietf:params:xml:ns:caldav'><C:time-range start='20261027T000000Z' end='20261028T000000Z'/></C:free-busy-query>",
            "Depth",
            "1");
    assertThat(busy.statusCode()).describedAs(busy.body()).isEqualTo(200);
    assertThat(busy.body()).contains("20261027T100000Z/20261027T110000Z");
  }

  @Test
  void rawSyncPagesFreezeContentAndPropertiesAcrossWritesAndRetries() throws Exception {
    String calendar = create("paged-wire", "VEVENT");
    for (String name : List.of("a", "b", "c")) {
      assertThat(
              send("PUT", calendar + name + ".ics", event("wire-" + name, "original-" + name))
                  .statusCode())
          .isEqualTo(201);
      assertThat(
              send(
                      "PROPPATCH",
                      calendar + name + ".ics",
                      "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:test' xml:lang='fr'><D:set><D:prop><X:note>original-"
                          + name
                          + "</X:note></D:prop></D:set></D:propertyupdate>")
                  .statusCode())
          .isEqualTo(207);
    }
    String report =
        sync("", 1)
            .replace(
                "xmlns:D='DAV:'",
                "xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:X='urn:test'")
            .replace("<D:getetag/>", "<D:getetag/><C:calendar-data/><X:note/>");
    var first = send("REPORT", calendar, report);
    assertThat(first.statusCode()).isEqualTo(207);
    assertThat(first.body()).contains("original-a", "507 Insufficient Storage");
    String cursor = text(first, DavXml.DAV, "sync-token");
    assertThat(send("PUT", calendar + "b.ics", event("wire-b", "changed-b")).statusCode())
        .isEqualTo(204);
    assertThat(
            send(
                    "PROPPATCH",
                    calendar + "b.ics",
                    "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:test'><D:set><D:prop><X:note>changed-b</X:note></D:prop></D:set></D:propertyupdate>")
                .statusCode())
        .isEqualTo(207);
    assertThat(send("DELETE", calendar + "c.ics", "").statusCode()).isEqualTo(204);
    assertThat(
            send("PUT", calendar + "0.ics", event("wire-zero", "new-before-boundary")).statusCode())
        .isEqualTo(201);
    String continuation =
        report.replace(
            "<D:sync-token></D:sync-token>", "<D:sync-token>" + cursor + "</D:sync-token>");
    var second = send("REPORT", calendar, continuation);
    var retry = send("REPORT", calendar, continuation);
    assertThat(second.statusCode()).isEqualTo(207);
    assertThat(text(second, DavXml.CAL, "calendar-data"))
        .contains("original-b")
        .doesNotContain("changed-b");
    assertThat(text(retry, DavXml.CAL, "calendar-data"))
        .isEqualTo(text(second, DavXml.CAL, "calendar-data"));
    assertThat(text(second, "urn:test", "note")).isEqualTo("original-b");
    var third =
        send(
            "REPORT",
            calendar,
            report.replace(
                "<D:sync-token></D:sync-token>",
                "<D:sync-token>" + text(second, DavXml.DAV, "sync-token") + "</D:sync-token>"));
    assertThat(third.statusCode()).isEqualTo(207);
    assertThat(third.body()).contains("original-c").doesNotContain("507 Insufficient Storage");
    var delta = send("REPORT", calendar, sync(text(third, DavXml.DAV, "sync-token"), 100));
    assertThat(delta.statusCode()).isEqualTo(207);
    assertThat(delta.body()).contains("0.ics", "b.ics", "c.ics", "404 Not Found");
    assertThat(send("GET", calendar + "b.ics", "").body()).contains("changed-b");
  }

  @Test
  void partialSyncTokensNeverBypassCurrentSharesOrDisabledAccounts() throws Exception {
    String calendar = create("paged-share", "VEVENT");
    for (String name : List.of("a", "b"))
      send("PUT", calendar + name + ".ics", event("share-" + name, name));
    UUID id = datedCollection("paged-share");
    propertyStore.replaceAcl(
        alice, id, List.of(new org.busystack.caldav.storage.DavStore.Member(bob, "READ")));
    String shared = "/calendars/" + bob + "/" + id + "/";
    var first = sendAs("REPORT", shared, sync("", 1), bobAuth);
    assertThat(first.statusCode()).isEqualTo(207);
    String token = text(first, DavXml.DAV, "sync-token");
    assertThat(send("REPORT", calendar, sync(token, 1)).statusCode()).isEqualTo(403);
    propertyStore.replaceAcl(alice, id, List.of());
    assertThat(sendAs("REPORT", shared, sync(token, 1), bobAuth).statusCode()).isIn(403, 404);
    propertyStore.replaceAcl(
        alice, id, List.of(new org.busystack.caldav.storage.DavStore.Member(bob, "READ")));
    assertThat(sendAs("REPORT", shared, sync(token, 1), bobAuth).statusCode()).isEqualTo(403);
    var fresh = sendAs("REPORT", shared, sync("", 1), bobAuth);
    assertThat(fresh.statusCode()).isEqualTo(207);
    accounts.disable(bob);
    assertThat(
            sendAs("REPORT", shared, sync(text(fresh, DavXml.DAV, "sync-token"), 1), bobAuth)
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void boundaryCollectionsKeepUnicodeResourceUrlsAccessibleThroughShallowDeepCopyAndMove()
      throws Exception {
    String parent = "";
    for (int i = 0; i < 16; i++) {
      parent += (parent.isEmpty() ? "" : "/") + "f" + i;
      assertThat(send("MKCOL", home + parent + "/", "").statusCode()).isEqualTo(201);
    }
    String calendar = create(parent + "/boundary", "VEVENT");
    String name = DavPaths.encode("旅行 %🗓.ics"), content = event("boundary-member", "Kept");
    assertThat(send("PUT", calendar + name, content).statusCode()).isEqualTo(201);
    assertThat(send("GET", calendar + name, "").body()).isEqualTo(content);
    assertThat(
            send(
                    "COPY",
                    calendar,
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + home + "shallow/",
                    "Depth",
                    "0")
                .statusCode())
        .isEqualTo(201);
    assertThat(send("GET", home + "shallow/" + name, "").statusCode()).isEqualTo(404);
    assertThat(
            send(
                    "COPY",
                    calendar,
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + home + "deep/")
                .statusCode())
        .isEqualTo(201);
    assertThat(send("GET", home + "deep/" + name, "").body()).isEqualTo(content);
    assertThat(
            send(
                    "MOVE",
                    home + "deep/",
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + calendar,
                    "Overwrite",
                    "T")
                .statusCode())
        .isEqualTo(204);
    assertThat(send("GET", calendar + name, "").body()).isEqualTo(content);
    assertThat(send("MKCOL", home + parent + "/folder/", "").statusCode()).isEqualTo(201);
    assertThat(send("MKCALENDAR", home + parent + "/folder/too-deep/", "").statusCode())
        .isEqualTo(400);
    assertThat(send("GET", home + parent + "/folder/too-deep/", "").statusCode()).isEqualTo(404);
  }

  @Test
  void concurrentDavWorkAdmissionRejectsBeforeMutationAndRecoversAfterSlotsRelease()
      throws Exception {
    String calendar = create("bounded-work", "VEVENT");
    UUID id = datedCollection("bounded-work");
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var holder =
          workers.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            jdbc.queryForObject(
                                "SELECT id FROM collections WHERE id=? FOR UPDATE", UUID.class, id);
                            locked.countDown();
                            try {
                              if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("Work admission coordination timed out");
                            } catch (InterruptedException ex) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(ex);
                            }
                          }));
      try {
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var requests = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
        for (int i = 0; i < 4; i++) {
          int n = i;
          requests.add(
              workers.submit(
                  () -> send("PUT", calendar + n + ".ics", event("work-" + n, "Bounded"))));
        }
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(15))
            .untilAsserted(
                () ->
                    assertThat(
                            jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%collections%'",
                                Integer.class))
                        .isGreaterThanOrEqualTo(4));
        var busy = send("REPORT", calendar, sync("", 1));
        assertThat(busy.statusCode()).isEqualTo(503);
        assertThat(busy.headers().firstValue("Retry-After")).contains("1");
        assertThat(busy.body()).doesNotContain("sync-token", "multistatus");
        release.countDown();
        holder.get(15, java.util.concurrent.TimeUnit.SECONDS);
        for (var pending : requests)
          assertThat(pending.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode())
              .isEqualTo(201);
      } finally {
        release.countDown();
      }
    }
    assertThat(send("REPORT", calendar, sync("", 10)).statusCode()).isEqualTo(207);
  }

  @Test
  void copyAndMoveRejectXmlInvalidDestinationSegmentsBeforeMutation() throws Exception {
    String calendar = create("xml-namespace-source", "VEVENT");
    String target = create("xml-namespace-target", "VEVENT");
    String resource = calendar + "safe.ics", content = event("xml-namespace-source", "Keep");
    assertThat(send("PUT", resource, content).statusCode()).isEqualTo(201);
    String sourceToken = currentCollectionToken(calendar),
        targetToken = currentCollectionToken(target);
    for (String forbidden : List.of("%EF%BF%BE", "%EF%BF%BF")) {
      String invalidResource = target + "bad" + forbidden + "leaf.ics";
      for (String method : List.of("COPY", "MOVE"))
        assertThat(
                send(
                        method,
                        resource,
                        "",
                        "Destination",
                        "https://calendar.example/team/dav" + invalidResource)
                    .statusCode())
            .as(method + " XML-invalid resource destination " + forbidden)
            .isEqualTo(400);
      assertThat(
              send(
                      "MOVE",
                      calendar,
                      "",
                      "Destination",
                      "https://calendar.example/team/dav" + home + "bad" + forbidden + "calendar/")
                  .statusCode())
          .isEqualTo(400);
      assertThat(
              send("PUT", invalidResource, event("xml-invalid-" + forbidden, "Rejected"))
                  .statusCode())
          .isEqualTo(400);
    }
    assertThat(send("GET", resource, "").body()).isEqualTo(content);
    assertThat(currentCollectionToken(calendar)).isEqualTo(sourceToken);
    assertThat(currentCollectionToken(target)).isEqualTo(targetToken);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM calendar_resources WHERE collection_id=?",
                Integer.class,
                datedCollection("xml-namespace-target")))
        .isZero();
    for (String permitted :
        List.of("valid\uFDD0.ics", "valid" + new String(Character.toChars(0x1FFFE)) + ".ics")) {
      String path = target + DavPaths.encode(permitted);
      String data = event("xml-permitted-" + permitted.codePointAt(5), "Preserved");
      assertThat(send("PUT", path, data).statusCode()).isEqualTo(201);
      assertThat(send("GET", path, "").body()).isEqualTo(data);
      var response = send("PROPFIND", path, "", "Depth", "0");
      assertThat(response.statusCode()).isEqualTo(207);
      assertThatCode(() -> xml(response)).doesNotThrowAnyException();
    }
  }

  @Test
  void copyAndMoveRejectFirewallInaccessibleDestinationSegmentsBeforeMutation() throws Exception {
    String calendar = create("namespace-segment", "VEVENT");
    String target = create("namespace-target", "VEVENT");
    String resource = calendar + "safe.ics", content = event("namespace-segment", "Keep");
    assertThat(send("PUT", resource, content).statusCode()).isEqualTo(201);
    String before = currentCollectionToken(calendar);
    for (String forbidden : List.of("%3B", "%E2%80%A8", "%E2%80%A9")) {
      assertThat(
              send(
                      "COPY",
                      resource,
                      "",
                      "Destination",
                      "https://calendar.example/team/dav" + target + "bad" + forbidden + "leaf.ics")
                  .statusCode())
          .isEqualTo(400);
      assertThat(
              send(
                      "MOVE",
                      calendar,
                      "",
                      "Destination",
                      "https://calendar.example/team/dav" + home + "bad" + forbidden + "calendar/")
                  .statusCode())
          .isEqualTo(400);
    }
    assertThat(send("GET", resource, "").body()).isEqualTo(content);
    assertThat(currentCollectionToken(calendar)).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM calendar_resources WHERE collection_id=?",
                Integer.class,
                datedCollection("namespace-segment")))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM calendar_resources WHERE collection_id=?",
                Integer.class,
                datedCollection("namespace-target")))
        .isZero();
  }

  @Test
  void syncContinuationParentLockWaitFailsBoundedlyWithoutAdvancingToken() throws Exception {
    String calendar = create("sync-cursor-lock", "VEVENT");
    UUID collection = datedCollection("sync-cursor-lock");
    assertThat(send("PUT", calendar + "a.ics", event("sync-lock-a", "A")).statusCode())
        .isEqualTo(201);
    assertThat(send("PUT", calendar + "b.ics", event("sync-lock-b", "B")).statusCode())
        .isEqualTo(201);
    long cursorCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM sync_continuations WHERE collection_id=?",
            Long.class,
            collection);
    String stableBefore = currentCollectionToken(calendar);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var writer =
          workers.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            jdbc.queryForObject(
                                "SELECT id FROM collections WHERE id=? FOR UPDATE",
                                UUID.class,
                                collection);
                            locked.countDown();
                            try {
                              if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("Cursor parent lock release timed out");
                            } catch (InterruptedException ex) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(ex);
                            }
                          }));
      try {
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var report = workers.submit(() -> send("REPORT", calendar, sync("", 1)));
        // The continuation transaction has its own deadline; a proxy/client timeout must not
        // leave the servlet and a DAV-work slot waiting indefinitely on the database writer.
        var response = report.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        assertThat(response.body()).doesNotContain("sync-token");
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM sync_continuations WHERE collection_id=?",
                    Long.class,
                    collection))
            .isEqualTo(cursorCount);
      } finally {
        release.countDown();
        writer.get(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
    assertThat(currentCollectionToken(calendar)).isEqualTo(stableBefore);
    assertThat(send("REPORT", calendar, sync("", 1)).statusCode()).isEqualTo(207);
    assertThat(send("GET", calendar + "a.ics", "").statusCode()).isEqualTo(200);
  }

  @Test
  void syncEmptyPropertySelectionReturnsAnEmptySuccessfulPropstat() throws Exception {
    String calendar = create("sync-empty-properties", "VEVENT");
    assertThat(send("PUT", calendar + "one.ics", event("empty-prop-sync", "One")).statusCode())
        .isEqualTo(201);
    var report =
        send("REPORT", calendar, sync("", 1).replace("<D:prop><D:getetag/></D:prop>", "<D:prop/>"));
    assertThat(report.statusCode()).isEqualTo(207);
    var result = xml(report);
    assertThat(result.getElementsByTagNameNS("DAV:", "response").getLength()).isEqualTo(1);
    var response = (Element) result.getElementsByTagNameNS("DAV:", "response").item(0);
    assertThat(response.getElementsByTagNameNS("DAV:", "propstat").getLength()).isEqualTo(1);
    var propstat = (Element) response.getElementsByTagNameNS("DAV:", "propstat").item(0);
    var properties = DavXml.required(propstat, "DAV:", "prop");
    assertThat(DavXml.children(properties)).isEmpty();
    assertThat(DavXml.required(propstat, "DAV:", "status").getTextContent())
        .isEqualTo("HTTP/1.1 200 OK");
    assertThat(result.getElementsByTagNameNS("DAV:", "sync-token").getLength()).isEqualTo(1);
  }

  private int propertyStatus(HttpResponse<String> response, String namespace, String local) {
    for (Element item : DavXml.children(xml(response)))
      if (DavXml.is(item, DavXml.DAV, "response"))
        for (Element propstat : DavXml.children(item, DavXml.DAV, "propstat"))
          if (DavXml.child(DavXml.required(propstat, DavXml.DAV, "prop"), namespace, local) != null)
            return Integer.parseInt(
                DavXml.required(propstat, DavXml.DAV, "status").getTextContent().split(" ")[1]);
    throw new AssertionError("Property status missing: " + namespace + local);
  }

  private String propRequest(String body) {
    return "<D:propfind xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav'><D:prop>"
        + body
        + "</D:prop></D:propfind>";
  }

  private String aclGrant(UUID principal, String grants) {
    return "<D:ace><D:principal><D:href>https://calendar.example/team/dav/principals/"
        + principal
        + "/</D:href></D:principal><D:grant>"
        + grants
        + "</D:grant></D:ace>";
  }

  private String aclBody(String aces) {
    return "<D:acl xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:X='urn:audit'>"
        + aces
        + "</D:acl>";
  }

  @Test
  void commandExtensionsAreIgnoredAsWholeSubtreesWithoutLosingDeadXml() throws Exception {
    String calendar = create("xml-extensions", "VEVENT");
    send("PUT", calendar + "one.ics", event("xml-extensions", "One"));
    String set =
        "<D:set><D:prop><X:kept xml:lang='ja'>before<X:mixed a='v'/>after</X:kept></D:prop></D:set>";
    String normal =
        "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:audit'>" + set + "</D:propertyupdate>";
    String extension =
        normal.replace(
            "</D:propertyupdate>",
            "<X:ignored><D:set><D:prop><X:smuggled>bad</X:smuggled></D:prop></D:set></X:ignored></D:propertyupdate>");
    assertThat(send("PROPPATCH", calendar, normal).statusCode()).isEqualTo(207);
    assertThat(send("PROPPATCH", calendar, extension).statusCode()).isEqualTo(207);
    var values =
        send(
            "PROPFIND",
            calendar,
            propRequest("<X:kept xmlns:X='urn:audit'/><X:smuggled xmlns:X='urn:audit'/>"),
            "Depth",
            "0");
    assertThat(propertyStatus(values, "urn:audit", "kept")).isEqualTo(200);
    assertThat(propertyStatus(values, "urn:audit", "smuggled")).isEqualTo(404);
    assertThat(values.body()).contains("before", "after", "xml:lang=\"ja\"", "a=\"v\"");
    String base = query("<C:comp-filter name='VEVENT'/>");
    String added =
        base.replace(
            "<C:comp-filter name='VEVENT'/>",
            "<C:comp-filter name='VEVENT' xmlns:X='urn:audit' X:ignored='yes'><X:ignored><C:comp-filter name='VTODO'/></X:ignored></C:comp-filter>");
    assertThat(principalReportHrefs(send("REPORT", calendar, added, "Depth", "1")))
        .isEqualTo(principalReportHrefs(send("REPORT", calendar, base, "Depth", "1")));
    String selector =
        "<C:comp name='VCALENDAR'><C:comp name='VEVENT'><C:prop name='UID'/></C:comp></C:comp>";
    String projection =
        base.replace("<C:calendar-data/>", "<C:calendar-data>" + selector + "</C:calendar-data>");
    String extraSelector =
        selector.replace(
            "</C:comp></C:comp>",
            "<X:ignored xmlns:X='urn:audit'><C:prop name='SUMMARY'/></X:ignored></C:comp></C:comp>");
    assertThat(
            text(
                send("REPORT", calendar, projection.replace(selector, extraSelector), "Depth", "1"),
                DavXml.CAL,
                "calendar-data"))
        .isEqualTo(
            text(send("REPORT", calendar, projection, "Depth", "1"), DavXml.CAL, "calendar-data"));
    String read = aclGrant(bob, "<D:privilege><D:read/></D:privilege>");
    String ignoredWrite =
        "<X:ignored>"
            + aclGrant(
                bob, "<D:privilege><D:read/></D:privilege><D:privilege><D:write/></D:privilege>")
            + "</X:ignored>";
    assertThat(send("ACL", calendar, aclBody(read + ignoredWrite)).statusCode()).isEqualTo(200);
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='xml-extensions'",
            UUID.class,
            alice);
    assertThat(
            sendAs("PUT", "/calendars/" + bob + "/" + id + "/bad.ics", event("bad", "Bad"), bobAuth)
                .statusCode())
        .isEqualTo(403);
    String search =
        "<D:principal-search-property-set xmlns:D='DAV:' xmlns:X='urn:audit' X:ignored='yes'><X:ignored><D:property-search/></X:ignored></D:principal-search-property-set>";
    assertThat(send("REPORT", "/principals/", search, "Depth", "0").statusCode()).isEqualTo(200);
    assertThat(send("REPORT", "/principals/", search, "Depth", "0", "Depth", "1").statusCode())
        .isEqualTo(400);

    String expanded =
        "<D:expand-property xmlns:D='DAV:' xmlns:X='urn:audit'><D:property name='owner'><X:ignored><D:property name='acl'/></X:ignored><D:property name='displayname'/></D:property><X:ignored><D:property name='calendar-data' namespace='urn:ietf:params:xml:ns:caldav'/></X:ignored></D:expand-property>";
    var expansion = send("REPORT", calendar, expanded, "Depth", "0");
    assertThat(expansion.statusCode()).isEqualTo(207);
    assertThat(expansion.body())
        .contains("Alice &amp; Calendar")
        .doesNotContain("calendar-data", "<D:acl>");
    var duplicate = send("PROPPATCH", calendar, normal.replace("<D:set>", "<D:set><D:prop/>"));
    assertThat(duplicate.statusCode()).isEqualTo(400);
  }

  @Test
  void abstractAclGrantsFailAtomicallyAndEffectivePrivilegesAreNonabstract() throws Exception {
    String calendar = create("model", "VEVENT");
    send("PUT", calendar + "one.ics", event("model", "One"));
    String read = aclGrant(bob, "<D:privilege><D:read/></D:privilege>");
    assertThat(send("ACL", calendar, aclBody(read)).statusCode()).isEqualTo(200);
    String before =
        text(
            send("PROPFIND", calendar, propRequest("<D:sync-token/>"), "Depth", "0"),
            DavXml.DAV,
            "sync-token");
    for (String abstractName :
        List.of(
            "read-current-user-privilege-set",
            "write-properties",
            "bind",
            "unbind",
            "C:read-free-busy")) {
      String element = abstractName.startsWith("C:") ? abstractName : "D:" + abstractName;
      var failure =
          send(
              "ACL",
              calendar,
              aclBody(
                  aclGrant(
                      bob,
                      "<D:privilege><D:read/></D:privilege><D:privilege><"
                          + element
                          + "/></D:privilege>")));
      assertThat(failure.statusCode()).isEqualTo(403);
      assertThat(xml(failure).getElementsByTagNameNS(DavXml.DAV, "no-abstract").getLength())
          .isEqualTo(1);
    }
    assertThat(
            text(
                send("PROPFIND", calendar, propRequest("<D:sync-token/>"), "Depth", "0"),
                DavXml.DAV,
                "sync-token"))
        .isEqualTo(before);
    assertThat(send("ACL", calendar, aclBody(read)).statusCode()).isEqualTo(200);
    assertThat(
            text(
                send("PROPFIND", calendar, propRequest("<D:sync-token/>"), "Depth", "0"),
                DavXml.DAV,
                "sync-token"))
        .isEqualTo(before);
    assertThat(send("MKCOL", home + "ordinary-model/", "").statusCode()).isEqualTo(201);
    for (String path :
        List.of(
            "/",
            "/principals/",
            "/principals/" + alice + "/",
            home,
            home + "ordinary-model/",
            calendar,
            calendar + "one.ics")) {
      var response =
          send(
              "PROPFIND",
              path,
              propRequest("<D:supported-privilege-set/><D:current-user-privilege-set/>"),
              "Depth",
              "0");
      assertNonabstractEffectivePrivileges(xml(response), true);
    }
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='model'", UUID.class, alice);
    String shared = "/calendars/" + bob + "/" + id + "/";
    assertNonabstractEffectivePrivileges(
        xml(
            sendAs(
                "PROPFIND",
                shared,
                propRequest("<D:supported-privilege-set/><D:current-user-privilege-set/>"),
                bobAuth,
                "Depth",
                "0")),
        false);
    assertThat(
            send(
                    "ACL",
                    calendar,
                    aclBody(
                        aclGrant(
                            bob,
                            "<D:privilege><D:read/></D:privilege><D:privilege><D:write/></D:privilege>")))
                .statusCode())
        .isEqualTo(200);
    var writer =
        sendAs(
            "PROPFIND",
            shared + "one.ics",
            propRequest("<D:supported-privilege-set/><D:current-user-privilege-set/>"),
            bobAuth,
            "Depth",
            "0");
    assertNonabstractEffectivePrivileges(xml(writer), false);
    assertThat(xml(writer).getElementsByTagNameNS(DavXml.DAV, "write").getLength())
        .isGreaterThan(1);
    UUID group =
        groupDirectory.create(groupAdministrator(), "ACL model group", java.util.Set.of("bob"));
    assertThat(
            send("ACL", calendar, aclBody(aclGrant(group, "<D:privilege><D:read/></D:privilege>")))
                .statusCode())
        .isEqualTo(200);
    assertNonabstractEffectivePrivileges(
        xml(
            sendAs(
                "PROPFIND",
                shared,
                propRequest("<D:supported-privilege-set/><D:current-user-privilege-set/>"),
                bobAuth,
                "Depth",
                "0")),
        false);
    assertThat(
            send(
                    "ACL",
                    calendar,
                    aclBody(
                        aclGrant(
                            group,
                            "<D:privilege><D:read/></D:privilege><D:privilege><D:write/></D:privilege>")))
                .statusCode())
        .isEqualTo(200);
    assertNonabstractEffectivePrivileges(
        xml(
            sendAs(
                "PROPFIND",
                shared + "one.ics",
                propRequest("<D:supported-privilege-set/><D:current-user-privilege-set/>"),
                bobAuth,
                "Depth",
                "0")),
        false);
  }

  private void assertNonabstractEffectivePrivileges(Element root, boolean owner) {
    var supported = root.getElementsByTagNameNS(DavXml.DAV, "supported-privilege");
    java.util.Set<javax.xml.namespace.QName> nonabstract = new java.util.HashSet<>();
    for (int i = 0; i < supported.getLength(); i++) {
      Element privilege = (Element) supported.item(i);
      if (DavXml.child(privilege, DavXml.DAV, "abstract") == null)
        nonabstract.add(
            DavXml.name(
                DavXml.children(DavXml.required(privilege, DavXml.DAV, "privilege")).getFirst()));
    }
    Element current =
        (Element) root.getElementsByTagNameNS(DavXml.DAV, "current-user-privilege-set").item(0);
    java.util.Set<javax.xml.namespace.QName> actual = new java.util.HashSet<>();
    for (Element privilege : DavXml.children(current, DavXml.DAV, "privilege"))
      actual.add(DavXml.name(DavXml.children(privilege).getFirst()));
    assertThat(nonabstract).containsAll(actual);
    assertThat(actual.contains(DavXml.d("all"))).isEqualTo(owner);
  }

  @Test
  void propertyInventoriesAndQueryCollationsRespectVisibilityAndReportContext() throws Exception {
    String calendar = create("property-inventory", "VEVENT");
    send("PUT", calendar + "one.ics", event("property-inventory", "One"));
    assertThat(send("MKCOL", home + "property-folder/", "").statusCode()).isEqualTo(201);
    for (String path : List.of(home, home + "property-folder/", calendar, calendar + "one.ics")) {
      var response =
          send(
              "PROPFIND",
              path,
              propRequest("<D:group/><D:supported-report-set/><C:supported-collation-set/>"),
              "Depth",
              "0");
      assertThat(propertyStatus(response, DavXml.DAV, "group")).isEqualTo(200);
      assertThat(propertyStatus(response, DavXml.CAL, "supported-collation-set")).isEqualTo(200);
      assertThat(response.body()).contains("calendar-query", "i;ascii-casemap", "i;octet");
    }
    var names =
        send(
            "PROPFIND",
            calendar,
            "<D:propfind xmlns:D='DAV:'><D:propname/></D:propfind>",
            "Depth",
            "0");
    assertThat(xml(names).getElementsByTagNameNS(DavXml.DAV, "acl").getLength()).isEqualTo(1);
    assertThat(xml(names).getElementsByTagNameNS(DavXml.DAV, "group").getLength()).isEqualTo(1);
    assertThat(xml(names).getElementsByTagNameNS(DavXml.CAL, "calendar-data").getLength()).isZero();
    var all =
        send(
            "PROPFIND",
            calendar,
            "<D:propfind xmlns:D='DAV:'><D:allprop/></D:propfind>",
            "Depth",
            "0");
    assertThat(xml(all).getElementsByTagNameNS(DavXml.DAV, "acl").getLength()).isZero();
    assertThat(xml(all).getElementsByTagNameNS(DavXml.DAV, "sync-token").getLength()).isZero();
    var data =
        send("PROPFIND", calendar + "one.ics", propRequest("<C:calendar-data/>"), "Depth", "0");
    assertThat(propertyStatus(data, DavXml.CAL, "calendar-data")).isEqualTo(404);
    assertThat(data.body()).doesNotContain("BEGIN:VCALENDAR");
    for (String property : List.of("<D:group/>", "<C:supported-collation-set/>")) {
      var protectedWrite =
          send(
              "PROPPATCH",
              calendar,
              "<D:propertyupdate xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav'><D:set><D:prop>"
                  + property
                  + "</D:prop></D:set></D:propertyupdate>");
      assertThat(protectedWrite.body()).contains("cannot-modify-protected-property");
    }
    assertThat(
            send("ACL", calendar, aclBody(aclGrant(bob, "<D:privilege><D:read/></D:privilege>")))
                .statusCode())
        .isEqualTo(200);
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='property-inventory'",
            UUID.class,
            alice);
    String shared = "/calendars/" + bob + "/" + id + "/";
    var readerNames =
        sendAs(
            "PROPFIND",
            shared,
            "<D:propfind xmlns:D='DAV:'><D:propname/></D:propfind>",
            bobAuth,
            "Depth",
            "0");
    assertThat(xml(readerNames).getElementsByTagNameNS(DavXml.DAV, "acl").getLength()).isZero();
    var readerAcl = sendAs("PROPFIND", shared, propRequest("<D:acl/>"), bobAuth, "Depth", "0");
    assertThat(propertyStatus(readerAcl, DavXml.DAV, "acl")).isEqualTo(403);
    assertThat(readerAcl.body())
        .contains("need-privileges", "read-acl")
        .doesNotContain("<D:grant>");
    assertThat(
            send("REPORT", calendar, query("<C:comp-filter name='VEVENT'/>"), "Depth", "1").body())
        .contains("BEGIN:VCALENDAR");
    assertThat(send("REPORT", calendar, sync("", 10), "Depth", "0").statusCode()).isEqualTo(207);
  }

  @Test
  void resourceOptionsChecksCurrentReadAccessAndReportsSupportedTargetMethods() throws Exception {
    String calendar = create("options-access", "VEVENT");
    send("PUT", calendar + "one.ics", event("options-access", "One"));
    assertThat(
            send("OPTIONS", calendar + "one.ics", "").headers().firstValue("Allow").orElseThrow())
        .contains("PUT")
        .doesNotContain("MKCALENDAR", "ACL");
    assertThat(sendAs("OPTIONS", calendar + "one.ics", "", bobAuth).statusCode()).isEqualTo(403);
    assertThat(send("OPTIONS", calendar + "missing.ics", "").statusCode()).isEqualTo(404);
    assertThat(
            send("ACL", calendar, aclBody(aclGrant(bob, "<D:privilege><D:read/></D:privilege>")))
                .statusCode())
        .isEqualTo(200);
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='options-access'",
            UUID.class,
            alice);
    String shared = "/calendars/" + bob + "/" + id + "/one.ics";
    assertThat(sendAs("OPTIONS", shared, "", bobAuth).statusCode()).isEqualTo(200);
    assertThat(
            send(
                    "ACL",
                    calendar,
                    aclBody(
                        aclGrant(
                            bob,
                            "<D:privilege><D:read/></D:privilege><D:privilege><D:write/></D:privilege>")))
                .statusCode())
        .isEqualTo(200);
    assertThat(sendAs("OPTIONS", shared, "", bobAuth).statusCode()).isEqualTo(200);
    assertThat(send("ACL", calendar, aclBody("")).statusCode()).isEqualTo(200);
    assertThat(sendAs("OPTIONS", shared, "", bobAuth).statusCode()).isIn(403, 404);
  }

  @Test
  void queryTimezonesAreValidatedBeforeEnumeratingEmptyCalendar() throws Exception {
    String calendar = create("empty-query-timezone", "VEVENT");
    String archived;
    try (var fixture = getClass().getResourceAsStream("/calendar/audit/future-zone.ics")) {
      assertThat(fixture).isNotNull();
      archived = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
    }
    String valid =
        archived.substring(0, archived.indexOf("BEGIN:VEVENT\r\n")) + "END:VCALENDAR\r\n";
    String template =
        query("<C:comp-filter name='VEVENT'/>")
            .replace(
                "</C:calendar-query>",
                "<C:timezone><![CDATA[__TIMEZONE__]]></C:timezone></C:calendar-query>");
    String selected = "<D:displayname/><C:calendar-timezone/><D:sync-token/>";
    var before = send("PROPFIND", calendar, propRequest(selected), "Depth", "0");
    String token = text(before, DavXml.DAV, "sync-token");
    var control = send("REPORT", calendar, template.replace("__TIMEZONE__", valid), "Depth", "1");
    assertThat(control.statusCode()).isEqualTo(207);
    assertThat(principalReportHrefs(control)).isEmpty();
    for (String invalid :
        List.of(
            "not a calendar",
            valid.replace("UNTIL=20290531T230000Z", "UNTIL=20290531T230000"),
            valid.replace("UNTIL=20290531T230000Z", "UNTIL=20290531"))) {
      var failure =
          send("REPORT", calendar, template.replace("__TIMEZONE__", invalid), "Depth", "1");
      assertThat(failure.statusCode()).isEqualTo(403);
      assertThat(xml(failure).getElementsByTagNameNS(DavXml.CAL, "valid-calendar-data").getLength())
          .isEqualTo(1);
      assertThat(xml(failure).getElementsByTagNameNS(DavXml.CAL, "valid-filter").getLength())
          .isZero();
      var after = send("PROPFIND", calendar, propRequest(selected), "Depth", "0");
      assertThat(after.body()).isEqualTo(before.body());
      assertThat(text(after, DavXml.DAV, "sync-token")).isEqualTo(token);
    }
    String repeated =
        template
            .replace("__TIMEZONE__", valid)
            .replace(
                "</C:calendar-query>",
                "<C:timezone><![CDATA[" + valid + "]]></C:timezone></C:calendar-query>");
    assertThat(send("REPORT", calendar, repeated, "Depth", "1").statusCode()).isEqualTo(400);
    String extension =
        template
            .replace("__TIMEZONE__", valid)
            .replace(
                "</C:calendar-query>",
                "<X:ignored xmlns:X='urn:audit'><C:timezone>not a calendar</C:timezone></X:ignored></C:calendar-query>");
    var extended = send("REPORT", calendar, extension, "Depth", "1");
    assertThat(extended.statusCode()).isEqualTo(207);
    assertThat(extended.body()).isEqualTo(control.body());
    String bounded =
        valid.replace(
            "BEGIN:VTIMEZONE\r\n", "X-WORK-LIMIT:" + "a".repeat(65536) + "\r\nBEGIN:VTIMEZONE\r\n");
    var limited = send("REPORT", calendar, template.replace("__TIMEZONE__", bounded), "Depth", "1");
    assertThat(limited.statusCode()).isEqualTo(507);
    assertThat(
            xml(limited)
                .getElementsByTagNameNS(DavXml.DAV, "number-of-matches-within-limits")
                .getLength())
        .isEqualTo(1);
    assertThat(send("PROPFIND", calendar, propRequest(selected), "Depth", "0").body())
        .isEqualTo(before.body());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM calendar_resources r JOIN collections c ON c.id=r.collection_id WHERE c.owner_id=?",
                Integer.class,
                alice))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM collections WHERE owner_id=?", Integer.class, alice))
        .isEqualTo(1);
  }

  @Test
  void timezonePropertyFailuresKeepCalDavConditionAndAtomicSiblingState() throws Exception {
    String calendar = create("timezone-property-errors", "VEVENT");
    String valid =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Timezone property//EN\r\nBEGIN:VTIMEZONE\r\nTZID:Fixed\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0100\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nEND:VCALENDAR\r\n";
    String start =
        "<D:propertyupdate xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav'><D:set><D:prop><C:calendar-timezone><![CDATA[";
    String end =
        "]]></C:calendar-timezone><D:displayname>changed</D:displayname></D:prop></D:set></D:propertyupdate>";
    assertThat(send("PROPPATCH", calendar, start + valid + end).statusCode()).isEqualTo(207);
    String archived;
    try (var fixture = getClass().getResourceAsStream("/calendar/audit/future-zone.ics")) {
      assertThat(fixture).isNotNull();
      archived = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
    }
    String utcUntil =
        archived.substring(0, archived.indexOf("BEGIN:VEVENT\r\n")) + "END:VCALENDAR\r\n";
    var validRecurrence = send("PROPPATCH", calendar, start + utcUntil + end);
    assertThat(propertyStatus(validRecurrence, DavXml.CAL, "calendar-timezone")).isEqualTo(200);
    assertThat(propertyStatus(validRecurrence, DavXml.DAV, "displayname")).isEqualTo(200);
    String selected = "<C:calendar-timezone/><D:displayname/><D:sync-token/>";
    var before = send("PROPFIND", calendar, propRequest(selected), "Depth", "0");
    String syncToken = text(before, DavXml.DAV, "sync-token");
    int index = 0;
    for (String invalid :
        List.of(
            "not a calendar",
            event("not-timezone", "Event"),
            valid.replace(
                "END:VCALENDAR",
                valid.substring(valid.indexOf("BEGIN:VTIMEZONE"), valid.indexOf("END:VCALENDAR"))
                    + "END:VCALENDAR"),
            utcUntil.replace("UNTIL=20290531T230000Z", "UNTIL=20290531T230000"),
            utcUntil.replace("UNTIL=20290531T230000Z", "UNTIL=20290531"))) {
      var failure =
          send("PROPPATCH", calendar, start + invalid + end.replace("changed", "not-applied"));
      assertThat(propertyStatus(failure, DavXml.CAL, "calendar-timezone")).isEqualTo(409);
      assertThat(propertyStatus(failure, DavXml.DAV, "displayname")).isEqualTo(424);
      assertThat(xml(failure).getElementsByTagNameNS(DavXml.CAL, "valid-calendar-data").getLength())
          .isEqualTo(1);
      var after = send("PROPFIND", calendar, propRequest(selected), "Depth", "0");
      assertThat(after.body()).isEqualTo(before.body());
      assertThat(text(after, DavXml.DAV, "sync-token")).isEqualTo(syncToken);
      String rejected = home + "timezone-initialization-" + index++ + "/";
      int collectionCount =
          jdbc.queryForObject(
              "SELECT count(*) FROM collections WHERE owner_id=?", Integer.class, alice);
      String initialization =
          "<C:mkcalendar xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:set><D:prop><D:displayname>not-created</D:displayname><C:calendar-timezone><![CDATA["
              + invalid
              + "]]></C:calendar-timezone></D:prop></D:set></C:mkcalendar>";
      var rejectedCreation = send("MKCALENDAR", rejected, initialization);
      assertThat(rejectedCreation.statusCode()).isEqualTo(409);
      assertThat(
              xml(rejectedCreation)
                  .getElementsByTagNameNS(DavXml.CAL, "valid-calendar-data")
                  .getLength())
          .isEqualTo(1);
      assertThat(send("GET", rejected, "").statusCode()).isEqualTo(404);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM collections WHERE owner_id=?", Integer.class, alice))
          .isEqualTo(collectionCount);
      assertThat(send("PROPFIND", calendar, propRequest(selected), "Depth", "0").body())
          .isEqualTo(before.body());
    }
    String initialized = home + "timezone-initialization-valid/";
    assertThat(
            send(
                    "MKCALENDAR",
                    initialized,
                    "<C:mkcalendar xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:set><D:prop><C:calendar-timezone><![CDATA["
                        + utcUntil
                        + "]]></C:calendar-timezone></D:prop></D:set></C:mkcalendar>")
                .statusCode())
        .isEqualTo(201);
    assertThat(
            propertyStatus(
                send("PROPFIND", initialized, propRequest("<C:calendar-timezone/>"), "Depth", "0"),
                DavXml.CAL,
                "calendar-timezone"))
        .isEqualTo(200);
  }

  @Test
  void nonemptyMkcalendarRequiresRecognizedSetAndPropWithoutCreatingState() throws Exception {
    int index = 0;
    for (String content :
        List.of(
            "",
            "<D:set/>",
            "<D:set><D:prop/></D:set><D:set><D:prop/></D:set>",
            "<D:set><D:prop/><D:prop/></D:set>",
            "<X:ignored><D:set><D:prop/></D:set></X:ignored>")) {
      String path = home + "invalid-mkcalendar-" + index++ + "/";
      int before =
          jdbc.queryForObject(
              "SELECT count(*) FROM collections WHERE owner_id=?", Integer.class, alice);
      var result =
          send(
              "MKCALENDAR",
              path,
              "<C:mkcalendar xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:' xmlns:X='urn:audit'>"
                  + content
                  + "</C:mkcalendar>");
      assertThat(result.statusCode()).isEqualTo(400);
      assertThat(send("GET", path, "").statusCode()).isEqualTo(404);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM collections WHERE owner_id=?", Integer.class, alice))
          .isEqualTo(before);
    }
    assertThat(send("MKCALENDAR", home + "empty-http-body/", "").statusCode()).isEqualTo(201);
    assertThat(
            send(
                    "MKCALENDAR",
                    home + "valid-empty-prop/",
                    "<C:mkcalendar xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:set><D:prop/></D:set></C:mkcalendar>")
                .statusCode())
        .isEqualTo(201);
  }

  @Test
  void operationSpecificFailureStatusHeadersAndConditionsArePreserved() throws Exception {
    String calendar = create("failure-contracts", "VEVENT");
    for (String target : List.of("/", home, "/principals/" + alice)) {
      var result = send("DELETE", target, "");
      assertThat(result.statusCode()).isEqualTo(405);
      assertThat(result.headers().firstValue("Allow").orElseThrow())
          .contains("GET", "PROPFIND")
          .doesNotContain("DELETE");
      assertThat(xml(result).getElementsByTagNameNS(DavXml.DAV, "need-privileges").getLength())
          .isZero();
    }
    for (String method : List.of("MKCOL", "MKCALENDAR")) {
      var result = send(method, calendar, "");
      assertThat(result.statusCode()).isEqualTo(405);
      assertThat(result.headers().firstValue("Allow").orElseThrow())
          .contains("GET", "PROPFIND", "ACL")
          .doesNotContain("MKCALENDAR");
      if (method.equals("MKCALENDAR"))
        assertThat(
                xml(result).getElementsByTagNameNS(DavXml.DAV, "resource-must-be-null").getLength())
            .isEqualTo(1);
    }
    var forbidden =
        send("PROPFIND", "/calendars/" + bob + "/", propRequest("<D:displayname/>"), "Depth", "0");
    assertThat(forbidden.statusCode()).isEqualTo(403);
    Element privileges =
        (Element) xml(forbidden).getElementsByTagNameNS(DavXml.DAV, "need-privileges").item(0);
    assertThat(DavXml.child(privileges, DavXml.DAV, "resource")).isNotNull();
    String inaccessibleHome = "/team/dav/calendars/" + bob + "/";
    assertThat(
            send(
                    "PROPPATCH",
                    calendar,
                    "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:audit'><D:set><D:prop><X:target><D:href>"
                        + inaccessibleHome
                        + "</D:href></X:target></D:prop></D:set></D:propertyupdate>")
                .statusCode())
        .isEqualTo(207);
    var expanded =
        send(
            "REPORT",
            calendar,
            "<D:expand-property xmlns:D='DAV:'><D:property name='target' namespace='urn:audit'><D:property name='displayname'/></D:property></D:expand-property>",
            "Depth",
            "0");
    assertThat(expanded.statusCode()).isEqualTo(207);
    Element missingPrivileges =
        (Element) xml(expanded).getElementsByTagNameNS(DavXml.DAV, "need-privileges").item(0);
    Element missingResource = DavXml.required(missingPrivileges, DavXml.DAV, "resource");
    assertThat(DavXml.required(missingResource, DavXml.DAV, "href").getTextContent())
        .endsWith(inaccessibleHome);
    assertThat(
            DavXml.child(
                DavXml.required(missingResource, DavXml.DAV, "privilege"), DavXml.DAV, "read"))
        .isNotNull();
    String read = aclGrant(bob, "<D:privilege><D:read/></D:privilege>");
    var count = send("ACL", calendar, aclBody(read.repeat(1001)));
    assertThat(count.statusCode()).isEqualTo(403);
    assertThat(xml(count).getElementsByTagNameNS(DavXml.DAV, "limited-number-of-aces").getLength())
        .isEqualTo(1);
    var duplicate = send("ACL", calendar, aclBody(read.repeat(2)));
    assertThat(duplicate.statusCode()).isEqualTo(403);
    assertThat(xml(duplicate).getElementsByTagNameNS(DavXml.DAV, "no-ace-conflict").getLength())
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_members", Integer.class))
        .isZero();
  }

  @Test
  void freeBusyGeneratesOneCompleteValidComponentForEmptyAndOccupiedCalendars() throws Exception {
    String calendar = create("generated-freebusy", "VEVENT");
    String request =
        "<C:free-busy-query xmlns:C='urn:ietf:params:xml:ns:caldav'><C:time-range start='20261027T000000Z' end='20261028T000000Z'/></C:free-busy-query>";
    for (boolean populated : List.of(false, true)) {
      if (populated) send("PUT", calendar + "one.ics", event("generated-freebusy", "One"));
      var response = send("REPORT", calendar, request, "Depth", "1");
      assertThat(response.statusCode()).isEqualTo(200);
      var parsed =
          new net.fortuna.ical4j.data.CalendarBuilder()
              .build(new java.io.StringReader(response.body()));
      assertThat(parsed.validate().hasErrors()).isFalse();
      assertThat(parsed.getComponents()).hasSize(1);
      var busy = parsed.getComponents().getFirst();
      assertThat(busy.getName()).isEqualTo("VFREEBUSY");
      assertThat(busy.getProperties("DTSTAMP")).hasSize(1);
      assertThat(busy.getProperties("UID")).hasSize(1);
      assertThat(busy.getProperties("DTSTART")).hasSize(1);
      assertThat(busy.getProperties("DTEND")).hasSize(1);
      assertThat(busy.getProperty("DTSTAMP").orElseThrow().getValue()).endsWith("Z");
      assertThat(busy.getProperties("FREEBUSY").isEmpty()).isEqualTo(!populated);
      if (populated)
        assertThat(response.body())
            .contains("FREEBUSY;FBTYPE=BUSY:20261027T100000Z/20261027T110000Z");
    }
  }

  @Test
  void freeBusyUsesCollectionTimezoneAndIgnoresOutOfContextRequestTimezone() throws Exception {
    String calendar = create("freebusy-floating-zone", "VEVENT");
    String zone =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Floating timezone test//EN\r\nBEGIN:VTIMEZONE\r\nTZID:Fixed\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0100\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nEND:VCALENDAR\r\n";
    String patch =
        "<D:propertyupdate xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav'><D:set><D:prop><C:calendar-timezone><![CDATA["
            + zone
            + "]]></C:calendar-timezone></D:prop></D:set></D:propertyupdate>";
    assertThat(send("PROPPATCH", calendar, patch).statusCode()).isEqualTo(207);
    String floating =
        event("freebusy-floating-zone", "Floating")
            .replace("20261027T100000Z", "20261027T100000")
            .replace("20261027T110000Z", "20261027T110000");
    send("PUT", calendar + "floating.ics", floating);
    String report =
        "<C:free-busy-query xmlns:C='urn:ietf:params:xml:ns:caldav'><C:time-range start='20261027T000000Z' end='20261028T000000Z'/></C:free-busy-query>";
    String extra =
        report.replace(
            "</C:free-busy-query>",
            "<C:timezone><![CDATA["
                + zone.replace("+0100", "+0300")
                + "]]></C:timezone></C:free-busy-query>");
    assertThat(send("REPORT", calendar, report, "Depth", "1").body())
        .contains("20261027T090000Z/20261027T100000Z");
    assertThat(send("REPORT", calendar, extra, "Depth", "1").body())
        .contains("20261027T090000Z/20261027T100000Z")
        .doesNotContain("20261027T070000Z");
  }

  @Test
  void namespaceAliasesRetainResourceIdentityAndCurrentSharedAuthorization() throws Exception {
    String calendar = create("alias+calendar", "VEVENT");
    String first = calendar + "a+b-%E9%9B%AA.ics";
    String alias = calendar.replace("+", "%2B") + "a%2Bb-%e9%9b%aa.ics";
    send("PUT", first, event("alias-identity", "Identity"));
    var original = send("GET", first, "");
    var alternate = send("GET", alias, "");
    assertThat(alternate.statusCode()).isEqualTo(200);
    assertThat(alternate.body()).isEqualTo(original.body());
    assertThat(alternate.headers().firstValue("ETag"))
        .isEqualTo(original.headers().firstValue("ETag"));
    assertThat(
            send("ACL", calendar, aclBody(aclGrant(bob, "<D:privilege><D:read/></D:privilege>")))
                .statusCode())
        .isEqualTo(200);
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='alias+calendar'",
            UUID.class,
            alice);
    String shared = "/calendars/" + bob + "/" + id + "/a%2Bb-%E9%9B%AA.ics";
    assertThat(sendAs("GET", shared, "", bobAuth).body()).isEqualTo(original.body());
    var owner = send("PROPFIND", first, propRequest("<D:owner/>"), "Depth", "0");
    var sharedOwner = sendAs("PROPFIND", shared, propRequest("<D:owner/>"), bobAuth, "Depth", "0");
    assertThat(text(sharedOwner, DavXml.DAV, "owner")).isEqualTo(text(owner, DavXml.DAV, "owner"));
    assertThat(sendAs("PUT", shared, event("alias-identity", "Forbidden"), bobAuth).statusCode())
        .isEqualTo(403);
    assertThat(send("ACL", calendar, aclBody("")).statusCode()).isEqualTo(200);
    assertThat(sendAs("GET", shared, "", bobAuth).statusCode()).isIn(403, 404);
    assertThat(sendAs("OPTIONS", shared, "", bobAuth).statusCode()).isIn(403, 404);
  }

  @Test
  void legacyInvalidResourcesRemainRetrievableButCannotBypassValidationThroughCollectionCopy()
      throws Exception {
    String calendar = create("legacy-copy", "VEVENT");
    String invalid = event("legacy-copy", "Legacy").replace("DTSTART:20261027T100000Z\r\n", "");
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM collections WHERE owner_id=? AND name='legacy-copy'",
            UUID.class,
            alice);
    jdbc.update(
        "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) VALUES(?,?,?,?,?,1)",
        id,
        "legacy.ics",
        "legacy-copy",
        invalid.getBytes(StandardCharsets.UTF_8),
        "\"legacy\"");
    jdbc.update("UPDATE collections SET revision=1 WHERE id=?", id);
    assertThat(send("GET", calendar + "legacy.ics", "").body()).isEqualTo(invalid);
    int before =
        jdbc.queryForObject(
            "SELECT count(*) FROM collections WHERE owner_id=?", Integer.class, alice);
    for (String method : List.of("COPY", "MOVE")) {
      String target = home + "legacy-destination-" + method + "/";
      var result =
          send(method, calendar, "", "Destination", "https://calendar.example/team/dav" + target);
      assertThat(result.statusCode()).isEqualTo(403);
      assertThat(result.body()).contains("valid-calendar-data");
      assertThat(send("GET", target, "").statusCode()).isEqualTo(404);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM collections WHERE owner_id=?", Integer.class, alice))
          .isEqualTo(before);
      assertThat(send("GET", calendar + "legacy.ics", "").body()).isEqualTo(invalid);
    }
    assertThat(send("DELETE", calendar + "legacy.ics", "").statusCode()).isEqualTo(204);
  }

  @Test
  void originalFutureTimezoneFixtureProducesValidFreeBusyAndPreservesSourceBytes()
      throws Exception {
    String calendar = create("future-freebusy", "VEVENT");
    String body;
    try (var input = getClass().getResourceAsStream("/calendar/audit/future-zone.ics")) {
      assertThat(input).isNotNull();
      body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(send("PUT", calendar + "future.ics", body).statusCode()).isEqualTo(201);
    String request =
        "<C:free-busy-query xmlns:C='urn:ietf:params:xml:ns:caldav'><C:time-range start='20400401T000000Z' end='20400402T000000Z'/></C:free-busy-query>";
    var response = send("REPORT", calendar, request, "Depth", "1");
    assertThat(response.statusCode()).isEqualTo(200);
    var parsed =
        new net.fortuna.ical4j.data.CalendarBuilder()
            .build(new java.io.StringReader(response.body()));
    assertThat(parsed.validate().hasErrors()).isFalse();
    assertThat(parsed.getComponents()).hasSize(1);
    assertThat(parsed.getComponents().getFirst().getProperties("DTSTAMP")).hasSize(1);
    assertThat(response.body()).contains("20400401T100000Z/20400401T110000Z");
    assertThat(send("GET", calendar + "future.ics", "").body()).isEqualTo(body);
  }

  @Test
  void concurrentPropertyPatchesRetainBothValuesAndCompleteSyncState() throws Exception {
    String calendar = create("property-interleaving", "VEVENT"), path = calendar + "one.ics";
    String content = event("property-interleaving", "One");
    send("PUT", path, content);
    String token = text(send("REPORT", calendar, sync("", 10)), DavXml.DAV, "sync-token");
    var etag = send("GET", path, "").headers().firstValue("ETag").orElseThrow();
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          workers.submit(
              () ->
                  send(
                      "PROPPATCH",
                      path,
                      "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:audit'><D:set><D:prop><X:first>one</X:first></D:prop></D:set></D:propertyupdate>"));
      var b =
          workers.submit(
              () ->
                  send(
                      "PROPPATCH",
                      path,
                      "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:audit'><D:set><D:prop><X:second>two</X:second></D:prop></D:set></D:propertyupdate>"));
      assertThat(a.get().statusCode()).isEqualTo(207);
      assertThat(b.get().statusCode()).isEqualTo(207);
    }
    var properties =
        send(
            "PROPFIND",
            path,
            propRequest("<X:first xmlns:X='urn:audit'/><X:second xmlns:X='urn:audit'/>"),
            "Depth",
            "0");
    assertThat(propertyStatus(properties, "urn:audit", "first")).isEqualTo(200);
    assertThat(propertyStatus(properties, "urn:audit", "second")).isEqualTo(200);
    assertThat(text(properties, "urn:audit", "first")).isEqualTo("one");
    assertThat(text(properties, "urn:audit", "second")).isEqualTo("two");
    String request =
        sync(token, 10)
            .replace(
                "<D:getetag/>",
                "<D:getetag/><X:first xmlns:X='urn:audit'/><X:second xmlns:X='urn:audit'/>");
    var changes = send("REPORT", calendar, request);
    assertThat(principalReportHrefs(changes))
        .containsExactly("https://calendar.example/team/dav" + path);
    assertThat(text(changes, "urn:audit", "first")).isEqualTo("one");
    assertThat(text(changes, "urn:audit", "second")).isEqualTo("two");
    var original = send("GET", path, "");
    assertThat(original.body()).isEqualTo(content);
    assertThat(original.headers().firstValue("ETag").orElseThrow()).isEqualTo(etag);
  }

  @Test
  void oversizedCalendarPutReportsAdvertisedSizePreconditionWithoutChangingState()
      throws Exception {
    String calendar = create("size-precondition", "VEVENT"), path = calendar + "one.ics";
    String content = event("size-precondition", "Original");
    var created = send("PUT", path, content);
    var maximum = send("PROPFIND", calendar, propRequest("<C:max-resource-size/>"), "Depth", "0");
    assertThat(text(maximum, DavXml.CAL, "max-resource-size"))
        .isEqualTo(Integer.toString(org.busystack.caldav.calendar.CalendarContent.MAX_BYTES));
    String template =
        event("size-at-limit", "At limit").replace("END:VEVENT\r\n", "X-BULK:\r\nEND:VEVENT\r\n");
    String atLimit =
        template.replace(
            "X-BULK:\r\n",
            "X-BULK:"
                + "a"
                    .repeat(
                        org.busystack.caldav.calendar.CalendarContent.MAX_BYTES
                            - template.getBytes(StandardCharsets.UTF_8).length)
                + "\r\n");
    assertThat(atLimit.getBytes(StandardCharsets.UTF_8))
        .hasSize(org.busystack.caldav.calendar.CalendarContent.MAX_BYTES);
    assertThat(
            send("PUT", calendar + "limit.ics", atLimit, "Content-Type", "text/calendar")
                .statusCode())
        .isEqualTo(201);
    assertThat(send("GET", calendar + "limit.ics", "").body()).isEqualTo(atLimit);
    assertThat(
            send(
                    "PUT",
                    calendar + "limit.ics",
                    atLimit.replace("X-BULK:a", "X-BULK:"),
                    "Content-Type",
                    "text/calendar")
                .statusCode())
        .isEqualTo(204);
    String before =
        text(
            send("PROPFIND", calendar, propRequest("<D:sync-token/>"), "Depth", "0"),
            DavXml.DAV,
            "sync-token");
    for (String target : List.of(path, calendar + "missing.ics")) {
      var response =
          send(
              "PUT",
              target,
              "x".repeat(org.busystack.caldav.calendar.CalendarContent.MAX_BYTES + 1),
              "Content-Type",
              "text/calendar");
      assertThat(response.statusCode()).isEqualTo(413);
      assertThat(xml(response).getElementsByTagNameNS(DavXml.CAL, "max-resource-size").getLength())
          .isEqualTo(1);
    }
    assertThat(send("GET", path, "").body()).isEqualTo(content);
    assertThat(send("GET", path, "").headers().firstValue("ETag"))
        .isEqualTo(created.headers().firstValue("ETag"));
    assertThat(send("GET", calendar + "missing.ics", "").statusCode()).isEqualTo(404);
    assertThat(
            text(
                send("PROPFIND", calendar, propRequest("<D:sync-token/>"), "Depth", "0"),
                DavXml.DAV,
                "sync-token"))
        .isEqualTo(before);
  }

  @Test
  void semanticListTextQueriesReturnCorrectHrefsOverHttpForEscapesAndCollations() throws Exception {
    String calendar = create("semantic-text-http", "VEVENT");
    String original;
    String originalQuery;
    try (var input = getClass().getResourceAsStream("/calendar/audit/valid-text-list.ics");
        var request = getClass().getResourceAsStream("/calendar/audit/categories-query.xml")) {
      assertThat(input).isNotNull();
      assertThat(request).isNotNull();
      original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      originalQuery = new String(request.readAllBytes(), StandardCharsets.UTF_8);
    }
    String originalPath = calendar + "original.ics";
    assertThat(send("PUT", originalPath, original, "Content-Type", "text/calendar").statusCode())
        .isEqualTo(201);
    assertThat(send("GET", originalPath, "").body()).isEqualTo(original);
    var originalResult = send("REPORT", calendar, originalQuery, "Depth", "1");
    assertThat(originalResult.statusCode()).isEqualTo(207);
    assertThat(principalReportHrefs(originalResult))
        .containsExactly("https://calendar.example/team/dav" + originalPath);

    // Add typed-value neighbors after checking the original counterexample unchanged.
    // Columns are resource name, iCalendar encoded TEXT, and decoded semantic TEXT.
    String[][] cases = {
      {"original", "alpha\\;beta", "alpha;beta"},
      {"comma", "alpha\\,beta", "alpha,beta"},
      {"backslash", "alpha\\\\beta", "alpha\\beta"},
      {"newline", "alpha\\nbeta", "alpha\nbeta"}
    };
    java.util.Set<String> all = new java.util.HashSet<>();
    for (String[] value : cases) {
      String resource = calendar + value[0] + ".ics";
      String body =
          original
              .replace("UID:valid-text-list", "UID:semantic-http-" + value[0])
              .replace("CATEGORIES:alpha\\;beta,other", "CATEGORIES:" + value[1] + ",other")
              .replace(
                  "END:VEVENT\r\n",
                  "RESOURCES:" + value[1] + ",other\r\nSUMMARY:" + value[1] + "\r\nEND:VEVENT\r\n");
      // The original resource's UID remains immutable during its corrective replacement.
      if (value[0].equals("original"))
        body = body.replace("UID:semantic-http-original", "UID:valid-text-list");
      assertThat(send("PUT", resource, body, "Content-Type", "text/calendar").statusCode())
          .isEqualTo(value[0].equals("original") ? 204 : 201);
      assertThat(send("GET", resource, "").body()).isEqualTo(body);
      all.add("https://calendar.example/team/dav" + resource);
    }
    String repeatedPath = calendar + "repeated.ics";
    String repeated =
        original
            .replace("UID:valid-text-list", "UID:semantic-http-repeated")
            .replace(
                "CATEGORIES:alpha\\;beta,other",
                "CATEGORIES:RepeatedFirst\r\nCATEGORIES:RepeatedSecond")
            .replace(
                "END:VEVENT\r\n",
                "RESOURCES:RepeatedFirst\r\nRESOURCES:RepeatedSecond\r\nSUMMARY:RepeatedSummary\r\nEND:VEVENT\r\n");
    assertThat(send("PUT", repeatedPath, repeated, "Content-Type", "text/calendar").statusCode())
        .isEqualTo(201);
    assertThat(send("GET", repeatedPath, "").body()).isEqualTo(repeated);
    String repeatedHref = "https://calendar.example/team/dav" + repeatedPath;
    all.add(repeatedHref);

    for (String property : List.of("CATEGORIES", "RESOURCES", "SUMMARY")) {
      for (String collation : List.of("i;octet", "i;ascii-casemap")) {
        for (String[] value : cases) {
          String expected = "https://calendar.example/team/dav" + calendar + value[0] + ".ics";
          var positive =
              send(
                  "REPORT",
                  calendar,
                  semanticTextQuery(property, value[2], collation, false),
                  "Depth",
                  "1");
          assertThat(positive.statusCode()).isEqualTo(207);
          assertThat(principalReportHrefs(positive)).containsExactly(expected);
          var negative =
              send(
                  "REPORT",
                  calendar,
                  semanticTextQuery(property, value[2], collation, true),
                  "Depth",
                  "1");
          assertThat(negative.statusCode()).isEqualTo(207);
          var otherResources = new java.util.HashSet<>(all);
          otherResources.remove(expected);
          assertThat(principalReportHrefs(negative))
              .containsExactlyInAnyOrderElementsOf(otherResources);
        }
        var caseMatch =
            send(
                "REPORT",
                calendar,
                semanticTextQuery(property, "ALPHA;BETA", collation, false),
                "Depth",
                "1");
        assertThat(caseMatch.statusCode()).isEqualTo(207);
        if (collation.equals("i;ascii-casemap"))
          assertThat(principalReportHrefs(caseMatch))
              .containsExactly("https://calendar.example/team/dav" + originalPath);
        else assertThat(principalReportHrefs(caseMatch)).isEmpty();
        if (!property.equals("SUMMARY")) {
          for (String value : List.of("RepeatedFirst", "RepeatedSecond")) {
            var instance =
                send(
                    "REPORT",
                    calendar,
                    semanticTextQuery(property, value, collation, false),
                    "Depth",
                    "1");
            assertThat(instance.statusCode()).isEqualTo(207);
            assertThat(principalReportHrefs(instance)).containsExactly(repeatedHref);
          }
        }
      }
    }
  }

  @Test
  void reportsRestoreFoldedUtf8CharactersWithoutChangingAuthoritativeBytes() throws Exception {
    String calendar = create("split-utf8", "VEVENT"), path = calendar + "folded.ics";
    String summary = "before€ middle🙂after", original = event("split-utf8", summary);
    String[] parts = original.split("€|🙂", -1);
    byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
    byte[] smile = "🙂".getBytes(StandardCharsets.UTF_8);
    var folded = new java.io.ByteArrayOutputStream();
    folded.writeBytes(parts[0].getBytes(StandardCharsets.UTF_8));
    folded.write(euro, 0, 1);
    folded.writeBytes("\r\n ".getBytes(StandardCharsets.US_ASCII));
    folded.write(euro, 1, euro.length - 1);
    folded.writeBytes(parts[1].getBytes(StandardCharsets.UTF_8));
    folded.write(smile, 0, 2);
    folded.writeBytes("\r\n\t".getBytes(StandardCharsets.US_ASCII));
    folded.write(smile, 2, smile.length - 2);
    folded.writeBytes(parts[2].getBytes(StandardCharsets.UTF_8));
    byte[] raw = folded.toByteArray();
    var put =
        http.send(
            request(path, aliceAuth)
                .header("Content-Type", "text/calendar; charset=utf-8")
                .header("If-None-Match", "*")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(raw))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(put.statusCode()).isEqualTo(201);
    String etag = put.headers().firstValue("ETag").orElseThrow();
    var before =
        http.send(request(path, aliceAuth).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertThat(before.statusCode()).isEqualTo(200);
    assertThat(before.body()).isEqualTo(raw);
    assertThat(before.headers().firstValue("ETag")).hasValue(etag);
    String href = "https://calendar.example/team/dav" + path;
    String multiget =
        "<C:calendar-multiget xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:prop><D:getetag/><C:calendar-data/></D:prop><D:href>"
            + href
            + "</D:href></C:calendar-multiget>";
    String synchronization =
        sync("", 10)
            .replace(
                "<D:getetag/>",
                "<D:getetag/><C:calendar-data xmlns:C='urn:ietf:params:xml:ns:caldav'/>");
    var queried =
        send(
            "REPORT",
            calendar,
            semanticTextQuery("SUMMARY", "€ middle🙂", "i;octet", false),
            "Depth",
            "1");
    var fetched = send("REPORT", calendar, multiget);
    var synchronizedData = send("REPORT", calendar, synchronization, "Depth", "0");
    for (var report : List.of(queried, fetched, synchronizedData)) {
      assertThat(report.statusCode()).isEqualTo(207);
      assertThat(principalReportHrefs(report)).containsExactly(href);
      assertThat(text(report, DavXml.DAV, "getetag")).isEqualTo(etag);
      String content = text(report, DavXml.CAL, "calendar-data");
      assertThat(content).isEqualTo(original).doesNotContain("\uFFFD");
      var parsed =
          org.busystack.caldav.calendar.CalendarContent.parse(
              content.getBytes(StandardCharsets.UTF_8), "VEVENT");
      var event = parsed.calendar().getComponents().getFirst();
      assertThat(event.getRequiredProperty(net.fortuna.ical4j.model.Property.SUMMARY).getValue())
          .isEqualTo(summary);
      assertThat(event.getRequiredProperty("X-CLIENT-PROPERTY").getValue())
          .isEqualTo("preserve me");
    }
    String selected =
        "<C:calendar-data><C:comp name='VCALENDAR'><C:comp name='VEVENT'><C:prop name='SUMMARY'/></C:comp></C:comp></C:calendar-data>";
    var projected = send("REPORT", calendar, multiget.replace("<C:calendar-data/>", selected));
    assertThat(projected.statusCode()).isEqualTo(207);
    assertThat(principalReportHrefs(projected)).containsExactly(href);
    assertThat(text(projected, DavXml.CAL, "calendar-data"))
        .contains("SUMMARY:" + summary)
        .doesNotContain("\uFFFD", "UID:", "DTSTART:", "X-CLIENT-PROPERTY");
    var after =
        http.send(request(path, aliceAuth).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertThat(after.statusCode()).isEqualTo(200);
    assertThat(after.body()).isEqualTo(raw);
    assertThat(after.headers().firstValue("ETag")).hasValue(etag);
  }

  private String semanticTextQuery(
      String property, String value, String collation, boolean negate) {
    String text = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    return query(
        "<C:comp-filter name='VEVENT'><C:prop-filter name='"
            + property
            + "'><C:text-match collation='"
            + collation
            + "' negate-condition='"
            + (negate ? "yes" : "no")
            + "'>"
            + text
            + "</C:text-match></C:prop-filter></C:comp-filter>");
  }

  private static String capturedObservanceFixture(String zone) throws Exception {
    try (var input =
        DavHttpIT.class.getResourceAsStream(
            "/calendar/observance-until/thunderbird159-" + zone + ".ics")) {
      return new String(
          java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void repairedPutOmitsValidatorsButAllReadPathsUseCorrectedAuthoritativeBytes() throws Exception {
    for (String zone : List.of("vancouver", "berlin")) {
      String calendar = create("observance-http-" + zone, "VEVENT"), path = calendar + "client.ics";
      String input = capturedObservanceFixture(zone);
      String before = currentCollectionToken(calendar);
      var put = send("PUT", path, input, "Content-Type", "text/calendar", "If-None-Match", "*");
      assertThat(put.statusCode()).isEqualTo(201);
      assertThat(put.headers().firstValue("ETag")).isEmpty();
      assertThat(put.headers().firstValue("Last-Modified")).isEmpty();
      var get = send("GET", path, "");
      String stored = get.body(), etag = get.headers().firstValue("ETag").orElseThrow();
      assertThat(stored).isNotEqualTo(input);
      assertThat(etag).startsWith("\"").doesNotStartWith("W/");
      org.busystack.caldav.calendar.CalendarContent.parse(
          stored.getBytes(StandardCharsets.UTF_8), "VEVENT");
      assertThat(
              text(
                  send("PROPFIND", path, propRequest("<D:getetag/>"), "Depth", "0"),
                  DavXml.DAV,
                  "getetag"))
          .isEqualTo(etag);
      String multiget =
          "<C:calendar-multiget xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:'><D:prop><C:calendar-data/><D:getetag/></D:prop><D:href>https://calendar.example/team/dav"
              + path
              + "</D:href></C:calendar-multiget>";
      var multi = send("REPORT", calendar, multiget);
      assertThat(multi.statusCode()).isEqualTo(207);
      assertThat(text(multi, DavXml.CAL, "calendar-data")).isEqualTo(stored);
      var sync =
          send(
              "REPORT",
              calendar,
              sync(before, 10)
                  .replace(
                      "<D:getetag/>",
                      "<D:getetag/><C:calendar-data xmlns:C='urn:ietf:params:xml:ns:caldav'/>"),
              "Depth",
              "0");
      assertThat(sync.statusCode()).isEqualTo(207);
      assertThat(text(sync, DavXml.CAL, "calendar-data")).isEqualTo(stored);
      // These instants follow the captured rules: Vancouver's last 2026 onset leaves UTC-07;
      // Berlin's winter 2026 offset is UTC+01. No host future-DST assumption is involved.
      String start = zone.equals("vancouver") ? "20261109T170000Z" : "20261109T090000Z";
      String end = zone.equals("vancouver") ? "20261109T180000Z" : "20261109T100000Z";
      String filter =
          "<C:comp-filter name='VEVENT'><C:time-range start='"
              + start
              + "' end='"
              + end
              + "'/></C:comp-filter>";
      var query =
          send(
              "REPORT",
              calendar,
              query(filter)
                  .replace(
                      "<C:calendar-data/>",
                      "<C:calendar-data><C:expand start='20261109T000000Z' end='20261110T000000Z'/></C:calendar-data>"),
              "Depth",
              "1");
      assertThat(query.statusCode()).isEqualTo(207);
      assertThat(principalReportHrefs(query))
          .containsExactly("https://calendar.example/team/dav" + path);
      assertThat(text(query, DavXml.CAL, "calendar-data"))
          .contains("DTSTART:" + start, "DTEND:" + end);
      var busy =
          send(
              "REPORT",
              calendar,
              "<C:free-busy-query xmlns:C='urn:ietf:params:xml:ns:caldav'><C:time-range start='20261109T000000Z' end='20261110T000000Z'/></C:free-busy-query>",
              "Depth",
              "1");
      assertThat(busy.statusCode()).isEqualTo(200);
      assertThat(busy.body()).contains(start + "/" + end);
      // Transformation is relative to this request, including repeated original submissions.
      var repeated = send("PUT", path, input, "If-Match", etag);
      assertThat(repeated.statusCode()).isEqualTo(204);
      assertThat(repeated.headers().firstValue("ETag")).isEmpty();
      assertThat(repeated.headers().firstValue("Last-Modified")).isEmpty();
      assertThat(send("GET", path, "").body()).isEqualTo(stored);
      assertThat(send("GET", path, "").headers().firstValue("ETag")).contains(etag);
      var valid = send("PUT", path, stored, "If-Match", etag);
      assertThat(valid.statusCode()).isEqualTo(204);
      assertThat(valid.headers().firstValue("ETag")).contains(etag);
      String edited =
          stored
              .replace("T100000\r\nDTEND", "T120000\r\nDTEND")
              .replace("T110000\r\nTRANSP", "T130000\r\nTRANSP");
      assertThat(send("PUT", path, edited, "If-Match", etag).statusCode()).isEqualTo(204);
      assertThat(send("GET", path, "").body()).isEqualTo(edited);
    }
  }

  @Test
  void repairFailuresConflictsAndConcurrentWritesPreserveAtomicHistoryAndSync() throws Exception {
    String calendar = create("observance-atomic", "VEVENT"), path = calendar + "one.ics";
    String original = capturedObservanceFixture("vancouver");
    var accepted = send("PUT", path, original);
    assertThat(accepted.statusCode()).isEqualTo(201);
    var stored = send("GET", path, "");
    String etag = stored.headers().firstValue("ETag").orElseThrow();
    UUID collection =
        jdbc.queryForObject(
            "SELECT collection_id FROM dav_bindings WHERE home_id=? AND path=?",
            UUID.class,
            alice,
            "observance-atomic");
    var state = admissionHistoryState(collection);
    String token = currentCollectionToken(calendar);
    for (String invalid :
        List.of(
            original.replace(
                "TZOFFSETFROM:-080000", "TZOFFSETFROM:-080000\r\nTZOFFSETFROM:-070000"),
            original.replace("UNTIL=19610924T020000", "UNTIL=19610931T020000"),
            original.replace("BYDAY=-1SU", "BYDAY=0SU"),
            original.replace("FREQ=YEARLY", "FREQ=YEARLY;COUNT=1"),
            original.replace("FREQ=YEARLY", "FREQ=YEARLY;FREQ=YEARLY"),
            original.replaceFirst("BEGIN:VCALENDAR", "XBEGIN:VCALENDAR"),
            original.replace("DTEND;TZID=America/Vancouver:20261109T110000", "DURATION:P1W2D"))) {
      for (String target : List.of(path, calendar + "rejected.ics")) {
        var rejected = send("PUT", target, invalid, "If-Match", target.equals(path) ? etag : "*");
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(
                xml(rejected).getElementsByTagNameNS(DavXml.CAL, "valid-calendar-data").getLength())
            .isEqualTo(1);
        assertThat(admissionHistoryState(collection)).isEqualTo(state);
        assertThat(currentCollectionToken(calendar)).isEqualTo(token);
        assertThat(send("GET", path, "").body()).isEqualTo(stored.body());
        assertThat(send("GET", path, "").headers().firstValue("ETag")).contains(etag);
        assertThat(send("GET", calendar + "rejected.ics", "").statusCode()).isEqualTo(404);
      }
    }
    assertThat(send("PUT", path, original, "If-Match", "\"stale\"").statusCode()).isEqualTo(412);
    assertThat(send("PUT", path, original, "If-None-Match", "*").statusCode()).isEqualTo(412);
    assertThat(send("PUT", calendar + "duplicate.ics", original, "If-None-Match", "*").statusCode())
        .isEqualTo(403);
    assertThat(admissionHistoryState(collection)).isEqualTo(state);
    assertThat(currentCollectionToken(calendar)).isEqualTo(token);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          workers.submit(
              () ->
                  send(
                      "PUT",
                      path,
                      original.replace("SUMMARY:TB159", "SUMMARY:First TB159"),
                      "If-Match",
                      etag));
      var second =
          workers.submit(
              () ->
                  send(
                      "PUT",
                      path,
                      original.replace("SUMMARY:TB159", "SUMMARY:Second TB159"),
                      "If-Match",
                      etag));
      assertThat(List.of(first.get().statusCode(), second.get().statusCode()))
          .containsExactlyInAnyOrder(204, 412);
    }
    assertThat(((Number) admissionHistoryState(collection).get("revision")).longValue())
        .isEqualTo(((Number) state.get("revision")).longValue() + 1);
  }
}
