package org.busystack.caldav.management;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.namespace.QName;
import org.busystack.caldav.config.DeploymentProperties;
import org.busystack.caldav.protocol.DavPaths;
import org.busystack.caldav.protocol.DavXml;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.security.audit.SecurityHashes;
import org.busystack.caldav.storage.CalendarStore;
import org.busystack.caldav.storage.DavStore;
import org.busystack.caldav.storage.StoreException;
import org.busystack.caldav.storage.WriteCondition;
import org.busystack.caldav.xml.XmlCharacters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded browser projections and compound operations over the authoritative DAV stores. */
@Service
public class PortalService {
  public static final QName DESCRIPTION = new QName(DavXml.CAL, "calendar-description");
  public static final QName COLOR = new QName("http://apple.com/ns/ical/", "calendar-color");
  private static final QName NAME = new QName(DavXml.DAV, "displayname");

  public record CollectionView(
      UUID id,
      String name,
      String component,
      String ownerName,
      String permission,
      String description,
      String color,
      String address,
      String state,
      String deletionState) {
    public String type() {
      return "VTODO".equals(component) ? "Task list" : "Calendar";
    }

    public String access() {
      return accessLabel(permission);
    }

    public boolean owned() {
      return "OWNER".equals(permission);
    }

    public boolean writable() {
      return !"READ".equals(permission);
    }
  }

  public record Setup(
      long passkeys, long owned, long shared, long credentials, long recoveryCodes) {}

  public record DirectGrant(
      String username, String permission, boolean enabled, String effective) {}

  public record GroupGrant(UUID id, String name, String permission) {}

  public record EffectiveMember(
      String username, boolean enabled, String permission, boolean direct, long groups) {
    public String access() {
      return enabled ? accessLabel(permission) : "Disabled — access denied";
    }
  }

  private final ManagementService management;
  private final CalendarStore calendars;
  private final DavStore dav;
  private final JdbcTemplate jdbc;
  private final DavPaths paths;
  private final SecurityAudit audit;

  public PortalService(
      ManagementService management,
      CalendarStore calendars,
      DavStore dav,
      JdbcTemplate jdbc,
      DeploymentProperties deployment,
      SecurityAudit audit) {
    this.management = management;
    this.calendars = calendars;
    this.dav = dav;
    this.jdbc = jdbc;
    this.paths = new DavPaths(deployment);
    this.audit = audit;
  }

