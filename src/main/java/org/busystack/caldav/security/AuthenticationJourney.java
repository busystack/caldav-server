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
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validated, single-use browser destinations retained across a legitimate authentication rotation.
 */
@Component
public class AuthenticationJourney {
  private static final String SECTION = "journey";
  private static final String PROVIDER_FLOW =
      AuthenticationJourney.class.getName() + ".providerFlow";
  private static final Duration LIFETIME = Duration.ofMinutes(15);
  private static final int MAX_FLOWS = 8;
  private static final int MAX_PATH_BYTES = 4096;
  private final String prefix;
  private final SessionSecurity sessions;
  private final BrowserFlowStore store;

  private record FlowState(
      LinkedHashMap<String, Flow> flows, java.util.Map<String, String> defaults) {
    FlowState() {
      this(new LinkedHashMap<>(), new java.util.HashMap<>());
    }
  }

  private record Owner(UUID account, String username, UUID epoch) implements Serializable {}

  private record Flow(
      String destination,
      Owner owner,
      Instant expires,
      Draft draft,
      boolean ready,
      String retryUsername)
      implements Serializable {
    boolean belongsTo(Owner current) {
      return owner == null || owner.equals(current);
    }

    Flow bind(Owner current) {
      return new Flow(destination, current, expires, draft, ready, null);
    }
  }

