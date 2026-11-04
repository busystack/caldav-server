package org.busystack.caldav.management;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.storage.StoreException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationService {
  public record AccountView(
      UUID id,
      String username,
      String displayName,
      boolean administrator,
      boolean enabled,
      boolean external) {}

  public record AuditEntry(
      Instant time,
      String action,
      String outcome,
      String username,
      UUID actor,
      String targetType,
      UUID targetId,
      UUID relatedAccount) {}

  private final ManagementService management;
  private final AccountService accounts;
  private final JdbcTemplate jdbc;
  private final SecurityAudit audit;
  private static final String ACCOUNT_SELECT =
      "SELECT a.id,a.username,a.display_name,a.administrator,a.enabled,EXISTS(SELECT 1 FROM external_identities e WHERE e.account_id=a.id) AS external FROM accounts a";

  public AdministrationService(
      ManagementService management,
      AccountService accounts,
      JdbcTemplate jdbc,
      SecurityAudit audit) {
    this.management = management;
    this.accounts = accounts;
    this.jdbc = jdbc;
    this.audit = audit;
  }

  public PortalPage<AccountView> accounts(
      Authentication auth, String query, String status, int page) {
    management.requireAdministrator(auth);
    PortalService.checkQuery(query);
    if (!Set.of("all", "enabled", "disabled", "local", "external").contains(status))
      throw new IllegalArgumentException("Invalid account filter");
    String clause =
        switch (status) {
          case "enabled" -> " AND a.enabled";
          case "disabled" -> " AND NOT a.enabled";
          case "local" ->
              " AND NOT EXISTS(SELECT 1 FROM external_identities e WHERE e.account_id=a.id)";
          case "external" ->
              " AND EXISTS(SELECT 1 FROM external_identities e WHERE e.account_id=a.id)";
          default -> "";
        };
    return PortalPage.of(
        jdbc.query(
            ACCOUNT_SELECT
                + " WHERE (strpos(lower(a.username),lower(?))>0 OR strpos(lower(a.display_name),lower(?))>0)"
                + clause
                + " ORDER BY a.username,a.id LIMIT 51 OFFSET ?",
            AdministrationService::account,
            query,
            query,
            PortalPage.offset(page)),
        page);
  }

  public AccountView account(Authentication auth, UUID id) {
    management.requireAdministrator(auth);
    var rows = jdbc.query(ACCOUNT_SELECT + " WHERE a.id=?", AdministrationService::account, id);
    if (rows.isEmpty()) throw new StoreException(StoreException.Kind.NOT_FOUND);
    return rows.getFirst();
  }

  @Transactional
  public UUID create(Authentication auth, String username, String displayName, String password) {
    management.requireAdministrator(auth);
    FormValidation.account(username, displayName, password);
    if (accounts.find(username) != null)
      throw FormValidation.conflict(
          "username", "This username is already in use. Choose another username.");
    UUID id;
    try {
      id = accounts.provision(username, displayName, password, false);
    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
      throw FormValidation.conflict(
          "username", "This username is already in use. Choose another username.");
    }
    audit.targeted(
        management.current(auth).id(),
        SecurityAudit.Action.ACCOUNT_CREATED,
        SecurityAudit.Outcome.SUCCESS,
        SecurityAudit.TargetType.ACCOUNT,
        id,
        null);
    return id;
  }

  @Transactional
  public void edit(Authentication auth, UUID id, String expected, String displayName) {
    management.requireAdministrator(auth);
    account(auth, id);
    if (!FormValidation.displayName(displayName))
      throw FormValidation.field(
          "displayName", "Use a display name of 1–200 characters without control characters.");
    accounts.updateDisplayName(id, expected, displayName);
    audit.targeted(
        management.current(auth).id(),
        SecurityAudit.Action.ACCOUNT_PROFILE_UPDATED,
        SecurityAudit.Outcome.SUCCESS,
        SecurityAudit.TargetType.ACCOUNT,
        id,
        null);
  }

  @Transactional
  public void disable(Authentication auth, UUID id, boolean confirmed) {
    management.requireAdministrator(auth);
    account(auth, id);
    if (!confirmed)
      throw new IllegalArgumentException("Confirm disabling this account and revoking its access");
    UUID actor = management.current(auth).id();
    accounts.disable(id);
    audit.targeted(
        actor,
        SecurityAudit.Action.ACCOUNT_DISABLED,
        SecurityAudit.Outcome.SUCCESS,
        SecurityAudit.TargetType.ACCOUNT,
        id,
        null);
  }

  public PortalPage<AuditEntry> audit(
      Authentication auth, String action, String outcome, String username, int page) {
    management.requireAdministrator(auth);
    PortalService.checkQuery(username);
    if (!action.isEmpty()) SecurityAudit.Action.valueOf(action);
    if (!outcome.isEmpty()) SecurityAudit.Outcome.valueOf(outcome);
    return PortalPage.of(
        jdbc.query(
            "SELECT s.occurred_at,s.action,s.outcome,a.username,s.account_id,s.target_type,s.target_id,s.related_account_id FROM security_audit s LEFT JOIN accounts a ON a.id=s.account_id WHERE (?='' OR s.action=?) AND (?='' OR s.outcome=?) AND (?='' OR a.username=?) ORDER BY s.id DESC LIMIT 51 OFFSET ?",
            (rs, row) ->
                new AuditEntry(
                    rs.getTimestamp(1).toInstant(),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getObject(5, UUID.class),
                    rs.getString(6),
                    rs.getObject(7, UUID.class),
                    rs.getObject(8, UUID.class)),
            action,
            action,
            outcome,
            outcome,
            username,
            username,
            PortalPage.offset(page)),
        page);
  }

  private static AccountView account(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new AccountView(
        rs.getObject(1, UUID.class),
        rs.getString(2),
        rs.getString(3),
        rs.getBoolean(4),
        rs.getBoolean(5),
        rs.getBoolean(6));
  }
}
