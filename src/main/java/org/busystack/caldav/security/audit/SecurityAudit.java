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
    DATABASE_RESTORE,
    PASSWORD_LOGIN,
    PASSWORD_CHANGED,
    ACCOUNT_DISABLED,
    OFFLINE_RECOVERY,
    PASSKEY_LOGIN,
    SESSION_REVOKED,
    RECOVERY_CODES_ISSUED,
    RECOVERY_USED,
    GROUP_CREATED,
    GROUP_UPDATED,
    GROUP_MEMBER_ADDED,
    GROUP_MEMBER_REMOVED,
    GROUP_DELETED,
    GROUP_SHARING_CHANGED,
    COLLECTION_CREATED,
    COLLECTION_UPDATED,
    COLLECTION_DELETED,
    COLLECTION_IMPORTED,
    INDIVIDUAL_SHARING_CHANGED,
    ACCOUNT_CREATED,
    ACCOUNT_PROFILE_UPDATED
  }

  public enum Outcome {
    SUCCESS,
    FAILURE,
    THROTTLED
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
