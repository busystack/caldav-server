package org.busystack.caldav.sharing;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.security.session.SessionSecurity;
import org.busystack.caldav.storage.CalendarStore;
import org.busystack.caldav.storage.DavMutationLock;
import org.busystack.caldav.storage.StoreException;
import org.busystack.caldav.xml.XmlCharacters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local sharing groups are directory metadata; administration never bypasses calendar ACLs. */
@Service
public class GroupService {
  public record Member(UUID account, String username, String displayName) {}

  public record Group(UUID id, String name, List<Member> members) {
    public Group {
      members = List.copyOf(members);
    }
  }

  public record Choice(UUID id, String name) {}

  public record Grant(
      UUID collection, String collectionName, UUID group, String groupName, String permission) {}

  public record GrantPage(List<Grant> rows, int page, boolean hasNext) {
    public GrantPage {
      rows = List.copyOf(rows);
    }
  }

  private final JdbcTemplate jdbc;
  private final SessionSecurity sessions;
  private final CalendarStore calendars;
  private final org.busystack.caldav.security.audit.SecurityAudit audit;

  public GroupService(
      JdbcTemplate jdbc,
      SessionSecurity sessions,
      CalendarStore calendars,
      org.busystack.caldav.security.audit.SecurityAudit audit) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.calendars = calendars;
    this.audit = audit;
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public List<Group> list(Authentication authentication) {
    administrator(authentication);
    var groups =
        jdbc.query(
            "SELECT id,name FROM sharing_groups ORDER BY name LIMIT 1001",
            (rs, row) -> new Choice(rs.getObject(1, UUID.class), rs.getString(2)));
    if (groups.size() > 1000) limit();
    return groups.stream()
        .map(
            group ->
                new Group(
                    group.id(),
                    group.name(),
                    jdbc.query(
                        "SELECT a.id,a.username,a.display_name FROM accounts a JOIN sharing_group_members m ON m.account_id=a.id WHERE m.group_id=? ORDER BY a.username",
                        (rs, row) ->
                            new Member(
                                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                        group.id())))
        .toList();
  }

  public List<Choice> choices(Authentication authentication) {
    sessions.assertCurrent(authentication);
    var choices =
        jdbc.query(
            "SELECT id,name FROM sharing_groups ORDER BY name LIMIT 1001",
            (rs, row) -> new Choice(rs.getObject(1, UUID.class), rs.getString(2)));
    if (choices.size() > 1000) limit();
    return List.copyOf(choices);
  }

  public List<Grant> ownedGrants(Authentication authentication) {
    return ownedGrants(authentication, 0).rows();
  }

  public GrantPage ownedGrants(Authentication authentication, int page) {
    if (page < 0 || page > Integer.MAX_VALUE / 50)
      throw new IllegalArgumentException("Invalid group sharing page");
    var actor = sessions.assertCurrent(authentication);
    var grants =
        jdbc.query(
            "SELECT c.id,c.name,g.id,g.name,m.permission FROM collection_group_members m JOIN collections c ON c.id=m.collection_id JOIN sharing_groups g ON g.id=m.group_id WHERE c.owner_id=? ORDER BY c.id,g.id LIMIT 51 OFFSET ?",
            (rs, row) ->
                new Grant(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getString(5)),
            actor.id(),
            page * 50);
    return new GrantPage(
        grants.size() > 50 ? grants.subList(0, 50) : grants, page, grants.size() > 50);
  }

  @Transactional(timeout = 30)
  public UUID create(Authentication authentication, String name, Set<String> usernames) {
    RecentAuthentication.requireMfa(authentication);
    DavMutationLock.exclusive(jdbc);
    UUID actor = administrator(authentication);
    validateName(name);
    var members = accounts(usernames);
    if (jdbc.queryForObject("SELECT count(*) FROM sharing_groups", Long.class) >= 1000) limit();
    if (jdbc.queryForObject("SELECT count(*) FROM sharing_group_members", Long.class)
            + members.size()
        > 10000) limit();
    uniqueName(name, null);
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO sharing_groups(id,name,created_by) VALUES (?,?,?)", id, name, actor);
    for (UUID member : members)
      jdbc.update(
          "INSERT INTO sharing_group_members(group_id,account_id) VALUES (?,?)", id, member);
    audit.record(
        actor,
        org.busystack.caldav.security.audit.SecurityAudit.Action.GROUP_CREATED,
        org.busystack.caldav.security.audit.SecurityAudit.Outcome.SUCCESS,
        null);
    return id;
  }

  @Transactional(timeout = 30)
  public void update(
      Authentication authentication, UUID group, String name, Set<String> usernames) {
    RecentAuthentication.requireMfa(authentication);
    DavMutationLock.exclusive(jdbc);
    UUID actor = administrator(authentication);
    validateName(name);
    var desired = accounts(usernames);
    requireGroup(group);
    uniqueName(name, group);
    lockAffected(group);
    long existingCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM sharing_group_members WHERE group_id=?", Long.class, group);
    if (jdbc.queryForObject("SELECT count(*) FROM sharing_group_members", Long.class)
            - existingCount
            + desired.size()
        > 10000) limit();
    jdbc.query("SELECT set_config('busystack.defer_dav_bindings','on',true)", rs -> {});
    jdbc.update("UPDATE sharing_groups SET name=? WHERE id=?", name, group);
    var existing =
        jdbc.queryForList(
            "SELECT account_id FROM sharing_group_members WHERE group_id=?", UUID.class, group);
    for (UUID member : existing)
      if (!desired.contains(member))
        jdbc.update(
            "DELETE FROM sharing_group_members WHERE group_id=? AND account_id=?", group, member);
    for (UUID member : desired)
      if (!existing.contains(member))
        jdbc.update(
            "INSERT INTO sharing_group_members(group_id,account_id) VALUES (?,?)", group, member);
    jdbc.query("SELECT set_config('busystack.defer_dav_bindings','off',true)", rs -> {});
    reconcileAffected(group);
    audit.record(
        actor,
        org.busystack.caldav.security.audit.SecurityAudit.Action.GROUP_UPDATED,
        org.busystack.caldav.security.audit.SecurityAudit.Outcome.SUCCESS,
        null);
  }

  @Transactional(timeout = 30)
  public void delete(Authentication authentication, UUID group) {
    RecentAuthentication.requireMfa(authentication);
    DavMutationLock.exclusive(jdbc);
    UUID actor = administrator(authentication);
    requireGroup(group);
    lockAffected(group);
    jdbc.query("SELECT set_config('busystack.defer_dav_bindings','on',true)", rs -> {});
    // Reconcile with no members while grants still identify the complete affected set.
    // Other groups and direct grants remain effective throughout this indivisible commit.
    jdbc.update("DELETE FROM sharing_group_members WHERE group_id=?", group);
    reconcileAffected(group);
    jdbc.update("DELETE FROM sharing_groups WHERE id=?", group);
    jdbc.query("SELECT set_config('busystack.defer_dav_bindings','off',true)", rs -> {});
    audit.record(
        actor,
        org.busystack.caldav.security.audit.SecurityAudit.Action.GROUP_DELETED,
        org.busystack.caldav.security.audit.SecurityAudit.Outcome.SUCCESS,
        null);
  }

  @Transactional(timeout = 30)
  public void grant(Authentication authentication, UUID collection, UUID group, String permission) {
    RecentAuthentication.requireMfa(authentication);
    DavMutationLock.shared(jdbc);
    UUID actor = sessions.assertCurrent(authentication).id();
    if (jdbc.queryForList(
            "SELECT id FROM collections WHERE id=? FOR UPDATE", UUID.class, collection)
        .isEmpty()) throw new StoreException(StoreException.Kind.NOT_FOUND);
    if (!calendars.collection(actor, collection).owner().equals(actor))
      throw new AccessDeniedException("Only the collection owner may manage sharing");
    requireGroup(group);
    if ("REVOKE".equals(permission))
      jdbc.update(
          "DELETE FROM collection_group_members WHERE collection_id=? AND group_id=?",
          collection,
          group);
    else {
      if (!Set.of("READ", "WRITE").contains(permission))
        throw new IllegalArgumentException("Group permission must be READ, WRITE or REVOKE");
      jdbc.update(
          "INSERT INTO collection_group_members(collection_id,group_id,permission) VALUES (?,?,?) ON CONFLICT(collection_id,group_id) DO UPDATE SET permission=EXCLUDED.permission",
          collection,
          group,
          permission);
    }
    audit.record(
        actor,
        org.busystack.caldav.security.audit.SecurityAudit.Action.GROUP_SHARING_CHANGED,
        org.busystack.caldav.security.audit.SecurityAudit.Outcome.SUCCESS,
        null);
  }

  private UUID administrator(Authentication authentication) {
    RecentAuthentication.requireMfa(authentication);
    var actor = sessions.assertCurrent(authentication);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT administrator FROM accounts WHERE id=? AND enabled",
            Boolean.class,
            actor.id())))
      throw new AccessDeniedException("Current administrator authentication is required");
    return actor.id();
  }

  private Set<UUID> accounts(Set<String> usernames) {
    if (usernames == null || usernames.size() > 1000)
      throw new IllegalArgumentException("A group supports at most 1000 members");
    Set<UUID> members = new LinkedHashSet<>();
    for (String username : usernames) {
      var found =
          jdbc.queryForList(
              "SELECT id FROM accounts WHERE username=? AND enabled", UUID.class, username);
      if (found.size() != 1)
        throw new IllegalArgumentException("Every group member must name an active account");
      members.add(found.getFirst());
    }
    return Set.copyOf(members);
  }

  private void requireGroup(UUID id) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sharing_groups WHERE id=?)", Boolean.class, id)))
      throw new IllegalArgumentException("Unknown sharing group");
  }

  private void lockAffected(UUID group) {
    streamGroupCollections(
        "SELECT c.id FROM collections c JOIN collection_group_members m ON m.collection_id=c.id WHERE m.group_id=? ORDER BY c.id FOR UPDATE OF c",
        group);
  }

  private void reconcileAffected(UUID group) {
    jdbc.query("SELECT dav_reconcile_group_memberships(?)", rs -> {}, group);
  }

  private void streamGroupCollections(String sql, UUID group) {
    // A server cursor bounds JDBC result buffering while the exclusive mutation lock makes
    // membership, effective ACLs, bindings and token invalidation one indivisible commit.
    jdbc.query(
        connection -> {
          var statement = connection.prepareStatement(sql);
          statement.setObject(1, group);
          statement.setFetchSize(128);
          return statement;
        },
        (org.springframework.jdbc.core.RowCallbackHandler) rs -> {});
  }

  private void uniqueName(String name, UUID except) {
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sharing_groups WHERE name=? AND (?::uuid IS NULL OR id<>?))",
            Boolean.class,
            name,
            except,
            except))) throw new IllegalArgumentException("Group name is already in use");
  }

  private static void validateName(String name) {
    if (name == null || name.isBlank() || name.length() > 200 || !XmlCharacters.valid(name))
      throw new IllegalArgumentException("Group name must contain 1–200 characters");
  }

  private static void limit() {
    throw new StoreException(StoreException.Kind.LIMIT);
  }
}
