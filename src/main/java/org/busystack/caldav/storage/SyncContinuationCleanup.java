package org.busystack.caldav.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Expiry never depends on cleanup; keep maintenance writes outside repeatable-read reports. */
@Component
public class SyncContinuationCleanup {
  private final JdbcTemplate jdbc;

  public SyncContinuationCleanup(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void pruneExpired() {
    jdbc.update(
        "DELETE FROM sync_continuations WHERE id IN (SELECT id FROM sync_continuations WHERE expires_at<now() ORDER BY expires_at,id LIMIT 1024 FOR UPDATE SKIP LOCKED)");
  }
}
