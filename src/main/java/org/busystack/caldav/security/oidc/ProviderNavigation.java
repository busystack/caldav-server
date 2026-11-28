package org.busystack.caldav.security.oidc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.busystack.caldav.security.AuthenticationJourney;
import org.busystack.caldav.security.BrowserFlowStore;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** Provider state owns its original authorization request, continuation and optional link. */
final class ProviderNavigation
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
  private static final String SECTION = "upstream";
  private static final String CALLBACK_LINK = ProviderNavigation.class.getName() + ".link";
  private static final int LIMIT = 8;

  private record Authorization(
      String authorizationUri,
      String client,
      String redirect,
      Set<String> scopes,
      String state,
      Map<String, Object> parameters,
      Map<String, Object> attributes,
      String requestUri) {
    Authorization(OAuth2AuthorizationRequest request) {
      this(
          request.getAuthorizationUri(),
          request.getClientId(),
          request.getRedirectUri(),
          request.getScopes(),
          request.getState(),
          request.getAdditionalParameters(),
          request.getAttributes(),
          request.getAuthorizationRequestUri());
    }

    OAuth2AuthorizationRequest rebuild() {
      return OAuth2AuthorizationRequest.authorizationCode()
          .authorizationUri(authorizationUri)
          .clientId(client)
          .redirectUri(redirect)
          .scopes(scopes)
          .state(state)
          .additionalParameters(parameters)
          .attributes(attributes)
          .authorizationRequestUri(requestUri)
          .build();
    }
  }

  private record Pending(
      Authorization authorization,
      String flow,
      ExternalIdentityService.LinkIntent link,
      Instant expires) {}

  private record State(
      LinkedHashMap<String, ExternalIdentityService.LinkIntent> links,
      LinkedHashMap<String, Pending> requests) {
    State() {
      this(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    void prune() {
      links.values().removeIf(link -> !BrowserFlowStore.current(link.issuedAt().plusSeconds(300)));
      requests.values().removeIf(request -> !BrowserFlowStore.current(request.expires()));
    }
  }

  private final BrowserFlowStore store;
  private final AuthenticationJourney journey;

  ProviderNavigation(BrowserFlowStore store, AuthenticationJourney journey) {
    this.store = store;
    this.journey = journey;
  }

  String startLink(HttpServletRequest request, ExternalIdentityService.LinkIntent intent) {
    return store.update(
        request,
        true,
        SECTION,
        State.class,
        State::new,
        state -> {
          state.prune();
          String handle = UUID.randomUUID().toString();
          state.links().put(handle, intent);
          while (state.links().size() > LIMIT) state.links().pollFirstEntry();
          return handle;
        });
  }

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    var state = store.read(request, SECTION, State.class, State::new);
    state.prune();
    var pending = state.requests().get(request.getParameter("state"));
    return pending == null ? null : pending.authorization().rebuild();
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorization,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (authorization == null) {
      removeAuthorizationRequest(request, response);
      return;
    }
    String flow = journey.handle(request);
    String linkHandle = request.getParameter("link");
    store.update(
        request,
        true,
        SECTION,
        State.class,
        State::new,
        state -> {
          state.prune();
          ExternalIdentityService.LinkIntent link = null;
          if (linkHandle != null) {
            if (!AuthenticationJourney.validHandle(linkHandle)) throw unavailable();
            link = state.links().get(linkHandle);
            var current = SecurityContextHolder.getContext().getAuthentication();
            if (link == null
                || current == null
                || !link.username().equals(current.getName())
                || !link.sessionBinding()
                    .equals(BrowserFlowStore.fingerprint(request.getSession(false).getId()))
                || !link.proof()
                    .epoch()
                    .equals(
                        org.busystack.caldav.security.session.SessionSecurity.capturedEpoch(
                            current.getAuthorities()))) throw unavailable();
            state.links().remove(linkHandle);
          }
          state
              .requests()
              .put(
                  authorization.getState(),
                  new Pending(
                      new Authorization(authorization),
                      flow,
                      link,
                      link == null
                          ? Instant.now().plusSeconds(900)
                          : link.issuedAt().plusSeconds(300)));
          while (state.requests().size() > LIMIT) state.requests().pollFirstEntry();
          return null;
        });
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    String incoming = request.getParameter("state");
    if (incoming == null || incoming.length() > 256) return null;
    Pending pending =
        store.update(
            request,
            false,
            SECTION,
            State.class,
            State::new,
            state -> {
              state.prune();
              return state.requests().remove(incoming);
            });
    if (pending == null) return null;
    journey.providerCallback(request, pending.flow());
    request.setAttribute(CALLBACK_LINK, pending.link());
    return pending.authorization().rebuild();
  }

  static ExternalIdentityService.LinkIntent link(HttpServletRequest request) {
    return (ExternalIdentityService.LinkIntent) request.getAttribute(CALLBACK_LINK);
  }

  private static OAuth2AuthorizationException unavailable() {
    return new OAuth2AuthorizationException(
        new OAuth2Error("invalid_request"), "Link request ended");
  }
}
