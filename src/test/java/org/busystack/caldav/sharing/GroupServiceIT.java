package org.busystack.caldav.sharing;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.session.SessionSecurity;
import org.busystack.caldav.storage.CalendarStore;
import org.busystack.caldav.storage.DavStore;
import org.busystack.caldav.storage.StoreException;
import org.busystack.caldav.storage.WriteCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
class GroupServiceIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired GroupService groups;
  @Autowired AccountService accounts;
  @Autowired CalendarStore calendars;
  @Autowired DavStore dav;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.busystack.caldav.management.ManagementService management;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  UUID administrator, owner, bob, carol, calendar;

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    administrator =
        accounts.provision("administrator", "Administrator", "correct horse battery staple", true);
    owner = accounts.provision("owner", "Calendar owner", "correct horse battery staple", false);
    bob = accounts.provision("bob", "Bob", "correct horse battery staple", false);
    carol = accounts.provision("carol", "Carol", "correct horse battery staple", false);
    calendar = calendars.createCollection(owner, "Shared calendar", "VEVENT");
  }

  private Authentication auth(UUID id, boolean mfa) {
    var account = accounts.find(id);
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(
        new SimpleGrantedAuthority(account.administrator() ? "ROLE_ADMIN" : "ROLE_USER"));
    authorities.add(SessionSecurity.authority(account.securityEpoch()));
    authorities.add(
        FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY));
    if (mfa)
      authorities.add(
          FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY));
    return UsernamePasswordAuthenticationToken.authenticated(account.username(), null, authorities);
  }

  private UUID group(String name, Set<String> members) {
    return groups.create(auth(administrator, true), name, members);
  }

  private byte[] event(String uid) {
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Group fixture//EN\r\nBEGIN:VEVENT\r\nUID:"
            + uid
            + "\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20261028T000000Z\r\nX-CLIENT:preserve\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private void denied(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfAny(AccessDeniedException.class, StoreException.class);
  }

  @Test
  void individualGrantListingIsOwnerScopedAndDisabledTargetsCanBeRevoked() {
    management.share(auth(owner, true), calendar, "bob", "READ");
    assertThat(management.individualShares(auth(owner, false), 0).rows())
        .singleElement()
        .satisfies(
            share -> {
              assertThat(share.username()).isEqualTo("bob");
              assertThat(share.permission()).isEqualTo("READ");
            });
    assertThat(management.individualShares(auth(bob, false), 0).rows()).isEmpty();
    assertThat(management.individualShares(auth(administrator, false), 0).rows()).isEmpty();
    accounts.disable(bob);
    assertThat(management.individualShares(auth(owner, false), 0).rows())
        .singleElement()
        .extracting(org.busystack.caldav.management.ManagementService.IndividualShare::enabled)
        .isEqualTo(false);
    management.share(auth(owner, true), calendar, "bob", "REVOKE");
    assertThat(management.individualShares(auth(owner, false), 0).rows()).isEmpty();
  }

  @Test
  void administrationRequiresCurrentAdministratorAndMfaButSharingChoicesDoNot() {
    denied(() -> groups.create(auth(administrator, false), "Denied", Set.of()));
    denied(() -> groups.create(auth(owner, true), "Denied", Set.of()));
    UUID group = group("Engineering", Set.of("bob"));
    assertThat(groups.choices(auth(owner, false)))
        .containsExactly(new GroupService.Choice(group, "Engineering"));
    denied(() -> groups.list(auth(owner, true)));
    denied(() -> groups.list(auth(administrator, false)));
    var stale = auth(administrator, true);
    jdbc.update("UPDATE accounts SET security_epoch=gen_random_uuid() WHERE id=?", administrator);
    denied(() -> groups.update(stale, group, "Changed", Set.of("bob")));
    assertThat(groups.list(auth(administrator, true)))
        .singleElement()
        .satisfies(value -> assertThat(value.name()).isEqualTo("Engineering"));
  }

  @Test
  void groupReadWriteAndMembershipRevocationUseTheSameAuthoritativeStore() {
    UUID group = group("Team", Set.of("bob"));
    groups.grant(auth(owner, true), calendar, group, "READ");
    dav.putResource(owner, calendar, "original.ics", event("original"), WriteCondition.create());
    assertThat(calendars.collections(bob))
        .extracting(CalendarStore.Collection::id)
        .contains(calendar);
    assertThat(dav.snapshot(bob, bob, calendar.toString()).resources()).hasSize(1);
    denied(
        () ->
            dav.putResource(
                bob, calendar, "forbidden.ics", event("forbidden"), WriteCondition.create()));
    groups.grant(auth(owner, true), calendar, group, "WRITE");
    dav.putResource(bob, calendar, "written.ics", event("written"), WriteCondition.create());
    String token = calendars.sync(bob, calendar, null, 100).token();
    groups.update(auth(administrator, true), group, "Team renamed", Set.of("carol"));
    denied(() -> calendars.get(bob, calendar, "original.ics"));
    denied(() -> calendars.sync(bob, calendar, token, 100));
    assertThat(dav.snapshot(carol, carol, calendar.toString()).resources()).hasSize(2);
    dav.putResource(carol, calendar, "carol.ics", event("carol"), WriteCondition.create());
    assertThat(calendars.get(owner, calendar, "carol.ics")).isNotNull();
  }

  @Test
  void overlappingDirectAndGroupGrantsHaveWritePrecedenceAndKeepBindingsStable() {
    UUID read = group("Readers", Set.of("bob")), write = group("Writers", Set.of("bob"));
    groups.grant(auth(owner, true), calendar, read, "READ");
    groups.grant(auth(owner, true), calendar, write, "WRITE");
    String path = dav.children(bob, bob, "").getFirst().binding().path();
    assertThat(dav.permission(bob, calendar)).isEqualTo("WRITE");
    groups.update(auth(administrator, true), write, "Writers", Set.of());
    assertThat(dav.permission(bob, calendar)).isEqualTo("READ");
    assertThat(dav.children(bob, bob, "").getFirst().binding().path()).isEqualTo(path);
    calendars.share(owner, calendar, bob, "WRITE");
    groups.update(auth(administrator, true), read, "Readers", Set.of());
    assertThat(dav.permission(bob, calendar)).isEqualTo("WRITE");
    assertThat(dav.children(bob, bob, "").getFirst().binding().path()).isEqualTo(path);
    calendars.share(owner, calendar, bob, null);
    denied(() -> calendars.collection(bob, calendar));
    assertThat(dav.children(bob, bob, "")).isEmpty();
  }

  @Test
  void groupAdministrationDoesNotBypassOwnershipOrGrantAnAdministratorRole() {
    UUID group = group("Administrators", Set.of("bob"));
    denied(() -> groups.grant(auth(administrator, true), calendar, group, "WRITE"));
    denied(() -> groups.grant(auth(owner, false), calendar, group, "READ"));
    groups.grant(auth(owner, true), calendar, group, "READ");
    denied(() -> calendars.collection(administrator, calendar));
    assertThat(accounts.find(bob).administrator()).isFalse();
    denied(() -> groups.list(auth(bob, true)));
    assertThat(groups.ownedGrants(auth(bob, false))).isEmpty();
    groups.update(
        auth(administrator, true), group, "Administrators", Set.of("bob", "administrator"));
    assertThat(dav.permission(administrator, calendar)).isEqualTo("READ");
    denied(() -> groups.grant(auth(administrator, true), calendar, group, "WRITE"));
  }

  @Test
  void deletingAGroupRevokesOnlyDerivedPermissionsAndRetiresItsPrincipalId() {
    UUID group = group("Temporary", Set.of("bob"));
    groups.grant(auth(owner, true), calendar, group, "READ");
    calendars.share(owner, calendar, carol, "READ");
    dav.putResource(owner, calendar, "one.ics", event("one"), WriteCondition.create());
    byte[] preserved = calendars.get(owner, calendar, "one.ics").content();
    groups.delete(auth(administrator, true), group);
    denied(() -> calendars.collection(bob, calendar));
    assertThat(calendars.get(carol, calendar, "one.ics").content()).isEqualTo(preserved);
    assertThat(dav.recognizedPrincipal(group)).isFalse();
    UUID replacement = group("Temporary", Set.of());
    assertThat(replacement).isNotEqualTo(group);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO sharing_groups(id,name,created_by) VALUES (?,?,?)",
                    group,
                    "Cannot reuse identity",
                    administrator))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  @Test
  void principalVisibilityDisclosesMembershipOnlyToMembersOwnersAndAdministrators() {
    UUID group = group("Private membership", Set.of("bob"));
    assertThat(groups.choices(auth(owner, false)))
        .extracting(GroupService.Choice::id)
        .contains(group);
    denied(() -> dav.principal(owner, group));
    assertThat(dav.groupMembers(bob, group))
        .extracting(DavStore.Principal::id)
        .containsExactly(bob);
    assertThat(dav.principals(bob)).extracting(DavStore.Principal::id).doesNotContain(carol, owner);
    groups.grant(auth(owner, true), calendar, group, "READ");
    assertThat(dav.bindingFor(bob, calendar).home()).isEqualTo(bob);
    assertThat(dav.bindingFor(owner, calendar).home()).isEqualTo(owner);
    assertThat(dav.bindingFor(carol, calendar)).isNull();
    assertThat(dav.principal(owner, group).group()).isTrue();
    assertThat(dav.principal(owner, group).owner()).isEqualTo(administrator);
    assertThat(dav.groupMembers(owner, group))
        .extracting(DavStore.Principal::id)
        .containsExactly(bob);
    assertThat(dav.principalGroups(owner, bob))
        .extracting(DavStore.Principal::id)
        .containsExactly(group);
    assertThat(dav.principalGroups(bob, group)).isEmpty();
    UUID unrelated = group("Only another member", Set.of("carol"));
    assertThat(dav.principalGroups(administrator, bob))
        .extracting(DavStore.Principal::id)
        .containsExactly(group)
        .doesNotContain(unrelated);
  }

  @Test
  void groupAndIndividualAclsReplaceAtomicallyWithoutEscalation() {
    UUID group = group("Editors", Set.of("bob"));
    dav.replaceAcl(
        owner,
        calendar,
        List.of(new DavStore.Member(group, "WRITE", true), new DavStore.Member(carol, "READ")));
    assertThat(dav.members(owner, calendar))
        .containsExactlyInAnyOrder(
            new DavStore.Member(group, "WRITE", true), new DavStore.Member(carol, "READ"));
    assertThat(dav.permission(bob, calendar)).isEqualTo("WRITE");
    assertThat(dav.permission(carol, calendar)).isEqualTo("READ");
    assertThatThrownBy(
            () ->
                dav.replaceAcl(
                    owner,
                    calendar,
                    List.of(new DavStore.Member(UUID.randomUUID(), "WRITE", true))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(dav.permission(bob, calendar)).isEqualTo("WRITE");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO sharing_groups(id,name,created_by) VALUES (?,?,?)",
                    owner,
                    "No account collision",
                    administrator))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThat(accounts.find(owner).enabled()).isTrue();
  }

  @Test
  void membershipRevocationOrdersAfterAnInFlightWriteAndRejectsSubsequentWrites() throws Exception {
    UUID group = group("Concurrent editors", Set.of("bob"));
    var administratorAuthentication = auth(administrator, true);
    groups.grant(auth(owner, true), calendar, group, "WRITE");
    var written = new java.util.concurrent.CountDownLatch(1);
    var allowCommit = new java.util.concurrent.CountDownLatch(1);
    var holderPid = new java.util.concurrent.atomic.AtomicInteger();
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var mutation =
          java.util.concurrent.CompletableFuture.runAsync(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            holderPid.set(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            dav.putResource(
                                bob,
                                calendar,
                                "before-revocation.ics",
                                event("before-revocation"),
                                WriteCondition.create());
                            written.countDown();
                            try {
                              if (!allowCommit.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("Group revocation coordination timed out");
                            } catch (InterruptedException failure) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(failure);
                            }
                          }),
              workers);
      try {
        assertThat(written.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var revocation =
            java.util.concurrent.CompletableFuture.runAsync(
                () ->
                    groups.update(
                        administratorAuthentication, group, "Concurrent editors", Set.of()),
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
        allowCommit.countDown();
        mutation.get(15, java.util.concurrent.TimeUnit.SECONDS);
        revocation.get(15, java.util.concurrent.TimeUnit.SECONDS);
      } finally {
        allowCommit.countDown();
      }
    }
    assertThat(calendars.get(owner, calendar, "before-revocation.ics")).isNotNull();
    denied(
        () ->
            dav.putResource(
                bob,
                calendar,
                "after-revocation.ics",
                event("after-revocation"),
                WriteCondition.create()));
    assertThat(calendars.get(owner, calendar, "after-revocation.ics")).isNull();
    assertThat(dav.children(bob, bob, "")).isEmpty();
  }

  @Test
  void failedReconciliationRollsBackMembershipNameBindingsAndSynchronizationTogether() {
    UUID group = group("Atomic", Set.of("bob"));
    groups.grant(auth(owner, true), calendar, group, "READ");
    String token = calendars.sync(bob, calendar, null, 100).token();
    jdbc.execute("CREATE TABLE blocked_group_binding(account_id uuid PRIMARY KEY)");
    jdbc.update("INSERT INTO blocked_group_binding VALUES (?)", carol);
    jdbc.execute(
        "CREATE FUNCTION reject_group_binding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS(SELECT 1 FROM blocked_group_binding WHERE account_id=NEW.home_id) THEN RAISE EXCEPTION 'simulated group binding failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_group_binding BEFORE INSERT ON dav_bindings FOR EACH ROW EXECUTE FUNCTION reject_group_binding()");
    try {
      assertThatThrownBy(
              () ->
                  groups.update(
                      auth(administrator, true), group, "Must roll back", Set.of("bob", "carol")))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(groups.list(auth(administrator, true)).getFirst().name()).isEqualTo("Atomic");
      assertThat(dav.groupMembers(bob, group))
          .extracting(DavStore.Principal::id)
          .containsExactly(bob);
      assertThat(calendars.sync(bob, calendar, token, 100).changes()).isEmpty();
      denied(() -> calendars.collection(carol, calendar));
    } finally {
      jdbc.execute("DROP TRIGGER reject_group_binding ON dav_bindings");
      jdbc.execute("DROP FUNCTION reject_group_binding()");
      jdbc.execute("DROP TABLE blocked_group_binding");
    }
    groups.update(auth(administrator, true), group, "Atomic", Set.of("bob", "carol"));
    assertThat(dav.permission(carol, calendar)).isEqualTo("READ");
  }
}
