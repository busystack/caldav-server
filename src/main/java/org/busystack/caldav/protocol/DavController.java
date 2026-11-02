package org.busystack.caldav.protocol;

import static org.busystack.caldav.protocol.DavXml.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
  private final java.util.concurrent.Semaphore workSlots = new java.util.concurrent.Semaphore(4);
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
  void request(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    if (!workSlots.tryAcquire()) {
      response.setStatus(503);
      response.setHeader("Retry-After", "1");
      return;
    }
    try {
      write(response, request.getMethod(), dispatch(request, authentication));
    } finally {
      workSlots.release();
    }
  }

  // The protocol has already evaluated all validators under its snapshot/mutation locks.
  // A ResponseEntity return would ask MVC to evaluate GET/HEAD date conditions a second
  // time, using its different safe-method If-Match and lenient date parsing rules.
  private static void write(
      HttpServletResponse response, String method, ResponseEntity<byte[]> result)
      throws IOException {
    response.setStatus(result.getStatusCode().value());
    result
        .getHeaders()
        .forEach(
            (name, values) -> {
              if (!values.isEmpty()) {
                response.setHeader(name, values.getFirst());
                for (int index = 1; index < values.size(); index++)
                  response.addHeader(name, values.get(index));
              }
            });
    byte[] body = result.getBody();
    if (body != null && !method.equals("HEAD")) response.getOutputStream().write(body);
  }

  private ResponseEntity<byte[]> dispatch(HttpServletRequest request, Authentication authentication)
      throws IOException {
    UUID actor = UUID.fromString(authentication.getName());
    try {
      List<String> parts = paths.parse(request.getRequestURI());
      String method = request.getMethod();
      byte[] body = body(request);
      boolean mutating =
          Set.of("PUT", "DELETE", "MKCALENDAR", "MKCOL", "PROPPATCH", "COPY", "MOVE", "ACL")
              .contains(method);
      boolean metadataCondition =
          Set.of("PROPPATCH", "ACL", "MKCALENDAR", "MKCOL").contains(method)
              && (request.getHeader("If-Match") != null
                  || request.getHeader("If-None-Match") != null);
      Supplier<ResponseEntity<byte[]>> handler =
          () -> {
            if (mutating) {
              if (request.getHeader("If") != null || metadataCondition)
                org.busystack.caldav.storage.DavMutationLock.exclusive(jdbc);
              else org.busystack.caldav.storage.DavMutationLock.shared(jdbc);
            }
            evaluateIf(actor, request);
            if (metadataCondition) {
              DavNode existing = null;
              try {
                existing = resources.resolve(actor, parts);
              } catch (DavException ex) {
                if (ex.status() != 404) throw ex;
              } catch (StoreException ex) {
                if (ex.kind() != StoreException.Kind.NOT_FOUND) throw ex;
              }
              HttpConditions.write(request, paths)
                  .check(
                      existing == null || existing.metadata() == null
                          ? null
                          : existing.metadata().etag(),
                      existing != null);
            }
            return switch (method) {
              case "OPTIONS" -> options(actor, parts);
              case "GET", "HEAD" -> get(actor, parts, request);
              case "PROPFIND" -> propfind(actor, parts, request, body);
              case "REPORT" -> report(actor, parts, request, parse(body));
              case "PUT" -> put(actor, parts, request, body);
              case "DELETE" -> delete(actor, parts, request);
              case "MKCALENDAR", "MKCOL" -> create(actor, parts, method, body);
              case "PROPPATCH" -> patch(actor, parts, request, parse(body));
              case "COPY", "MOVE" -> transfer(actor, parts, request);
              case "ACL" -> acl(actor, parts, parse(body));
              default -> methodNotAllowed(actor, parts, null);
            };
          };
      return (mutating ? mutation : snapshot).execute(status -> handler.get());
    } catch (DavException ex) {
      if (ex.status() == 405)
        return methodNotAllowed(actor, paths.parse(request.getRequestURI()), ex.condition());
      return error(ex.status(), ex.condition(), ex.failingHref(), ex.requiredPrivilege());
    } catch (StoreException ex) {
      return switch (ex.kind()) {
        case NOT_FOUND -> error(404, null);
        case FORBIDDEN -> {
          if (ex.requiredAccess() == null) yield error(403, null);
          String href =
              paths.href(
                  paths.parse(request.getRequestURI()), request.getRequestURI().endsWith("/"));
          if (ex.collection() != null) {
            try {
              var binding = store.bindingFor(actor, ex.collection());
              if (binding != null) {
                List<String> parts = new ArrayList<>(List.of("calendars", actor.toString()));
                parts.addAll(List.of(binding.path().split("/")));
                href = paths.href(parts, true);
              }
            } catch (StoreException stateChanged) {
              if (stateChanged.kind() != StoreException.Kind.FORBIDDEN) throw stateChanged;
            }
          }
          QName privilege =
              switch (ex.requiredAccess()) {
                case READ -> d("read");
                case WRITE -> d("write");
                case OWNER -> d("write-acl");
              };
          yield error(403, d("need-privileges"), href, privilege);
        }
        case PRECONDITION -> error(412, null);
        case UID_CONFLICT -> error(403, c("no-uid-conflict"));
        case INVALID_TOKEN -> error(403, d("valid-sync-token"));
        case LIMIT -> error(507, d("number-of-matches-within-limits"));
        case EXISTS -> methodNotAllowed(actor, paths.parse(request.getRequestURI()), null);
        case INVALID_PROPERTY -> error(409, d("valid-property-value"));
        default -> error(409, null);
      };
    } catch (QueryException ex) {
      if (ex.kind() == QueryException.Kind.LIMIT_EXCEEDED
          && Set.of("PUT", "MKCALENDAR").contains(request.getMethod()))
        return error(
            413, new QName("urn:busystack:params:xml:ns:dav", "calendar-complexity-limit"));
      return queryError(ex);
    } catch (org.busystack.caldav.calendar.CalendarValidationException ex) {
      return error(
          403,
          c(
              switch (ex.reason()) {
                case INVALID_DATA -> "valid-calendar-data";
                case INVALID_OBJECT -> "valid-calendar-object-resource";
                case UNSUPPORTED_COMPONENT -> "supported-calendar-component";
                case UNSUPPORTED_RSCALE -> "supported-rscale";
              }));
    } catch (org.springframework.dao.QueryTimeoutException
        | org.springframework.transaction.TransactionTimedOutException timeout) {
      return ResponseEntity.status(503).header("Retry-After", "1").body(new byte[0]);
    } catch (IllegalArgumentException ex) {
      return error(400, null);
    }
  }

  // An explicit OPTIONS mapping prevents MVC's synthetic HttpOptionsHandler from replacing DAV
  // headers.
  @RequestMapping(
      value = "/dav/**",
      method = org.springframework.web.bind.annotation.RequestMethod.OPTIONS)
  void optionsRequest(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    request(request, response, authentication);
  }

  private void evaluateIf(UUID actor, HttpServletRequest request) {
    var values = java.util.Collections.list(request.getHeaders("If"));
    if (values.isEmpty()) return;
    DavIf condition = DavIf.parse(String.join(" ", values), request.getRequestURI(), paths);
    if (!condition.matchesStates(
        parts -> {
          try {
            DavNode node = resources.resolve(actor, parts);
            return new DavIf.ResourceState(
                node.metadata() == null ? null : node.metadata().etag(),
                node.kind() == DavNode.Kind.CALENDAR
                    ? calendars.currentSyncToken(actor, node.collectionId())
                    : null);
          } catch (DavException ex) {
            if (ex.status() == 404) return DavIf.ResourceState.ABSENT;
            throw ex;
          } catch (StoreException ex) {
            if (ex.kind() == StoreException.Kind.NOT_FOUND) return DavIf.ResourceState.ABSENT;
            throw ex;
          }
        })) throw new DavException(412, null);
  }

  private static String allow() {
    return "OPTIONS, GET, HEAD, PROPFIND, PROPPATCH, REPORT, MKCALENDAR, MKCOL, PUT, DELETE, COPY, MOVE, ACL";
  }

  private static String allow(DavNode.Kind kind) {
    String read = "OPTIONS, GET, HEAD, PROPFIND, REPORT";
    return switch (kind) {
      case CALENDAR -> read + ", PROPPATCH, DELETE, COPY, MOVE, ACL";
      case FOLDER -> read + ", PROPPATCH, DELETE, COPY, MOVE";
      case RESOURCE -> read + ", PUT, DELETE, PROPPATCH, COPY, MOVE";
      default -> read;
    };
  }

  private ResponseEntity<byte[]> options(UUID actor, List<String> parts) {
    DavNode node = resources.resolve(actor, parts);
    return ResponseEntity.ok()
        .header("Allow", allow(node.kind()))
        .header("DAV", "1, access-control, calendar-access, sync-collection")
        .body(new byte[0]);
  }

  private ResponseEntity<byte[]> methodNotAllowed(UUID actor, List<String> parts, QName condition) {
    DavNode node = resources.resolve(actor, parts);
    var response = error(405, condition);
    var headers = new org.springframework.http.HttpHeaders();
    headers.putAll(response.getHeaders());
    headers.set("Allow", allow(node.kind()));
    return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
  }

  private static byte[] body(HttpServletRequest request) throws IOException {
    String encoding = request.getHeader("Content-Encoding");
    if (encoding != null && !encoding.equalsIgnoreCase("identity"))
      throw new DavException(415, null);
    boolean calendarPut = request.getMethod().equals("PUT");
    int limit = calendarPut ? Math.min(MAX_REQUEST, CalendarContent.MAX_BYTES) : MAX_REQUEST;
    QName condition =
        calendarPut && limit == CalendarContent.MAX_BYTES ? c("max-resource-size") : null;
    if (request.getContentLengthLong() > limit) throw new DavException(413, condition);
    byte[] bytes = request.getInputStream().readNBytes(limit + 1);
    if (bytes.length > limit) throw new DavException(413, condition);
    return bytes;
  }

  private ResponseEntity<byte[]> get(UUID actor, List<String> parts, HttpServletRequest request) {
    DavNode node = resources.resolve(actor, parts);
    if (node.kind() == DavNode.Kind.RESOURCE) {
      String etag = node.metadata().etag();
      java.time.Instant origin = java.time.Instant.now();
      java.time.Instant modifiedAt =
          org.busystack.caldav.storage.ModificationDates.effective(
              node.metadata().modifiedAt(), origin);
      if (HttpConditions.notModified(request, etag, modifiedAt, paths))
        return ResponseEntity.status(304)
            .eTag(etag)
            .header("Date", HttpDateValues.format(origin))
            .header("Last-Modified", HttpDateValues.format(modifiedAt))
            .body(new byte[0]);
      byte[] bytes =
          request.getMethod().equals("HEAD")
              ? new byte[0]
              : resources.content(actor, node).resource().content();
      return ResponseEntity.ok()
          .eTag(etag)
          .header("Date", HttpDateValues.format(origin))
          .header("Last-Modified", HttpDateValues.format(modifiedAt))
          .header("Cache-Control", "private, no-cache")
          .header("Content-Type", "text/calendar; charset=utf-8")
          .contentLength(node.metadata().contentLength())
          .body(bytes);
    }
    if (!request.getRequestURI().endsWith("/"))
      return ResponseEntity.status(308)
          .header("Location", paths.href(parts, true))
          .body(new byte[0]);
    if (HttpConditions.notModified(request, null, paths))
      return ResponseEntity.status(304).body(new byte[0]);
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
    Map<UUID, FloatingTimezone> timezones = timezoneCache();
    for (DavNode node : resources.descendants(actor, resources.resolve(actor, parts), depth))
      properties.response(
          multistatus,
          actor,
          node,
          requested,
          responseTimezone(actor, node, requested, null, timezones));
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
    } catch (org.busystack.caldav.calendar.CalendarValidationException ex) {
      throw ex;
    } catch (IllegalArgumentException ex) {
      throw new DavException(403, c("valid-calendar-data"));
    }
    var response =
        ResponseEntity.status(result.created() ? 201 : 204)
            .header("Location", paths.href(parts, false));
    // RFC 9110 §9.3.4: validators describe the stored representation, not transformed PUT bytes.
    if (!result.transformed()) response.eTag(result.resource().etag());
    return response.body(new byte[0]);
  }

  private ResponseEntity<byte[]> delete(
      UUID actor, List<String> parts, HttpServletRequest request) {
    DavNode node = resources.resolve(actor, parts);
    if (node.kind() == DavNode.Kind.RESOURCE)
      calendars.delete(
          actor, node.collectionId(), node.metadata().name(), HttpConditions.write(request, paths));
    else if (node.kind() == DavNode.Kind.CALENDAR || node.kind() == DavNode.Kind.FOLDER)
      store.deleteBinding(
          actor, node.principal(), node.relativePath(), HttpConditions.write(request, paths));
    else return methodNotAllowed(actor, parts, null);
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
      Element set = required(root, DAV, "set"), prop = required(set, DAV, "prop");
      for (Element value : children(prop)) {
        QName name = name(value);
        if (name.equals(c("supported-calendar-component-set"))) {
          var components = children(value, CAL, "comp");
          if (components.size() != 1
              || !is(components.getFirst(), CAL, "comp")
              || !Set.of("VEVENT", "VTODO").contains(components.getFirst().getAttribute("name")))
            throw new DavException(403, c("supported-calendar-component"));
          component = components.getFirst().getAttribute("name");
        } else if (name.equals(d("displayname"))) {
          validateProperty(name, value);
          display = value.getTextContent();
          values.put(name, new String(propertyBytes(value), StandardCharsets.UTF_8));
        } else if (DavProperties.protectedProperty(name))
          throw new DavException(403, d("cannot-modify-protected-property"));
        else {
          validateProperty(name, value);
          values.put(name, new String(propertyBytes(value), StandardCharsets.UTF_8));
        }
      }
    }
    if (!home.equals(actor))
      throw new DavException(
          403, d("need-privileges"), paths.href(parts.subList(0, 2), true), d("bind"));
    try {
      store.create(actor, home, relative, display, component, values);
    } catch (StoreException failure) {
      if (failure.kind() == StoreException.Kind.EXISTS && method.equals("MKCALENDAR"))
        throw new DavException(405, d("resource-must-be-null"));
      throw failure;
    }
    return ResponseEntity.status(201).header("Location", paths.href(parts, true)).body(new byte[0]);
  }

  private ResponseEntity<byte[]> patch(
      UUID actor, List<String> parts, HttpServletRequest request, Element root) {
    if (!is(root, DAV, "propertyupdate")) throw new DavException(400, null);
    DavNode node = resources.resolve(actor, parts);
    if (node.entry() == null) throw new DavException(403, d("cannot-modify-protected-property"));
    Map<QName, String> set = new LinkedHashMap<>();
    Set<QName> remove = new LinkedHashSet<>(), all = new LinkedHashSet<>();
    Map<QName, DavException> bad = new LinkedHashMap<>();
    for (Element operation : children(root, DAV, "set", "remove")) {
      for (Element value : children(required(operation, DAV, "prop"))) {
        QName name = name(value);
        all.add(name);
        if (DavProperties.protectedProperty(name))
          bad.put(name, new DavException(403, d("cannot-modify-protected-property")));
        else if (name.equals(d("displayname"))
            && node.kind() != DavNode.Kind.RESOURCE
            && is(operation, DAV, "remove"))
          bad.put(name, new DavException(409, d("valid-property-value")));
        else if (is(operation, DAV, "set")) {
          try {
            validateProperty(name, value);
            set.put(name, new String(propertyBytes(value), StandardCharsets.UTF_8));
            remove.remove(name);
          } catch (DavException ex) {
            if (ex.status() != 403 && ex.status() != 409) throw ex;
            bad.put(name, ex);
          } catch (QueryException ex) {
            bad.put(
                name,
                new DavException(
                    409,
                    name.equals(c("calendar-timezone"))
                        ? c("valid-calendar-data")
                        : d("valid-property-value")));
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
          node.metadata() == null ? null : node.metadata().name(),
          set,
          remove,
          HttpConditions.write(request, paths));
    }
    var document = document();
    Element multistatus = add(document, d("multistatus")),
        response = add(multistatus, d("response"));
    text(response, d("href"), paths.href(parts, node.collection()));
    for (QName property : all) {
      Element propstat = add(response, d("propstat"));
      add(add(propstat, d("prop")), property);
      int status =
          bad.isEmpty() ? 200 : bad.containsKey(property) ? bad.get(property).status() : 424;
      text(propstat, d("status"), DavProperties.status(status));
      DavException failure = bad.get(property);
      if (failure != null)
        DavErrors.append(
            propstat, failure.condition(), failure.failingHref(), failure.requiredPrivilege());
    }
    return xml(207, document);
  }

  private static void validateProperty(QName name, Element value) {
    if (bytes(value).length > 65536) throw new DavException(403, d("valid-property-value"));
    if (name.equals(d("displayname"))
        && (value.getTextContent().isBlank()
            || value.getTextContent().length() > 200
            || !children(value).isEmpty())) throw new DavException(409, d("valid-property-value"));
    if (name.equals(c("calendar-timezone"))) {
      try {
        FloatingTimezone.parse(value.getTextContent());
      } catch (QueryException failure) {
        if (failure.kind() == QueryException.Kind.LIMIT_EXCEEDED)
          throw new DavException(
              413, new QName("urn:busystack:params:xml:ns:dav", "calendar-complexity-limit"));
        throw new DavException(409, c("valid-calendar-data"));
      } catch (IllegalArgumentException failure) {
        throw new DavException(409, c("valid-calendar-data"));
      }
    }
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
              source.metadata().name(),
              parent.collectionId(),
              target.getLast(),
              move,
              overwrite,
              HttpConditions.write(request, paths));
    } else if (source.kind() == DavNode.Kind.CALENDAR || source.kind() == DavNode.Kind.FOLDER) {
      if (target.size() < 3 || !target.getFirst().equals("calendars"))
        throw new DavException(409, null);
      if (!DavResources.uuid(target.get(1)).equals(actor))
        throw new DavException(
            403,
            d("need-privileges"),
            paths.href(target.subList(0, target.size() - 1), true),
            d("bind"));
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
    if (!properties.privileges(actor, node).contains(d("write-acl")))
      throw new DavException(403, d("need-privileges"), paths.href(parts, true), d("write-acl"));
    List<DavStore.Member> members = new ArrayList<>();
    List<Element> aces = children(root, DAV, "ace");
    if (aces.size() > 1000) throw new DavException(403, d("limited-number-of-aces"));
    Set<UUID> principals = new LinkedHashSet<>();
    for (Element ace : aces) {
      if (child(ace, DAV, "deny") != null) throw new DavException(403, d("grant-only"));
      if (child(ace, DAV, "invert") != null) throw new DavException(403, d("no-invert"));
      if (child(ace, DAV, "protected") != null || child(ace, DAV, "inherited") != null)
        throw new DavException(403, d("no-protected-ace-conflict"));
      Element principal = required(ace, DAV, "principal");
      var principalForms =
          children(
              principal,
              DAV,
              "href",
              "all",
              "authenticated",
              "unauthenticated",
              "self",
              "property");
      if (principalForms.size() != 1) throw new DavException(400, null);
      Element href = child(principal, DAV, "href");
      if (href == null) throw new DavException(403, d("allowed-principal"));
      List<String> who = paths.parse(scalarText(href));
      if (who.size() != 2 || !who.getFirst().equals("principals"))
        throw new DavException(403, d("recognized-principal"));
      UUID id = DavResources.uuid(who.get(1));
      if (!store.recognizedPrincipal(id)) throw new DavException(403, d("recognized-principal"));
      if (id.equals(node.entry().collection().owner()))
        throw new DavException(403, d("no-protected-ace-conflict"));
      if (!principals.add(id)) throw new DavException(403, d("no-ace-conflict"));
      Set<QName> grants = new LinkedHashSet<>();
      var privileges = children(required(ace, DAV, "grant"), DAV, "privilege");
      if (privileges.isEmpty()) throw new DavException(400, null);
      for (Element privilege : privileges) {
        // A privilege's ANY child is its requested QName, including unknown privileges.
        // Siblings outside the known model are extensions unless no known QName exists.
        List<Element> names = children(privilege);
        List<Element> recognized =
            names.stream()
                .filter(value -> DavPrivileges.find(node.kind(), name(value)) != null)
                .toList();
        if (recognized.size() > 1 || recognized.isEmpty() && names.size() != 1)
          throw new DavException(400, null);
        grants.add(name(recognized.isEmpty() ? names.getFirst() : recognized.getFirst()));
      }
      members.add(
          new DavStore.Member(
              id, DavPrivileges.permission(node.kind(), grants), store.isGroupPrincipal(id)));
    }
    store.replaceAcl(actor, node.collectionId(), members);
    return ResponseEntity.ok().body(new byte[0]);
  }

  private ResponseEntity<byte[]> report(
      UUID actor, List<String> parts, HttpServletRequest request, Element root) {
    boolean freeBusyRequest = is(root, CAL, "free-busy-query");
    DavNode node;
    try {
      node = resources.resolve(actor, parts);
    } catch (StoreException failure) {
      if (freeBusyRequest && failure.kind() == StoreException.Kind.FORBIDDEN)
        return error(404, null);
      throw failure;
    } catch (DavException failure) {
      if (freeBusyRequest && failure.status() == 403) return error(404, null);
      throw failure;
    }
    if (is(root, CAL, "calendar-query")) return query(actor, node, request, root);
    if (is(root, CAL, "calendar-multiget")) return multiget(actor, node, root);
    if (is(root, CAL, "free-busy-query")) return freeBusy(actor, node, request, root);
    if (is(root, DAV, "sync-collection")) return sync(actor, node, request, root);
    return principalReport(actor, node, request, root);
  }

  private ResponseEntity<byte[]> query(
      UUID actor, DavNode node, HttpServletRequest request, Element root) {
    var filter = CalendarQueryXml.filter(root);
    FloatingTimezone suppliedTimezone = queryTimezone(root);
    var matcher = new CalendarQuery(QueryLimits.defaults());
    var requested = DavProperties.requested(root);
    var document = document();
    Element multi = add(document, d("multistatus"));
    Map<UUID, FloatingTimezone> timezones = timezoneCache();
    for (DavNode metadata : resources.candidates(actor, node, depth(request, 0), true, false)) {
      DavNode candidate = resources.content(actor, metadata);
      if (candidate.kind() == DavNode.Kind.RESOURCE) {
        var timezone =
            suppliedTimezone != null
                ? suppliedTimezone
                : timezones.computeIfAbsent(
                    candidate.collectionId(), id -> timezone(actor, candidate, null));
        var parsed =
            CalendarContent.parse(
                candidate.resource().content(), candidate.entry().collection().component());
        if (matcher.matches(parsed, filter, timezone))
          properties.response(multi, actor, candidate, requested, timezone);
      }
    }
    return xml(207, document);
  }

  private static FloatingTimezone queryTimezone(Element root) {
    Element supplied = child(root, CAL, "timezone");
    if (supplied == null) return null;
    try {
      return FloatingTimezone.parse(scalarText(supplied));
    } catch (QueryException failure) {
      if (failure.kind() == QueryException.Kind.LIMIT_EXCEEDED) throw failure;
      throw new DavException(403, c("valid-calendar-data"));
    } catch (IllegalArgumentException failure) {
      throw new DavException(403, c("valid-calendar-data"));
    }
  }

  private ResponseEntity<byte[]> multiget(UUID actor, DavNode node, Element root) {
    var document = document();
    Element multi = add(document, d("multistatus"));
    var requested = DavProperties.requested(root);
    var hrefs = children(root).stream().filter(value -> is(value, DAV, "href")).toList();
    if (hrefs.isEmpty() || node.kind() == DavNode.Kind.RESOURCE && hrefs.size() != 1)
      throw new DavException(400, null);
    int count = 0;
    Map<UUID, FloatingTimezone> timezones = timezoneCache();
    for (Element href : hrefs) {
      if (++count > 10000) throw new DavException(507, d("number-of-matches-within-limits"));
      List<String> parts = paths.parse(scalarText(href));
      boolean member =
          node.kind() == DavNode.Kind.RESOURCE
              ? parts.equals(node.segments())
              : parts.size() > node.segments().size()
                  && parts.subList(0, node.segments().size()).equals(node.segments());
      if (!member) throw new DavException(403, c("valid-calendar-object-resource"));
      try {
        DavNode target = resources.resolveMember(actor, parts);
        if (target.kind() != DavNode.Kind.RESOURCE) throw new DavException(404, null);
        properties.response(
            multi,
            actor,
            target,
            requested,
            responseTimezone(actor, target, requested, root, timezones));
      } catch (DavException ex) {
        if (ex.status() != 404 && ex.status() != 403) throw ex;
        Element response = add(multi, d("response"));
        text(response, d("href"), paths.href(parts, false));
        text(response, d("status"), DavProperties.status(ex.status()));
        if (ex.condition() != null)
          DavErrors.append(response, ex.condition(), ex.failingHref(), ex.requiredPrivilege());
      }
    }
    return xml(207, document);
  }

  private ResponseEntity<byte[]> sync(
      UUID actor, DavNode node, HttpServletRequest request, Element root) {
    if (node.kind() != DavNode.Kind.CALENDAR) throw new DavException(403, d("supported-report"));
    String level = scalarText(required(root, DAV, "sync-level"));
    int depth = depth(request, 0);
    if (depth != 0 && !(compatibility.allowSyncDepthOne() && depth == 1 && level.equals("1")))
      throw new DavException(400, null);
    if (!Set.of("1", "infinite").contains(level))
      throw new DavException(403, d("sync-traversal-supported"));
    required(root, DAV, "prop");
    String token = scalarText(required(root, DAV, "sync-token"));
    int limit = 10000;
    Element limitElement = child(root, DAV, "limit");
    if (limitElement != null)
      try {
        String digits = scalarText(required(limitElement, DAV, "nresults"));
        if (digits.isEmpty() || !digits.chars().allMatch(value -> value >= '0' && value <= '9'))
          throw new DavException(400, null);
        int significant = 0;
        while (significant < digits.length() - 1 && digits.charAt(significant) == '0')
          significant++;
        // nresults is a client ceiling, not a minimum or a server allocation request.
        // Larger valid integers saturate at the server page cap without overflowing int.
        limit =
            digits.length() - significant > 5
                ? 10000
                : Math.min(10000, Integer.parseInt(digits.substring(significant)));
      } catch (NumberFormatException ex) {
        throw new DavException(400, null);
      }
    var requested = DavProperties.requested(root);
    boolean includeContent = requested.properties().containsKey(c("calendar-data"));
    FloatingTimezone timezone =
        includeContent ? timezone(actor, node, null) : FloatingTimezone.of(ZoneOffset.UTC);
    long pageBudget = MAX_RESPONSE - 65536L;
    long[] remaining = {pageBudget};
    Map<String, Element> rendered = new LinkedHashMap<>();
    var page =
        calendars.sync(
            actor,
            node.collectionId(),
            token,
            limit,
            includeContent,
            change -> {
              var sizing = document();
              Element holder = add(sizing, d("multistatus"));
              Element item = syncResponse(holder, actor, node, change, requested, timezone);
              long cost = Math.max(bytes(sizing).length, copyWork(item));
              if (cost <= remaining[0]) {
                rendered.put(change.name(), item);
                remaining[0] -= cost;
              }
              return cost;
            },
            pageBudget);
    var document = document();
    Element multi = add(document, d("multistatus"));
    for (var change : page.changes()) appendAdopt(multi, rendered.remove(change.name()));
    if (page.truncated()) {
      Element incomplete = add(multi, d("response"));
      text(incomplete, d("href"), paths.href(node.segments(), true));
      text(incomplete, d("status"), DavProperties.status(507));
      add(add(incomplete, d("error")), d("number-of-matches-within-limits"));
    }
    text(multi, d("sync-token"), page.token());
    return xml(207, document);
  }

  private Element syncResponse(
      Element multi,
      UUID actor,
      DavNode collection,
      CalendarStore.Change change,
      DavProperties.Requested requested,
      FloatingTimezone timezone) {
    List<String> parts = new ArrayList<>(collection.segments());
    parts.add(change.name());
    if (change.deleted()) {
      Element response = add(multi, d("response"));
      text(response, d("href"), paths.href(parts, false));
      text(response, d("status"), DavProperties.status(404));
      return response;
    } else
      return properties.response(
          multi,
          actor,
          new DavNode(
              parts,
              DavNode.Kind.RESOURCE,
              collection.principal(),
              collection.entry(),
              change.resource(),
              change.metadata(),
              true),
          requested,
          timezone);
  }

  private ResponseEntity<byte[]> freeBusy(
      UUID actor, DavNode node, HttpServletRequest request, Element root) {
    if (!node.collection()) throw new DavException(403, d("supported-report"));
    var range = CalendarQueryXml.range(required(root, CAL, "time-range"));
    Map<UUID, FloatingTimezone> timezones = timezoneCache();
    Iterable<CalendarFreeBusy.Source> parsed =
        () ->
            java.util.stream.StreamSupport.stream(
                    resources.candidates(actor, node, depth(request, 0), true, true).spliterator(),
                    false)
                .filter(candidate -> candidate.entry().collection().component().equals("VEVENT"))
                .map(
                    candidate ->
                        new CalendarFreeBusy.Source(
                            CalendarContent.parse(
                                resources.content(actor, candidate).resource().content(), "VEVENT"),
                            timezones.computeIfAbsent(
                                candidate.collectionId(), id -> timezone(actor, candidate, null))))
                .iterator();
    var periods = new CalendarFreeBusy(QueryLimits.defaults()).querySources(parsed, range);
    var calendar = new net.fortuna.ical4j.model.Calendar();
    calendar.add(new net.fortuna.ical4j.model.property.ProdId("-//BusyStack//CalDAV Server//EN"));
    calendar.add(
        new net.fortuna.ical4j.model.property.Version(
            new net.fortuna.ical4j.model.ParameterList(), "2.0"));
    var busy = new net.fortuna.ical4j.model.component.VFreeBusy(range.start(), range.end());
    busy.add(new net.fortuna.ical4j.model.property.Uid(UUID.randomUUID().toString()));
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

  private static Map<UUID, FloatingTimezone> timezoneCache() {
    return new java.util.LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<UUID, FloatingTimezone> entry) {
        return size() > 64;
      }
    };
  }

  private FloatingTimezone responseTimezone(
      UUID actor,
      DavNode node,
      DavProperties.Requested requested,
      Element report,
      Map<UUID, FloatingTimezone> cache) {
    if (requested.context() != DavProperties.Context.CALENDAR_REPORT
        || node.collectionId() == null
        || !requested.properties().containsKey(c("calendar-data")))
      return FloatingTimezone.of(ZoneOffset.UTC);
    return cache.computeIfAbsent(node.collectionId(), id -> timezone(actor, node, report));
  }

  private FloatingTimezone timezone(UUID actor, DavNode node, Element report) {
    Element timezone = report == null ? null : child(report, CAL, "timezone");
    if (timezone != null) return FloatingTimezone.parse(scalarText(timezone));
    if (node.collectionId() != null) {
      String value = store.deadProperty(actor, node.collectionId(), null, c("calendar-timezone"));
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
        .header("Date", HttpDateValues.format(java.time.Instant.now()))
        .header("Content-Type", "application/xml; charset=utf-8")
        .header("Cache-Control", "private, no-store")
        .body(bytes(document));
  }

  static ResponseEntity<byte[]> error(int status, QName condition) {
    return error(status, condition, null, null);
  }

  static ResponseEntity<byte[]> error(int status, QName condition, String href, QName privilege) {
    var document = document();
    DavErrors.append(document, condition, href, privilege);
    var response = xml(status, document);
    if (status != 405) return response;
    return ResponseEntity.status(status)
        .headers(response.getHeaders())
        .header("Allow", allow())
        .body(response.getBody());
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
