package org.busystack.caldav.management;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.storage.CalendarStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Browser operations enforce current account state and sensitive-operation factors at the service.
 */
@Service
public class ManagementService {
  public record Credential(
      UUID id, String label, Instant created, Instant expires, boolean revoked) {}

  public record UserView(
      UUID id, String username, String displayName, boolean administrator, boolean enabled) {}

  private final AccountService accounts;
  private final CalendarStore calendars;
  private final JdbcTemplate jdbc;
  private final org.busystack.caldav.security.session.SessionSecurity sessions;

  public ManagementService(
      AccountService accounts,
      CalendarStore calendars,
      JdbcTemplate jdbc,
      org.busystack.caldav.security.session.SessionSecurity sessions) {
    this.accounts = accounts;
    this.calendars = calendars;
    this.jdbc = jdbc;
    this.sessions = sessions;
  }

  public UserView current(Authentication auth) {
    return view(requireAccount(auth));
  }

  public boolean externallyManaged(Authentication auth) {
    return accounts.isExternallyManaged(requireAccount(auth).id());
  }

  public List<CalendarStore.Collection> collections(Authentication auth) {
    return calendars.collections(requireAccount(auth).id());
  }

  public List<Credential> credentials(Authentication auth) {
    UUID actor = requireAccount(auth).id();
    return jdbc.query(
        "SELECT id,label,created_at,expires_at,revoked_at FROM dav_credentials WHERE account_id=? ORDER BY created_at DESC",
        (rs, row) ->
            new Credential(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5) != null),
        actor);
  }

  public List<UserView> users(Authentication auth) {
    requireAdministrator(auth);
    return jdbc.query(
        "SELECT id,username,display_name,administrator,enabled FROM accounts ORDER BY username",
        (rs, row) ->
            new UserView(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getBoolean(4),
                rs.getBoolean(5)));
  }

  @Transactional
  public void provision(Authentication auth, String username, String displayName, String password) {
    requireAdministrator(auth);
    accounts.provision(username, displayName, password, false);
  }

  @Transactional
  public void disable(Authentication auth, UUID id) {
    requireAdministrator(auth);
    accounts.disable(id);
  }

  @Transactional
  public AccountService.IssuedCredential issue(Authentication auth, String label, int days) {
    RecentAuthentication.requireMfa(auth);
    if (days < 1 || days > 365)
      throw new IllegalArgumentException("Expiry must be between 1 and 365 days");
    return accounts.issueDavCredential(
        requireAccount(auth).id(), label, Instant.now().plusSeconds(days * 86400L));
  }

  @Transactional
  public void revoke(Authentication auth, UUID credential) {
    RecentAuthentication.requireMfa(auth);
    accounts.revokeDav(requireAccount(auth).id(), credential);
  }

  @Transactional
  public void createCollection(Authentication auth, String name, String component) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    RecentAuthentication.requireMfa(auth);
    calendars.createCollection(requireAccount(auth).id(), name, component);
  }

  @Transactional
  public void share(Authentication auth, UUID collection, String username, String permission) {
    org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
    RecentAuthentication.requireMfa(auth);
    var member = accounts.find(username);
    if (member == null || !member.enabled())
      throw new IllegalArgumentException("Unknown active account");
    calendars.share(
        requireAccount(auth).id(),
        collection,
        member.id(),
        "REVOKE".equals(permission) ? null : permission);
  }

  private AccountService.Account requireAccount(Authentication auth) {
    if (auth == null || !auth.isAuthenticated())
      throw new AccessDeniedException("Authentication required");
    sessions.assertCurrent(auth);
    var account = accounts.find(auth.getName());
    if (account == null || !account.enabled() || !accounts.isClientAccessCurrent(account.id()))
      throw new AccessDeniedException("Account unavailable");
    return account;
  }

  private void requireAdministrator(Authentication auth) {
    RecentAuthentication.requireMfa(auth);
    if (!requireAccount(auth).administrator())
      throw new AccessDeniedException("Administrator required");
  }

  private static UserView view(AccountService.Account account) {
    return new UserView(
        account.id(),
        account.username(),
        account.displayName(),
        account.administrator(),
        account.enabled());
  }
}
