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
}
