package org.busystack.caldav.security.password;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.security.session.ClientAccessRevocation;
import org.busystack.caldav.security.session.SessionSecurity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local password lifecycle and the DAO provider's supported adaptive-hash upgrade hook. */
@Service
public class AccountPasswordService implements UserDetailsPasswordService {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final SessionSecurity sessions;
  private final ClientAccessRevocation clients;
  private final SecurityAudit audit;

  public AccountPasswordService(
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      SessionSecurity sessions,
      ClientAccessRevocation clients,
      SecurityAudit audit) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.sessions = sessions;
    this.clients = clients;
    this.audit = audit;
  }

  @Transactional
  public void change(Authentication authentication, String replacement, String peer) {
    RecentAuthentication.requireMfa(authentication);
    validate(replacement);
    var account = sessions.assertForReset(authentication);
    Boolean local =
        jdbc.queryForObject(
            "SELECT password_hash IS NOT NULL AND NOT EXISTS(SELECT 1 FROM external_identities e WHERE e.account_id=a.id) FROM accounts a WHERE a.id=?",
            Boolean.class,
            account.id());
    if (!Boolean.TRUE.equals(local))
      throw new AccessDeniedException(
          "This account's primary sign-in is managed by its identity provider");
    jdbc.update(
        "UPDATE accounts SET password_hash=? WHERE id=?",
        passwords.encode(replacement),
        account.id());
    jdbc.update("DELETE FROM account_recovery_codes WHERE account_id=?", account.id());
    clients.revoke(account.id(), account.username());
    audit.record(
        account.id(), SecurityAudit.Action.PASSWORD_CHANGED, SecurityAudit.Outcome.SUCCESS, peer);
  }

  @Override
  @Transactional
  public UserDetails updatePassword(UserDetails user, String upgradedHash) {
    UUID captured = SessionSecurity.capturedEpoch(user.getAuthorities());
    if (captured == null)
      throw new CredentialsExpiredException("Account credential changed during sign-in");
    int changed =
        jdbc.update(
            "UPDATE accounts a SET password_hash=? WHERE username=? AND password_hash=? AND security_epoch=? AND enabled AND NOT EXISTS(SELECT 1 FROM external_identities e WHERE e.account_id=a.id)",
            upgradedHash,
            user.getUsername(),
            user.getPassword(),
            captured);
    if (changed != 1)
      throw new CredentialsExpiredException("Account credential changed during sign-in");
    return User.withUserDetails(user).password(upgradedHash).build();
  }

  private static void validate(String password) {
    if (password == null
        || password.length() < 14
        || password.length() > 72
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException(
          "Password must contain at least fourteen characters and at most seventy-two UTF-8 bytes");
  }
}
