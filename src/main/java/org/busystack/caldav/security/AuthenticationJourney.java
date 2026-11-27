package org.busystack.caldav.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.busystack.caldav.config.DeploymentProperties;
import org.busystack.caldav.security.session.SessionSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validated, single-use browser destinations retained across a legitimate authentication rotation.
 */
@Component
public class AuthenticationJourney {
  private static final String FLOWS = "SPRING_SECURITY_BUSYSTACK_FLOWS";
  private static final String DEFAULT = "SPRING_SECURITY_BUSYSTACK_FLOW";
  private static final Duration LIFETIME = Duration.ofMinutes(15);
  private static final int MAX_FLOWS = 8;
  private static final int MAX_PATH_BYTES = 4096;
  private final String prefix;
  private final SessionSecurity sessions;
  private final HttpSessionRequestCache cache = new HttpSessionRequestCache();

  private record Owner(UUID account, String username, UUID epoch) implements Serializable {}

  private record Flow(String destination, Owner owner, Instant expires, Draft draft, boolean ready)
      implements Serializable {
    boolean belongsTo(Owner current) {
      return owner == null || owner.equals(current);
    }

    Flow bind(Owner current) {
      return new Flow(destination, current, expires, draft, ready);
    }
  }

  AuthenticationJourney(DeploymentProperties deployment) {
    this(deployment, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AuthenticationJourney(DeploymentProperties deployment, SessionSecurity sessions) {
    prefix = deployment.publicBaseUrl().getPath();
    this.sessions = sessions;
  }

  private Owner owner(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication
            instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
      return null;
    if (sessions == null)
      return new Owner(
          null,
          authentication.getName(),
          SessionSecurity.capturedEpoch(authentication.getAuthorities()));
    var account = sessions.assertCurrent(authentication);
    return new Owner(account.id(), account.username(), account.epoch());
  }

  public String safe(String candidate) {
    if (candidate == null
        || !candidate.startsWith(prefix + "/")
        || candidate.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES
        || candidate.codePoints().anyMatch(Character::isISOControl)) return prefix + "/";
    try {
      URI uri = URI.create(candidate);
      String path = uri.getRawPath();
      if (uri.isAbsolute()
          || uri.getRawAuthority() != null
          || uri.getRawFragment() != null
          || path.contains("%")
          || path.contains("\\")
          || path.contains("//")
          || path.contains(";")
          || java.util.Arrays.stream(path.split("/"))
              .anyMatch(part -> part.equals(".") || part.equals(".."))
          || !uri.normalize().getRawPath().equals(path)) return prefix + "/";
      String local = path.substring(prefix.length());
      if (local.startsWith("/login")
          || local.startsWith("/setup")
          || local.equals("/auth")
          || local.startsWith("/auth/")
          || local.startsWith("/webauthn")
          || local.startsWith("/logout")) return prefix + "/";
      String query = uri.getRawQuery();
      if (query == null) return path;
      var allowed =
          local.equals("/oauth2/authorize")
              ? java.util.Set.of(
                  "response_type",
                  "client_id",
                  "redirect_uri",
                  "scope",
                  "state",
                  "code_challenge",
                  "code_challenge_method")
              : java.util.Set.of(
                  "client",
                  "from",
                  "destination",
                  "scope",
                  "q",
                  "status",
                  "method",
                  "page",
                  "directPage",
                  "groupPage",
                  "effectivePage",
                  "component",
                  "action",
                  "outcome",
                  "username",
                  "returnCollection",
                  "member");
      var fields =
          java.util.Arrays.stream(query.split("&"))
              .filter(parameter -> allowed.contains(parameter.split("=", 2)[0]))
              .toList();
      return fields.isEmpty() ? path : path + "?" + String.join("&", fields);
    } catch (IllegalArgumentException invalid) {
      return prefix + "/";
    }
  }

  public record Draft(String path, java.util.Map<String, String> fields) implements Serializable {
    public Draft {
      fields = java.util.Map.copyOf(fields);
    }
  }

  public String start(HttpServletRequest request, String destination, Authentication auth) {
    return start(request, destination, auth, null);
  }

  public String start(
      HttpServletRequest request, String destination, Authentication auth, Draft draft) {
    var session = request.getSession();
    var flows = flows(request);
    String handle = UUID.randomUUID().toString();
    var flow = new Flow(safe(destination), owner(auth), Instant.now().plus(LIFETIME), draft, false);
    flows.put(handle, flow);
    while (flows.size() > MAX_FLOWS
        || flows.values().stream().mapToInt(AuthenticationJourney::bytes).sum() > 32768)
      flows.pollFirstEntry();
    session.setAttribute(FLOWS, flows);
    return handle;
  }

  private static int bytes(Flow flow) {
    int size = flow.destination().getBytes(StandardCharsets.UTF_8).length;
    if (flow.draft() != null) {
      size += flow.draft().path().length();
      for (var field : flow.draft().fields().entrySet())
        size += field.getKey().length() + field.getValue().getBytes(StandardCharsets.UTF_8).length;
    }
    return size;
  }

  public void remember(HttpServletRequest request, String destination) {
    request.getSession().setAttribute(DEFAULT, start(request, destination, null));
  }

  public void capture(HttpServletRequest request, HttpServletResponse response) {
    var saved = cache.getRequest(request, response);
    if (saved == null) return;
    try {
      if ("GET".equals(saved.getMethod())) {
        URI uri = URI.create(saved.getRedirectUrl());
        remember(
            request, uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
      }
    } finally {
      cache.removeRequest(request, response);
    }
  }

  @SuppressWarnings("unchecked")
  private LinkedHashMap<String, Flow> flows(HttpServletRequest request) {
    var session = request.getSession(false);
    Object value = session == null ? null : session.getAttribute(FLOWS);
    var flows =
        value instanceof LinkedHashMap<?, ?> stored
            ? new LinkedHashMap<>((LinkedHashMap<String, Flow>) stored)
            : new LinkedHashMap<String, Flow>();
    Instant now = Instant.now();
    flows.values().removeIf(flow -> !flow.expires().isAfter(now));
    return flows;
  }

  public String handle(HttpServletRequest request) {
    String explicit = request.getParameter("flow");
    if (explicit != null) return explicit.matches("[a-f0-9-]{36}") ? explicit : "";
    var session = request.getSession(false);
    return session != null && session.getAttribute(DEFAULT) instanceof String handle ? handle : "";
  }

  public String destination(HttpServletRequest request) {
    var flow = flows(request).get(handle(request));
    return flow != null && flow.owner() == null ? flow.destination() : prefix + "/";
  }

  public String action(HttpServletRequest request, Authentication auth, String handle) {
    var flow = flows(request).get(handle);
    if (flow == null || flow.owner() == null || !flow.belongsTo(owner(auth))) return null;
    String path =
        flow.draft() == null
            ? URI.create(flow.destination()).getPath().substring(prefix.length())
            : flow.draft().path();
    if (path.startsWith("/credentials"))
      return path.endsWith("/revoke")
          ? "revoke the application password"
          : "create an application password";
    if (path.startsWith("/portability"))
      return path.endsWith("/export") ? "export your data" : "continue your import";
    if (path.startsWith("/admin")) return "continue this administrator task";
    if (path.startsWith("/security/passkeys")) return "manage your passkeys";
    if (path.startsWith("/security/recovery")) return "manage recovery codes";
    if (path.startsWith("/oauth")) return "authorize or manage application access";
    if (path.startsWith("/shares") || path.startsWith("/group-shares")) return "change sharing";
    return "continue your task";
  }

  public String consume(HttpServletRequest request, Authentication auth, String handle) {
    var flows = flows(request);
    var flow = flows.remove(handle);
    String target = prefix + "/";
    if (flow != null && flow.belongsTo(owner(auth)) && !flow.ready()) {
      target = flow.destination();
      if (flow.draft() != null) {
        flows.put(
            handle, new Flow(flow.destination(), flow.owner(), flow.expires(), flow.draft(), true));
        target = prefix + "/forms/" + handle;
      }
    }
    var session = request.getSession(false);
    if (session != null) {
      session.setAttribute(FLOWS, flows);
      if (handle.equals(session.getAttribute(DEFAULT))) session.removeAttribute(DEFAULT);
    }
    return target;
  }

  public Draft takeDraft(HttpServletRequest request, Authentication auth, String handle) {
    var flows = flows(request);
    var flow = flows.get(handle);
    if (flow == null || !flow.ready() || !flow.belongsTo(owner(auth))) return null;
    flows.remove(handle);
    request.getSession().setAttribute(FLOWS, flows);
    return flow.draft();
  }

  public void primarySuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication auth)
      throws IOException {
    capture(request, response);
    var flows = flows(request);
    String handle = handle(request);
    Owner current = owner(auth);
    flows.values().removeIf(flow -> !flow.belongsTo(current));
    if (flows.containsKey(handle))
      flows.computeIfPresent(handle, (key, flow) -> flow.bind(current));
    request.getSession().setAttribute(FLOWS, flows);
    response.sendRedirect(prefix + "/auth/continue" + (handle.isEmpty() ? "" : "?flow=" + handle));
  }

  public void passkeySuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication auth)
      throws IOException {
    String handle = handle(request);
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    // The GET consumes the flow after the updated session cookie has reached the browser.
    JsonMapper.shared()
        .writeValue(
            response.getWriter(),
            java.util.Map.of("redirectUrl", prefix + "/auth/continue?flow=" + handle));
  }
}
