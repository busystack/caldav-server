package org.busystack.caldav.security.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores bounded structured security metadata; no credentials, assertion bodies, or free text. */
@Service
public class SecurityAudit {
  public enum Action {
    DATABASE_RESTORE("Database restored"),
    PASSWORD_LOGIN("Password sign-in"),
    PASSWORD_CHANGED("Password changed"),
    ACCOUNT_DISABLED("Account disabled"),
    OFFLINE_RECOVERY("Offline account recovery"),
    PASSKEY_LOGIN("Passkey verification"),
    SESSION_REVOKED("Browser session ended"),
    RECOVERY_CODES_ISSUED("Recovery codes generated"),
    RECOVERY_USED("Recovery code used"),
    GROUP_CREATED("Group created"),
    GROUP_UPDATED("Group updated"),
    GROUP_MEMBER_ADDED("Group member added"),
    GROUP_MEMBER_REMOVED("Group member removed"),
    GROUP_DELETED("Group deleted"),
    GROUP_SHARING_CHANGED("Group share changed"),
    COLLECTION_CREATED("Resource created"),
    COLLECTION_UPDATED("Resource settings updated"),
    COLLECTION_DELETED("Resource deleted"),
    COLLECTION_IMPORTED("Resource import"),
    INDIVIDUAL_SHARING_CHANGED("Direct share changed"),
    ACCOUNT_CREATED("Account created"),
    ACCOUNT_PROFILE_UPDATED("Account profile updated");

    private final String label;

    Action(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  public enum Outcome {
    SUCCESS("Succeeded"),
    FAILURE("Failed"),
    THROTTLED("Rate limited");

    private final String label;

    Outcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  public enum TargetType {
    ACCOUNT,
    GROUP
  }

  private final JdbcTemplate jdbc;

  public SecurityAudit(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void record(UUID account, Action action, Outcome outcome, String peer) {
    write(account, action, outcome, peer, null, null, null);
  }

  @Transactional
  public void targeted(
      UUID actor,
      Action action,
      Outcome outcome,
      TargetType type,
      UUID target,
      UUID relatedAccount) {
    java.util.Objects.requireNonNull(type);
    java.util.Objects.requireNonNull(target);
    if (type != TargetType.GROUP && relatedAccount != null)
      throw new IllegalArgumentException("Membership context requires a group");
    write(actor, action, outcome, null, type, target, relatedAccount);
  }

  /** Bounded administrative membership replacement; one pruning pass for the complete delta. */
  @Transactional
  public void membershipDelta(
      UUID actor, UUID group, java.util.Set<UUID> before, java.util.Set<UUID> after) {
    if (before.size() > 1000 || after.size() > 1000)
      throw new IllegalArgumentException("Membership audit limit");
    var rows = new java.util.ArrayList<Object[]>();
    for (UUID member : before.stream().sorted().toList()) {
      if (!after.contains(member))
        rows.add(
            new Object[] {
              actor, Action.GROUP_MEMBER_REMOVED.name(), Outcome.SUCCESS.name(), group, member
            });
    }
    for (UUID member : after.stream().sorted().toList()) {
      if (!before.contains(member))
        rows.add(
            new Object[] {
              actor, Action.GROUP_MEMBER_ADDED.name(), Outcome.SUCCESS.name(), group, member
            });
    }
    if (rows.isEmpty()) return;
    jdbc.batchUpdate(
        "INSERT INTO security_audit(account_id,action,outcome,target_type,target_id,related_account_id) VALUES (?,?,?,'GROUP',?,?)",
        rows);
    pruneCount();
  }

  private void write(
      UUID account,
      Action action,
      Outcome outcome,
      String peer,
      TargetType type,
      UUID target,
      UUID relatedAccount) {
    String peerHash =
        peer == null
            ? null
            : SecurityHashes.sha256(peer.length() > 128 ? peer.substring(0, 128) : peer);
    jdbc.update(
        "INSERT INTO security_audit(account_id,action,outcome,peer_hash,target_type,target_id,related_account_id) VALUES (?,?,?,?,?,?,?)",
        account,
        action.name(),
        outcome.name(),
        peerHash,
        type == null ? null : type.name(),
        target,
        relatedAccount);
    pruneCount();
  }

  private void pruneCount() {
    jdbc.update(
        "DELETE FROM security_audit WHERE id <= (SELECT id FROM security_audit ORDER BY id DESC OFFSET 9999 LIMIT 1)");
  }

  @Scheduled(fixedDelay = 60000)
  @Transactional
  public void prune() {
    jdbc.update("DELETE FROM security_audit WHERE occurred_at < now()-interval '30 days'");
    jdbc.update("DELETE FROM login_throttle WHERE expires_at < now()");
    jdbc.update("DELETE FROM webauthn_ceremonies WHERE expires_at < now()");
  }
}
