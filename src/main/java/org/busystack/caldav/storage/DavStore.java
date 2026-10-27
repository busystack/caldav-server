package org.busystack.caldav.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.namespace.QName;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.xml.XmlCharacters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** URI bindings and DAV metadata surround the authoritative calendar resource store. */
@Service
public class DavStore {
  public record Binding(
      UUID home, String path, UUID collectionId, String displayName, boolean calendar) {}

  public record Entry(Binding binding, CalendarStore.Collection collection, String permission) {}

  public record Member(UUID account, String permission, boolean group) {
    public Member(UUID account, String permission) {
      this(account, permission, false);
    }
  }

  public record Property(String namespace, String name, String xml) {}

  public record Principal(UUID id, String username, String displayName, boolean group, UUID owner) {
    public Principal(UUID id, String username, String displayName) {
      this(id, username, displayName, false, id);
    }
  }

  public record Snapshot(
      Entry entry,
      List<CalendarStore.Metadata> resources,
      List<Property> properties,
      String syncToken) {
    public Snapshot {
      resources = List.copyOf(resources);
      properties = List.copyOf(properties);
    }
  }

  public record PutResult(CalendarStore.Resource resource, boolean created) {}

  private static final int MAX_ROWS = 10000;
  private static final QName DISPLAY_NAME = new QName("DAV:", "displayname");
  private final JdbcTemplate jdbc;
  private final CalendarStore calendars;

  public DavStore(JdbcTemplate jdbc, CalendarStore calendars) {
    this.jdbc = jdbc;
    this.calendars = calendars;
  }

