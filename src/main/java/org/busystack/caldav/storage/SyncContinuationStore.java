package org.busystack.caldav.storage;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Save opaque traversal state without foreign-key checks against an older report snapshot.
 * PostgreSQL may reject an RR insert referencing a concurrently updated collection, even when its
 * primary key did not change. A fresh short RC transaction checks the current access state.
 */
@Service
public class SyncContinuationStore {
  private record Current(
      UUID generation,
      long revision,
      long minimum,
      UUID epoch,
      boolean enabled,
      boolean permitted) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate writes;

  public SyncContinuationStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    writes = new TransactionTemplate(transactions);
    writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    // Cursor bookkeeping must not pin a servlet/work slot indefinitely behind a writer.
    writes.setTimeout(10);
  }

  public UUID save(
      UUID actor,
      CalendarStore.Collection collection,
      UUID epoch,
      long lower,
      long upper,
      long floor,
      boolean initial,
      String lastName) {
    return writes.execute(
        status -> {
          var states =
              jdbc.query(
                  "SELECT c.generation,c.revision,c.min_retained_revision,s.recovery_epoch,a.enabled,(c.owner_id=a.id OR EXISTS(SELECT 1 FROM collection_effective_members m WHERE m.collection_id=c.id AND m.account_id=a.id)) AS permitted FROM collections c CROSS JOIN system_state s CROSS JOIN accounts a WHERE c.id=? AND s.id=1 AND a.id=? FOR KEY SHARE OF c,s,a",
                  (rs, row) ->
                      new Current(
                          rs.getObject(1, UUID.class),
                          rs.getLong(2),
                          rs.getLong(3),
                          rs.getObject(4, UUID.class),
                          rs.getBoolean(5),
                          rs.getBoolean(6)),
                  collection.id(),
                  actor);
          if (states.isEmpty()) throw new StoreException(StoreException.Kind.INVALID_TOKEN);
          Current current = states.getFirst();
          if (!current.enabled() || !current.permitted())
            throw new StoreException(
                StoreException.Kind.FORBIDDEN, StoreException.RequiredAccess.READ, collection.id());
          if (!current.generation().equals(collection.generation())
              || !current.epoch().equals(epoch)
              || current.minimum() > floor
              || current.revision() < upper)
            throw new StoreException(StoreException.Kind.INVALID_TOKEN);
          UUID id = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO sync_continuations(id,collection_id,account_id,generation,recovery_epoch,lower_revision,upper_revision,retention_floor,initial_sync,last_name) VALUES (?,?,?,?,?,?,?,?,?,?)",
              id,
              collection.id(),
              actor,
              collection.generation(),
              epoch,
              lower,
              upper,
              floor,
              initial,
              lastName);
          return id;
        });
  }
}
