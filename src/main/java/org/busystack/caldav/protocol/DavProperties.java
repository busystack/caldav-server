package org.busystack.caldav.protocol;

import static org.busystack.caldav.protocol.DavXml.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.namespace.QName;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.query.CalendarDataProjection;
import org.busystack.caldav.calendar.query.FloatingTimezone;
import org.busystack.caldav.calendar.query.QueryLimits;
import org.busystack.caldav.storage.CalendarStore;
import org.busystack.caldav.storage.DavStore;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

@Service
final class DavProperties {
  record Requested(
      boolean all,
      boolean names,
      Map<QName, Element> properties,
      CalendarDataProjection projection) {
    Requested(boolean all, boolean names, Map<QName, Element> properties) {
      this(all, names, properties, new CalendarDataProjection(QueryLimits.defaults()));
    }

    Requested {
      properties = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
  }

  private static final List<String> COMMON =
      List.of(
          "resourcetype",
          "displayname",
          "owner",
          "current-user-principal",
          "principal-collection-set",
          "supported-report-set",
          "current-user-privilege-set",
          "supported-privilege-set",
          "acl-restrictions",
          "inherited-acl-set");
  private static final Set<QName> WRITABLE =
      Set.of(d("displayname"), c("calendar-description"), c("calendar-timezone"));
  private final DavStore store;
  private final DavPaths paths;
  private final CalendarStore calendars;

  DavProperties(
      DavStore store,
      CalendarStore calendars,
      org.busystack.caldav.config.DeploymentProperties deployment) {
    this.store = store;
    this.calendars = calendars;
    this.paths = new DavPaths(deployment);
  }

  static Requested requested(Element root) {
    if (root == null) return new Requested(true, false, Map.of());
    Element prop = child(root, DAV, "prop"),
        all = child(root, DAV, "allprop"),
        names = child(root, DAV, "propname"),
        include = child(root, DAV, "include");
    if ((prop != null ? 1 : 0) + (all != null ? 1 : 0) + (names != null ? 1 : 0) > 1
        || include != null && all == null) throw new DavException(400, null);
    Map<QName, Element> selection = new LinkedHashMap<>();
    if (prop != null)
      for (Element p : children(prop)) {
        if (selection.put(name(p), p) != null) throw new DavException(400, null);
      }
    if (include != null) for (Element p : children(include)) selection.put(name(p), p);
    return new Requested(prop == null && names == null, names != null, selection);
  }

  Element response(
      Element multistatus,
      UUID actor,
      DavNode node,
      Requested requested,
      FloatingTimezone timezone) {
    Element response = add(multistatus, d("response"));
    text(response, d("href"), paths.href(node.segments(), node.trailingSlash()));
    Map<QName, String> dead =
        requested.names()
            ? Map.of()
            : dead(actor, node, requested.all() ? null : requested.properties().keySet());
    Set<QName> names = new LinkedHashSet<>();
    if (requested.all() || requested.names()) {
      names.addAll(allNames(actor, node));
      names.addAll(requested.names() ? deadNames(actor, node) : dead.keySet());
    }
    names.addAll(requested.properties().keySet());
    Map<Integer, List<Element>> statuses = new LinkedHashMap<>();
    Map<Integer, DavException> failures = new LinkedHashMap<>();
    for (QName property : names) {
      // Build on the response document's shared work budget and move the completed node,
      // avoiding a second DOM copy for every property.
      Element holder = multistatus.getOwnerDocument().createElementNS(DAV, "D:prop");
      int status = 200;
      try {
        Element value =
            requested.names()
                ? add(holder, property)
                : value(
                    holder,
                    actor,
                    node,
                    property,
                    requested.properties().get(property),
                    dead,
                    timezone,
                    requested.projection());
        if (value == null) {
          status = 404;
          add(holder, property);
        }
      } catch (DavException ex) {
        if (ex.status() != 403) throw ex;
        status = 403;
        failures.putIfAbsent(status, ex);
        while (holder.hasChildNodes()) holder.removeChild(holder.getFirstChild());
        add(holder, property);
      }
      statuses
          .computeIfAbsent(status, ignored -> new ArrayList<>())
          .add((Element) holder.getFirstChild());
    }
    // A successful changed member requires a propstat even for an explicit empty prop set.
    if (statuses.isEmpty()) statuses.put(200, List.of());
    for (var entry : statuses.entrySet()) {
      Element propstat = add(response, d("propstat")), prop = add(propstat, d("prop"));
      for (Element value : entry.getValue()) prop.appendChild(value);
      text(propstat, d("status"), status(entry.getKey()));
      var failure = failures.get(entry.getKey());
      if (failure != null)
        DavErrors.append(
            propstat, failure.condition(), failure.failingHref(), failure.requiredPrivilege());
    }
    return response;
  }

  private Set<QName> allNames(UUID actor, DavNode node) {
    Set<QName> names = new LinkedHashSet<>();
    for (String local : COMMON) names.add(d(local));
    if (node.kind() == DavNode.Kind.RESOURCE) {
      names.add(d("getetag"));
      if (node.metadata().modifiedAt() != null) names.add(d("getlastmodified"));
      names.add(d("getcontenttype"));
      names.add(d("getcontentlength"));
    }
    if (node.kind() == DavNode.Kind.CALENDAR) {
      for (String local :
          List.of(
              "supported-calendar-component-set",
              "supported-calendar-data",
              "max-resource-size",
              "supported-collation-set")) names.add(c(local));
      names.add(d("sync-token"));
    }
    if (node.kind() == DavNode.Kind.PRINCIPAL) {
      names.add(d("principal-URL"));
      names.add(d("alternate-URI-set"));
      names.add(d("group-membership"));
      if (store.principal(actor, node.principal()).group()) names.add(d("group-member-set"));
      else names.add(c("calendar-home-set"));
    }
    return names;
  }

  Map<QName, String> dead(UUID actor, DavNode node) {
    return dead(actor, node, null);
  }

  Map<QName, String> dead(UUID actor, DavNode node, Set<QName> selected) {
    if (selected != null) {
      selected =
          selected.stream()
              .filter(property -> !liveOnly(property))
              .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
      if (selected.isEmpty()) return Map.of();
    }
    if (node.entry() == null) return Map.of();
    if (node.collectionId() != null) {
      if (node.historical())
        return calendars.historicalProperties(
            actor,
            node.collectionId(),
            node.metadata().name(),
            node.metadata().revision(),
            selected);
      return store.deadProperties(
          actor,
          node.collectionId(),
          node.metadata() == null ? null : node.metadata().name(),
          selected);
    }
    return store.folderProperties(actor, node.principal(), node.relativePath(), selected);
  }

  private Set<QName> deadNames(UUID actor, DavNode node) {
    if (node.entry() == null) return Set.of();
    if (node.collectionId() != null) {
      if (node.historical())
        return calendars.historicalPropertyNames(
            actor, node.collectionId(), node.metadata().name(), node.metadata().revision());
      return store.deadPropertyNames(
          actor, node.collectionId(), node.metadata() == null ? null : node.metadata().name());
    }
    return store.folderPropertyNames(actor, node.principal(), node.relativePath());
  }

  Element value(
      Element parent,
      UUID actor,
      DavNode node,
      QName property,
      Element selector,
      Map<QName, String> dead,
      FloatingTimezone timezone) {
    return value(
        parent,
        actor,
        node,
        property,
        selector,
        dead,
        timezone,
        new CalendarDataProjection(QueryLimits.defaults()));
  }

  private Element value(
      Element parent,
      UUID actor,
      DavNode node,
      QName property,
      Element selector,
      Map<QName, String> dead,
      FloatingTimezone timezone,
      CalendarDataProjection projection) {
    if (dead.containsKey(property)) {
      Element stored = parse(dead.get(property).getBytes(StandardCharsets.UTF_8));
      return appendCopy(parent, stored);
    }
    if (property.equals(d("resourcetype"))) {
      Element value = add(parent, property);
      if (node.collection()) add(value, d("collection"));
      if (node.kind() == DavNode.Kind.CALENDAR) add(value, c("calendar"));
      if (node.kind() == DavNode.Kind.PRINCIPAL) add(value, d("principal"));
      return value;
    }
    if (property.equals(d("displayname"))) return text(parent, property, displayName(actor, node));
    if (property.equals(d("current-user-principal")))
      return href(parent, property, principal(actor));
    if (property.equals(d("principal-collection-set")))
      return href(parent, property, paths.href(List.of("principals"), true));
    if (property.equals(d("owner"))) return href(parent, property, principal(owner(actor, node)));
    if (property.equals(d("supported-report-set"))) {
      Element value = add(parent, property);
      List<QName> reports =
          new ArrayList<>(
              List.of(
                  d("expand-property"),
                  d("principal-property-search"),
                  d("principal-search-property-set"),
                  d("acl-principal-prop-set")));
      if (node.collection()
          || (node.kind() == DavNode.Kind.PRINCIPAL
              && store.principal(actor, node.principal()).group()))
        reports.add(d("principal-match"));
      if (node.kind() == DavNode.Kind.CALENDAR
          || node.kind() == DavNode.Kind.RESOURCE
          || node.kind() == DavNode.Kind.FOLDER
          || node.kind() == DavNode.Kind.HOME)
        reports.addAll(List.of(c("calendar-query"), c("calendar-multiget")));
      if (Set.of(DavNode.Kind.CALENDAR, DavNode.Kind.FOLDER, DavNode.Kind.HOME)
          .contains(node.kind())) reports.add(c("free-busy-query"));
      if (node.kind() == DavNode.Kind.CALENDAR) reports.add(d("sync-collection"));
      for (QName report : reports) add(add(add(value, d("supported-report")), d("report")), report);
      return value;
    }
    if (property.equals(d("current-user-privilege-set"))) {
      Element value = add(parent, property);
      for (QName privilege : privileges(actor, node)) add(add(value, d("privilege")), privilege);
      return value;
    }
    if (property.equals(d("supported-privilege-set")))
      return supportedPrivileges(parent, property, node);
    if (property.equals(d("acl-restrictions"))) {
      Element value = add(parent, property);
      add(value, d("grant-only"));
      add(value, d("no-invert"));
      add(add(add(value, d("required-principal")), d("property")), d("owner"));
      return value;
    }
    if (property.equals(d("inherited-acl-set"))) {
      Element value = add(parent, property);
      if (node.kind() == DavNode.Kind.RESOURCE) {
        var parts = node.segments().subList(0, node.segments().size() - 1);
        text(value, d("href"), paths.href(parts, true));
      }
      return value;
    }
    if (property.equals(d("acl"))) return acl(parent, actor, node, property);
    if (node.kind() == DavNode.Kind.PRINCIPAL) {
      if (property.equals(d("principal-URL")))
        return href(parent, property, principal(node.principal()));
      if (property.equals(d("alternate-URI-set"))) return add(parent, property);
      if (property.equals(d("group-member-set"))) {
        if (!store.principal(actor, node.principal()).group()) return null;
        Element value = add(parent, property);
        for (var member : store.groupMembers(actor, node.principal()))
          text(value, d("href"), principal(member.id()));
        return value;
      }
      if (property.equals(d("group-membership"))) {
        Element value = add(parent, property);
        for (var group : store.principalGroups(actor, node.principal()))
          text(value, d("href"), principal(group.id()));
        return value;
      }
      if (property.equals(c("calendar-home-set"))) {
        if (store.principal(actor, node.principal()).group()) return null;
        return href(
            parent, property, paths.href(List.of("calendars", node.principal().toString()), true));
      }
    }
    if (node.kind() == DavNode.Kind.RESOURCE) {
      if (property.equals(d("getetag"))) return text(parent, property, node.metadata().etag());
      if (property.equals(d("getlastmodified")) && node.metadata().modifiedAt() != null)
        return text(
            parent,
            property,
            HttpDateValues.format(
                org.busystack.caldav.storage.ModificationDates.effective(
                    node.metadata().modifiedAt(), java.time.Instant.now())));
      if (property.equals(d("getcontenttype")))
        return text(parent, property, "text/calendar; charset=utf-8");
      if (property.equals(d("getcontentlength")))
        return text(parent, property, Long.toString(node.metadata().contentLength()));
      if (property.equals(c("calendar-data"))) {
        CalendarStore.Resource resource = node.resource();
        if (resource == null) {
          if (node.historical())
            throw new IllegalStateException(
                "Historical calendar data was not selected by synchronization");
          resource = calendars.get(actor, node.collectionId(), node.metadata().name());
          if (resource == null) throw new DavException(404, null);
        }
        String content;
        var selection = selector == null ? null : CalendarQueryXml.selection(selector);
        if (selector == null || children(selector).isEmpty())
          content = new String(resource.content(), StandardCharsets.UTF_8);
        else
          content =
              projection
                  .project(
                      CalendarContent.parse(
                          resource.content(), node.entry().collection().component()),
                      selection,
                      timezone)
                  .toString();
        return text(parent, property, content);
      }
    }
    if (node.kind() == DavNode.Kind.CALENDAR) {
      if (property.equals(d("sync-token")))
        return text(parent, property, calendars.currentSyncToken(actor, node.collectionId()));
      if (property.equals(c("supported-calendar-component-set"))) {
        Element value = add(parent, property);
        add(value, c("comp")).setAttribute("name", node.entry().collection().component());
        return value;
      }
      if (property.equals(c("supported-calendar-data"))) {
        Element value = add(parent, property), data = add(value, c("calendar-data"));
        data.setAttribute("content-type", "text/calendar");
        data.setAttribute("version", "2.0");
        return value;
      }
      if (property.equals(c("max-resource-size")))
        return text(parent, property, Integer.toString(CalendarContent.MAX_BYTES));
      if (property.equals(c("supported-collation-set"))) {
        Element value = add(parent, property);
        text(value, c("supported-collation"), "i;ascii-casemap");
        text(value, c("supported-collation"), "i;octet");
        return value;
      }
    }
    return null;
  }

  String displayName(UUID actor, DavNode node) {
    if (node.entry() != null)
      return node.kind() == DavNode.Kind.RESOURCE
          ? node.metadata().name()
          : node.entry().binding().displayName();
    if (node.kind() == DavNode.Kind.PRINCIPAL)
      return store.principal(actor, node.principal()).displayName();
    return switch (node.kind()) {
      case ROOT -> "BusyStack CalDAV";
      case PRINCIPALS -> "Principals";
      case CALENDARS -> "Calendar homes";
      case HOME -> "Calendars and tasks";
      default -> "";
    };
  }

  List<QName> privileges(UUID actor, DavNode node) {
    List<QName> result = new ArrayList<>(List.of(d("read"), d("read-current-user-privilege-set")));
    String permission =
        node.collectionId() == null
            ? (owner(actor, node).equals(actor) ? "OWNER" : "READ")
            : store.permission(actor, node.collectionId());
    boolean owner = permission.equals("OWNER"), writable = !permission.equals("READ");
    switch (node.kind()) {
      case CALENDAR -> {
        result.add(c("read-free-busy"));
        if (writable)
          result.addAll(List.of(d("write"), d("write-properties"), d("bind"), d("unbind")));
        if (owner) result.addAll(List.of(d("read-acl"), d("write-acl")));
      }
      case RESOURCE -> {
        result.add(c("read-free-busy"));
        if (writable) result.addAll(List.of(d("write"), d("write-content"), d("write-properties")));
        if (owner) result.add(d("read-acl"));
      }
      case HOME, FOLDER -> {
        result.add(c("read-free-busy"));
        if (owner) {
          result.addAll(List.of(d("write"), d("bind"), d("unbind"), d("read-acl")));
          if (node.kind() == DavNode.Kind.FOLDER) result.add(d("write-properties"));
        }
      }
      default -> {
        if (owner) result.add(d("read-acl"));
      }
    }
    return List.copyOf(result);
  }

  private Element acl(Element parent, UUID actor, DavNode node, QName property) {
    UUID owner = owner(actor, node);
    if (!owner.equals(actor))
      throw new DavException(
          403,
          d("need-privileges"),
          paths.href(node.segments(), node.trailingSlash()),
          d("read-acl"));
    Element value = add(parent, property);
    Element ownerAce = add(value, d("ace"));
    add(add(add(ownerAce, d("principal")), d("property")), d("owner"));
    add(add(add(ownerAce, d("grant")), d("privilege")), d("all"));
    add(ownerAce, d("protected"));
    if (node.collectionId() != null)
      for (var member : store.members(actor, node.collectionId())) {
        Element ace = add(value, d("ace"));
        text(add(ace, d("principal")), d("href"), principal(member.account()));
        Element grant = add(ace, d("grant"));
        for (QName privilege : List.of(d("read"))) add(add(grant, d("privilege")), privilege);
        if (member.permission().equals("WRITE")) add(add(grant, d("privilege")), d("write"));
        if (node.kind() == DavNode.Kind.RESOURCE)
          text(
              add(ace, d("inherited")),
              d("href"),
              paths.href(node.segments().subList(0, node.segments().size() - 1), true));
      }
    return value;
  }

  private Element supportedPrivileges(Element parent, QName property, DavNode node) {
    Element value = add(parent, property),
        all = supported(value, d("all"), "All privileges applicable to this resource");
    Element read = supported(all, d("read"), "Read calendar data and permitted properties");
    add(
        supported(read, d("read-current-user-privilege-set"), "Read current privileges"),
        d("abstract"));
    if (Set.of(DavNode.Kind.CALENDAR, DavNode.Kind.RESOURCE, DavNode.Kind.HOME, DavNode.Kind.FOLDER)
        .contains(node.kind()))
      add(supported(read, c("read-free-busy"), "Read free/busy information"), d("abstract"));
    if (Set.of(DavNode.Kind.CALENDAR, DavNode.Kind.RESOURCE, DavNode.Kind.HOME, DavNode.Kind.FOLDER)
        .contains(node.kind())) {
      Element write = supported(all, d("write"), "Modify supported content or collection members");
      if (node.kind() == DavNode.Kind.RESOURCE)
        add(supported(write, d("write-content"), "Modify calendar resource data"), d("abstract"));
      if (node.kind() != DavNode.Kind.HOME)
        add(supported(write, d("write-properties"), "Modify writable properties"), d("abstract"));
      if (node.collection())
        for (String privilege : List.of("bind", "unbind"))
          add(supported(write, d(privilege), privilege), d("abstract"));
    }
    supported(all, d("read-acl"), "Read the permitted ACL");
    if (node.kind() == DavNode.Kind.CALENDAR)
      supported(all, d("write-acl"), "Collection owner manages sharing");
    return value;
  }

  private Element supported(Element parent, QName privilege, String description) {
    Element value = add(parent, d("supported-privilege"));
    add(add(value, d("privilege")), privilege);
    Element text = text(value, d("description"), description);
    text.setAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "xml:lang", "en");
    return value;
  }

