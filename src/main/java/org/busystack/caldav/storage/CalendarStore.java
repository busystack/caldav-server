package org.busystack.caldav.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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

  public record Resource(
      String name, String uid, byte[] content, String etag, long revision, Instant modifiedAt) {
    public Resource(String name, String uid, byte[] content, String etag, long revision) {
      this(name, uid, content, etag, revision, null);
    }

    public Resource {
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }
  }

  public record Change(String name, boolean deleted, Resource resource) {}

  public record SyncPage(String token, List<Change> changes) {}

  private final JdbcTemplate jdbc;

  public CalendarStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public UUID createCollection(UUID actor, String name, String component) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    requireEnabled(actor);
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

  @Transactional
  public Resource put(
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
    var parsed = org.busystack.caldav.calendar.CalendarContent.parse(content, c.component());
    if (!parsed.uid().equals(uid))
      throw new IllegalArgumentException("UID index does not match the resource");
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
    return new Resource(name, uid, content, etag, next, modifiedAt);
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

  /** All response rows and the token come from one MVCC snapshot. No live queries after return. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SyncPage sync(UUID actor, UUID collectionId, String token, int limit) {
    if (limit < 1 || limit > 10000) throw new StoreException(StoreException.Kind.LIMIT);
    Collection c = collection(actor, collectionId);
    String prefix = tokenPrefix(c);
    long after = 0;
    if (token != null && !token.isEmpty()) {
      if (!token.startsWith(prefix)) throw new StoreException(StoreException.Kind.INVALID_TOKEN);
      try {
        after = Long.parseLong(token.substring(prefix.length()));
      } catch (NumberFormatException ex) {
        throw new StoreException(StoreException.Kind.INVALID_TOKEN);
      }
      if (after < c.minimum() || after > c.revision())
        throw new StoreException(StoreException.Kind.INVALID_TOKEN);
    }
    List<Change> changes;
    if (token == null || token.isEmpty()) {
      var budget =
          jdbc.queryForObject(
              "SELECT count(*),COALESCE(sum(octet_length(content)),0) FROM calendar_resources WHERE collection_id=?",
              (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)},
              collectionId);
      if (budget[0] > limit || budget[1] > 32L * 1024 * 1024)
        throw new StoreException(StoreException.Kind.LIMIT);
      changes =
          jdbc.query(
              "SELECT * FROM calendar_resources WHERE collection_id=? ORDER BY name LIMIT ?",
              (rs, row) -> new Change(rs.getString("name"), false, readResource(rs)),
              collectionId,
              limit + 1);
    } else {
      var budget =
          jdbc.queryForObject(
              "SELECT count(*),COALESCE(sum(octet_length(r.content)),0) FROM (SELECT DISTINCT ON (name) name,deleted FROM collection_changes WHERE collection_id=? AND revision>? ORDER BY name,revision DESC) latest LEFT JOIN calendar_resources r ON r.collection_id=? AND r.name=latest.name",
              (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)},
              collectionId,
              after,
              collectionId);
      if (budget[0] > limit || budget[1] > 32L * 1024 * 1024)
        throw new StoreException(StoreException.Kind.LIMIT);
      changes =
          jdbc.query(
              "SELECT latest.name,latest.deleted,r.uid,r.content,r.etag,r.revision,r.updated_at FROM (SELECT DISTINCT ON (name) name,deleted,revision FROM collection_changes WHERE collection_id=? AND revision>? ORDER BY name,revision DESC) latest LEFT JOIN calendar_resources r ON r.collection_id=? AND r.name=latest.name ORDER BY latest.name LIMIT ?",
              (rs, row) ->
                  new Change(
                      rs.getString("name"),
                      rs.getBoolean("deleted"),
                      rs.getBoolean("deleted") ? null : readResource(rs)),
              collectionId,
              after,
              collectionId,
              limit + 1);
    }
    // Until protocol pagination is added, fail the whole response; never omit rows then advance.
    if (changes.size() > limit) throw new StoreException(StoreException.Kind.LIMIT);
    return new SyncPage(prefix + c.revision(), List.copyOf(changes));
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
