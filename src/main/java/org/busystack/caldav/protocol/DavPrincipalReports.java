package org.busystack.caldav.protocol;

import static org.busystack.caldav.protocol.DavXml.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.namespace.QName;
import org.busystack.caldav.calendar.query.FloatingTimezone;
import org.busystack.caldav.storage.DavStore;
import org.springframework.http.ResponseEntity;
import org.w3c.dom.Element;

/** Required DAV ACL/principal reports operate only on the caller's visible principal graph. */
final class DavPrincipalReports {
  private final DavStore store;
  private final DavResources resources;
  private final DavProperties properties;
  private final DavPaths paths;
  private int expansionWork;

  DavPrincipalReports(
      DavStore store, DavResources resources, DavProperties properties, DavPaths paths) {
    this.store = store;
    this.resources = resources;
    this.properties = properties;
    this.paths = paths;
  }

  ResponseEntity<byte[]> report(
      UUID actor, DavNode node, HttpServletRequest request, Element root) {
    if (is(root, DAV, "principal-search-property-set")) return searchable(request, root);
    if (is(root, DAV, "expand-property")) return expand(actor, node, request, root);
    List<DavNode> result = new ArrayList<>();
    if (is(root, DAV, "principal-property-search")) {
      requireDepthZero(request);
      List<Element> searches =
          children(root).stream().filter(e -> is(e, DAV, "property-search")).toList();
      if (searches.isEmpty()) throw new DavException(400, null);
      boolean collectionSet = child(root, DAV, "apply-to-principal-collection-set") != null;
      if (!collectionSet && !node.collection()) throw new DavException(403, d("supported-report"));
      for (var principal : store.principals(actor)) {
        DavNode candidate =
            resources.resolve(actor, List.of("principals", principal.id().toString()));
        if (!collectionSet
            && !(candidate.segments().size() > node.segments().size()
                && candidate.segments().subList(0, node.segments().size()).equals(node.segments())))
          continue;
        boolean matches = true;
        for (Element search : searches) {
          Element prop = required(search, DAV, "prop"), match = required(search, DAV, "match");
          if (children(prop).isEmpty() || match.getTextContent().length() > 8192)
            throw new DavException(400, null);
          boolean propertyMatch = true;
          for (Element p : children(prop)) {
            propertyMatch &=
                name(p).equals(d("displayname"))
                    && principal
                        .displayName()
                        .toLowerCase(Locale.ROOT)
                        .contains(match.getTextContent().toLowerCase(Locale.ROOT));
          }
          matches &= propertyMatch;
        }
        if (matches) result.add(candidate);
      }
    } else if (is(root, DAV, "acl-principal-prop-set")) {
      requireDepthZero(request);
      if (!properties.privileges(actor, node).contains(d("read-acl")))
        throw new DavException(
            403,
            d("need-privileges"),
            paths.href(node.segments(), node.trailingSlash()),
            d("read-acl"));
      Set<UUID> principals = new LinkedHashSet<>();
      if (node.collectionId() != null) {
        principals.add(node.entry().collection().owner());
        for (var member : store.members(actor, node.collectionId()))
          principals.add(member.account());
      } else principals.add(properties.owner(actor, node));
      for (UUID principal : principals)
        result.add(resources.resolve(actor, List.of("principals", principal.toString())));
    } else if (is(root, DAV, "principal-match")) {
      requireDepthZero(request);
      Element self = child(root, DAV, "self"),
          principalProperty = child(root, DAV, "principal-property");
      if ((self == null) == (principalProperty == null)) throw new DavException(400, null);
      boolean group =
          node.kind() == DavNode.Kind.PRINCIPAL && store.principal(actor, node.principal()).group();
      if (!node.collection() && !(self != null && group))
        throw new DavException(403, d("supported-report"));
      var candidates = node.collection() ? resources.descendants(actor, node, 20) : List.of(node);
      if (self != null) {
        for (DavNode candidate : candidates)
          if ((!candidate.segments().equals(node.segments()) || group)
              && candidate.kind() == DavNode.Kind.PRINCIPAL
              && (candidate.principal().equals(actor)
                  || store.groupContains(actor, candidate.principal()))) result.add(candidate);
      } else {
        if (children(principalProperty).size() != 1) throw new DavException(400, null);
        QName property = name(children(principalProperty).getFirst());
        for (DavNode candidate : candidates) {
          if (candidate.segments().equals(node.segments())) continue;
          var document = document();
          Element holder = add(document, d("prop"));
          Element value =
              properties.value(
                  holder,
                  actor,
                  candidate,
                  property,
                  null,
                  properties.dead(actor, candidate, Set.of(property)),
                  FloatingTimezone.of(java.time.ZoneOffset.UTC));
          if (value != null
              && children(value).stream()
                  .anyMatch(e -> is(e, DAV, "href") && matchesPrincipal(actor, e.getTextContent())))
            result.add(candidate);
        }
      }
    } else throw new DavException(403, d("supported-report"));
    var document = document();
    Element multi = add(document, d("multistatus"));
    var requested = DavProperties.requested(root);
    for (DavNode candidate : result)
      properties.response(
          multi, actor, candidate, requested, FloatingTimezone.of(java.time.ZoneOffset.UTC));
    return DavController.xml(207, document);
  }

