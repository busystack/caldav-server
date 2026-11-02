package org.busystack.caldav.management;

import java.util.UUID;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.sharing.GroupService;
import org.busystack.caldav.storage.DavStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SharingManagementService {
  private final PortalService portal;
  private final ManagementService management;
  private final DavStore dav;
  private final GroupService groups;
  private final JdbcTemplate jdbc;
  private final SecurityAudit audit;

  public SharingManagementService(
      PortalService portal,
      ManagementService management,
      DavStore dav,
      GroupService groups,
      JdbcTemplate jdbc,
      SecurityAudit audit) {
    this.portal = portal;
    this.management = management;
    this.dav = dav;
    this.groups = groups;
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Transactional
  public void direct(
      Authentication auth, UUID collection, String username, String permission, String expected) {
    RecentAuthentication.requireMfa(auth);
    UUID actor = management.current(auth).id();
    dav.lockCollection(actor, collection);
    portal.owner(auth, collection);
    var current =
        jdbc.queryForList(
            "SELECT m.permission FROM collection_members m JOIN accounts a ON a.id=m.account_id WHERE m.collection_id=? AND a.username=?",
            String.class,
            collection,
            username);
    PortalService.checkState(expected, current.isEmpty() ? "NONE" : current.getFirst());
    management.share(auth, collection, username, permission);
    audit.record(
        actor,
        SecurityAudit.Action.INDIVIDUAL_SHARING_CHANGED,
        SecurityAudit.Outcome.SUCCESS,
        null);
  }

  @Transactional
  public void group(
      Authentication auth, UUID collection, UUID group, String permission, String expected) {
    RecentAuthentication.requireMfa(auth);
    UUID actor = management.current(auth).id();
    dav.lockCollection(actor, collection);
    portal.owner(auth, collection);
    var current =
        jdbc.queryForList(
            "SELECT permission FROM collection_group_members WHERE collection_id=? AND group_id=?",
            String.class,
            collection,
            group);
    PortalService.checkState(expected, current.isEmpty() ? "NONE" : current.getFirst());
    groups.grant(auth, collection, group, permission);
  }
}
