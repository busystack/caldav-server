package org.busystack.caldav.protocol;

import static org.busystack.caldav.protocol.DavXml.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.xml.namespace.QName;
import org.busystack.caldav.calendar.CalendarContent;
import org.busystack.caldav.calendar.query.CalendarFreeBusy;
import org.busystack.caldav.calendar.query.CalendarQuery;
import org.busystack.caldav.calendar.query.FloatingTimezone;
import org.busystack.caldav.calendar.query.QueryException;
import org.busystack.caldav.calendar.query.QueryLimits;
import org.busystack.caldav.config.DeploymentProperties;
import org.busystack.caldav.storage.CalendarStore;
import org.busystack.caldav.storage.DavStore;
import org.busystack.caldav.storage.StoreException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.w3c.dom.Element;

/** DAV HTTP boundary. Browser sessions cannot authenticate this security chain. */
@Controller
final class DavController {
  private final DavStore store;
  private final CalendarStore calendars;
  private final DavResources resources;
  private final DavProperties properties;
  private final DavPaths paths;
  private final TransactionTemplate snapshot;
  private final TransactionTemplate mutation;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final org.busystack.caldav.config.DavCompatibilityProperties compatibility;

  DavController(
      DavStore store,
      CalendarStore calendars,
      DavResources resources,
      DavProperties properties,
      DeploymentProperties deployment,
      PlatformTransactionManager transactions,
      org.springframework.jdbc.core.JdbcTemplate jdbc,
      org.busystack.caldav.config.DavCompatibilityProperties compatibility) {
    this.store = store;
    this.calendars = calendars;
    this.resources = resources;
    this.properties = properties;
    this.paths = new DavPaths(deployment);
    snapshot = new TransactionTemplate(transactions);
    snapshot.setReadOnly(true);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    mutation = new TransactionTemplate(transactions);
    this.jdbc = jdbc;
    this.compatibility = compatibility;
  }

  @RequestMapping("/dav/**")
  ResponseEntity<byte[]> request(HttpServletRequest request, Authentication authentication)
      throws IOException {
    UUID actor = UUID.fromString(authentication.getName());
    try {
      List<String> parts = paths.parse(request.getRequestURI());
      String method = request.getMethod();
      byte[] body = body(request);
      boolean mutating =
          Set.of("PUT", "DELETE", "MKCALENDAR", "MKCOL", "PROPPATCH", "COPY", "MOVE", "ACL")
              .contains(method);
      Supplier<ResponseEntity<byte[]>> handler =
          () -> {
            if (mutating) {
              if (request.getHeader("If") != null)
                org.busystack.caldav.storage.DavMutationLock.exclusive(jdbc);
              else org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
            }
            evaluateIf(actor, request);
            return switch (method) {
              case "OPTIONS" -> options();
              case "GET", "HEAD" -> get(actor, parts, request);
              case "PROPFIND" -> propfind(actor, parts, request, body);
              case "REPORT" -> report(actor, parts, request, parse(body));
              case "PUT" -> put(actor, parts, request, body);
              case "DELETE" -> delete(actor, parts, request);
              case "MKCALENDAR", "MKCOL" -> create(actor, parts, method, body);
              case "PROPPATCH" -> patch(actor, parts, parse(body));
              case "COPY", "MOVE" -> transfer(actor, parts, request);
              case "ACL" -> acl(actor, parts, parse(body));
              default -> ResponseEntity.status(405).header("Allow", allow()).body(new byte[0]);
            };
          };
      return (mutating ? mutation : snapshot).execute(status -> handler.get());
    } catch (DavException ex) {
      return error(ex.status(), ex.condition());
    } catch (StoreException ex) {
      return switch (ex.kind()) {
        case NOT_FOUND -> error(404, null);
        case FORBIDDEN -> error(403, d("need-privileges"));
        case PRECONDITION -> error(412, null);
        case UID_CONFLICT -> error(403, c("no-uid-conflict"));
        case INVALID_TOKEN -> error(403, d("valid-sync-token"));
        case LIMIT -> error(507, d("number-of-matches-within-limits"));
        case EXISTS -> error(405, null);
        case INVALID_PROPERTY -> error(409, d("valid-property-value"));
        default -> error(409, null);
      };
    } catch (QueryException ex) {
      return queryError(ex);
    } catch (IllegalArgumentException ex) {
      return error(400, null);
    }
  }