  public Entry resolve(UUID actor, UUID home, String path) {
    requireEnabled(actor);
    checkPath(path, true);
    if (path.isEmpty()) {
      if (!actor.equals(home)) forbidden();
      return new Entry(new Binding(home, "", null, principalName(home), false), null, "OWNER");
    }
    var bindings =
        jdbc.query(
            "SELECT * FROM dav_bindings WHERE home_id=? AND path=?",
            (rs, row) ->
                new Binding(
                    home,
                    rs.getString("path"),
                    rs.getObject("collection_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getObject("collection_id") != null),
            home,
            path);
    if (bindings.isEmpty()) missing();
    Binding binding = bindings.getFirst();
    if (!binding.calendar()) {
      if (!actor.equals(home)) forbidden();
      return new Entry(binding, null, "OWNER");
    }
    var collection = calendars.collection(actor, binding.collectionId());
    return new Entry(binding, collection, permission(actor, collection.id()));
  }

  public List<Entry> children(UUID actor, UUID home, String path) {
    Entry parent = resolve(actor, home, path);
    if (parent.binding().calendar()) return List.of();
    List<String> paths =
        jdbc.queryForList(
            "SELECT path FROM dav_bindings WHERE home_id=? AND left(path,?)=? AND position('/' in substring(path from ?))=0 ORDER BY path LIMIT ?",
            String.class,
            home,
            characters(prefix(path)),
            prefix(path),
            characters(prefix(path)) + 1,
            MAX_ROWS + 1);
    bounded(paths.size());
    return paths.stream().map(child -> resolve(actor, home, child)).toList();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot snapshot(UUID actor, UUID home, String path) {
    Entry entry = resolve(actor, home, path);
    if (!entry.binding().calendar())
      return new Snapshot(entry, List.of(), properties(folderProperties(home, path)), null);
    UUID id = entry.collection().id();
    var resources = calendars.metadataBatch(actor, id, "", MAX_ROWS + 1);
    bounded(resources.size());
    String token = calendars.currentSyncToken(actor, id);
    return new Snapshot(entry, resources, properties(deadProperties(actor, id, null)), token);
  }

  @Transactional
  public Entry create(
      UUID actor,
      UUID home,
      String path,
      String displayName,
      String component,
      Map<QName, String> properties) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    writableHome(actor, home);
    checkPath(path, false);
    DavNamespace.binding(path, component != null);
    checkDisplayName(displayName);
    lockHomes(home);
    if (component != null && !Set.of("VEVENT", "VTODO").contains(component))
      throw new IllegalArgumentException("Unsupported calendar component");
    requireFolder(actor, home, parent(path));
    if (bindingExists(home, path)) throw new StoreException(StoreException.Kind.EXISTS);
    validateProperties(properties, Set.of());
    if (component == null) {
      jdbc.update(
          "INSERT INTO dav_bindings(home_id,path,display_name) VALUES (?,?,?)",
          home,
          path,
          displayName);
    } else {
      UUID id = calendars.createCollection(actor, displayName, component);
      jdbc.update(
          "UPDATE dav_bindings SET path=? WHERE home_id=? AND collection_id=?", path, home, id);
    }
    patch(actor, home, path, null, properties, Set.of());
    return resolve(actor, home, path);
  }

  @Transactional
  public PutResult putResource(
      UUID actor, UUID collection, String name, byte[] content, WriteCondition condition) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    if (content == null || content.length == 0)
      throw new org.busystack.caldav.calendar.CalendarValidationException(
          org.busystack.caldav.calendar.CalendarValidationException.Reason.INVALID_DATA);
    if (content.length > 1048576) limit();
    lockCollections(collection);
    requireWrite(actor, collection);
    var current = calendars.get(actor, collection, name);
    var parsed =
        CalendarContent.parse(content, calendars.collection(actor, collection).component());
    return new PutResult(
        calendars.put(actor, collection, name, parsed.uid(), content, condition), current == null);
  }

  public String permission(UUID actor, UUID collection) {
    var stored = calendars.collection(actor, collection);
    if (stored.owner().equals(actor)) return "OWNER";
    var permissions =
        jdbc.queryForList(
            "SELECT permission FROM collection_effective_members WHERE collection_id=? AND account_id=?",
            String.class,
            collection,
            actor);
    if (permissions.isEmpty())
      throw new StoreException(
          StoreException.Kind.FORBIDDEN, StoreException.RequiredAccess.READ, collection);
    return permissions.getFirst();
  }

  public List<Member> members(UUID actor, UUID collection) {
    requireOwner(actor, collection);
    var acl =
        jdbc.query(
            "SELECT account_id AS principal_id,permission,false AS is_group FROM collection_members WHERE collection_id=? UNION ALL SELECT group_id,permission,true FROM collection_group_members WHERE collection_id=? ORDER BY principal_id",
            (rs, row) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)),
            collection,
            collection);
    if (acl.size() > 1000) limit();
    return List.copyOf(acl);
  }

  @Transactional
  public void replaceAcl(UUID actor, UUID collection, List<Member> members) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    lockCollections(collection);
    requireOwner(actor, collection);
    if (members.size() > 1000) limit();
    Set<UUID> distinct = new LinkedHashSet<>();
    for (Member member : members) {
      if (member.account().equals(actor)
          || !distinct.add(member.account())
          || !Set.of("READ", "WRITE").contains(member.permission()))
        throw new IllegalArgumentException("Invalid collection ACL");
      if (member.group()) {
        if (!isGroupPrincipal(member.account()))
          throw new IllegalArgumentException("Unknown sharing group");
      } else requireEnabled(member.account());
    }
    var existing = members(actor, collection);
    jdbc.query("SELECT set_config('busystack.defer_dav_bindings','on',true)", rs -> {});
    for (Member old : existing)
      if (members.stream()
          .noneMatch(
              value -> value.account().equals(old.account()) && value.group() == old.group()))
        jdbc.update(
            "DELETE FROM "
                + (old.group()
                    ? "collection_group_members WHERE group_id=?"
                    : "collection_members WHERE account_id=?")
                + " AND collection_id=?",
            old.account(),
            collection);
    for (Member member : members) {
      String table = member.group() ? "collection_group_members" : "collection_members";
      String key = member.group() ? "group_id" : "account_id";
      jdbc.update(
          "INSERT INTO "
              + table
              + "(collection_id,"
              + key
              + ",permission) VALUES (?,?,?) ON CONFLICT(collection_id,"
              + key
              + ") DO UPDATE SET permission=EXCLUDED.permission",
          collection,
          member.account(),
          member.permission());
    }
    jdbc.query("SELECT set_config('busystack.defer_dav_bindings','off',true)", rs -> {});
    jdbc.query("SELECT dav_reconcile_memberships(?)", rs -> {}, collection);
  }

  private static final String VISIBLE_GROUPS =
      "SELECT g.* FROM sharing_groups g WHERE (EXISTS(SELECT 1 FROM accounts administrator WHERE administrator.id=? AND administrator.enabled AND administrator.administrator) OR EXISTS(SELECT 1 FROM sharing_group_members gm WHERE gm.group_id=g.id AND gm.account_id=?) OR EXISTS(SELECT 1 FROM collection_group_members cg JOIN collections c ON c.id=cg.collection_id WHERE cg.group_id=g.id AND c.owner_id=?))";

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Principal> principals(UUID actor) {
    requireEnabled(actor);
    List<Principal> visible = new ArrayList<>(accountPrincipals(actor, null));
    var groups =
        jdbc.query(
            VISIBLE_GROUPS + " ORDER BY g.id LIMIT 1001",
            (rs, row) ->
                new Principal(
                    rs.getObject("id", UUID.class),
                    "group:" + rs.getString("name"),
                    rs.getString("name"),
                    true,
                    rs.getObject("created_by", UUID.class)),
            actor,
            actor,
            actor);
    visible.addAll(groups);
    bounded(visible.size());
    return List.copyOf(visible);
  }

  /** Own-home URI metadata for protocol errors; never enumerates another principal's home. */
  public Binding bindingFor(UUID actor, UUID collection) {
    requireEnabled(actor);
    return jdbc
        .query(
            "SELECT home_id,path,collection_id,display_name FROM dav_bindings WHERE home_id=? AND collection_id=?",
            (rs, row) ->
                new Binding(
                    rs.getObject("home_id", UUID.class),
                    rs.getString("path"),
                    rs.getObject("collection_id", UUID.class),
                    rs.getString("display_name"),
                    true),
            actor,
            collection)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public boolean isGroupPrincipal(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sharing_groups WHERE id=?)", Boolean.class, id));
  }

  public boolean recognizedPrincipal(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? UNION ALL SELECT 1 FROM sharing_groups WHERE id=?)",
            Boolean.class,
            id,
            id));
  }

  public Principal principal(UUID actor, UUID id) {
    requireEnabled(actor);
    var accounts = accountPrincipals(actor, id);
    if (!accounts.isEmpty()) return accounts.getFirst();
    var groups =
        jdbc.query(
            VISIBLE_GROUPS + " AND g.id=?",
            (rs, row) ->
                new Principal(
                    rs.getObject("id", UUID.class),
                    "group:" + rs.getString("name"),
                    rs.getString("name"),
                    true,
                    rs.getObject("created_by", UUID.class)),
            actor,
            actor,
            actor,
            id);
    if (groups.isEmpty()) missing();
    return groups.getFirst();
  }

  private List<Principal> accountPrincipals(UUID actor, UUID id) {
    String sql =
        "WITH visible_groups AS ("
            + VISIBLE_GROUPS
            + ") SELECT a.id,a.username,a.display_name FROM accounts a WHERE (a.id=? OR EXISTS(SELECT 1 FROM collections c JOIN collection_effective_members m ON m.collection_id=c.id WHERE c.owner_id=a.id AND m.account_id=?) OR EXISTS(SELECT 1 FROM collection_effective_members m JOIN collections c ON c.id=m.collection_id WHERE m.account_id=a.id AND c.owner_id=?) OR EXISTS(SELECT 1 FROM sharing_group_members gm JOIN visible_groups g ON g.id=gm.group_id WHERE gm.account_id=a.id) OR EXISTS(SELECT 1 FROM visible_groups g WHERE g.created_by=a.id))";
    List<Object> parameters = new ArrayList<>(List.of(actor, actor, actor, actor, actor, actor));
    if (id != null) {
      sql += " AND a.id=?";
      parameters.add(id);
    }
    return jdbc.query(
        sql + " ORDER BY a.id LIMIT 10001",
        (rs, row) -> new Principal(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
        parameters.toArray());
  }

  public boolean groupContains(UUID actor, UUID group) {
    requireEnabled(actor);
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sharing_group_members WHERE group_id=? AND account_id=?)",
            Boolean.class,
            group,
            actor));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Principal> groupMembers(UUID actor, UUID group) {
    if (!principal(actor, group).group()) throw new IllegalArgumentException("Not a sharing group");
    var members =
        jdbc.query(
            "SELECT a.id,a.username,a.display_name FROM accounts a JOIN sharing_group_members m ON m.account_id=a.id WHERE m.group_id=? ORDER BY a.id LIMIT 1001",
            (rs, row) ->
                new Principal(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            group);
    if (members.size() > 1000) limit();
    return List.copyOf(members);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Principal> principalGroups(UUID actor, UUID account) {
    // Groups are not nested. Query the visible memberships directly instead of one query per
    // visible group for every principal in a directory response.
    if (principal(actor, account).group()) return List.of();
    var groups =
        jdbc.query(
            VISIBLE_GROUPS
                + " AND EXISTS(SELECT 1 FROM sharing_group_members member WHERE member.group_id=g.id AND member.account_id=?) ORDER BY g.id LIMIT 1001",
            (rs, row) ->
                new Principal(
                    rs.getObject("id", UUID.class),
                    "group:" + rs.getString("name"),
                    rs.getString("name"),
                    true,
                    rs.getObject("created_by", UUID.class)),
            actor,
            actor,
            actor,
            account);
    if (groups.size() > 1000) limit();
    return List.copyOf(groups);
  }

  public static final long MAX_PROPERTY_BYTES = 1024L * 1024;

  private record PropertyTarget(String table, String predicate, List<Object> arguments) {}

  private PropertyTarget propertyTarget(Entry entry, String resource) {
    if (!entry.binding().calendar())
      return new PropertyTarget(
          "dav_binding_properties",
          "home_id=? AND path=?",
          List.of(entry.binding().home(), entry.binding().path()));
    if (resource == null)
      return new PropertyTarget(
          "dav_collection_properties", "collection_id=?", List.of(entry.collection().id()));
    return new PropertyTarget(
        "dav_resource_properties",
        "collection_id=? AND resource_name=?",
        List.of(entry.collection().id(), resource));
  }

  private void requirePropertyResource(UUID actor, UUID collection, String resourceName) {
    calendars.collection(actor, collection);
    if (resourceName != null
        && !Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM calendar_resources WHERE collection_id=? AND name=?)",
                Boolean.class,
                collection,
                resourceName))) missing();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<QName, String> deadProperties(UUID actor, UUID collection, String resourceName) {
    return deadProperties(actor, collection, resourceName, null);
  }

  /** Null selects all values; an explicit set fetches only those names from PostgreSQL. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<QName, String> deadProperties(
      UUID actor, UUID collection, String resourceName, Set<QName> selected) {
    requirePropertyResource(actor, collection, resourceName);
    return readProperties(
        new PropertyTarget(
            resourceName == null ? "dav_collection_properties" : "dav_resource_properties",
            resourceName == null ? "collection_id=?" : "collection_id=? AND resource_name=?",
            resourceName == null ? List.of(collection) : List.of(collection, resourceName)),
        selected);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Set<QName> deadPropertyNames(UUID actor, UUID collection, String resourceName) {
    requirePropertyResource(actor, collection, resourceName);
    return readPropertyNames(
        new PropertyTarget(
            resourceName == null ? "dav_collection_properties" : "dav_resource_properties",
            resourceName == null ? "collection_id=?" : "collection_id=? AND resource_name=?",
            resourceName == null ? List.of(collection) : List.of(collection, resourceName)));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public String deadProperty(UUID actor, UUID collection, String resourceName, QName name) {
    return deadProperties(actor, collection, resourceName, Set.of(name)).get(name);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<QName, String> folderProperties(
      UUID actor, UUID home, String path, Set<QName> selected) {
    Entry entry = resolve(actor, home, path);
    if (entry.binding().calendar()) throw new IllegalArgumentException("Expected folder binding");
    if (path.isEmpty()) return Map.of();
    return readProperties(propertyTarget(entry, null), selected);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Set<QName> folderPropertyNames(UUID actor, UUID home, String path) {
    Entry entry = resolve(actor, home, path);
    if (entry.binding().calendar()) throw new IllegalArgumentException("Expected folder binding");
    if (path.isEmpty()) return Set.of();
    return readPropertyNames(propertyTarget(entry, null));
  }

  private Map<QName, String> readProperties(PropertyTarget target, Set<QName> selected) {
    if (selected != null && selected.isEmpty()) return Map.of();
    if (selected != null && selected.size() > 1000) limit();
    List<Object> arguments = new ArrayList<>(target.arguments());
    String selection = "";
    if (selected != null) {
      List<String> clauses = new ArrayList<>();
      for (QName name : selected) {
        clauses.add("(namespace=? AND name=?)");
        arguments.add(name.getNamespaceURI());
        arguments.add(name.getLocalPart());
      }
      selection = " AND (" + String.join(" OR ", clauses) + ")";
    }
    String from = " FROM " + target.table() + " WHERE " + target.predicate() + selection;
    long[] size =
        jdbc.queryForObject(
            "SELECT count(*),COALESCE(sum(octet_length(xml)),0)" + from,
            (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)},
            arguments.toArray());
    if (size[0] > 1000 || size[1] > MAX_PROPERTY_BYTES) limit();
    return propertyMap(
        jdbc.query(
            "SELECT namespace,name,xml" + from + " ORDER BY namespace,name LIMIT 1001",
            (rs, row) -> new Property(rs.getString(1), rs.getString(2), rs.getString(3)),
            arguments.toArray()));
  }

  private Set<QName> readPropertyNames(PropertyTarget target) {
    var names =
        jdbc.query(
            "SELECT namespace,name FROM "
                + target.table()
                + " WHERE "
                + target.predicate()
                + " ORDER BY namespace,name LIMIT 1001",
            (rs, row) -> new QName(rs.getString(1), rs.getString(2)),
            target.arguments().toArray());
    if (names.size() > 1000) limit();
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(names));
  }

  /** The caller holds the binding home and, for shared resources, the collection row lock. */
  private List<Map.Entry<QName, String>> checkPropertyBudget(
      Entry entry, String resource, Map<QName, String> set, Set<QName> remove) {
    PropertyTarget target = propertyTarget(entry, resource);
    Map<QName, Integer> lengths = new LinkedHashMap<>();
    jdbc.query(
        "SELECT namespace,name,octet_length(xml) FROM "
            + target.table()
            + " WHERE "
            + target.predicate(),
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> lengths.put(new QName(rs.getString(1), rs.getString(2)), rs.getInt(3)),
        target.arguments().toArray());
    Map<QName, Integer> original = Map.copyOf(lengths);
    long previous = lengths.values().stream().mapToLong(Integer::longValue).sum();
    int previousCount = lengths.size();
    remove.forEach(lengths::remove);
    set.forEach((name, xml) -> lengths.put(name, xml.getBytes(StandardCharsets.UTF_8).length));
    long next = lengths.values().stream().mapToLong(Integer::longValue).sum();
    // Previously accepted oversized targets remain repairable by removal or a non-increasing
    // update.
    if (next > MAX_PROPERTY_BYTES && next > previous
        || lengths.size() > 1000 && lengths.size() > previousCount) limit();
    return set.entrySet().stream()
        .sorted(
            java.util.Comparator.comparingLong(
                item ->
                    item.getValue().getBytes(StandardCharsets.UTF_8).length
                        - original.getOrDefault(item.getKey(), 0)))
        .toList();
  }

  @Transactional
  public void patch(
      UUID actor,
      UUID home,
      String path,
      String resourceName,
      Map<QName, String> set,
      Set<QName> remove) {
    patch(actor, home, path, resourceName, set, remove, WriteCondition.none());
  }

  @Transactional
  public void patch(
      UUID actor,
      UUID home,
      String path,
      String resourceName,
      Map<QName, String> set,
      Set<QName> remove,
      WriteCondition condition) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    writableHome(actor, home);
    checkPath(path, true);
    lockHomes(home);
    Entry entry = resolve(actor, home, path);
    if (entry.binding().calendar()) {
      lockCollections(entry.collection().id());
      requireWrite(actor, entry.collection().id());
    } else if (resourceName != null) throw new StoreException(StoreException.Kind.CONFLICT);
    CalendarStore.Metadata resource =
        resourceName == null
            ? null
            : calendars.metadata(actor, entry.collection().id(), resourceName);
    if (resourceName != null && resource == null) missing();
    condition.check(
        resource == null ? null : resource.etag(),
        true,
        resource == null ? null : resource.modifiedAt());
    validateProperties(set, remove);
    var orderedProperties = checkPropertyBudget(entry, resourceName, set, remove);
    if (remove.contains(DISPLAY_NAME) && resourceName == null)
      throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
    if (set.containsKey(DISPLAY_NAME)) {
      String display = propertyText(DISPLAY_NAME, set.get(DISPLAY_NAME));
      checkDisplayName(display);
      if (resourceName == null) {
        if (entry.binding().calendar())
          jdbc.update("UPDATE collections SET name=? WHERE id=?", display, entry.collection().id());
        else if (!path.isEmpty())
          jdbc.update(
              "UPDATE dav_bindings SET display_name=? WHERE home_id=? AND path=?",
              display,
              home,
              path);
        else throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
      }
    }
    if (path.isEmpty() && (!set.isEmpty() || !remove.isEmpty()))
      throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
    for (QName name : remove) deleteProperty(entry, resourceName, name);
    for (var item : orderedProperties)
      putProperty(entry, resourceName, item.getKey(), item.getValue());
    QName timezoneProperty = new QName("urn:ietf:params:xml:ns:caldav", "calendar-timezone");
    if (resourceName == null
        && entry.binding().calendar()
        && (set.containsKey(timezoneProperty) || remove.contains(timezoneProperty)))
      jdbc.update(
          "UPDATE collections SET generation=gen_random_uuid() WHERE id=?",
          entry.collection().id());
    if (resourceName != null && (!set.isEmpty() || !remove.isEmpty())) {
      long revision = changed(entry.collection().id(), resourceName, false);
      jdbc.update(
          "UPDATE calendar_resources SET revision=? WHERE collection_id=? AND name=?",
          revision,
          entry.collection().id(),
          resourceName);
    }
  }

  @Transactional
  public void renameDisplayName(UUID actor, UUID home, String path, String name) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    checkDisplayName(name);
    writableHome(actor, home);
    lockHomes(home);
    Entry entry = resolve(actor, home, path);
    if (entry.binding().calendar()) {
      lockCollections(entry.collection().id());
      requireWrite(actor, entry.collection().id());
      jdbc.update("UPDATE collections SET name=? WHERE id=?", name, entry.collection().id());
    } else if (!path.isEmpty())
      jdbc.update(
          "UPDATE dav_bindings SET display_name=? WHERE home_id=? AND path=?", name, home, path);
    else throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
  }

  @Transactional
  public void deleteBinding(UUID actor, UUID home, String path, WriteCondition condition) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    writableHome(actor, home);
    checkPath(path, false);
    lockHomes(home);
    Entry entry = resolve(actor, home, path);
    condition.check(bindingEtag(entry), true);
    List<Binding> tree = tree(home, path);
    lockCollections(
        tree.stream().filter(Binding::calendar).map(Binding::collectionId).toArray(UUID[]::new));
    for (Binding binding : tree)
      if (binding.calendar()) requireOwner(actor, binding.collectionId());
    for (Binding binding : tree)
      if (binding.calendar())
        jdbc.update("DELETE FROM collections WHERE id=?", binding.collectionId());
    jdbc.update(
        "DELETE FROM dav_bindings WHERE home_id=? AND (path=? OR left(path,?)=?)",
        home,
        path,
        characters(prefix(path)),
        prefix(path));
  }

  @Transactional
  public boolean transferResource(
      UUID actor,
      UUID sourceCollection,
      String sourceName,
      UUID destinationCollection,
      String destinationName,
      boolean move,
      boolean overwrite,
      WriteCondition condition) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    checkSegment(sourceName);
    checkSegment(destinationName);
    lockCollections(sourceCollection, destinationCollection);
    calendars.collection(actor, sourceCollection);
    requireWrite(actor, destinationCollection);
    DavNamespace.resourceBindings(jdbc, destinationCollection, destinationName);
    if (move) requireWrite(actor, sourceCollection);
    if (sourceCollection.equals(destinationCollection) && sourceName.equals(destinationName))
      forbidden();
    var source = calendars.get(actor, sourceCollection, sourceName);
    if (source == null) missing();
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT count(*)>1000 OR COALESCE(sum(octet_length(xml)),0)>1048576 FROM dav_resource_properties WHERE collection_id=? AND resource_name=?",
            Boolean.class,
            sourceCollection,
            sourceName))) limit();
    condition.check(source.etag(), true, source.modifiedAt());
    var destination = calendars.get(actor, destinationCollection, destinationName);
    if (destination != null && !overwrite)
      throw new StoreException(StoreException.Kind.PRECONDITION);
    if (destination != null && !destination.uid().equals(source.uid()))
      throw new StoreException(StoreException.Kind.UID_CONFLICT);
    CalendarContent.parse(
        source.content(), calendars.collection(actor, destinationCollection).component());
    var duplicate =
        jdbc.queryForList(
            "SELECT name FROM calendar_resources WHERE collection_id=? AND uid=? AND name<>?",
            String.class,
            destinationCollection,
            source.uid(),
            destinationName);
    if (duplicate.stream()
        .anyMatch(
            name ->
                !move
                    || !sourceCollection.equals(destinationCollection)
                    || !name.equals(sourceName)))
      throw new StoreException(StoreException.Kind.UID_CONFLICT);
    java.sql.Timestamp unchangedDate =
        destination != null && java.util.Arrays.equals(source.content(), destination.content())
            ? java.sql.Timestamp.from(destination.modifiedAt())
            : null;
    if (destination != null)
      jdbc.update(
          "DELETE FROM calendar_resources WHERE collection_id=? AND name=?",
          destinationCollection,
          destinationName);
    if (move) {
      changed(sourceCollection, sourceName, true);
      long revision = changed(destinationCollection, destinationName, false);
      jdbc.update(
          "UPDATE calendar_resources SET collection_id=?,name=?,revision=?,updated_at=COALESCE(?::timestamptz,clock_timestamp()) WHERE collection_id=? AND name=?",
          destinationCollection,
          destinationName,
          revision,
          unchangedDate,
          sourceCollection,
          sourceName);
    } else {
      long revision = changed(destinationCollection, destinationName, false);
      jdbc.update(
          "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision,updated_at) VALUES (?,?,?,?,?,?,COALESCE(?::timestamptz,clock_timestamp()))",
          destinationCollection,
          destinationName,
          source.uid(),
          source.content(),
          source.etag(),
          revision,
          unchangedDate);
      jdbc.update(
          "INSERT INTO dav_resource_properties(collection_id,resource_name,namespace,name,xml) SELECT ?,?,namespace,name,xml FROM dav_resource_properties WHERE collection_id=? AND resource_name=?",
          destinationCollection,
          destinationName,
          sourceCollection,
          sourceName);
    }
    return destination == null;
  }

  public static String bindingEtag(Entry entry) {
    return entry.collection() == null
        ? null
        : "\"collection-"
            + entry.collection().generation()
            + "-"
            + entry.collection().revision()
            + "\"";
  }

  @Transactional
  public boolean transfer(
      UUID actor,
      UUID sourceHome,
      String sourcePath,
      UUID destinationHome,
      String destinationPath,
      boolean move,
      boolean overwrite,
      WriteCondition condition) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    return transfer(
        actor,
        sourceHome,
        sourcePath,
        destinationHome,
        destinationPath,
        move,
        overwrite,
        condition,
        true);
  }

  @Transactional
  public boolean transfer(
      UUID actor,
      UUID sourceHome,
      String sourcePath,
      UUID destinationHome,
      String destinationPath,
      boolean move,
      boolean overwrite,
      WriteCondition condition,
      boolean deep) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    writableHome(actor, destinationHome);
    if (move) writableHome(actor, sourceHome);
    checkPath(sourcePath, false);
    checkPath(destinationPath, false);
    lockHomes(sourceHome, destinationHome);
    if (sourceHome.equals(destinationHome)
        && (sourcePath.equals(destinationPath)
            || destinationPath.startsWith(prefix(sourcePath))
            || sourcePath.startsWith(prefix(destinationPath)))) forbidden();
    Entry source = resolve(actor, sourceHome, sourcePath);
    requireFolder(actor, destinationHome, parent(destinationPath));
    List<Binding> selected = deep ? tree(sourceHome, sourcePath) : List.of(source.binding());
    boolean existed = bindingExists(destinationHome, destinationPath);
    if (existed && !overwrite) throw new StoreException(StoreException.Kind.PRECONDITION);
    List<Binding> replaced = existed ? tree(destinationHome, destinationPath) : List.of();
    List<UUID> ids = new ArrayList<>();
    selected.stream().filter(Binding::calendar).map(Binding::collectionId).forEach(ids::add);
    replaced.stream().filter(Binding::calendar).map(Binding::collectionId).forEach(ids::add);
    lockCollections(ids.toArray(UUID[]::new));
    source = resolve(actor, sourceHome, sourcePath);
    condition.check(bindingEtag(source), true);
    for (Binding item : selected) {
      calendarsIfVisible(actor, item);
      if (move && item.calendar()) requireOwner(actor, item.collectionId());
    }
    for (Binding item : replaced) if (item.calendar()) requireOwner(actor, item.collectionId());
    // Validate the entire resulting namespace before deleting an overwritten destination.
    for (Binding item : selected) {
      String target = destinationPath + item.path().substring(sourcePath.length());
      DavNamespace.binding(target, item.calendar());
      propertyCopyBudget(item, deep);
      if (item.calendar() && deep)
        jdbc.query(
            connection -> {
              var statement =
                  connection.prepareStatement(
                      "SELECT name FROM calendar_resources WHERE collection_id=? ORDER BY name");
              statement.setObject(1, item.collectionId());
              statement.setFetchSize(128);
              return statement;
            },
            (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> DavNamespace.resource(target, rs.getString(1)));
    }
    if (move && !deep) throw new IllegalArgumentException("MOVE requires complete subtree");
    if (existed) deleteBinding(actor, destinationHome, destinationPath, WriteCondition.none());
    if (move) {
      jdbc.update(
          "UPDATE dav_bindings SET path=?||substring(path from ?) WHERE home_id=? AND (path=? OR left(path,?)=?)",
          destinationPath,
          characters(sourcePath) + 1,
          sourceHome,
          sourcePath,
          characters(prefix(sourcePath)),
          prefix(sourcePath));
      // Moving a namespace changes every contained resource's URI; an overwritten
      // destination must never inherit the old source's earlier modification date.
      for (Binding item : selected)
        if (item.calendar()) {
          long previous = calendars.collection(actor, item.collectionId()).revision();
          jdbc.update(
              "UPDATE calendar_resources r SET revision=?+n.position,updated_at=clock_timestamp() FROM (SELECT name,row_number() OVER(ORDER BY name COLLATE \"C\") AS position FROM calendar_resources WHERE collection_id=?) n WHERE r.collection_id=? AND r.name=n.name",
              previous,
              item.collectionId(),
              item.collectionId());
          jdbc.update(
              "INSERT INTO collection_changes(collection_id,revision,name,deleted) SELECT collection_id,revision,name,false FROM calendar_resources WHERE collection_id=? AND revision>?",
              item.collectionId(),
              previous);
          jdbc.update(
              "UPDATE collections SET revision=COALESCE((SELECT max(revision) FROM calendar_resources WHERE collection_id=?),revision) WHERE id=?",
              item.collectionId(),
              item.collectionId());
        }
    } else {
      for (Binding item : selected) {
        String target = destinationPath + item.path().substring(sourcePath.length());
        Entry created =
            create(
                actor,
                destinationHome,
                target,
                item.displayName(),
                item.calendar()
                    ? calendars.collection(actor, item.collectionId()).component()
                    : null,
                Map.of());
        if (item.calendar()) {
          UUID targetId = created.collection().id();
          jdbc.update(
              "INSERT INTO dav_collection_properties(collection_id,namespace,name,xml) SELECT ?,namespace,name,xml FROM dav_collection_properties WHERE collection_id=?",
              targetId,
              item.collectionId());
          if (deep) {
            // The source is already validated and collection-locked. Copy bytes and indexes in
            // PostgreSQL instead of accumulating every calendar body in application memory.
            jdbc.update(
                "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision,updated_at) SELECT ?,name,uid,content,etag,row_number() OVER(ORDER BY name COLLATE \"C\"),clock_timestamp() FROM calendar_resources WHERE collection_id=?",
                targetId,
                item.collectionId());
            jdbc.update(
                "INSERT INTO collection_changes(collection_id,revision,name,deleted) SELECT collection_id,revision,name,false FROM calendar_resources WHERE collection_id=?",
                targetId);
            jdbc.update(
                "UPDATE collections SET revision=(SELECT COALESCE(max(revision),0) FROM calendar_resources WHERE collection_id=?) WHERE id=?",
                targetId,
                targetId);
            jdbc.update(
                "INSERT INTO dav_resource_properties(collection_id,resource_name,namespace,name,xml) SELECT ?,resource_name,namespace,name,xml FROM dav_resource_properties WHERE collection_id=?",
                targetId,
                item.collectionId());
          }
        } else
          jdbc.update(
              "INSERT INTO dav_binding_properties(home_id,path,namespace,name,xml) SELECT ?,?,namespace,name,xml FROM dav_binding_properties WHERE home_id=? AND path=?",
              destinationHome,
              target,
              sourceHome,
              item.path());
      }
    }
    return !existed;
  }

  private void calendarsIfVisible(UUID actor, Binding item) {
    if (item.calendar()) calendars.collection(actor, item.collectionId());
  }

  private void propertyCopyBudget(Binding binding, boolean deep) {
    boolean excessive;
    if (!binding.calendar())
      excessive =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT count(*)>1000 OR COALESCE(sum(octet_length(xml)),0)>1048576 FROM dav_binding_properties WHERE home_id=? AND path=?",
                  Boolean.class,
                  binding.home(),
                  binding.path()));
    else {
      excessive =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT count(*)>1000 OR COALESCE(sum(octet_length(xml)),0)>1048576 FROM dav_collection_properties WHERE collection_id=?",
                  Boolean.class,
                  binding.collectionId()));
      if (deep && !excessive)
        excessive =
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM dav_resource_properties WHERE collection_id=? GROUP BY resource_name HAVING count(*)>1000 OR sum(octet_length(xml))>1048576)",
                    Boolean.class,
                    binding.collectionId()));
    }
    if (excessive) limit();
  }

  private Map<QName, String> folderProperties(UUID home, String path) {
    if (path.isEmpty()) return Map.of();
    return readProperties(
        new PropertyTarget("dav_binding_properties", "home_id=? AND path=?", List.of(home, path)),
        null);
  }

  private static Map<QName, String> propertyMap(List<Property> properties) {
    Map<QName, String> result = new LinkedHashMap<>();
    for (Property value : properties)
      result.put(new QName(value.namespace(), value.name()), value.xml());
    return Map.copyOf(result);
  }

  private static List<Property> properties(Map<QName, String> map) {
    return map.entrySet().stream()
        .map(
            item ->
                new Property(
                    item.getKey().getNamespaceURI(), item.getKey().getLocalPart(), item.getValue()))
        .toList();
  }

  private void deleteProperty(Entry entry, String resource, QName name) {
    if (!entry.binding().calendar())
      jdbc.update(
          "DELETE FROM dav_binding_properties WHERE home_id=? AND path=? AND namespace=? AND name=?",
          entry.binding().home(),
          entry.binding().path(),
          name.getNamespaceURI(),
          name.getLocalPart());
    else if (resource == null)
      jdbc.update(
          "DELETE FROM dav_collection_properties WHERE collection_id=? AND namespace=? AND name=?",
          entry.collection().id(),
          name.getNamespaceURI(),
          name.getLocalPart());
    else
      jdbc.update(
          "DELETE FROM dav_resource_properties WHERE collection_id=? AND resource_name=? AND namespace=? AND name=?",
          entry.collection().id(),
          resource,
          name.getNamespaceURI(),
          name.getLocalPart());
  }

  private void putProperty(Entry entry, String resource, QName name, String xml) {
    try {
      if (!entry.binding().calendar())
        jdbc.update(
            "INSERT INTO dav_binding_properties(home_id,path,namespace,name,xml) VALUES (?,?,?,?,?) ON CONFLICT(home_id,path,namespace,name) DO UPDATE SET xml=EXCLUDED.xml",
            entry.binding().home(),
            entry.binding().path(),
            name.getNamespaceURI(),
            name.getLocalPart(),
            xml);
      else if (resource == null)
        jdbc.update(
            "INSERT INTO dav_collection_properties(collection_id,namespace,name,xml) VALUES (?,?,?,?) ON CONFLICT(collection_id,namespace,name) DO UPDATE SET xml=EXCLUDED.xml",
            entry.collection().id(),
            name.getNamespaceURI(),
            name.getLocalPart(),
            xml);
      else
        jdbc.update(
            "INSERT INTO dav_resource_properties(collection_id,resource_name,namespace,name,xml) VALUES (?,?,?,?,?) ON CONFLICT(collection_id,resource_name,namespace,name) DO UPDATE SET xml=EXCLUDED.xml",
            entry.collection().id(),
            resource,
            name.getNamespaceURI(),
            name.getLocalPart(),
            xml);
    } catch (org.springframework.dao.DataIntegrityViolationException failure) {
      if (failure.getMostSpecificCause() instanceof java.sql.SQLException sql
          && "23514".equals(sql.getSQLState())
          && sql.getMessage().contains("property_storage_budget")) limit();
      throw failure;
    }
  }

  private static final Set<String> PROTECTED_DAV =
      Set.of(
          "resourcetype",
          "getetag",
          "getcontenttype",
          "getcontentlength",
          "getlastmodified",
          "creationdate",
          "owner",
          "acl",
          "acl-restrictions",
          "inherited-acl-set",
          "principal-collection-set",
          "current-user-principal",
          "current-user-privilege-set",
          "supported-privilege-set",
          "supported-report-set",
          "supported-method-set",
          "sync-token",
          "supportedlock",
          "lockdiscovery",
          "principal-URL",
          "group-member-set",
          "group-membership");
  private static final Set<String> PROTECTED_CAL =
      Set.of(
          "calendar-data",
          "calendar-home-set",
          "calendar-user-address-set",
          "supported-calendar-component-set",
          "supported-calendar-data",
          "max-resource-size",
          "min-date-time",
          "max-date-time",
          "max-instances",
          "max-attendees-per-instance",
          "schedule-inbox-URL",
          "schedule-outbox-URL");

  private static void validateProperties(Map<QName, String> set, Set<QName> remove) {
    if (set.size() + remove.size() > 1000) limit();
    Set<QName> names = new LinkedHashSet<>(set.keySet());
    names.addAll(remove);
    for (QName name : names) {
      if (name == null
          || name.getNamespaceURI().length() > 1024
          || name.getLocalPart().length() > 200
          || name.getLocalPart().isEmpty()
          || (name.getNamespaceURI().equals("DAV:") && PROTECTED_DAV.contains(name.getLocalPart()))
          || (name.getNamespaceURI().equals("urn:ietf:params:xml:ns:caldav")
              && PROTECTED_CAL.contains(name.getLocalPart())))
        throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
      if (set.containsKey(name)) {
        String xml = set.get(name);
        if (xml == null || xml.getBytes(StandardCharsets.UTF_8).length > 65536) limit();
        propertyText(name, xml);
      }
    }
  }

  private static String propertyText(QName name, String xml) {
    try {
      var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setAttribute("jdk.xml.maxElementDepth", "32");
      factory.setAttribute("jdk.xml.elementAttributeLimit", "100");
      factory.setAttribute("jdk.xml.maxXMLNameLimit", "256");
      var parser = factory.newDocumentBuilder();
      parser.setErrorHandler(
          new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void error(org.xml.sax.SAXParseException failure)
                throws org.xml.sax.SAXException {
              throw failure;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException failure)
                throws org.xml.sax.SAXException {
              throw failure;
            }
          });
      var element =
          parser
              .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)))
              .getDocumentElement();
      if (!name.getLocalPart().equals(element.getLocalName())
          || !name.getNamespaceURI()
              .equals(element.getNamespaceURI() == null ? "" : element.getNamespaceURI()))
        throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
      return element.getTextContent();
    } catch (javax.xml.parsers.ParserConfigurationException
        | org.xml.sax.SAXException
        | java.io.IOException failure) {
      throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
    }
  }

  private void writableHome(UUID actor, UUID home) {
    requireEnabled(actor);
    if (!actor.equals(home)) forbidden();
  }

  private void requireEnabled(UUID actor) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND enabled)", Boolean.class, actor)))
      forbidden();
  }

  private void requireOwner(UUID actor, UUID id) {
    if (!calendars.collection(actor, id).owner().equals(actor))
      throw new StoreException(
          StoreException.Kind.FORBIDDEN, StoreException.RequiredAccess.OWNER, id);
  }

  private void requireWrite(UUID actor, UUID id) {
    if ("READ".equals(permission(actor, id)))
      throw new StoreException(
          StoreException.Kind.FORBIDDEN, StoreException.RequiredAccess.WRITE, id);
  }

  private String principalName(UUID home) {
    var names =
        jdbc.queryForList("SELECT display_name FROM accounts WHERE id=?", String.class, home);
    if (names.isEmpty()) missing();
    return names.getFirst();
  }

  private void lockHomes(UUID... homes) {
    java.util.Arrays.stream(homes)
        .distinct()
        .sorted()
        .forEach(
            home ->
                jdbc.query(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                    rs -> {},
                    "busystack.dav.home:" + home));
  }

  private void lockCollections(UUID... ids) {
    java.util.Arrays.stream(ids)
        .distinct()
        .sorted()
        .forEach(
            id -> {
              if (jdbc.queryForList(
                      "SELECT id FROM collections WHERE id=? FOR UPDATE", UUID.class, id)
                  .isEmpty()) missing();
            });
  }

  private void requireFolder(UUID actor, UUID home, String path) {
    try {
      if (resolve(actor, home, path).binding().calendar())
        throw new StoreException(StoreException.Kind.CONFLICT);
    } catch (StoreException failure) {
      if (failure.kind() == StoreException.Kind.NOT_FOUND)
        throw new StoreException(StoreException.Kind.CONFLICT);
      throw failure;
    }
  }

  private boolean bindingExists(UUID home, String path) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM dav_bindings WHERE home_id=? AND path=?)",
            Boolean.class,
            home,
            path));
  }

  private List<Binding> tree(UUID home, String path) {
    var rows =
        jdbc.query(
            "SELECT * FROM dav_bindings WHERE home_id=? AND (path=? OR left(path,?)=?) ORDER BY length(path),path LIMIT ?",
            (rs, row) ->
                new Binding(
                    home,
                    rs.getString("path"),
                    rs.getObject("collection_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getObject("collection_id") != null),
            home,
            path,
            characters(prefix(path)),
            prefix(path),
            MAX_ROWS + 1);
    bounded(rows.size());
    return rows;
  }

  private long changed(UUID id, String name, boolean deleted) {
    long revision =
        jdbc.queryForObject(
            "UPDATE collections SET revision=revision+1 WHERE id=? RETURNING revision",
            Long.class,
            id);
    jdbc.update(
        "INSERT INTO collection_changes(collection_id,revision,name,deleted) VALUES (?,?,?,?)",
        id,
        revision,
        name,
        deleted);
    return revision;
  }

  private static CalendarStore.Resource resource(java.sql.ResultSet rs)
      throws java.sql.SQLException {
    return new CalendarStore.Resource(
        rs.getString("name"),
        rs.getString("uid"),
        rs.getBytes("content"),
        rs.getString("etag"),
        rs.getLong("revision"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static String prefix(String path) {
    return path.isEmpty() ? "" : path + "/";
  }

  private static int characters(String value) {
    return value.codePointCount(0, value.length());
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private static void checkPath(String path, boolean root) {
    if (path == null || (!root && path.isEmpty()))
      throw new IllegalArgumentException("Invalid DAV path");
    DavNamespace.binding(path, false);
  }

  private static void checkSegment(String name) {
    DavNamespace.segment(name);
  }

  private static void checkDisplayName(String name) {
    if (name == null || name.isBlank() || name.length() > 200 || !XmlCharacters.valid(name))
      throw new IllegalArgumentException("Invalid display name");
  }

  private static void bounded(int count) {
    if (count > MAX_ROWS) limit();
  }

  private static void limit() {
    throw new StoreException(StoreException.Kind.LIMIT);
  }

  private static void missing() {
    throw new StoreException(StoreException.Kind.NOT_FOUND);
  }

  private static void forbidden() {
    throw new StoreException(StoreException.Kind.FORBIDDEN);
  }
}
