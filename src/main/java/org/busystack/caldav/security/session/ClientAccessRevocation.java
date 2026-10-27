package org.busystack.caldav.security.session;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal lifecycle operation; the caller owns the authenticated account/admin decision. */
@Service
public class ClientAccessRevocation {
  private final JdbcTemplate jdbc;
  private final SessionSecurity sessions;

  public ClientAccessRevocation(JdbcTemplate jdbc, SessionSecurity sessions) {
    this.jdbc = jdbc;
    this.sessions = sessions;
  }

  @Transactional
  public void revoke(UUID account, String username) {
    jdbc.query("SELECT id FROM accounts WHERE id=? FOR UPDATE", rs -> {}, account);
    jdbc.update(
        "UPDATE dav_credentials SET revoked_at=COALESCE(revoked_at,now()) WHERE account_id=?",
        account);
    jdbc.update(
        "UPDATE oauth_refresh_families SET revoked_at=COALESCE(revoked_at,now()) WHERE account_id=?",
        account);
    jdbc.update(
        "DELETE FROM oauth2_authorization_consent c USING oauth2_authorization a WHERE (a.attributes::jsonb ->> 'busystack.account_id')=? AND c.registered_client_id=a.registered_client_id AND c.principal_name=a.principal_name",
        account.toString());
    jdbc.update(
        "DELETE FROM oauth2_authorization WHERE principal_name IN (?,?) OR attributes::jsonb ->> 'busystack.account_id'=?",
        username,
        account.toString(),
        account.toString());
    jdbc.update(
        "DELETE FROM oauth2_authorization_consent WHERE principal_name IN (?,?)",
        username,
        account.toString());
    sessions.invalidateAll(account);
  }
}
