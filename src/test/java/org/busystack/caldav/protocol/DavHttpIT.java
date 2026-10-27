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
    assertThat(send("REPORT", calendar, sync("", 100), "Depth", "1").statusCode()).isEqualTo(400);
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
    assertThat(send("REPORT", calendar, sync(token, 1)).statusCode()).isEqualTo(507);
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
    assertThat(xml(resource).getElementsByTagNameNS("DAV:", "write-content").getLength())
        .isEqualTo(1);
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
    String token = currentCollectionToken(calendar);
    assertThat(send("REPORT", calendar, sync("", 100)).statusCode()).isEqualTo(507);
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
    for (String unsupported : List.of("0", "10001", "9".repeat(1000)))
      assertThat(
              send(
                      "REPORT",
                      calendar,
                      sync("", 1)
                          .replace(
                              "<D:nresults>1</D:nresults>",
                              "<D:nresults>" + unsupported + "</D:nresults>"))
                  .statusCode())
          .isEqualTo(507);
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
    var limited = send("REPORT", calendar, report);
    assertThat(limited.statusCode()).isEqualTo(507);
    assertThat(limited.body())
        .contains("number-of-matches-within-limits")
        .doesNotContain("multistatus", "calendar-data");
    assertThat(send("GET", resource, "").body()).isEqualTo(content);
  }
}
