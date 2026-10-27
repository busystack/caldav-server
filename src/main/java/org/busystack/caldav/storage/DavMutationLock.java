package org.busystack.caldav.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared ordinary writes; exclusive validation of cross-resource DAV If conditions. */
public final class DavMutationLock {
  public static final long KEY = 0x425553595441434BL;

  private DavMutationLock() {}

  public static void shared(JdbcTemplate jdbc) {
    requireTransaction();
    jdbc.query("SELECT pg_advisory_xact_lock_shared(?)", rs -> {}, KEY);
  }

  public static void exclusive(JdbcTemplate jdbc) {
    requireTransaction();
    jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> {}, KEY);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("DAV mutation lock requires an active transaction");
  }
}
