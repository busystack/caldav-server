package org.busystack.caldav.management;

import java.util.UUID;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.sharing.GroupService;
import org.busystack.caldav.storage.DavStore;
import org.busystack.caldav.storage.StoreException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SharingManagementService {
  record Removal(String collection, String target, String permission, String consequence) {}

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

  Removal directRemoval(Authentication auth, UUID collection, String username) {
    var resource = portal.owner(auth, collection);
    var rows =
        jdbc.query(
            """
        SELECT a.username, m.permission, a.enabled, EXISTS (
          SELECT 1 FROM sharing_group_members u
          JOIN collection_group_members g ON g.group_id=u.group_id
          WHERE u.account_id=a.id AND g.collection_id=m.collection_id
        )
        FROM collection_members m JOIN accounts a ON a.id=m.account_id
        WHERE m.collection_id=? AND a.username=?
        """,
            (rs, row) ->
                new Removal(
                    resource.name(),
                    rs.getString(1),
                    rs.getString(2),
                    !rs.getBoolean(3)
                        ? "This account is disabled. Any separate group grant remains in place."
                        : rs.getBoolean(4)
                            ? "The account will continue to have access through one or more group grants."
                            : "The account will lose access through sharing after this grant is removed."),
            collection,
            username);
    if (rows.isEmpty()) throw new StoreException(StoreException.Kind.NOT_FOUND);
    return rows.getFirst();
  }

  Removal groupRemoval(Authentication auth, UUID collection, UUID group) {
    var resource = portal.owner(auth, collection);
    var rows =
        jdbc.query(
            """
        SELECT g.name, grant_row.permission,
            (SELECT count(*) FROM sharing_group_members m JOIN accounts a ON a.id=m.account_id
             WHERE m.group_id=g.id AND a.enabled AND a.id<>?
               AND NOT EXISTS (SELECT 1 FROM collection_members d
                  WHERE d.collection_id=grant_row.collection_id AND d.account_id=a.id)
               AND NOT EXISTS (SELECT 1 FROM sharing_group_members m2
                  JOIN collection_group_members g2 ON g2.group_id=m2.group_id
                  WHERE m2.account_id=a.id AND g2.collection_id=grant_row.collection_id
                    AND g2.group_id<>g.id))
        FROM collection_group_members grant_row JOIN sharing_groups g ON g.id=grant_row.group_id
        WHERE grant_row.collection_id=? AND grant_row.group_id=?
        """,
            (rs, row) -> {
              long affected = rs.getLong(3);
              return new Removal(
                  resource.name(),
                  rs.getString(1),
                  rs.getString(2),
                  (affected == 0
                      ? "Every enabled recipient keeps any access they have through other grants."
                      : (affected == 1
                              ? "One enabled account will lose access. "
                              : affected + " enabled accounts will lose access. ")
                          + "Other direct and group grants will continue to apply."));
            },
            management.current(auth).id(),
            collection,
            group);
    if (rows.isEmpty()) throw new StoreException(StoreException.Kind.NOT_FOUND);
    return rows.getFirst();
  }
}
