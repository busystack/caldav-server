package org.busystack.caldav.security.oauth;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Client, grant, account, then data rows: one transaction lock order for OAuth mutations. */
@Service
public class OAuthClientLock {
  private final JdbcTemplate jdbc;

  public OAuthClientLock(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lock(String clientId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("OAuth client lock requires an active transaction");
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        rs -> {},
        "busystack.oauth.client:" + clientId);
  }

  /** Recheck after locking: an authentication object may refer to a removed registration. */
  public boolean matches(String clientId, String registrationId) {
    lock(clientId);
    List<String> current =
        jdbc.queryForList(
            "SELECT id FROM oauth2_registered_client WHERE client_id=?", String.class, clientId);
    return current.size() == 1
        && (registrationId == null || current.getFirst().equals(registrationId));
  }

  public boolean registrationExists(String registrationId) {
    List<String> ids =
        jdbc.queryForList(
            "SELECT client_id FROM oauth2_registered_client WHERE id=?",
            String.class,
            registrationId);
    return ids.size() == 1 && matches(ids.getFirst(), registrationId);
  }
}
