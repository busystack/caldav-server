package org.busystack.caldav.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import javax.xml.namespace.QName;
import org.busystack.caldav.xml.XmlCharacters;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarStore {
  public record Collection(
      UUID id,
      UUID owner,
      String name,
      String component,
      UUID generation,
      long revision,
      long minimum) {}

  /** Metadata projections never contain a calendar body or a placeholder byte array. */
  public record Metadata(
      String name,
      String uid,
      String etag,
      long revision,
      Instant modifiedAt,
      long contentLength) {}

  public record Resource(
      String name, String uid, byte[] content, String etag, long revision, Instant modifiedAt) {
    public Resource(String name, String uid, byte[] content, String etag, long revision) {
      this(name, uid, content, etag, revision, null);
    }

    public Resource {
      content = content.clone();
    }

    public Metadata metadata() {
      return new Metadata(name, uid, etag, revision, modifiedAt, content.length);
    }

    @Override
    public byte[] content() {
      return content.clone();
    }
  }

  public record Change(String name, boolean deleted, Metadata metadata, Resource resource) {
    public Change(String name, boolean deleted, Resource resource) {
      this(name, deleted, resource == null ? null : resource.metadata(), resource);
    }
  }

  public record SyncPage(String token, List<Change> changes, boolean truncated) {}

  private record Traversal(long lower, long upper, long floor, boolean initial, String lastName) {}

  private static final long MAX_RETAINED_CONTENT_BYTES = 4L * 1024 * 1024;

  private final JdbcTemplate jdbc;
  private final SyncContinuationStore continuations;
  private final org.busystack.caldav.calendar.CalendarAdmission admission;

  public CalendarStore(JdbcTemplate jdbc, SyncContinuationStore continuations) {
    this(
        jdbc,
        continuations,
        new org.busystack.caldav.calendar.CalendarAdmission(
            new org.busystack.caldav.config.DavCompatibilityProperties(true, true)));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public CalendarStore(
      JdbcTemplate jdbc,
      SyncContinuationStore continuations,
      org.busystack.caldav.calendar.CalendarAdmission admission) {
    this.jdbc = jdbc;
    this.continuations = continuations;
    this.admission = admission;
  }

  @Transactional
  public UUID createCollection(UUID actor, String name, String component) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    requireEnabled(actor);
    if (!XmlCharacters.valid(name)) throw new IllegalArgumentException("Invalid display name");
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO collections(id,owner_id,name,component) VALUES (?,?,?,?)",
        id,
        actor,
        name,
        component);
    return id;
  }

  public List<Collection> collections(UUID actor) {
    requireEnabled(actor);
    return jdbc.query(
        "SELECT c.* FROM collections c WHERE c.owner_id=? OR EXISTS (SELECT 1 FROM collection_effective_members m WHERE m.collection_id=c.id AND m.account_id=?) ORDER BY c.id",
        CalendarStore::collection,
        actor,
        actor);
  }

  @Transactional
  public void share(UUID actor, UUID collection, UUID member, String permission) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    Collection c = lock(collection);
    requireEnabled(actor);
    if (!c.owner().equals(actor))
      throw new StoreException(
          StoreException.Kind.FORBIDDEN, StoreException.RequiredAccess.OWNER, c.id());
    if (actor.equals(member))
      throw new IllegalArgumentException("Owner permissions cannot be replaced");
    var previous =
        jdbc.queryForList(
            "SELECT permission FROM collection_members WHERE collection_id=? AND account_id=?",
            String.class,
            collection,
            member);
    if (java.util.Objects.equals(permission, previous.isEmpty() ? null : previous.getFirst()))
      return;
    if (permission == null)
      jdbc.update(
          "DELETE FROM collection_members WHERE collection_id=? AND account_id=?",
          collection,
          member);
    else
      jdbc.update(
          "INSERT INTO collection_members(collection_id,account_id,permission) VALUES (?,?,?) ON CONFLICT (collection_id,account_id) DO UPDATE SET permission=EXCLUDED.permission",
          collection,
          member,
          permission);
    // Collection-scoped generation invalidates existing cursors on any permission change.
    jdbc.update("UPDATE collections SET generation=gen_random_uuid() WHERE id=?", collection);
  }

  public Collection collection(UUID actor, UUID id) {
    Collection c = find(id);
    authorize(actor, c, false);
    return c;
  }

  public Resource get(UUID actor, UUID collectionId, String name) {
    collection(actor, collectionId);
    return resource(collectionId, name);
  }

  public Metadata metadata(UUID actor, UUID collectionId, String name) {
    collection(actor, collectionId);
    var rows =
        jdbc.query(
            "SELECT name,uid,etag,revision,updated_at,octet_length(content) AS content_length FROM calendar_resources WHERE collection_id=? AND name=?",
            CalendarStore::readMetadata,
            collectionId,
            name);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /**
   * Stable keyset batch within the caller's report transaction; calendar bodies are not selected.
   */
  public List<Metadata> metadataBatch(UUID actor, UUID collectionId, String after, int limit) {
    if (limit < 1 || limit > 10001) throw new IllegalArgumentException("Invalid metadata batch");
    collection(actor, collectionId);
    return jdbc.query(
        "SELECT name,uid,etag,revision,updated_at,octet_length(content) AS content_length FROM calendar_resources WHERE collection_id=? AND name COLLATE \"C\">? COLLATE \"C\" ORDER BY name COLLATE \"C\" LIMIT ?",
        CalendarStore::readMetadata,
        collectionId,
        after,
        limit);
  }

  private static Metadata readMetadata(java.sql.ResultSet rs, int row)
      throws java.sql.SQLException {
    return new Metadata(
        rs.getString("name"),
        rs.getString("uid"),
        rs.getString("etag"),
        rs.getLong("revision"),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getLong("content_length"));
  }

  /** Fetch a bounded query batch after one authorization check, without per-object lookups. */
  public List<Resource> resourceBatch(UUID actor, UUID collectionId, String after) {
    var metadata = metadataBatch(actor, collectionId, after, 16);
    List<String> names = new ArrayList<>();
    long selectedBytes = 0;
    for (var item : metadata) {
      if (item.contentLength() > 4L * 1024 * 1024 - selectedBytes) break;
      names.add(item.name());
      selectedBytes += item.contentLength();
    }
    if (names.isEmpty()) {
      if (!metadata.isEmpty()) throw new StoreException(StoreException.Kind.LIMIT);
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
    List<Object> arguments = new ArrayList<>();
    arguments.add(collectionId);
    arguments.addAll(names);
    return jdbc.query(
        "SELECT name,uid,content,etag,revision,updated_at FROM calendar_resources WHERE collection_id=? AND name IN ("
            + placeholders
            + ") ORDER BY name COLLATE \"C\"",
        (rs, row) -> readResource(rs),
        arguments.toArray());
  }

  @Transactional
  public Resource put(
      UUID actor,
      UUID collectionId,
      String name,
      String uid,
      byte[] content,
      WriteCondition condition) {
    return putAdmitted(actor, collectionId, name, uid, content, condition).resource();
  }

  public record WriteResult(Resource resource, boolean transformed) {}

  @Transactional
  public WriteResult putAdmitted(
      UUID actor,
      UUID collectionId,
      String name,
      String uid,
      byte[] content,
      WriteCondition condition) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    checkName(name);
    if (content == null || content.length == 0)
      throw new org.busystack.caldav.calendar.CalendarValidationException(
          org.busystack.caldav.calendar.CalendarValidationException.Reason.INVALID_DATA);
    if (content.length > 1048576) throw new StoreException(StoreException.Kind.LIMIT);
    Collection c = lock(collectionId);
    authorize(actor, c, true);
    DavNamespace.resourceBindings(jdbc, collectionId, name);
    var accepted = admission.admit(content, c.component());
    var parsed = accepted.parsed();
    if (uid != null && !parsed.uid().equals(uid))
      throw new IllegalArgumentException("UID index does not match the resource");
    uid = parsed.uid();
    content = accepted.content();
    Resource old = resource(collectionId, name);
    condition.check(
        old == null ? null : old.etag(), old != null, old == null ? null : old.modifiedAt());
    if (old != null && !old.uid().equals(uid))
      throw new StoreException(StoreException.Kind.UID_CONFLICT);
    long next = c.revision() + 1;
    String etag = etag(content);
    Instant modifiedAt;
    try {
      modifiedAt =
          jdbc.queryForObject(
              "INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision,updated_at) VALUES (?,?,?,?,?,?,clock_timestamp()) ON CONFLICT (collection_id,name) DO UPDATE SET uid=EXCLUDED.uid,content=EXCLUDED.content,etag=EXCLUDED.etag,revision=EXCLUDED.revision,updated_at=CASE WHEN calendar_resources.content IS DISTINCT FROM EXCLUDED.content THEN clock_timestamp() ELSE calendar_resources.updated_at END RETURNING updated_at",
              (rs, row) -> rs.getTimestamp(1).toInstant(),
              collectionId,
              name,
              uid,
              content,
              etag,
              next);
    } catch (DuplicateKeyException ex) {
      throw new StoreException(StoreException.Kind.UID_CONFLICT);
    }
    changed(collectionId, next, name, false);
    if (accepted.transformed())
      org.slf4j.LoggerFactory.getLogger(CalendarStore.class)
          .info(
              "calendar_input_repaired repair=local_observance_until rules={}",
              accepted.repairedRules());
    return new WriteResult(
        new Resource(name, uid, content, etag, next, modifiedAt), accepted.transformed());
  }

  @Transactional
  public void delete(UUID actor, UUID collectionId, String name, WriteCondition condition) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    Collection c = lock(collectionId);
    authorize(actor, c, true);
    Resource old = resource(collectionId, name);
    condition.check(
        old == null ? null : old.etag(), old != null, old == null ? null : old.modifiedAt());
    if (old == null) throw new StoreException(StoreException.Kind.NOT_FOUND);
    jdbc.update(
        "DELETE FROM calendar_resources WHERE collection_id=? AND name=?", collectionId, name);
    changed(collectionId, c.revision() + 1, name, true);
  }

  /** Current collection state without loading bodies or imposing a report-result budget. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public String currentSyncToken(UUID actor, UUID collectionId) {
    Collection c = collection(actor, collectionId);
    return tokenPrefix(c) + c.revision();
  }

  private String tokenPrefix(Collection c) {
    return "urn:busystack:sync:"
        + jdbc.queryForObject("SELECT recovery_epoch FROM system_state WHERE id=1", UUID.class)
        + ":"
        + c.id()
        + ":"
        + c.generation()
        + ":";
  }

  /** Backwards-compatible content-bearing storage entry point; HTTP supplies exact XML sizing. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SyncPage sync(UUID actor, UUID collectionId, String token, int limit) {
    return sync(
        actor,
        collectionId,
        token,
        limit,
        true,
        change -> 512L + (change.metadata() == null ? 0 : change.metadata().contentLength() * 6),
        32L * 1024 * 1024);
  }

  /**
   * A frozen watermark plus immutable versions gives each continuation an identical view across
   * requests. The sizing callback runs with one bounded body at a time, before accepting a row.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SyncPage sync(
      UUID actor,
      UUID collectionId,
      String token,
      int limit,
      boolean includeContent,
      ToLongFunction<Change> responseBytes,
      long maxBytes) {
    if (limit < 1 || limit > 10000 || maxBytes < 1)
      throw new StoreException(StoreException.Kind.LIMIT);
    Collection c = collection(actor, collectionId);
    String prefix = tokenPrefix(c);
    Traversal traversal = traversal(actor, c, prefix, token);
    // Expiry is checked by lookup. Independent read-committed maintenance avoids turning
    // concurrent expired-row cleanup into serialization failures in this snapshot.
    String lower = traversal.initial() ? "" : " AND revision>?";
    String sql =
        "SELECT name,deleted,uid,etag,revision,updated_at,content_length FROM ("
            + "SELECT DISTINCT ON (name COLLATE \"C\") name,deleted,uid,etag,revision,updated_at,content_length "
            + "FROM calendar_resource_versions WHERE collection_id=? AND revision<=?"
            + lower
            + " AND name COLLATE \"C\">? COLLATE \"C\" ORDER BY name COLLATE \"C\",revision DESC) latest "
            + (traversal.initial() ? "WHERE NOT deleted " : "")
            + "ORDER BY name COLLATE \"C\" LIMIT ?";
    List<Change> accepted = new ArrayList<>();
    long bytes = 0, retainedContent = 0, retainedMetadata = 0;
    boolean truncated = false;
    String afterName = traversal.lastName();
    paging:
    while (true) {
      int batchSize = Math.min(128, limit - accepted.size() + 1);
      List<Object> arguments = new ArrayList<>(List.of(collectionId, traversal.upper()));
      if (!traversal.initial()) arguments.add(traversal.lower());
      arguments.add(afterName);
      arguments.add(batchSize);
      List<Change> candidates =
          jdbc.query(
              sql,
              (rs, row) -> {
                String name = rs.getString("name");
                boolean deleted = rs.getBoolean("deleted");
                return new Change(
                    name,
                    deleted,
                    deleted
                        ? null
                        : new Metadata(
                            name,
                            rs.getString("uid"),
                            rs.getString("etag"),
                            rs.getLong("revision"),
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getLong("content_length")),
                    null);
              },
              arguments.toArray());
      for (Change candidate : candidates) {
        long bodyLength =
            includeContent && candidate.metadata() != null
                ? candidate.metadata().contentLength()
                : 0;
        long metadataWork =
            256L
                + candidate.name().length() * 2L
                + (candidate.metadata() == null
                    ? 0
                    : candidate.metadata().uid().length() * 2L
                        + candidate.metadata().etag().length() * 2L);
        if (accepted.size() == limit
            || retainedContent + bodyLength > MAX_RETAINED_CONTENT_BYTES
            || retainedMetadata + metadataWork > MAX_RETAINED_CONTENT_BYTES) {
          truncated = true;
          break paging;
        }
        Change item = candidate;
        if (includeContent && !candidate.deleted()) {
          Resource body =
              jdbc.queryForObject(
                  "SELECT name,uid,content,etag,revision,updated_at FROM calendar_resource_versions WHERE collection_id=? AND name=? AND revision=?",
                  (rs, row) -> readResource(rs),
                  collectionId,
                  candidate.name(),
                  candidate.metadata().revision());
          item = new Change(candidate.name(), false, candidate.metadata(), body);
        }
        long selectedBytes = responseBytes.applyAsLong(item);
        if (selectedBytes < 0) throw new IllegalArgumentException("Negative response size");
        if (selectedBytes > maxBytes - bytes) {
          truncated = true;
          break paging;
        }
        accepted.add(item);
        bytes += selectedBytes;
        retainedContent += bodyLength;
        retainedMetadata += metadataWork;
        afterName = candidate.name();
      }
      if (candidates.size() < batchSize) break;
    }
    if (truncated && accepted.isEmpty()) throw new StoreException(StoreException.Kind.LIMIT);
    String nextToken = prefix + traversal.upper();
    if (truncated) {
      UUID continuation =
          continuations.save(
              actor,
              c,
              recoveryEpoch(),
              traversal.lower(),
              traversal.upper(),
              traversal.floor(),
              traversal.initial(),
              accepted.getLast().name());
      nextToken = prefix + "page:" + continuation;
    }
    return new SyncPage(nextToken, List.copyOf(accepted), truncated);
  }

  private Traversal traversal(UUID actor, Collection c, String prefix, String token) {
    if (token == null || token.isEmpty())
      return new Traversal(0, c.revision(), c.minimum(), true, "");
    if (token.length() > 512 || !token.startsWith(prefix)) invalidToken();
    String suffix = token.substring(prefix.length());
    if (suffix.startsWith("page:")) {
      UUID id;
      try {
        id = UUID.fromString(suffix.substring(5));
      } catch (IllegalArgumentException ex) {
        throw new StoreException(StoreException.Kind.INVALID_TOKEN);
      }
      var rows =
          jdbc.query(
              "SELECT lower_revision,upper_revision,retention_floor,initial_sync,last_name FROM sync_continuations WHERE id=? AND collection_id=? AND account_id=? AND generation=? AND recovery_epoch=? AND expires_at>now()",
              (rs, row) ->
                  new Traversal(
                      rs.getLong(1),
                      rs.getLong(2),
                      rs.getLong(3),
                      rs.getBoolean(4),
                      rs.getString(5)),
              id,
              c.id(),
              actor,
              c.generation(),
              recoveryEpoch());
      if (rows.isEmpty()) invalidToken();
      Traversal found = rows.getFirst();
      if (found.floor() < c.minimum() || found.upper() > c.revision()) invalidToken();
      return found;
    }
    long after;
    try {
      if (suffix.isEmpty() || !suffix.chars().allMatch(ch -> ch >= '0' && ch <= '9'))
        invalidToken();
      after = Long.parseLong(suffix);
    } catch (NumberFormatException ex) {
      throw new StoreException(StoreException.Kind.INVALID_TOKEN);
    }
    if (after < c.minimum() || after > c.revision()) invalidToken();
    return new Traversal(after, c.revision(), c.minimum(), false, "");
  }

  private UUID recoveryEpoch() {
    return jdbc.queryForObject("SELECT recovery_epoch FROM system_state WHERE id=1", UUID.class);
  }

  private static void invalidToken() {
    throw new StoreException(StoreException.Kind.INVALID_TOKEN);
  }

  /** Only names are projected from the historical property snapshot. */
  public Set<QName> historicalPropertyNames(
      UUID actor, UUID collectionId, String name, long revision) {
    collection(actor, collectionId);
    String from =
        " FROM calendar_resource_version_properties WHERE collection_id=? AND resource_name=? AND revision=?";
    List<Object> arguments = List.of(collectionId, name, revision);
    var size =
        jdbc.queryForObject(
            "SELECT count(*),COALESCE(sum(octet_length(namespace)+octet_length(property_name)),0)"
                + from,
            (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)},
            arguments.toArray());
    if (size[0] > 1000 || size[1] > 1024 * 1024)
      throw new StoreException(StoreException.Kind.LIMIT);
    return Set.copyOf(
        jdbc.query(
            "SELECT namespace,property_name" + from,
            (rs, row) -> new QName(rs.getString(1), rs.getString(2)),
            arguments.toArray()));
  }

  /** Null selects all values; explicit names are filtered in SQL before values reach JDBC. */
  public Map<QName, String> historicalProperties(
      UUID actor, UUID collectionId, String name, long revision, Set<QName> selection) {
    collection(actor, collectionId);
    if (selection != null && selection.isEmpty()) return Map.of();
    if (selection != null && selection.size() > 1000)
      throw new StoreException(StoreException.Kind.LIMIT);
    List<Object> arguments = new ArrayList<>(List.of(collectionId, name, revision));
    String clause = "";
    if (selection != null) {
      clause =
          " AND ("
              + String.join(
                  " OR ",
                  java.util.Collections.nCopies(
                      selection.size(), "(namespace=? AND property_name=?)"))
              + ")";
      for (QName item : selection) {
        arguments.add(item.getNamespaceURI());
        arguments.add(item.getLocalPart());
      }
    }
    String from =
        " FROM calendar_resource_version_properties WHERE collection_id=? AND resource_name=? AND revision=?"
            + clause;
    var size =
        jdbc.queryForObject(
            "SELECT count(*),COALESCE(sum(octet_length(xml)+octet_length(namespace)+octet_length(property_name)),0)"
                + from,
            (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)},
            arguments.toArray());
    if (size[0] > 1000 || size[1] > 1024 * 1024)
      throw new StoreException(StoreException.Kind.LIMIT);
    Map<QName, String> values = new LinkedHashMap<>();
    jdbc.query(
        "SELECT namespace,property_name,xml" + from,
        rs -> {
          values.put(new QName(rs.getString(1), rs.getString(2)), rs.getString(3));
        },
        arguments.toArray());
    return Map.copyOf(values);
  }

  @Transactional
  public void pruneHistory(UUID collectionId, long through) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    Collection c = lock(collectionId);
    if (through < c.minimum() || through > c.revision())
      throw new IllegalArgumentException("Invalid retention boundary");
    jdbc.update(
        "DELETE FROM collection_changes WHERE collection_id=? AND revision<=?",
        collectionId,
        through);
    jdbc.update(
        "DELETE FROM calendar_resource_versions v WHERE v.collection_id=? AND v.revision<? AND EXISTS (SELECT 1 FROM calendar_resource_versions newer WHERE newer.collection_id=v.collection_id AND newer.name=v.name AND newer.revision<=? AND newer.revision>v.revision)",
        collectionId,
        through,
        through);
    jdbc.update(
        "DELETE FROM sync_continuations WHERE collection_id=? AND retention_floor<?",
        collectionId,
        through);
    jdbc.update("UPDATE collections SET min_retained_revision=? WHERE id=?", through, collectionId);
  }

  private void changed(UUID id, long revision, String name, boolean deleted) {
    jdbc.update("UPDATE collections SET revision=? WHERE id=?", revision, id);
    jdbc.update(
        "INSERT INTO collection_changes(collection_id,revision,name,deleted) VALUES (?,?,?,?)",
        id,
        revision,
        name,
        deleted);
  }

  private Collection lock(UUID id) {
    List<Collection> list =
        jdbc.query(
            "SELECT * FROM collections WHERE id=? FOR UPDATE", CalendarStore::collection, id);
    if (list.isEmpty()) throw new StoreException(StoreException.Kind.NOT_FOUND);
    return list.getFirst();
  }

  private Collection find(UUID id) {
    List<Collection> list =
        jdbc.query("SELECT * FROM collections WHERE id=?", CalendarStore::collection, id);
    if (list.isEmpty()) throw new StoreException(StoreException.Kind.NOT_FOUND);
    return list.getFirst();
  }

  private void requireEnabled(UUID actor) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND enabled)", Boolean.class, actor)))
      throw new StoreException(StoreException.Kind.FORBIDDEN);
  }

  private void authorize(UUID actor, Collection c, boolean write) {
    requireEnabled(actor);
    if (c.owner().equals(actor)) return;
    List<String> permissions =
        jdbc.queryForList(
            "SELECT permission FROM collection_effective_members WHERE collection_id=? AND account_id=?",
            String.class,
            c.id(),
            actor);
    if (permissions.isEmpty())
      throw new StoreException(
          StoreException.Kind.FORBIDDEN, StoreException.RequiredAccess.READ, c.id());
    if (write && !"WRITE".equals(permissions.getFirst()))
      throw new StoreException(
          StoreException.Kind.FORBIDDEN, StoreException.RequiredAccess.WRITE, c.id());
  }

  private Resource resource(UUID collectionId, String name) {
    return jdbc
        .query(
            "SELECT * FROM calendar_resources WHERE collection_id=? AND name=?",
            (rs, row) -> readResource(rs),
            collectionId,
            name)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static Collection collection(java.sql.ResultSet rs, int row)
      throws java.sql.SQLException {
    return new Collection(
        rs.getObject("id", UUID.class),
        rs.getObject("owner_id", UUID.class),
        rs.getString("name"),
        rs.getString("component"),
        rs.getObject("generation", UUID.class),
        rs.getLong("revision"),
        rs.getLong("min_retained_revision"));
  }

  private static Resource readResource(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Resource(
        rs.getString("name"),
        rs.getString("uid"),
        rs.getBytes("content"),
        rs.getString("etag"),
        rs.getLong("revision"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static void checkName(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 255
        || name.equals(".")
        || name.equals("..")
        || name.codePoints().anyMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c)))
      throw new IllegalArgumentException("Invalid resource name");
  }

  private static String etag(byte[] bytes) {
    try {
      return "\""
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
          + "\"";
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
