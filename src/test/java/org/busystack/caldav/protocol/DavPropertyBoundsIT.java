package org.busystack.caldav.protocol;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
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
@org.springframework.context.annotation.Import(DavPropertyBoundsIT.AllocationProbe.class)
class DavPropertyBoundsIT {
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
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JdbcTemplate jdbc;
  @Autowired org.busystack.caldav.storage.DavStore store;
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

  @Test
  void inheritedLanguageIsStoredWithMixedPropertyContentAcrossTransfer() throws Exception {
    String calendar = home + "languages/";
    assertThat(
            send(
                    "MKCALENDAR",
                    calendar,
                    "<C:mkcalendar xmlns:C='urn:ietf:params:xml:ns:caldav' xmlns:D='DAV:' xmlns:X='urn:lang' xml:lang='fr'><D:set><D:prop><X:label> before <X:em xml:lang='ja'>雪</X:em> after </X:label></D:prop></D:set></C:mkcalendar>")
                .statusCode())
        .isEqualTo(201);
    String selection =
        "<D:propfind xmlns:D='DAV:' xmlns:X='urn:lang'><D:prop><X:label/></D:prop></D:propfind>";
    var read = send("PROPFIND", calendar, selection, "Depth", "0");
    assertThat(read.statusCode()).isEqualTo(207);
    var root = DavXml.parse(read.body().getBytes(StandardCharsets.UTF_8));
    var label = (Element) root.getElementsByTagNameNS("urn:lang", "label").item(0);
    assertThat(label.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")).isEqualTo("fr");
    assertThat(label.getTextContent()).isEqualTo(" before 雪 after ");
    assertThat(
            ((Element) label.getElementsByTagNameNS("urn:lang", "em").item(0))
                .getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
        .isEqualTo("ja");
    String[][] cases = {
      {"xml:lang='fr'", "", "", "", "fr"},
      {"xml:lang='fr'", "xml:lang='de'", "", "", "de"},
      {"xml:lang='fr'", "xml:lang='de'", "xml:lang='es'", "", "es"},
      {"xml:lang='fr'", "xml:lang='de'", "xml:lang='es'", "xml:lang='it'", "it"},
      {"xml:lang='fr'", "xml:lang='de'", "xml:lang='es'", "xml:lang=''", ""}
    };
    for (String[] item : cases) {
      assertThat(
              send(
                      "PROPPATCH",
                      calendar,
                      "<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:lang' "
                          + item[0]
                          + "><D:set "
                          + item[1]
                          + "><D:prop "
                          + item[2]
                          + "><X:label "
                          + item[3]
                          + ">  mixed <X:em xml:lang='ja'>text</X:em>  </X:label></D:prop></D:set></D:propertyupdate>")
                  .statusCode())
          .isEqualTo(207);
      var reread =
          DavXml.parse(
              send("PROPFIND", calendar, selection, "Depth", "0")
                  .body()
                  .getBytes(StandardCharsets.UTF_8));
      var value = (Element) reread.getElementsByTagNameNS("urn:lang", "label").item(0);
      assertThat(value.hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")).isTrue();
      assertThat(value.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
          .isEqualTo(item[4]);
      assertThat(value.getTextContent()).isEqualTo("  mixed text  ");
      assertThat(
              ((Element) value.getElementsByTagNameNS("urn:lang", "em").item(0))
                  .getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
          .isEqualTo("ja");
    }
    assertThat(
            send(
                    "COPY",
                    calendar,
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + home + "copy/")
                .statusCode())
        .isEqualTo(201);
    assertThat(
            send(
                    "MOVE",
                    home + "copy/",
                    "",
                    "Destination",
                    "https://calendar.example/team/dav" + home + "moved/")
                .statusCode())
        .isEqualTo(201);
    var transferred =
        DavXml.parse(
            send("PROPFIND", home + "moved/", selection, "Depth", "0")
                .body()
                .getBytes(StandardCharsets.UTF_8));
    var value = (Element) transferred.getElementsByTagNameNS("urn:lang", "label").item(0);
    assertThat(value.hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")).isTrue();
    assertThat(value.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")).isEmpty();
  }

  @Test
  void repeatedValidUpdatesCannotAccumulateUnboundedProperties() {
    store.create(alice, alice, "bounded", "Bounded", "VEVENT", java.util.Map.of());
    int rejected = 0;
    for (int i = 0; i < 32; i++) {
      var name = new javax.xml.namespace.QName("urn:bounds", "p" + i);
      String xml = "<X:p" + i + " xmlns:X='urn:bounds'>" + "x".repeat(60000) + "</X:p" + i + ">";
      try {
        store.patch(alice, alice, "bounded", null, java.util.Map.of(name, xml), java.util.Set.of());
      } catch (org.busystack.caldav.storage.StoreException ex) {
        assertThat(ex.kind()).isEqualTo(org.busystack.caldav.storage.StoreException.Kind.LIMIT);
        rejected++;
      }
    }
    assertThat(rejected).isGreaterThan(0);
  }

  private java.util.List<String> recordedSql() {
    return org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
        .filter(
            invocation ->
                invocation.getArguments().length > 0 && invocation.getArgument(0) instanceof String)
        .map(invocation -> (String) invocation.getArgument(0))
        .toList();
  }

  @Test
  void selectiveAndNameOnlyRequestsAvoidUnrelatedValuesAndCalendarBodies() throws Exception {
    var entry = store.create(alice, alice, "legacy", "Legacy", "VEVENT", java.util.Map.of());
    UUID id = entry.collection().id();
    var small = new javax.xml.namespace.QName("urn:bounds", "small");
    store.patch(
        alice,
        alice,
        "legacy",
        null,
        java.util.Map.of(small, "<X:small xmlns:X='urn:bounds'>ok</X:small>"),
        java.util.Set.of());
    // Model data admitted by V1-V9 in this disposable database. No production bypass is exposed.
    jdbc.execute(
        "ALTER TABLE dav_collection_properties DISABLE TRIGGER dav_collection_property_budget");
    try {
      jdbc.update(
          "INSERT INTO dav_collection_properties(collection_id,namespace,name,xml) SELECT ?, 'urn:bounds','large'||i,'<X:large'||i||' xmlns:X=\"urn:bounds\">'||repeat('x',60000)||'</X:large'||i||'>' FROM generate_series(1,24) i",
          id);
    } finally {
      jdbc.execute(
          "ALTER TABLE dav_collection_properties ENABLE TRIGGER dav_collection_property_budget");
    }
    org.mockito.Mockito.clearInvocations(jdbc);
    var selected =
        send(
            "PROPFIND",
            home + "legacy/",
            "<D:propfind xmlns:D='DAV:' xmlns:X='urn:bounds'><D:prop><X:small/></D:prop></D:propfind>",
            "Depth",
            "0");
    assertThat(selected.statusCode()).isEqualTo(207);
    assertThat(selected.body()).contains(">ok</").doesNotContain("large1");
    var selectedSql = recordedSql();
    assertThat(selectedSql.stream().filter(sql -> sql.startsWith("SELECT namespace,name,xml")))
        .allMatch(sql -> sql.contains("namespace=? AND name=?"));
    assertThat(selectedSql).noneMatch(sql -> sql.contains("SELECT * FROM calendar_resources"));
    org.mockito.Mockito.clearInvocations(jdbc);
    var names =
        send(
            "PROPFIND",
            home + "legacy/",
            "<D:propfind xmlns:D='DAV:'><D:propname/></D:propfind>",
            "Depth",
            "0");
    assertThat(names.statusCode()).isEqualTo(207);
    assertThat(names.body()).contains("large24").doesNotContain("xxxx");
    assertThat(recordedSql())
        .noneMatch(
            sql ->
                sql.startsWith("SELECT namespace,name,xml")
                    || sql.contains("SELECT * FROM calendar_resources"));
    org.mockito.Mockito.clearInvocations(jdbc);
    assertThat(
            send(
                    "PROPFIND",
                    home + "legacy/",
                    "<D:propfind xmlns:D='DAV:'><D:allprop/></D:propfind>",
                    "Depth",
                    "0")
                .statusCode())
        .isEqualTo(507);
    assertThat(recordedSql()).noneMatch(sql -> sql.startsWith("SELECT namespace,name,xml"));
    store.patch(
        alice,
        alice,
        "legacy",
        null,
        java.util.Map.of(),
        java.util.Set.of(new javax.xml.namespace.QName("urn:bounds", "large24")));
    assertThat(
            send(
                    "PROPFIND",
                    home + "legacy/",
                    "<D:propfind xmlns:D='DAV:' xmlns:X='urn:bounds'><D:prop><X:small/></D:prop></D:propfind>",
                    "Depth",
                    "0")
                .statusCode())
        .isEqualTo(207);
  }

  @Test
  void concurrentPropertyAdmissionRemainsAtomicAndSmallRequestsStayUsable() throws Exception {
    store.create(alice, alice, "concurrent", "Concurrent", "VEVENT", java.util.Map.of());
    for (int i = 0; i < 15; i++) {
      String local = "seed" + i;
      store.patch(
          alice,
          alice,
          "concurrent",
          null,
          java.util.Map.of(
              new javax.xml.namespace.QName("urn:bounds", local),
              "<X:" + local + " xmlns:X='urn:bounds'>" + "x".repeat(60000) + "</X:" + local + ">"),
          java.util.Set.of());
    }
    var jobs = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Boolean>>();
    for (int i = 0; i < 4; i++) {
      String local = "race" + i;
      jobs.add(
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  store.patch(
                      alice,
                      alice,
                      "concurrent",
                      null,
                      java.util.Map.of(
                          new javax.xml.namespace.QName("urn:bounds", local),
                          "<X:"
                              + local
                              + " xmlns:X='urn:bounds'>"
                              + "x".repeat(60000)
                              + "</X:"
                              + local
                              + ">"),
                      java.util.Set.of());
                  return true;
                } catch (org.busystack.caldav.storage.StoreException ex) {
                  assertThat(ex.kind())
                      .isEqualTo(org.busystack.caldav.storage.StoreException.Kind.LIMIT);
                  return false;
                }
              }));
    }
    assertThat(jobs.stream().filter(java.util.concurrent.CompletableFuture::join).count())
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(octet_length(xml)) FROM dav_collection_properties WHERE collection_id=(SELECT collection_id FROM dav_bindings WHERE home_id=? AND path='concurrent')",
                Long.class,
                alice))
        .isLessThanOrEqualTo(org.busystack.caldav.storage.DavStore.MAX_PROPERTY_BYTES);
    var reads = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Integer>>();
    for (int i = 0; i < 4; i++)
      reads.add(
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return send(
                          "PROPFIND",
                          home + "concurrent/",
                          "<D:propfind xmlns:D='DAV:'><D:propname/></D:propfind>",
                          "Depth",
                          "0")
                      .statusCode();
                } catch (Exception ex) {
                  throw new RuntimeException(ex);
                }
              }));
    assertThat(reads.stream().map(java.util.concurrent.CompletableFuture::join).toList())
        .containsOnly(207);
  }

  @Test
  void multiResourcePropertyWorkIsBoundedUnderConcurrentOversizedReports() throws Exception {
    store.create(alice, alice, "property-stress", "Property stress", null, java.util.Map.of());
    for (int target = 0; target < 8; target++) {
      String path = "property-stress/calendar-" + target;
      store.create(alice, alice, path, "Calendar " + target, "VEVENT", java.util.Map.of());
      var values = new java.util.LinkedHashMap<javax.xml.namespace.QName, String>();
      values.put(
          new javax.xml.namespace.QName("urn:bounds", "small"),
          "<X:small xmlns:X='urn:bounds'>ok</X:small>");
      for (int property = 0; property < 15; property++) {
        String local = "large" + property;
        values.put(
            new javax.xml.namespace.QName("urn:bounds", local),
            "<X:" + local + " xmlns:X='urn:bounds'>" + "x".repeat(60000) + "</X:" + local + ">");
      }
      store.patch(alice, alice, path, null, values, java.util.Set.of());
    }
    String path = home + "property-stress/";
    String selection =
        "<D:propfind xmlns:D='DAV:' xmlns:X='urn:bounds'><D:prop><X:small/></D:prop></D:propfind>";
    assertThat(send("PROPFIND", path, selection, "Depth", "1").statusCode()).isEqualTo(207);
    org.mockito.Mockito.clearInvocations(jdbc);
    assertThat(
            send(
                    "PROPFIND",
                    path,
                    "<D:propfind xmlns:D='DAV:'><D:propname/></D:propfind>",
                    "Depth",
                    "1")
                .statusCode())
        .isEqualTo(207);
    assertThat(recordedSql())
        .noneMatch(
            sql ->
                sql.startsWith("SELECT namespace,name,xml")
                    || sql.contains("SELECT * FROM calendar_resources"));
    for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans())
      if (pool.getType() == java.lang.management.MemoryType.HEAP) pool.resetPeakUsage();
    long start = System.nanoTime();
    org.mockito.Mockito.clearInvocations(jdbc);
    var reports = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Integer>>();
    for (int i = 0; i < 4; i++)
      reports.add(
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return send(
                          "PROPFIND",
                          path,
                          "<D:propfind xmlns:D='DAV:'><D:allprop/></D:propfind>",
                          "Depth",
                          "1")
                      .statusCode();
                } catch (Exception ex) {
                  throw new RuntimeException(ex);
                }
              }));
    assertThat(reports.stream().map(java.util.concurrent.CompletableFuture::join).toList())
        .containsOnly(507);
    long peak =
        java.lang.management.ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
            .mapToLong(pool -> pool.getPeakUsage().getUsed())
            .sum();
    System.out.printf(
        "PROPERTY_REPORT_STRESS targets=8 accepted_xml_bytes=%d parallel_reports=4 result=507 duration_ms=%d max_heap_bytes=%d sum_heap_pool_peak_bytes=%d jdbc_calls=%d%n",
        jdbc.queryForObject(
            "SELECT sum(octet_length(xml)) FROM dav_collection_properties WHERE collection_id IN(SELECT collection_id FROM dav_bindings WHERE home_id=? AND path LIKE 'property-stress/%')",
            Long.class, alice),
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
        Runtime.getRuntime().maxMemory(),
        peak,
        recordedSql().size());
    assertThat(send("PROPFIND", path, selection, "Depth", "1").statusCode()).isEqualTo(207);
    assertThat(send("OPTIONS", path, "").statusCode()).isEqualTo(200);
  }

  @Test
  void databaseBudgetRejectsConcurrentStaleRepeatableReadSnapshots() {
    var entry = store.create(alice, alice, "rr-budget", "RR budget", "VEVENT", java.util.Map.of());
    UUID id = entry.collection().id();
    for (int i = 0; i < 15; i++) {
      String local = "seed" + i;
      store.patch(
          alice,
          alice,
          "rr-budget",
          null,
          java.util.Map.of(
              new javax.xml.namespace.QName("urn:bounds", local),
              "<X:" + local + " xmlns:X='urn:bounds'>" + "x".repeat(60000) + "</X:" + local + ">"),
          java.util.Set.of());
    }
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<String>>();
    for (int i = 0; i < 2; i++) {
      String prefix = "tx" + i;
      futures.add(
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                var tx =
                    new org.springframework.transaction.support.TransactionTemplate(transactions);
                tx.setIsolationLevel(
                    org.springframework.transaction.TransactionDefinition
                        .ISOLATION_REPEATABLE_READ);
                try {
                  tx.executeWithoutResult(
                      ignored -> {
                        jdbc.queryForObject(
                            "SELECT sum(octet_length(xml)) FROM dav_collection_properties WHERE collection_id=?",
                            Long.class,
                            id);
                        try {
                          barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception failure) {
                          throw new RuntimeException(failure);
                        }
                        jdbc.update(
                            "INSERT INTO dav_collection_properties(collection_id,namespace,name,xml) SELECT ?, 'urn:bounds',p,'<X:'||p||' xmlns:X=\"urn:bounds\">'||repeat('x',60000)||'</X:'||p||'>' FROM (SELECT ?||i AS p FROM generate_series(1,2) i) names",
                            id,
                            prefix);
                      });
                  return "committed";
                } catch (RuntimeException failure) {
                  for (Throwable cause = failure; cause != null; cause = cause.getCause())
                    if (cause instanceof java.sql.SQLException sql
                        && "40001".equals(sql.getSQLState())) return "40001";
                  throw failure;
                }
              }));
    }
    assertThat(futures.stream().map(java.util.concurrent.CompletableFuture::join).toList())
        .containsExactlyInAnyOrder("committed", "40001");
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(octet_length(xml)) FROM dav_collection_properties WHERE collection_id=?",
                Long.class,
                id))
        .isLessThanOrEqualTo(org.busystack.caldav.storage.DavStore.MAX_PROPERTY_BYTES);
  }

  @Test
  void collectionTimezoneChangesInvalidateContinuationAndStableTokens() throws Exception {
    store.create(alice, alice, "zone", "Zone", "VEVENT", java.util.Map.of());
    UUID id = store.resolve(alice, alice, "zone").collection().id();
    for (String uid : java.util.List.of("a", "b")) {
      String content =
          "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Independent property timezone fixture//EN\r\nBEGIN:VEVENT\r\nUID:"
              + uid
              + "\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261027T100000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
      store.putResource(
          alice,
          id,
          uid + ".ics",
          content.getBytes(StandardCharsets.UTF_8),
          org.busystack.caldav.storage.WriteCondition.create());
    }
    String path = home + "zone/";
    String first =
        "<D:sync-collection xmlns:D='DAV:'><D:sync-token/><D:sync-level>1</D:sync-level><D:limit><D:nresults>1</D:nresults></D:limit><D:prop><D:getetag/></D:prop></D:sync-collection>";
    var initial = send("REPORT", path, first, "Depth", "0");
    assertThat(initial.statusCode()).isEqualTo(207);
    String continuation =
        DavXml.parse(initial.body().getBytes(StandardCharsets.UTF_8))
            .getElementsByTagNameNS(DavXml.DAV, "sync-token")
            .item(0)
            .getTextContent();
    var tokenProperty =
        send(
            "PROPFIND",
            path,
            "<D:propfind xmlns:D='DAV:'><D:prop><D:sync-token/></D:prop></D:propfind>",
            "Depth",
            "0");
    assertThat(tokenProperty.statusCode()).isEqualTo(207);
    String stable =
        DavXml.parse(tokenProperty.body().getBytes(StandardCharsets.UTF_8))
            .getElementsByTagNameNS(DavXml.DAV, "sync-token")
            .item(0)
            .getTextContent();
    String zone =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Independent property timezone fixture//EN\r\nBEGIN:VTIMEZONE\r\nTZID:Custom/Fixed\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0200\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nEND:VCALENDAR\r\n";
    String update =
        "<D:propertyupdate xmlns:D='DAV:' xmlns:C='urn:ietf:params:xml:ns:caldav'><D:set><D:prop><C:calendar-timezone>"
            + zone
            + "</C:calendar-timezone></D:prop></D:set></D:propertyupdate>";
    assertThat(send("PROPPATCH", path, update).statusCode()).isEqualTo(207);
    for (String old : java.util.List.of(continuation, stable)) {
      var report =
          send(
              "REPORT",
              path,
              "<D:sync-collection xmlns:D='DAV:'><D:sync-token>"
                  + old
                  + "</D:sync-token><D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop></D:sync-collection>",
              "Depth",
              "0");
      assertThat(report.statusCode()).isEqualTo(403);
      assertThat(report.body()).contains("valid-sync-token");
    }
    assertThat(send("REPORT", path, first, "Depth", "0").statusCode()).isEqualTo(207);
  }

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class AllocationProbe {
    private static final java.util.concurrent.atomic.AtomicReference<
            java.util.concurrent.CompletableFuture<Long>>
        NEXT = new java.util.concurrent.atomic.AtomicReference<>();

    static java.util.concurrent.CompletableFuture<Long> next() {
      var result = new java.util.concurrent.CompletableFuture<Long>();
      if (!NEXT.compareAndSet(null, result))
        throw new IllegalStateException("An allocation measurement is already pending");
      return result;
    }

    @org.springframework.context.annotation.Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>
        requestAllocationProbe() {
      if (!(java.lang.management.ManagementFactory.getThreadMXBean()
              instanceof com.sun.management.ThreadMXBean metrics)
          || !metrics.isThreadAllocatedMemorySupported())
        throw new IllegalStateException("Required Temurin allocation measurement is unavailable");
      if (!metrics.isThreadAllocatedMemoryEnabled()) metrics.setThreadAllocatedMemoryEnabled(true);
      jakarta.servlet.Filter filter =
          (request, response, chain) -> {
            var pending = NEXT.getAndSet(null);
            long thread = Thread.currentThread().threadId();
            long before = pending == null ? 0 : metrics.getThreadAllocatedBytes(thread);
            try {
              chain.doFilter(request, response);
            } finally {
              if (pending != null)
                pending.complete(metrics.getThreadAllocatedBytes(thread) - before);
            }
          };
      var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
      registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
      return registration;
    }
  }

  @Test
  void smallExplicitPropertyAllocationsDoNotScaleWithUnrelatedStoredValues() throws Exception {
    store.create(alice, alice, "allocation", "Allocation", "VEVENT", java.util.Map.of());
    var values = new java.util.LinkedHashMap<javax.xml.namespace.QName, String>();
    values.put(
        new javax.xml.namespace.QName("urn:bounds", "small"),
        "<X:small xmlns:X='urn:bounds'>ok</X:small>");
    for (int i = 0; i < 15; i++) {
      String local = "large" + i;
      values.put(
          new javax.xml.namespace.QName("urn:bounds", local),
          "<X:" + local + " xmlns:X='urn:bounds'>" + "x".repeat(60000) + "</X:" + local + ">");
    }
    store.patch(alice, alice, "allocation", null, values, java.util.Set.of());
    String path = home + "allocation/";
    String selection =
        "<D:propfind xmlns:D='DAV:' xmlns:X='urn:bounds'><D:prop><X:small/></D:prop></D:propfind>";
    assertThat(send("PROPFIND", path, selection, "Depth", "0").statusCode()).isEqualTo(207);
    org.mockito.Mockito.clearInvocations(jdbc);
    var smallMeasurement = AllocationProbe.next();
    assertThat(send("PROPFIND", path, selection, "Depth", "0").statusCode()).isEqualTo(207);
    long small = smallMeasurement.get(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(recordedSql().stream().filter(sql -> sql.startsWith("SELECT namespace,name,xml")))
        .allMatch(sql -> sql.contains("namespace=? AND name=?"));
    var allMeasurement = AllocationProbe.next();
    assertThat(
            send(
                    "PROPFIND",
                    path,
                    "<D:propfind xmlns:D='DAV:'><D:allprop/></D:propfind>",
                    "Depth",
                    "0")
                .statusCode())
        .isEqualTo(207);
    long all = allMeasurement.get(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(small).isLessThan(4L * 1024 * 1024);
    assertThat(all).isGreaterThan(small + 1024 * 1024);
    System.out.printf(
        "PROPERTY_REQUEST_ALLOCATIONS stored_xml_bytes=%d selected_property_bytes=%d all_properties_bytes=%d max_heap_bytes=%d%n",
        values.values().stream()
            .mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length)
            .sum(),
        small,
        all,
        Runtime.getRuntime().maxMemory());
  }
}