  // An explicit OPTIONS mapping prevents MVC's synthetic HttpOptionsHandler from replacing DAV
  // headers.
  @RequestMapping(
      value = "/dav/**",
      method = org.springframework.web.bind.annotation.RequestMethod.OPTIONS)
  ResponseEntity<byte[]> optionsRequest(HttpServletRequest request, Authentication authentication)
      throws IOException {
    return request(request, authentication);
  }

  private void evaluateIf(UUID actor, HttpServletRequest request) {
    var values = java.util.Collections.list(request.getHeaders("If"));
    if (values.isEmpty()) return;
    DavIf condition = DavIf.parse(String.join(" ", values), request.getRequestURI(), paths);
    if (!condition.matchesAll(
        parts -> {
          try {
            DavNode node = resources.resolve(actor, parts);
            return node.resource() == null ? null : node.resource().etag();
          } catch (DavException ex) {
            if (ex.status() == 404) return null;
            throw ex;
          } catch (StoreException ex) {
            if (ex.kind() == StoreException.Kind.NOT_FOUND) return null;
            throw ex;
          }
        })) throw new DavException(412, null);
  }

  private static String allow() {
    return "OPTIONS, GET, HEAD, PROPFIND, PROPPATCH, REPORT, MKCALENDAR, MKCOL, PUT, DELETE, COPY, MOVE, ACL";
  }

  private ResponseEntity<byte[]> options() {
    return ResponseEntity.ok()
        .header("Allow", allow())
        .header("DAV", "1, access-control, calendar-access, sync-collection")
        .body(new byte[0]);
  }

