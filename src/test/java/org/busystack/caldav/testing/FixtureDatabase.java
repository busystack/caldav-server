package org.busystack.caldav.testing;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable fixtures must not deadlock TRUNCATE against the real maintenance jobs. */
public final class FixtureDatabase {
  private FixtureDatabase() {}

  public static void clearAccounts(JdbcTemplate jdbc) {
    reset(jdbc, "TRUNCATE accounts CASCADE");
  }

  public static void reset(JdbcTemplate jdbc, String... statements) {
    var transaction =
        new TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    transaction.executeWithoutResult(
        status -> {
          // Match the production audit-prune order, then Spring Session's parent/child delete
          // order.
          // Jobs remain enabled; no sleep, retry or suppression hides a failure.
          jdbc.execute(
              "LOCK TABLE security_audit,login_throttle,webauthn_ceremonies,spring_session,spring_session_attributes IN ACCESS EXCLUSIVE MODE");
          for (String statement : statements) jdbc.execute(statement);
        });
  }
}