  private ResponseEntity<byte[]> searchable(HttpServletRequest request, Element root) {
    requireDepthZero(request);
    if (!children(root).isEmpty()) throw new DavException(400, null);
    var document = document();
    Element result = add(document, d("principal-search-property-set")),
        property = add(result, d("principal-search-property"));
    add(add(property, d("prop")), d("displayname"));
    Element description =
        text(property, d("description"), "Principal display name (case-insensitive substring)");
    description.setAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "xml:lang", "en");
    return DavController.xml(200, document);
  }

  private ResponseEntity<byte[]> expand(
      UUID actor, DavNode node, HttpServletRequest request, Element root) {
    for (Element property : children(root)) validateExpansion(property);
    var document = document();
    Element multi = add(document, d("multistatus"));
    for (DavNode candidate : resources.descendants(actor, node, reportDepth(request)))
      expandedResponse(multi, actor, candidate, children(root), 0);
    return DavController.xml(207, document);
  }

  private Element expandedResponse(
      Element parent, UUID actor, DavNode node, List<Element> selectors, int depth) {
    if (++expansionWork > 10000 || depth > 16)
      throw new DavException(507, d("number-of-matches-within-limits"));
    Map<QName, Element> requested = new LinkedHashMap<>();
    for (Element selector : selectors) requested.put(expansionName(selector), null);
    Element response =
        properties.response(
            parent,
            actor,
            node,
            new DavProperties.Requested(false, false, requested),
            FloatingTimezone.of(java.time.ZoneOffset.UTC));
    for (Element propstat : children(response))
      if (is(propstat, DAV, "propstat")) {
        Element status = required(propstat, DAV, "status");
        if (!status.getTextContent().equals(DavProperties.status(200))) continue;
        for (Element property : children(required(propstat, DAV, "prop"))) {
          Element selector =
              selectors.stream()
                  .filter(s -> expansionName(s).equals(name(property)))
                  .findFirst()
                  .orElse(null);
          if (selector == null || children(selector).isEmpty()) continue;
          for (Element href : new ArrayList<>(children(property)))
            if (is(href, DAV, "href")) {
              String value = href.getTextContent();
              property.removeChild(href);
              try {
                expandedResponse(
                    property,
                    actor,
                    resources.resolveMember(actor, paths.parse(value)),
                    children(selector),
                    depth + 1);
              } catch (DavException ex) {
                if (ex.status() != 403 && ex.status() != 404) throw ex;
                Element missing = add(property, d("response"));
                text(missing, d("href"), value);
                text(missing, d("status"), DavProperties.status(ex.status()));
              }
            }
        }
      }
    return response;
  }

  private static void validateExpansion(Element selector) {
    if (!is(selector, DAV, "property")
        || !selector.hasAttribute("name")
        || selector.getAttribute("name").isBlank()) throw new DavException(400, null);
    expansionName(selector);
    for (Element child : children(selector)) validateExpansion(child);
  }

  private static QName expansionName(Element selector) {
    String name = selector.getAttribute("name"),
        namespace = selector.hasAttribute("namespace") ? selector.getAttribute("namespace") : DAV;
    if (name.isEmpty()
        || name.indexOf(':') >= 0
        || name.length() > 255
        || namespace.length() > 1024) throw new DavException(400, null);
    try {
      document()
          .createElementNS(
              namespace.isEmpty() ? null : namespace, namespace.isEmpty() ? name : "P:" + name);
    } catch (org.w3c.dom.DOMException ex) {
      throw new DavException(400, null);
    }
    return new QName(namespace, name);
  }

  private boolean matchesPrincipal(UUID actor, String href) {
    try {
      var target = paths.parse(href);
      return target.size() == 2
          && target.getFirst().equals("principals")
          && (DavResources.uuid(target.get(1)).equals(actor)
              || store.groupContains(actor, DavResources.uuid(target.get(1))));
    } catch (DavException unknown) {
      return false;
    }
  }

  private static void requireDepthZero(HttpServletRequest request) {
    String depth = request.getHeader("Depth");
    if (depth != null && !depth.equals("0")) throw new DavException(400, null);
  }

  private static int reportDepth(HttpServletRequest request) {
    String depth = request.getHeader("Depth");
    if (depth == null || depth.equals("0")) return 0;
    if (depth.equals("1")) return 1;
    if (depth.equals("infinity")) return 20;
    throw new DavException(400, null);
  }
}