  UUID owner(UUID actor, DavNode node) {
    if (node.kind() == DavNode.Kind.PRINCIPAL)
      return store.principal(actor, node.principal()).owner();
    return node.entry() != null && node.entry().collection() != null
        ? node.entry().collection().owner()
        : node.principal() == null ? actor : node.principal();
  }

  private String principal(UUID id) {
    return paths.href(List.of("principals", id.toString()), true);
  }

  private static Element href(Node parent, QName property, String href) {
    Element value = add(parent, property);
    text(value, d("href"), href);
    return value;
  }

  static String status(int code) {
    return "HTTP/1.1 "
        + code
        + " "
        + switch (code) {
          case 200 -> "OK";
          case 403 -> "Forbidden";
          case 404 -> "Not Found";
          case 409 -> "Conflict";
          case 424 -> "Failed Dependency";
          case 507 -> "Insufficient Storage";
          default -> "Error";
        };
  }

  private static boolean liveOnly(QName property) {
    if (WRITABLE.contains(property)) return false;
    if (DAV.equals(property.getNamespaceURI()))
      return COMMON.contains(property.getLocalPart())
          || Set.of(
                  "acl",
                  "principal-URL",
                  "alternate-URI-set",
                  "group-member-set",
                  "group-membership",
                  "getetag",
                  "getlastmodified",
                  "getcontenttype",
                  "getcontentlength",
                  "sync-token")
              .contains(property.getLocalPart());
    return CAL.equals(property.getNamespaceURI())
        && Set.of(
                "calendar-data",
                "calendar-home-set",
                "supported-calendar-component-set",
                "supported-calendar-data",
                "max-resource-size",
                "supported-collation-set")
            .contains(property.getLocalPart());
  }

  static boolean protectedProperty(QName property) {
    return !WRITABLE.contains(property)
        && (DAV.equals(property.getNamespaceURI())
            || CAL.equals(property.getNamespaceURI())
                && !Set.of("calendar-description", "calendar-timezone")
                    .contains(property.getLocalPart()));
  }
}