  public AuthenticationJourney(
      DeploymentProperties deployment, SessionSecurity sessions, BrowserFlowStore store) {
    prefix = deployment.publicBaseUrl().getPath();
    this.sessions = sessions;
    this.store = store;
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
          local.equals("/oauth/consent")
              ? java.util.Set.of("client_id", "state")
              : local.equals("/oauth2/authorize")
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
              .filter(parameter -> allowedQuery(allowed, local, parameter))
              .toList();
      return fields.isEmpty() ? path : path + "?" + String.join("&", fields);
    } catch (IllegalArgumentException invalid) {
      return prefix + "/";
    }
  }

  private static boolean allowedQuery(
      java.util.Set<String> allowed, String path, String parameter) {
    String[] field = parameter.split("=", 2);
    if (field.length != 2) return false;
    if (field[0].equals("resource"))
      return (path.equals("/connections")
              || path.equals("/security/credentials")
              || path.equals("/security/credentials/new"))
          && validHandle(field[1]);
    if (field[0].equals("returnCollection")) return validHandle(field[1]);
    if (field[0].equals("memberQuery") || field[0].equals("memberPage"))
      return path.startsWith("/admin/groups/");
    return allowed.contains(field[0]);
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
    return store.update(
        request,
        true,
        SECTION,
        FlowState.class,
        FlowState::new,
        state -> add(state, destination, owner(auth), draft));
  }

  private String add(FlowState state, String destination, Owner owner, Draft draft) {
    var flows = flows(state);
    String handle = UUID.randomUUID().toString();
    flows.put(
        handle,
        new Flow(safe(destination), owner, Instant.now().plus(LIFETIME), draft, false, null));
    while (flows.size() > MAX_FLOWS
        || flows.values().stream().mapToInt(AuthenticationJourney::bytes).sum() > 32768)
      flows.pollFirstEntry();
    return handle;
  }

  private static int bytes(Flow flow) {
    int size =
        flow.destination().getBytes(StandardCharsets.UTF_8).length
            + (flow.retryUsername() == null ? 0 : flow.retryUsername().length());
    if (flow.draft() != null) {
      size += flow.draft().path().length();
      for (var field : flow.draft().fields().entrySet())
        size += field.getKey().length() + field.getValue().getBytes(StandardCharsets.UTF_8).length;
    }
    return size;
  }

  public void remember(HttpServletRequest request, String destination) {
    store.update(
        request,
        true,
        SECTION,
        FlowState.class,
        FlowState::new,
        state -> {
          state.defaults().put("default", add(state, destination, null, null));
          return null;
        });
  }

  private FlowState state(HttpServletRequest request) {
    return store.read(request, SECTION, FlowState.class, FlowState::new);
  }

  private static LinkedHashMap<String, Flow> flows(FlowState state) {
    state.flows().values().removeIf(flow -> !BrowserFlowStore.current(flow.expires()));
    return state.flows();
  }

  public static boolean validHandle(String handle) {
    return handle != null && handle.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}");
  }

  public void providerCallback(HttpServletRequest request, String handle) {
    request.setAttribute(PROVIDER_FLOW, handle == null ? "" : handle);
  }

  public String handle(HttpServletRequest request) {
    String explicit = request.getParameter("flow");
    if (explicit != null) return validHandle(explicit) ? explicit : "";
    return state(request).defaults().getOrDefault("default", "");
  }

  public boolean available(HttpServletRequest request, Authentication auth, String handle) {
    var flow = flows(state(request)).get(handle);
    return flow != null && flow.belongsTo(owner(auth));
  }

  public String destination(HttpServletRequest request) {
    var flow = flows(state(request)).get(handle(request));
    return flow != null && flow.owner() == null ? flow.destination() : prefix + "/";
  }

  public void loginRequired(
      HttpServletRequest request,
      HttpServletResponse response,
      org.springframework.security.core.AuthenticationException ignored)
      throws IOException {
    if (!"GET".equals(request.getMethod())
        || "/".equals(request.getServletPath()) && request.getQueryString() == null) {
      response.sendRedirect(prefix + "/login");
      return;
    }
    response.sendRedirect(
        prefix + "/login?flow=" + start(request, BrowserAccessFilter.destination(request), null));
  }

  public void primaryFailure(
      HttpServletRequest request,
      HttpServletResponse response,
      org.springframework.security.core.AuthenticationException ignored)
      throws IOException {
    String handle = requestedHandle(request);
    var flow = flows(state(request)).get(handle);
    String target = prefix + "/login?error";
    if (flow != null) {
      target += (flow.owner() == null ? "" : "&reauthenticate") + "&flow=" + handle;
      String username = request.getParameter("username");
      if (flow.owner() == null
          && "/login".equals(request.getServletPath())
          && username != null
          && username.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
        store.update(
            request,
            false,
            SECTION,
            FlowState.class,
            FlowState::new,
            state -> {
              state
                  .flows()
                  .computeIfPresent(
                      handle,
                      (key, current) ->
                          current.owner() == null
                              ? new Flow(
                                  current.destination(),
                                  null,
                                  current.expires(),
                                  current.draft(),
                                  current.ready(),
                                  username)
                              : current);
              return null;
            });
      }
    } else if (!handle.isEmpty()
        || request.getParameter("flow") != null && !request.getParameter("flow").isEmpty()) {
      target += "&interrupted";
    }
    response.setHeader("Cache-Control", "no-store");
    response.sendRedirect(target);
  }

  public String retryUsername(
      HttpServletRequest request, Authentication authentication, String handle) {
    var flow = flows(state(request)).get(handle);
    return flow != null && flow.belongsTo(owner(authentication)) ? flow.retryUsername() : null;
  }

  private String requestedHandle(HttpServletRequest request) {
    return request.getAttribute(PROVIDER_FLOW) instanceof String handle ? handle : handle(request);
  }

  public String action(HttpServletRequest request, Authentication auth, String handle) {
    var flow = flows(state(request)).get(handle);
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
    if (!validHandle(handle)) return prefix + "/";
    Owner current = owner(auth);
    return store.update(
        request,
        false,
        SECTION,
        FlowState.class,
        FlowState::new,
        state -> {
          var flows = flows(state);
          var flow = flows.get(handle);
          if (flow == null || !flow.belongsTo(current) || flow.ready()) return prefix + "/";
          state.defaults().remove("default", handle);
          if (flow.draft() == null) {
            flows.remove(handle);
            return flow.destination();
          }
          flows.put(
              handle,
              new Flow(flow.destination(), current, flow.expires(), flow.draft(), true, null));
          return prefix + "/forms/" + handle;
        });
  }

  public Draft takeDraft(HttpServletRequest request, Authentication auth, String handle) {
    if (!validHandle(handle)) return null;
    Owner current = owner(auth);
    return store.update(
        request,
        false,
        SECTION,
        FlowState.class,
        FlowState::new,
        state -> {
          var flows = flows(state);
          var flow = flows.get(handle);
          if (flow == null || !flow.ready() || !flow.belongsTo(current)) return null;
          flows.remove(handle);
          return flow.draft();
        });
  }

  public void primarySuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication auth)
      throws IOException {
    String handle = requestedHandle(request);
    String target = prefix + "/auth/continue";
    if (validHandle(handle)) {
      Owner current = owner(auth);
      target =
          store.update(
              request,
              false,
              SECTION,
              FlowState.class,
              FlowState::new,
              state -> {
                var flows = flows(state);
                var flow = flows.get(handle);
                if (flow == null || !flow.belongsTo(current) || flow.ready())
                  return prefix + "/auth/continue?interrupted";
                if (flow.destination().equals(prefix + "/security/recovery/use")) {
                  flows.remove(handle);
                  state.defaults().remove("default", handle);
                  return flow.destination();
                }
                flows.put(handle, flow.bind(current));
                return prefix + "/auth/continue?flow=" + handle;
              });
    }
    response.sendRedirect(target);
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
