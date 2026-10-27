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

  public record Member(UUID account, String permission) {}

  public record Property(String namespace, String name, String xml) {}

  public record Principal(UUID id, String username, String displayName) {}

  public record Snapshot(
      Entry entry,
      List<CalendarStore.Resource> resources,
      List<Property> properties,
      String syncToken) {
    public Snapshot {
      resources = List.copyOf(resources);
      properties = List.copyOf(properties);
    }
  }

  public record PutResult(CalendarStore.Resource resource, boolean created) {}

  private static final int MAX_ROWS = 10000;
  private static final long MAX_BYTES = 32L * 1024 * 1024;
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
    resourceBudget(id);
    var resources =
        jdbc.query(
            "SELECT * FROM calendar_resources WHERE collection_id=? ORDER BY name LIMIT ?",
            (rs, row) -> resource(rs),
            id,
            MAX_ROWS + 1);
    bounded(resources.size());
    var epoch =
        jdbc.queryForObject("SELECT recovery_epoch FROM system_state WHERE id=1", UUID.class);
    String token =
        "urn:busystack:sync:"
            + epoch
            + ":"
            + id
            + ":"
            + entry.collection().generation()
            + ":"
            + entry.collection().revision();
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
    if (content == null || content.length == 0 || content.length > 1048576) limit();
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
            "SELECT permission FROM collection_members WHERE collection_id=? AND account_id=?",
            String.class,
            collection,
            actor);
    if (permissions.isEmpty()) forbidden();
    return permissions.getFirst();
  }

  public List<Member> members(UUID actor, UUID collection) {
    requireOwner(actor, collection);
    return jdbc.query(
        "SELECT account_id,permission FROM collection_members WHERE collection_id=? ORDER BY account_id",
        (rs, row) -> new Member(rs.getObject(1, UUID.class), rs.getString(2)),
        collection);
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
      requireEnabled(member.account());
    }
    jdbc.update("DELETE FROM collection_members WHERE collection_id=?", collection);
    for (Member member : members)
      jdbc.update(
          "INSERT INTO collection_members(collection_id,account_id,permission) VALUES (?,?,?)",
          collection,
          member.account(),
          member.permission());
    jdbc.update("UPDATE collections SET generation=gen_random_uuid() WHERE id=?", collection);
  }

  public List<Principal> principals(UUID actor) {
    requireEnabled(actor);
    var visible =
        jdbc.query(
            "SELECT DISTINCT a.id,a.username,a.display_name FROM accounts a WHERE a.enabled AND (a.id=? OR EXISTS(SELECT 1 FROM collections c WHERE c.owner_id=a.id AND EXISTS(SELECT 1 FROM collection_members m WHERE m.collection_id=c.id AND m.account_id=?)) OR EXISTS(SELECT 1 FROM collection_members m JOIN collections c ON c.id=m.collection_id WHERE m.account_id=a.id AND c.owner_id=?)) ORDER BY a.id LIMIT ?",
            (rs, row) ->
                new Principal(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            actor,
            actor,
            actor,
            MAX_ROWS + 1);
    bounded(visible.size());
    return visible;
  }

  public Map<QName, String> deadProperties(UUID actor, UUID collection, String resourceName) {
    calendars.collection(actor, collection);
    if (resourceName != null && calendars.get(actor, collection, resourceName) == null) missing();
    return resourceName == null
        ? propertyMap(
            jdbc.query(
                "SELECT namespace,name,xml FROM dav_collection_properties WHERE collection_id=? ORDER BY namespace,name",
                (rs, row) -> new Property(rs.getString(1), rs.getString(2), rs.getString(3)),
                collection))
        : propertyMap(
            jdbc.query(
                "SELECT namespace,name,xml FROM dav_resource_properties WHERE collection_id=? AND resource_name=? ORDER BY namespace,name",
                (rs, row) -> new Property(rs.getString(1), rs.getString(2), rs.getString(3)),
                collection,
                resourceName));
  }

  @Transactional
  public void patch(
      UUID actor,
      UUID home,
      String path,
      String resourceName,
      Map<QName, String> set,
      Set<QName> remove) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    writableHome(actor, home);
    checkPath(path, true);
    lockHomes(home);
    Entry entry = resolve(actor, home, path);
    if (entry.binding().calendar()) {
      lockCollections(entry.collection().id());
      requireWrite(actor, entry.collection().id());
    } else if (resourceName != null) throw new StoreException(StoreException.Kind.CONFLICT);
    validateProperties(set, remove);
    if (resourceName != null && calendars.get(actor, entry.collection().id(), resourceName) == null)
      missing();
    if (remove.contains(DISPLAY_NAME))
      throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
    if (set.containsKey(DISPLAY_NAME)) {
      if (resourceName != null) throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
      String display = propertyText(DISPLAY_NAME, set.get(DISPLAY_NAME));
      checkDisplayName(display);
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
    if (path.isEmpty() && (!set.isEmpty() || !remove.isEmpty()))
      throw new StoreException(StoreException.Kind.INVALID_PROPERTY);
    for (QName name : remove) deleteProperty(entry, resourceName, name);
    for (var item : set.entrySet())
      if (!item.getKey().equals(DISPLAY_NAME))
        putProperty(entry, resourceName, item.getKey(), item.getValue());
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
    if (move) requireWrite(actor, sourceCollection);
    if (sourceCollection.equals(destinationCollection) && sourceName.equals(destinationName))
      forbidden();
    var source = calendars.get(actor, sourceCollection, sourceName);
    if (source == null) missing();
    condition.check(source.etag());
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
    if (destination != null)
      jdbc.update(
          "DELETE FROM calendar_resources WHERE collection_id=? AND name=?",
          destinationCollection,
          destinationName);
    if (move) {
      changed(sourceCollection, sourceName, true);
      long revision = changed(destinationCollection, destinationName, false);
      jdbc.update(
          "UPDATE calendar_resources SET collection_id=?,name=?,revision=?,updated_at=now() WHERE collection_id=? AND name=?",
          destinationCollection,
          destinationName,
          revision,
          sourceCollection,
          sourceName);
    } else {
      long revision = changed(destinationCollection, destinationName, false);
      jdbc.update(
          "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) VALUES (?,?,?,?,?,?)",
          destinationCollection,
          destinationName,
          source.uid(),
          source.content(),
          source.etag(),
          revision);
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
    if (move && !deep) throw new IllegalArgumentException("MOVE requires complete subtree");
    long resources = 0;
    long totalBytes = 0;
    if (!move && deep)
      for (Binding item : selected)
        if (item.calendar()) {
          var size = resourceBudget(item.collectionId());
          resources += size[0];
          totalBytes += size[1];
        }
    if (resources > MAX_ROWS || totalBytes > MAX_BYTES) limit();
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
            var rows =
                jdbc.query(
                    "SELECT * FROM calendar_resources WHERE collection_id=? ORDER BY name",
                    (rs, row) -> resource(rs),
                    item.collectionId());
            for (var row : rows)
              calendars.put(
                  actor, targetId, row.name(), row.uid(), row.content(), WriteCondition.create());
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

  private Map<QName, String> folderProperties(UUID home, String path) {
    if (path.isEmpty()) return Map.of();
    return propertyMap(
        jdbc.query(
            "SELECT namespace,name,xml FROM dav_binding_properties WHERE home_id=? AND path=? ORDER BY namespace,name",
            (rs, row) -> new Property(rs.getString(1), rs.getString(2), rs.getString(3)),
            home,
            path));
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
    long count;
    if (!entry.binding().calendar()) {
      jdbc.update(
          "INSERT INTO dav_binding_properties(home_id,path,namespace,name,xml) VALUES (?,?,?,?,?) ON CONFLICT(home_id,path,namespace,name) DO UPDATE SET xml=EXCLUDED.xml",
          entry.binding().home(),
          entry.binding().path(),
          name.getNamespaceURI(),
          name.getLocalPart(),
          xml);
      count =
          jdbc.queryForObject(
              "SELECT count(*) FROM dav_binding_properties WHERE home_id=? AND path=?",
              Long.class,
              entry.binding().home(),
              entry.binding().path());
    } else if (resource == null) {
      jdbc.update(
          "INSERT INTO dav_collection_properties(collection_id,namespace,name,xml) VALUES (?,?,?,?) ON CONFLICT(collection_id,namespace,name) DO UPDATE SET xml=EXCLUDED.xml",
          entry.collection().id(),
          name.getNamespaceURI(),
          name.getLocalPart(),
          xml);
      count =
          jdbc.queryForObject(
              "SELECT count(*) FROM dav_collection_properties WHERE collection_id=?",
              Long.class,
              entry.collection().id());
    } else {
      jdbc.update(
          "INSERT INTO dav_resource_properties(collection_id,resource_name,namespace,name,xml) VALUES (?,?,?,?,?) ON CONFLICT(collection_id,resource_name,namespace,name) DO UPDATE SET xml=EXCLUDED.xml",
          entry.collection().id(),
          resource,
          name.getNamespaceURI(),
          name.getLocalPart(),
          xml);
      count =
          jdbc.queryForObject(
              "SELECT count(*) FROM dav_resource_properties WHERE collection_id=? AND resource_name=?",
              Long.class,
              entry.collection().id(),
              resource);
    }
    if (count > 1000) limit();
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
      factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 64);
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
    if (!calendars.collection(actor, id).owner().equals(actor)) forbidden();
  }

  private void requireWrite(UUID actor, UUID id) {
    if ("READ".equals(permission(actor, id))) forbidden();
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

  private long[] resourceBudget(UUID id) {
    var size =
        jdbc.queryForObject(
            "SELECT count(*),COALESCE(sum(octet_length(content)),0) FROM calendar_resources WHERE collection_id=?",
            (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)},
            id);
    if (size[0] > MAX_ROWS || size[1] > MAX_BYTES) limit();
    return size;
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
        rs.getLong("revision"));
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
    if (path == null
        || path.length() > 4096
        || (!root && path.isEmpty())
        || path.split("/", -1).length > 32) throw new IllegalArgumentException("Invalid DAV path");
    if (!path.isEmpty()) for (String segment : path.split("/", -1)) checkSegment(segment);
  }

  private static void checkSegment(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 255
        || name.equals(".")
        || name.equals("..")
        || name.codePoints()
            .anyMatch(value -> value == '/' || value == '\\' || Character.isISOControl(value)))
      throw new IllegalArgumentException("Invalid DAV path segment");
  }

  private static void checkDisplayName(String name) {
    if (name == null || name.isBlank() || name.length() > 200)
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