  private static byte[] body(HttpServletRequest request) throws IOException {
    String encoding = request.getHeader("Content-Encoding");
    if (encoding != null && !encoding.equalsIgnoreCase("identity"))
      throw new DavException(415, null);
    if (request.getContentLengthLong() > MAX_REQUEST) throw new DavException(413, null);
    byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST + 1);
    if (bytes.length > MAX_REQUEST) throw new DavException(413, null);
    return bytes;
  }

  private ResponseEntity<byte[]> get(UUID actor, List<String> parts, HttpServletRequest request) {
    DavNode node = resources.resolve(actor, parts);
    if (node.kind() == DavNode.Kind.RESOURCE) {
      String etag = node.resource().etag();
      if (HttpConditions.notModified(request, etag, paths))
        return ResponseEntity.status(304).eTag(etag).body(new byte[0]);
      byte[] bytes = node.resource().content();
      return ResponseEntity.ok()
          .eTag(etag)
          .header("Cache-Control", "private, no-cache")
          .header("Content-Type", "text/calendar; charset=utf-8")
          .contentLength(bytes.length)
          .body(request.getMethod().equals("HEAD") ? new byte[0] : bytes);
    }
    if (!request.getRequestURI().endsWith("/"))
      return ResponseEntity.status(308)
          .header("Location", paths.href(parts, true))
          .body(new byte[0]);
    StringBuilder text = new StringBuilder(properties.displayName(actor, node)).append('\n');
    for (DavNode child : resources.children(actor, node))
      text.append(paths.href(child.segments(), child.trailingSlash())).append('\n');
    byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header("Content-Type", "text/plain; charset=utf-8")
        .header("Cache-Control", "private, no-store")
        .contentLength(bytes.length)
        .body(request.getMethod().equals("HEAD") ? new byte[0] : bytes);
  }

  private ResponseEntity<byte[]> propfind(
      UUID actor, List<String> parts, HttpServletRequest request, byte[] body) {
    Element root = body.length == 0 ? null : parse(body);
    if (root != null && !is(root, DAV, "propfind")) throw new DavException(400, null);
    int depth = depth(request, 20);
    var requested = DavProperties.requested(root);
    var document = document();
    var multistatus = add(document, d("multistatus"));
    for (DavNode node : resources.descendants(actor, resources.resolve(actor, parts), depth))
      properties.response(multistatus, actor, node, requested, timezone(actor, node, null));
    return xml(207, document);
  }

  private ResponseEntity<byte[]> put(
      UUID actor, List<String> parts, HttpServletRequest request, byte[] body) {
    if (parts.size() < 4 || request.getRequestURI().endsWith("/"))
      throw new DavException(409, null);
    String type = request.getContentType();
    if (type != null && !type.split(";", 2)[0].trim().equalsIgnoreCase("text/calendar"))
      throw new DavException(415, c("supported-calendar-data"));
    DavNode parent = resources.resolve(actor, parts.subList(0, parts.size() - 1));
    if (parent.kind() != DavNode.Kind.CALENDAR)
      throw new DavException(409, c("calendar-collection-location-ok"));
    DavStore.PutResult result;
    try {
      result =
          store.putResource(
              actor,
              parent.collectionId(),
              parts.getLast(),
              body,
              HttpConditions.write(request, paths));
    } catch (IllegalArgumentException ex) {
      throw new DavException(403, c("valid-calendar-data"));
    }
    return ResponseEntity.status(result.created() ? 201 : 204)
        .eTag(result.resource().etag())
        .header("Location", paths.href(parts, false))
        .body(new byte[0]);
  }

  private ResponseEntity<byte[]> delete(
      UUID actor, List<String> parts, HttpServletRequest request) {
    DavNode node = resources.resolve(actor, parts);
    if (node.kind() == DavNode.Kind.RESOURCE)
      calendars.delete(
          actor, node.collectionId(), node.resource().name(), HttpConditions.write(request, paths));
    else if (node.kind() == DavNode.Kind.CALENDAR || node.kind() == DavNode.Kind.FOLDER)
      store.deleteBinding(
          actor, node.principal(), node.relativePath(), HttpConditions.write(request, paths));
    else throw new DavException(403, d("need-privileges"));
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<byte[]> create(
      UUID actor, List<String> parts, String method, byte[] body) {
    if (parts.size() < 3 || !parts.getFirst().equals("calendars"))
      throw new DavException(403, null);
    UUID home = DavResources.uuid(parts.get(1));
    String relative = String.join("/", parts.subList(2, parts.size()));
    String display = parts.getLast(), component = method.equals("MKCALENDAR") ? "VEVENT" : null;
    Map<QName, String> values = new LinkedHashMap<>();
    if (body.length > 0) {
      if (method.equals("MKCOL")) throw new DavException(415, null);
      Element root = parse(body);
      if (!is(root, CAL, "mkcalendar")) throw new DavException(400, null);
      Element set = child(root, DAV, "set"), prop = set == null ? null : required(set, DAV, "prop");
      if (prop != null)
        for (Element value : children(prop)) {
          QName name = name(value);
          if (name.equals(c("supported-calendar-component-set"))) {
            var components = children(value);
            if (components.size() != 1
                || !is(components.getFirst(), CAL, "comp")
                || !Set.of("VEVENT", "VTODO").contains(components.getFirst().getAttribute("name")))
              throw new DavException(403, c("supported-calendar-component"));
            component = components.getFirst().getAttribute("name");
          } else if (name.equals(d("displayname"))) display = value.getTextContent();
          else if (DavProperties.protectedProperty(name))
            throw new DavException(403, d("cannot-modify-protected-property"));
          else {
            validateProperty(name, value);
            values.put(name, new String(bytes(value), StandardCharsets.UTF_8));
          }
        }
    }
    store.create(actor, home, relative, display, component, values);
    return ResponseEntity.status(201).header("Location", paths.href(parts, true)).body(new byte[0]);
  }

  private ResponseEntity<byte[]> patch(UUID actor, List<String> parts, Element root) {
    if (!is(root, DAV, "propertyupdate")) throw new DavException(400, null);
    DavNode node = resources.resolve(actor, parts);
    if (node.entry() == null) throw new DavException(403, d("cannot-modify-protected-property"));
    Map<QName, String> set = new LinkedHashMap<>();
    Set<QName> remove = new LinkedHashSet<>(), all = new LinkedHashSet<>();
    Map<QName, Integer> bad = new LinkedHashMap<>();
    for (Element operation : children(root)) {
      if (!is(operation, DAV, "set") && !is(operation, DAV, "remove"))
        throw new DavException(400, null);
      for (Element value : children(required(operation, DAV, "prop"))) {
        QName name = name(value);
        all.add(name);
        if (DavProperties.protectedProperty(name)) bad.put(name, 403);
        else if (name.equals(d("displayname"))
            && (node.kind() == DavNode.Kind.RESOURCE || is(operation, DAV, "remove")))
          bad.put(name, 409);
        else if (is(operation, DAV, "set")) {
          try {
            validateProperty(name, value);
            set.put(name, new String(bytes(value), StandardCharsets.UTF_8));
            remove.remove(name);
          } catch (DavException ex) {
            if (ex.status() != 403 && ex.status() != 409) throw ex;
            bad.put(name, ex.status());
          } catch (QueryException ex) {
            bad.put(name, 409);
          }
        } else {
          remove.add(name);
          set.remove(name);
        }
      }
    }
    if (all.isEmpty()) throw new DavException(400, null);
    if (bad.isEmpty()) {
      List<String> binding =
          node.kind() == DavNode.Kind.RESOURCE
              ? parts.subList(2, parts.size() - 1)
              : parts.subList(2, parts.size());
      store.patch(
          actor,
          node.principal(),
          String.join("/", binding),
          node.resource() == null ? null : node.resource().name(),
          set,
          remove);
    }
    var document = document();
    Element multistatus = add(document, d("multistatus")),
        response = add(multistatus, d("response"));
    text(response, d("href"), paths.href(parts, node.collection()));
    for (QName property : all) {
      Element propstat = add(response, d("propstat"));
      add(add(propstat, d("prop")), property);
      int status = bad.isEmpty() ? 200 : bad.containsKey(property) ? bad.get(property) : 424;
      text(propstat, d("status"), DavProperties.status(status));
      if (status == 403) add(add(propstat, d("error")), d("cannot-modify-protected-property"));
      else if (status == 409) add(add(propstat, d("error")), d("valid-property-value"));
    }
    return xml(207, document);
  }

  private static void validateProperty(QName name, Element value) {
    if (bytes(value).length > 65536) throw new DavException(403, d("valid-property-value"));
    if (name.equals(d("displayname"))
        && (value.getTextContent().isBlank()
            || value.getTextContent().length() > 200
            || !children(value).isEmpty())) throw new DavException(409, d("valid-property-value"));
    if (name.equals(c("calendar-timezone"))) FloatingTimezone.parse(value.getTextContent());
  }

  private ResponseEntity<byte[]> transfer(
      UUID actor, List<String> parts, HttpServletRequest request) {
    String destination = request.getHeader("Destination");
    if (destination == null) throw new DavException(400, null);
    List<String> target = paths.parse(destination);
    if (target.equals(parts)) throw new DavException(403, null);
    boolean move = request.getMethod().equals("MOVE"), overwrite = true;
    String value = request.getHeader("Overwrite");
    if (value != null) {
      if (!value.equals("T") && !value.equals("F")) throw new DavException(400, null);
      overwrite = value.equals("T");
    }
    String depth = request.getHeader("Depth");
    if (depth != null && !depth.equals("infinity") && !(depth.equals("0") && !move))
      throw new DavException(400, null);
    DavNode source = resources.resolve(actor, parts);
    boolean created;
    if (source.kind() == DavNode.Kind.RESOURCE) {
      if (target.size() < 4) throw new DavException(409, null);
      DavNode parent = resources.resolve(actor, target.subList(0, target.size() - 1));
      if (parent.kind() != DavNode.Kind.CALENDAR) throw new DavException(409, null);
      created =
          store.transferResource(
              actor,
              source.collectionId(),
              source.resource().name(),
              parent.collectionId(),
              target.getLast(),
              move,
              overwrite,
              HttpConditions.write(request, paths));
    } else if (source.kind() == DavNode.Kind.CALENDAR || source.kind() == DavNode.Kind.FOLDER) {
      if (target.size() < 3 || !target.getFirst().equals("calendars"))
        throw new DavException(409, null);
      created =
          store.transfer(
              actor,
              source.principal(),
              source.relativePath(),
              DavResources.uuid(target.get(1)),
              String.join("/", target.subList(2, target.size())),
              move,
              overwrite,
              HttpConditions.write(request, paths),
              !"0".equals(depth));
    } else throw new DavException(403, null);
    return ResponseEntity.status(created ? 201 : 204)
        .header("Location", paths.href(target, source.collection()))
        .body(new byte[0]);
  }

  private ResponseEntity<byte[]> acl(UUID actor, List<String> parts, Element root) {
    if (!is(root, DAV, "acl")) throw new DavException(400, null);
    DavNode node = resources.resolve(actor, parts);
    if (node.kind() != DavNode.Kind.CALENDAR)
      throw new DavException(403, d("no-inherited-ace-conflict"));
    List<DavStore.Member> members = new ArrayList<>();
    for (Element ace : children(root)) {
      if (!is(ace, DAV, "ace")) throw new DavException(400, null);
      if (child(ace, DAV, "deny") != null) throw new DavException(403, d("grant-only"));
      if (child(ace, DAV, "invert") != null) throw new DavException(403, d("no-invert"));
      if (child(ace, DAV, "protected") != null || child(ace, DAV, "inherited") != null)
        throw new DavException(403, d("no-protected-ace-conflict"));
      Element principal = required(ace, DAV, "principal");
      Element href = child(principal, DAV, "href");
      if (href == null) throw new DavException(403, d("recognized-principal"));
      List<String> who = paths.parse(href.getTextContent());
      if (who.size() != 2 || !who.getFirst().equals("principals"))
        throw new DavException(403, d("recognized-principal"));
      UUID id = DavResources.uuid(who.get(1));
      if (id.equals(node.entry().collection().owner()))
        throw new DavException(403, d("no-protected-ace-conflict"));
      Set<QName> grants = new LinkedHashSet<>();
      for (Element privilege : children(required(ace, DAV, "grant"))) {
        if (!is(privilege, DAV, "privilege") || children(privilege).size() != 1)
          throw new DavException(400, null);
        grants.add(name(children(privilege).getFirst()));
      }
      Set<QName> allowed =
          Set.of(
              d("read"),
              d("read-current-user-privilege-set"),
              c("read-free-busy"),
              d("write"),
              d("write-content"),
              d("write-properties"),
              d("bind"),
              d("unbind"));
      if (!allowed.containsAll(grants)) throw new DavException(403, d("not-supported-privilege"));
      if (!grants.contains(d("read"))) throw new DavException(403, d("no-ace-conflict"));
      boolean write = grants.contains(d("write"));
      if (!write
          && grants.stream()
              .anyMatch(
                  q ->
                      Set.of("write-content", "write-properties", "bind", "unbind")
                          .contains(q.getLocalPart())))
        throw new DavException(403, d("no-ace-conflict"));
      members.add(new DavStore.Member(id, write ? "WRITE" : "READ"));
    }
    store.replaceAcl(actor, node.collectionId(), members);
    return ResponseEntity.ok().body(new byte[0]);
  }

  private ResponseEntity<byte[]> report(
      UUID actor, List<String> parts, HttpServletRequest request, Element root) {
    DavNode node = resources.resolve(actor, parts);
    if (is(root, CAL, "calendar-query")) return query(actor, node, request, root);
    if (is(root, CAL, "calendar-multiget")) return multiget(actor, node, root);
    if (is(root, CAL, "free-busy-query")) return freeBusy(actor, node, request, root);
    if (is(root, DAV, "sync-collection")) return sync(actor, node, request, root);
    return principalReport(actor, node, request, root);
  }

  private ResponseEntity<byte[]> query(
      UUID actor, DavNode node, HttpServletRequest request, Element root) {
    var filter = CalendarQueryXml.filter(root);
    var matcher = new CalendarQuery(QueryLimits.defaults());
    var requested = DavProperties.requested(root);
    var document = document();
    Element multi = add(document, d("multistatus"));
    for (DavNode candidate : resources.descendants(actor, node, depth(request, 0)))
      if (candidate.kind() == DavNode.Kind.RESOURCE) {
        var timezone = timezone(actor, candidate, root);
        var parsed =
            CalendarContent.parse(
                candidate.resource().content(), candidate.entry().collection().component());
        if (matcher.matches(parsed, filter, timezone))
          properties.response(multi, actor, candidate, requested, timezone);
      }
    return xml(207, document);
  }

  private ResponseEntity<byte[]> multiget(UUID actor, DavNode node, Element root) {
    var document = document();
    Element multi = add(document, d("multistatus"));
    var requested = DavProperties.requested(root);
    int count = 0;
    for (Element href : children(root))
      if (is(href, DAV, "href")) {
        if (++count > 10000) throw new DavException(507, d("number-of-matches-within-limits"));
        List<String> parts = paths.parse(href.getTextContent());
        boolean member =
            node.kind() == DavNode.Kind.RESOURCE
                ? parts.equals(node.segments())
                : parts.size() > node.segments().size()
                    && parts.subList(0, node.segments().size()).equals(node.segments());
        if (!member) throw new DavException(403, c("valid-calendar-object-resource"));
        try {
          DavNode target = resources.resolve(actor, parts);
          if (target.kind() != DavNode.Kind.RESOURCE) throw new DavException(404, null);
          properties.response(multi, actor, target, requested, timezone(actor, target, root));
        } catch (DavException ex) {
          if (ex.status() != 404 && ex.status() != 403) throw ex;
          Element response = add(multi, d("response"));
          text(response, d("href"), paths.href(parts, false));
          text(response, d("status"), DavProperties.status(ex.status()));
        }
      }
    if (count == 0) throw new DavException(400, null);
    return xml(207, document);
  }

  private ResponseEntity<byte[]> sync(
      UUID actor, DavNode node, HttpServletRequest request, Element root) {
    if (node.kind() != DavNode.Kind.CALENDAR) throw new DavException(403, d("supported-report"));
    String level = required(root, DAV, "sync-level").getTextContent();
    int depth = depth(request, 0);
    if (depth != 0 && !(compatibility.allowSyncDepthOne() && depth == 1 && level.equals("1")))
      throw new DavException(400, null);
    if (!Set.of("1", "infinite").contains(level))
      throw new DavException(403, d("sync-traversal-supported"));
    required(root, DAV, "prop");
    String token = required(root, DAV, "sync-token").getTextContent();
    int limit = 10000;
    Element limitElement = child(root, DAV, "limit");
    if (limitElement != null)
      try {
        limit = Integer.parseInt(required(limitElement, DAV, "nresults").getTextContent());
      } catch (NumberFormatException ex) {
        throw new DavException(400, null);
      }
    var page = calendars.sync(actor, node.collectionId(), token, limit);
    var requested = DavProperties.requested(root);
    var document = document();
    Element multi = add(document, d("multistatus"));
    for (var change : page.changes()) {
      List<String> parts = new ArrayList<>(node.segments());
      parts.add(change.name());
      if (change.deleted()) {
        Element response = add(multi, d("response"));
        text(response, d("href"), paths.href(parts, false));
        text(response, d("status"), DavProperties.status(404));
      } else
        properties.response(
            multi,
            actor,
            new DavNode(
                parts, DavNode.Kind.RESOURCE, node.principal(), node.entry(), change.resource()),
            requested,
            timezone(actor, node, null));
    }
    text(multi, d("sync-token"), page.token());
    return xml(207, document);
  }

  private ResponseEntity<byte[]> freeBusy(
      UUID actor, DavNode node, HttpServletRequest request, Element root) {
    var range = CalendarQueryXml.range(required(root, CAL, "time-range"));
    List<CalendarFreeBusy.Source> parsed = new ArrayList<>();
    for (DavNode candidate : resources.descendants(actor, node, depth(request, 0)))
      if (candidate.kind() == DavNode.Kind.RESOURCE
          && candidate.entry().collection().component().equals("VEVENT"))
        parsed.add(
            new CalendarFreeBusy.Source(
                CalendarContent.parse(candidate.resource().content(), "VEVENT"),
                timezone(actor, candidate, root)));
    var periods = new CalendarFreeBusy(QueryLimits.defaults()).querySources(parsed, range);
    var calendar = new net.fortuna.ical4j.model.Calendar();
    calendar.add(new net.fortuna.ical4j.model.property.ProdId("-//BusyStack//CalDAV Server//EN"));
    calendar.add(
        new net.fortuna.ical4j.model.property.Version(
            new net.fortuna.ical4j.model.ParameterList(), "2.0"));
    var busy = new net.fortuna.ical4j.model.component.VFreeBusy(range.start(), range.end());
    busy.add(new net.fortuna.ical4j.model.property.Uid(UUID.randomUUID().toString()));
    busy.add(new net.fortuna.ical4j.model.property.DtStamp(Instant.now()));
    for (var period : periods) {
      var value =
          new net.fortuna.ical4j.model.property.FreeBusy(
              List.of(org.threeten.extra.Interval.of(period.start(), period.end())));
      value.add(
          new net.fortuna.ical4j.model.parameter.FbType(period.type().name().replace('_', '-')));
      busy.add(value);
    }
    calendar.add(busy);
    return ResponseEntity.ok()
        .header("Content-Type", "text/calendar; charset=utf-8")
        .body(calendar.toString().getBytes(StandardCharsets.UTF_8));
  }

  private FloatingTimezone timezone(UUID actor, DavNode node, Element report) {
    Element timezone = report == null ? null : child(report, CAL, "timezone");
    if (timezone != null) return FloatingTimezone.parse(timezone.getTextContent());
    if (node.collectionId() != null) {
      String value =
          store.deadProperties(actor, node.collectionId(), null).get(c("calendar-timezone"));
      if (value != null)
        return FloatingTimezone.parse(
            parse(value.getBytes(StandardCharsets.UTF_8)).getTextContent());
    }
    return FloatingTimezone.of(ZoneOffset.UTC);
  }

  private ResponseEntity<byte[]> principalReport(
      UUID actor, DavNode node, HttpServletRequest request, Element root) {
    return new DavPrincipalReports(store, resources, properties, paths)
        .report(actor, node, request, root);
  }

  private static int depth(HttpServletRequest request, int defaultDepth) {
    if (java.util.Collections.list(request.getHeaders("Depth")).size() > 1)
      throw new DavException(400, null);
    String depth = request.getHeader("Depth");
    if (depth == null) return defaultDepth;
    return switch (depth) {
      case "0" -> 0;
      case "1" -> 1;
      case "infinity" -> 20;
      default -> throw new DavException(400, null);
    };
  }

  static ResponseEntity<byte[]> xml(int status, org.w3c.dom.Node document) {
    return ResponseEntity.status(status)
        .header("Content-Type", "application/xml; charset=utf-8")
        .header("Cache-Control", "private, no-store")
        .body(bytes(document));
  }

  static ResponseEntity<byte[]> error(int status, QName condition) {
    var document = document();
    Element root = add(document, d("error"));
    if (condition != null) add(root, condition);
    return xml(status, document);
  }

  private static ResponseEntity<byte[]> queryError(QueryException ex) {
    return switch (ex.kind()) {
      case INVALID_FILTER -> error(403, c("valid-filter"));
      case UNSUPPORTED_FILTER -> error(403, c("supported-filter"));
      case UNSUPPORTED_COLLATION -> error(403, c("supported-collation"));
      case LIMIT_EXCEEDED -> error(507, d("number-of-matches-within-limits"));
      case INVALID_DATA -> error(403, c("valid-calendar-data"));
    };
  }
}