  public Setup setup(Authentication auth) {
    UUID actor = management.current(auth).id();
    return jdbc.queryForObject(
        "SELECT "
            + "(SELECT count(*) FROM user_credentials k JOIN user_entities u ON u.id=k.user_entity_user_id WHERE u.account_id=?),"
            + "(SELECT count(*) FROM collections WHERE owner_id=?),"
            + "(SELECT count(*) FROM collections c JOIN collection_effective_members m ON m.collection_id=c.id WHERE m.account_id=? AND c.owner_id<>?),"
            + "(SELECT count(*) FROM dav_credentials WHERE account_id=? AND revoked_at IS NULL AND expires_at>now()),"
            + "(SELECT count(*) FROM account_recovery_codes WHERE account_id=? AND used_at IS NULL AND revoked_at IS NULL AND recovery_epoch=(SELECT recovery_epoch FROM system_state WHERE id=1) AND expires_at>now())",
        (rs, row) ->
            new Setup(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
        actor,
        actor,
        actor,
        actor,
        actor,
        actor);
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public PortalPage<CollectionView> collections(
      Authentication auth, String scope, String query, int page) {
    UUID actor = management.current(auth).id();
    checkQuery(query);
    if (!Set.of("owned", "shared", "all", "writable").contains(scope))
      throw new IllegalArgumentException("Invalid collection filter");
    String restriction =
        switch (scope) {
          case "owned" -> " AND c.owner_id=?";
          case "shared" -> " AND c.owner_id<>?";
          case "writable" -> " AND (c.owner_id=? OR e.permission='WRITE')";
          default -> "";
        };
    List<Object> args = new ArrayList<>(List.of(actor, actor, actor));
    if (!scope.equals("all")) args.add(actor);
    args.add(query);
    args.add(PortalPage.offset(page));
    var rows =
        jdbc.query(
            "SELECT c.id,c.name,c.component,a.display_name,"
                + "CASE WHEN c.owner_id=? THEN 'OWNER' ELSE e.permission END AS permission,b.path,"
                + "d.xml AS description,k.xml AS color FROM collections c "
                + "JOIN accounts a ON a.id=c.owner_id JOIN dav_bindings b ON b.collection_id=c.id AND b.home_id=? "
                + "LEFT JOIN collection_effective_members e ON e.collection_id=c.id AND e.account_id=? "
                + "LEFT JOIN dav_collection_properties d ON d.collection_id=c.id AND d.namespace='urn:ietf:params:xml:ns:caldav' AND d.name='calendar-description' "
                + "LEFT JOIN dav_collection_properties k ON k.collection_id=c.id AND k.namespace='http://apple.com/ns/ical/' AND k.name='calendar-color' "
                + "WHERE (c.owner_id=b.home_id OR e.account_id IS NOT NULL)"
                + restriction
                + " AND strpos(lower(c.name),lower(?))>0 ORDER BY lower(c.name),c.id LIMIT 51 OFFSET ?",
            (rs, row) ->
                new CollectionView(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    text(rs.getString(7)),
                    text(rs.getString(8)),
                    address(actor, rs.getString(6)),
                    "",
                    ""),
            args.toArray());
    return PortalPage.of(rows, page);
  }

  public CollectionView collection(Authentication auth, UUID id) {
    UUID actor = management.current(auth).id();
    var collection = calendars.collection(actor, id);
    var binding = dav.bindingFor(actor, id);
    if (binding == null) throw new StoreException(StoreException.Kind.NOT_FOUND);
    var props = dav.deadProperties(actor, id, null, Set.of(DESCRIPTION, COLOR));
    String description = text(props.get(DESCRIPTION)), color = text(props.get(COLOR));
    String state =
        SecurityHashes.sha256(
            id
                + "\n"
                + collection.name()
                + "\n"
                + binding.path()
                + "\n"
                + props.get(DESCRIPTION)
                + "\n"
                + props.get(COLOR));
    String deletion = state + ":" + collection.generation() + ":" + collection.revision();
    return new CollectionView(
        id,
        collection.name(),
        collection.component(),
        jdbc.queryForObject(
            "SELECT display_name FROM accounts WHERE id=?", String.class, collection.owner()),
        dav.permission(actor, id),
        description,
        color,
        address(actor, binding.path()),
        state,
        deletion);
  }

  @Transactional
  public UUID create(
      Authentication auth, String name, String component, String description, String color) {
    RecentAuthentication.requireMfa(auth);
    UUID actor = management.current(auth).id();
    validateMetadata(name, description, color);
    if (!Set.of("VEVENT", "VTODO").contains(component))
      throw FormValidation.field("component", "Choose a calendar or task list.");
    UUID id = calendars.createCollection(actor, name, component);
    var binding = dav.bindingFor(actor, id);
    dav.patch(actor, actor, binding.path(), null, properties(name, description, color), Set.of());
    audit.record(
        actor, SecurityAudit.Action.COLLECTION_CREATED, SecurityAudit.Outcome.SUCCESS, null);
    return id;
  }

  @Transactional
  public void edit(
      Authentication auth, UUID id, String state, String name, String description, String color) {
    RecentAuthentication.requireMfa(auth);
    UUID actor = management.current(auth).id();
    var entry = dav.lockCollection(actor, id);
    var current = collection(auth, id);
    checkState(state, current.state());
    validateMetadata(name, description, color);
    dav.patch(
        actor, actor, entry.binding().path(), null, properties(name, description, color), Set.of());
    audit.record(
        actor, SecurityAudit.Action.COLLECTION_UPDATED, SecurityAudit.Outcome.SUCCESS, null);
  }

  @Transactional
  public void delete(Authentication auth, UUID id, String state, boolean confirmed) {
    RecentAuthentication.requireMfa(auth);
    UUID actor = management.current(auth).id();
    var entry = dav.lockCollection(actor, id);
    var current = owner(auth, id);
    if (!confirmed)
      throw new IllegalArgumentException("Confirm deletion of the collection and its contents");
    checkState(state, current.deletionState());
    dav.deleteBinding(
        actor, actor, entry.binding().path(), WriteCondition.match(DavStore.bindingEtag(entry)));
    audit.record(
        actor, SecurityAudit.Action.COLLECTION_DELETED, SecurityAudit.Outcome.SUCCESS, null);
  }

  public CollectionView owner(Authentication auth, UUID id) {
    var result = collection(auth, id);
    if (!result.owned()) throw new StoreException(StoreException.Kind.FORBIDDEN);
    return result;
  }

  public PortalPage<DirectGrant> direct(Authentication auth, UUID id, int page) {
    owner(auth, id);
    return PortalPage.of(
        jdbc.query(
            "SELECT a.username,m.permission,a.enabled,e.permission FROM collection_members m JOIN accounts a ON a.id=m.account_id LEFT JOIN collection_effective_members e ON e.collection_id=m.collection_id AND e.account_id=m.account_id WHERE m.collection_id=? ORDER BY a.username LIMIT 51 OFFSET ?",
            (rs, row) ->
                new DirectGrant(
                    rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)),
            id,
            PortalPage.offset(page)),
        page);
  }

  public PortalPage<GroupGrant> groupGrants(Authentication auth, UUID id, int page) {
    owner(auth, id);
    return PortalPage.of(
        jdbc.query(
            "SELECT g.id,g.name,m.permission FROM collection_group_members m JOIN sharing_groups g ON g.id=m.group_id WHERE m.collection_id=? ORDER BY g.name,g.id LIMIT 51 OFFSET ?",
            (rs, row) ->
                new GroupGrant(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            id,
            PortalPage.offset(page)),
        page);
  }

  public PortalPage<EffectiveMember> effective(Authentication auth, UUID id, int page) {
    owner(auth, id);
    return PortalPage.of(
        jdbc.query(
            "SELECT a.username,a.enabled,e.permission,EXISTS(SELECT 1 FROM collection_members d WHERE d.collection_id=e.collection_id AND d.account_id=e.account_id),"
                + "(SELECT count(*) FROM collection_group_members g JOIN sharing_group_members m ON m.group_id=g.group_id WHERE g.collection_id=e.collection_id AND m.account_id=e.account_id) "
                + "FROM collection_effective_members e JOIN accounts a ON a.id=e.account_id JOIN collections c ON c.id=e.collection_id WHERE e.collection_id=? AND e.account_id<>c.owner_id ORDER BY a.username LIMIT 51 OFFSET ?",
            (rs, row) ->
                new EffectiveMember(
                    rs.getString(1),
                    rs.getBoolean(2),
                    rs.getString(3),
                    rs.getBoolean(4),
                    rs.getLong(5)),
            id,
            PortalPage.offset(page)),
        page);
  }

  public PortalPage<ManagementService.Credential> credentials(Authentication auth, int page) {
    UUID actor = management.current(auth).id();
    return PortalPage.of(
        jdbc.query(
            "SELECT id,label,created_at,expires_at,revoked_at FROM dav_credentials WHERE account_id=? ORDER BY created_at DESC,id LIMIT 51 OFFSET ?",
            (rs, row) ->
                new ManagementService.Credential(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getTimestamp(3).toInstant(),
                    rs.getTimestamp(4).toInstant(),
                    rs.getTimestamp(5) != null),
            actor,
            PortalPage.offset(page)),
        page);
  }

  private String address(UUID actor, String path) {
    List<String> parts = new ArrayList<>(List.of("calendars", actor.toString()));
    parts.addAll(List.of(path.split("/")));
    return paths.href(parts, true);
  }

  private static Map<QName, String> properties(String name, String description, String color) {
    Map<QName, String> result = new LinkedHashMap<>();
    result.put(NAME, xml(NAME, name));
    result.put(DESCRIPTION, xml(DESCRIPTION, description));
    result.put(COLOR, xml(COLOR, color));
    return result;
  }

  private static String xml(QName name, String value) {
    return "<p:"
        + name.getLocalPart()
        + " xmlns:p=\""
        + name.getNamespaceURI()
        + "\">"
        + value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\r", "&#13;")
        + "</p:"
        + name.getLocalPart()
        + ">";
  }

  private static String text(String xml) {
    return xml == null ? "" : DavXml.parse(xml.getBytes(StandardCharsets.UTF_8)).getTextContent();
  }

  private static void validateMetadata(String name, String description, String color) {
    new FormValidation.Checks()
        .require(
            "name",
            name != null && !name.isBlank() && name.length() <= 200 && XmlCharacters.valid(name),
            "Use a name of 1–200 characters.")
        .require(
            "description",
            description != null && description.length() <= 8000 && XmlCharacters.valid(description),
            "Use a description of at most 8,000 supported characters.")
        .require(
            "color",
            color != null && (color.isEmpty() || color.matches("#[a-fA-F0-9]{6}([a-fA-F0-9]{2})?")),
            "Use #RRGGBB or #RRGGBBAA, or leave the colour empty.")
        .finish();
  }

  static void checkQuery(String query) {
    if (query == null || query.length() > 200)
      throw new IllegalArgumentException("Search is limited to 200 characters");
  }

  public static void checkState(String supplied, String current) {
    if (supplied == null || !supplied.equals(current))
      throw new StoreException(StoreException.Kind.PRECONDITION);
  }

  public static String accessLabel(String permission) {
    return switch (permission) {
      case "OWNER" -> "Owner";
      case "WRITE" -> "Read and write";
      default -> "Read-only";
    };
  }
}
