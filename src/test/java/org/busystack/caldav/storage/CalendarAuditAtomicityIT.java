package org.busystack.caldav.storage;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.calendar.*;
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
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class CalendarAuditAtomicityIT {
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
  UUID owner;

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    owner =
        accounts.provision(
            "calendar-auditor",
            "Calendar auditor",
            "independent atomicity fixture password",
            false);
  }

  private static byte[] fixture(String name) throws Exception {
    try (var input =
        CalendarAuditAtomicityIT.class.getResourceAsStream("/calendar/audit/" + name)) {
      if (input == null) throw new IllegalStateException("Missing original independent fixture");
      return input.readAllBytes();
    }
  }

  private long count(String table, UUID calendar) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE collection_id=?", Long.class, calendar);
  }

  private static byte[] valid(String type, String uid) {
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Atomic audit regression//EN\r\nBEGIN:"
            + type
            + "\r\nUID:"
            + uid
            + "\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261027T120000Z\r\nEND:"
            + type
            + "\r\nEND:VCALENDAR\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void allNineOriginalInvalidInputsLeaveCreationReplacementHistoryAndTokensUntouched()
      throws Exception {
    for (String name :
        List.of(
            "invalid-date-hours-duration.ics",
            "invalid-event-end-before-start.ics",
            "invalid-event-negative-duration.ics",
            "invalid-event-no-start.ics",
            "invalid-rule-count-and-until.ics",
            "invalid-rule-repeat-count.ics",
            "invalid-rule-repeat-freq.ics",
            "invalid-rule-until-type.ics",
            "invalid-todo-no-start-duration.ics")) {
      byte[] invalid = fixture(name);
      String text = new String(invalid, StandardCharsets.UTF_8);
      String uid =
          text.lines()
              .filter(line -> line.startsWith("UID:"))
              .findFirst()
              .orElseThrow()
              .substring(4);
      String type = name.contains("todo") ? "VTODO" : "VEVENT";
      UUID calendar = store.createCollection(owner, name, type);
      String emptyToken = store.currentSyncToken(owner, calendar);
      assertThatThrownBy(
              () ->
                  store.put(owner, calendar, "invalid.ics", uid, invalid, WriteCondition.create()))
          .as(name + " creation")
          .isInstanceOfSatisfying(
              CalendarValidationException.class,
              error ->
                  assertThat(error.reason())
                      .isEqualTo(CalendarValidationException.Reason.INVALID_DATA));
      assertThat(store.get(owner, calendar, "invalid.ics")).isNull();
      assertThat(store.currentSyncToken(owner, calendar)).isEqualTo(emptyToken);
      assertThat(count("calendar_resource_versions", calendar)).isZero();
      assertThat(count("collection_changes", calendar)).isZero();
      byte[] previous = valid(type, uid);
      var old = store.put(owner, calendar, "invalid.ics", uid, previous, WriteCondition.create());
      String token = store.currentSyncToken(owner, calendar);
      long versions = count("calendar_resource_versions", calendar),
          changes = count("collection_changes", calendar);
      assertThatThrownBy(
              () -> store.put(owner, calendar, "invalid.ics", uid, invalid, WriteCondition.none()))
          .as(name + " replacement")
          .isInstanceOf(CalendarValidationException.class);
      var after = store.get(owner, calendar, "invalid.ics");
      assertThat(after.content()).isEqualTo(previous);
      assertThat(after.etag()).isEqualTo(old.etag());
      assertThat(after.revision()).isEqualTo(old.revision());
      assertThat(store.currentSyncToken(owner, calendar)).isEqualTo(token);
      assertThat(count("calendar_resource_versions", calendar)).isEqualTo(versions);
      assertThat(count("collection_changes", calendar)).isEqualTo(changes);
      assertThat(store.sync(owner, calendar, token, 100).changes()).isEmpty();
    }
  }

  @Test
  void residualLexicalFailuresPreserveConditionalWritesRevisionsHistoryAndSynchronization()
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
      String originalName =
          name.equals("invalid-minus-zero-byday")
              ? "invalid-zero-byday"
              : name.contains("unicode") ? "invalid-leading-token" : name;
      byte[] invalid;
      try (var input =
          CalendarAuditAtomicityIT.class.getResourceAsStream(
              "/calendar/independent-verification/" + originalName + ".ics")) {
        if (input == null) throw new IllegalStateException("Missing independent residual fixture");
        invalid = input.readAllBytes();
      }
      if (name.equals("invalid-minus-zero-byday"))
        invalid =
            new String(invalid, StandardCharsets.UTF_8)
                .replace("BYDAY=0MO", "BYDAY=-0MO")
                .getBytes(StandardCharsets.UTF_8);
      if (name.contains("unicode"))
        invalid =
            new String(invalid, StandardCharsets.UTF_8)
                .replace(
                    "XBEGIN:VCALENDAR",
                    (name.contains("-bom-") ? "\uFEFF" : "") + "beg\u0131n:vcalendar")
                .getBytes(StandardCharsets.UTF_8);
      final byte[] rejected = invalid;
      String uid =
          new String(rejected, StandardCharsets.UTF_8)
              .lines()
              .filter(line -> line.startsWith("UID:"))
              .findFirst()
              .orElseThrow()
              .substring(4);
      UUID calendar = store.createCollection(owner, name, "VEVENT");
      String emptyToken = store.currentSyncToken(owner, calendar);
      long emptyRevision = revision(calendar);
      assertThatThrownBy(
              () ->
                  store.put(
                      owner, calendar, "residual.ics", uid, rejected, WriteCondition.create()))
          .as(name + " creation")
          .isInstanceOfSatisfying(
              CalendarValidationException.class,
              error ->
                  assertThat(error.reason())
                      .isEqualTo(CalendarValidationException.Reason.INVALID_DATA));
      assertThat(store.get(owner, calendar, "residual.ics")).isNull();
      assertThat(count("calendar_resources", calendar)).isZero();
      assertThat(count("calendar_resource_versions", calendar)).isZero();
      assertThat(count("collection_changes", calendar)).isZero();
      assertThat(revision(calendar)).isEqualTo(emptyRevision);
      assertThat(store.currentSyncToken(owner, calendar)).isEqualTo(emptyToken);

      byte[] previous = valid("VEVENT", uid);
      var old = store.put(owner, calendar, "residual.ics", uid, previous, WriteCondition.create());
      String token = store.currentSyncToken(owner, calendar);
      long collectionRevision = revision(calendar),
          versions = count("calendar_resource_versions", calendar),
          changes = count("collection_changes", calendar);
      assertThatThrownBy(
              () ->
                  store.put(
                      owner,
                      calendar,
                      "residual.ics",
                      uid,
                      rejected,
                      WriteCondition.match(old.etag())))
          .as(name + " conditional replacement")
          .isInstanceOfSatisfying(
              CalendarValidationException.class,
              error ->
                  assertThat(error.reason())
                      .isEqualTo(CalendarValidationException.Reason.INVALID_DATA));
      var after = store.get(owner, calendar, "residual.ics");
      assertThat(after.content()).isEqualTo(previous);
      assertThat(after.etag()).isEqualTo(old.etag());
      assertThat(after.revision()).isEqualTo(old.revision());
      assertThat(revision(calendar)).isEqualTo(collectionRevision);
      assertThat(count("calendar_resource_versions", calendar)).isEqualTo(versions);
      assertThat(count("collection_changes", calendar)).isEqualTo(changes);
      assertThat(store.currentSyncToken(owner, calendar)).isEqualTo(token);
      assertThat(store.sync(owner, calendar, token, 100).changes()).isEmpty();
    }
  }

  private long revision(UUID calendar) {
    return jdbc.queryForObject("SELECT revision FROM collections WHERE id=?", Long.class, calendar);
  }

  @Test
  void previouslyAcceptedInvalidDataRemainsRetrievableAndRepairableWithoutSilentMigration()
      throws Exception {
    byte[] invalid = fixture("invalid-event-no-start.ics");
    String uid = "invalid-event-no-start";
    UUID calendar = store.createCollection(owner, "Legacy accepted data", "VEVENT");
    // Represent data persisted by the audited buggy version, without using today's write parser.
    jdbc.update(
        "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) VALUES (?,?,?,?,?,1)",
        calendar,
        "legacy.ics",
        uid,
        invalid,
        "\"legacy-audit\"");
    jdbc.update("UPDATE collections SET revision=1 WHERE id=?", calendar);
    jdbc.update(
        "INSERT INTO collection_changes(collection_id,revision,name,deleted) VALUES (?,1,'legacy.ics',false)",
        calendar);
    assertThat(store.get(owner, calendar, "legacy.ics").content()).isEqualTo(invalid);
    assertThatThrownBy(() -> CalendarContent.parse(invalid, "VEVENT"))
        .isInstanceOf(CalendarValidationException.class);
    byte[] repaired = valid("VEVENT", uid);
    store.put(owner, calendar, "legacy.ics", uid, repaired, WriteCondition.none());
    assertThat(store.get(owner, calendar, "legacy.ics").content()).isEqualTo(repaired);
    store.delete(owner, calendar, "legacy.ics", WriteCondition.none());
    assertThat(store.get(owner, calendar, "legacy.ics")).isNull();
  }
}
