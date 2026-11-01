package org.busystack.caldav.protocol;

import static org.busystack.caldav.protocol.DavXml.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.namespace.QName;

/** The supported, effective and writable views of the application's coarse DAV privilege model. */
final class DavPrivileges {
  record Privilege(
      QName name, String description, boolean abstractPrivilege, List<Privilege> children) {
    Privilege {
      children = List.copyOf(children);
    }
  }

  private DavPrivileges() {}

  static boolean calendarReports(DavNode.Kind kind) {
    return Set.of(
            DavNode.Kind.CALENDAR, DavNode.Kind.RESOURCE, DavNode.Kind.HOME, DavNode.Kind.FOLDER)
        .contains(kind);
  }

  private static Privilege leaf(QName name, String description, boolean abstractPrivilege) {
    return new Privilege(name, description, abstractPrivilege, List.of());
  }

  static Privilege supported(DavNode.Kind kind) {
    List<Privilege> privileges = new ArrayList<>();
    List<Privilege> reads = new ArrayList<>();
    reads.add(leaf(d("read-current-user-privilege-set"), "Read current privileges", true));
    if (calendarReports(kind))
      reads.add(leaf(c("read-free-busy"), "Read free/busy information", true));
    privileges.add(
        new Privilege(d("read"), "Read calendar data and permitted properties", false, reads));
    if (calendarReports(kind)) {
      List<Privilege> writes = new ArrayList<>();
      if (kind == DavNode.Kind.RESOURCE)
        writes.add(leaf(d("write-content"), "Modify calendar resource data", true));
      if (kind != DavNode.Kind.HOME)
        writes.add(leaf(d("write-properties"), "Modify writable properties", true));
      if (kind != DavNode.Kind.RESOURCE) {
        writes.add(leaf(d("bind"), "Add collection members", true));
        writes.add(leaf(d("unbind"), "Remove collection members", true));
      }
      privileges.add(
          new Privilege(
              d("write"), "Modify supported content or collection members", false, writes));
    }
    privileges.add(leaf(d("read-acl"), "Read the permitted ACL", false));
    if (kind == DavNode.Kind.CALENDAR)
      privileges.add(leaf(d("write-acl"), "Collection owner manages sharing", false));
    return new Privilege(d("all"), "All privileges applicable to this resource", false, privileges);
  }

  static Privilege find(DavNode.Kind kind, QName name) {
    return find(supported(kind), name);
  }

  private static Privilege find(Privilege privilege, QName name) {
    if (privilege.name().equals(name)) return privilege;
    for (Privilege child : privilege.children()) {
      Privilege found = find(child, name);
      if (found != null) return found;
    }
    return null;
  }

  static List<QName> effective(DavNode.Kind kind, String permission) {
    boolean owner = permission.equals("OWNER"), writable = !permission.equals("READ");
    List<QName> result = new ArrayList<>(List.of(d("read")));
    if (writable && calendarReports(kind)) result.add(d("write"));
    if (owner) {
      result.add(d("read-acl"));
      if (kind == DavNode.Kind.CALENDAR) result.add(d("write-acl"));
      result.add(d("all"));
    }
    return List.copyOf(result);
  }

  static String permission(DavNode.Kind kind, Set<QName> grants) {
    for (QName name : grants) {
      Privilege supported = find(kind, name);
      if (supported == null) throw new DavException(403, d("not-supported-privilege"));
      if (supported.abstractPrivilege()) throw new DavException(403, d("no-abstract"));
    }
    if (!grants.contains(d("read")) || !Set.of(d("read"), d("write")).containsAll(grants))
      throw new DavException(403, d("no-ace-conflict"));
    return grants.contains(d("write")) ? "WRITE" : "READ";
  }
}
