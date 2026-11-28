package org.busystack.caldav.security.oidc;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.busystack.caldav.config.DeploymentProperties;
import org.busystack.caldav.security.AuthenticationJourney;
import org.busystack.caldav.security.FlowStoreFixture;
import org.busystack.caldav.security.session.BrowserSession;
import org.busystack.caldav.security.session.SessionSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;

class LinkingAuthorizationRequestResolverTest {
  private final MockHttpSession session = new MockHttpSession();
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final URI base = URI.create("https://calendar.example/team");
  private final org.busystack.caldav.security.BrowserFlowStore store = FlowStoreFixture.create();
  private final AuthenticationJourney journey =
      new AuthenticationJourney(new DeploymentProperties(base, base, false), null, store);
  private final ProviderNavigation navigation = new ProviderNavigation(store, journey);

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aParallelLoginCannotConsumeOrApplyAnotherRequestsLinkingIntent() {
    var resolver = resolver();
    var linking = start();
    var intent = intent(Instant.now());
    linking.setParameter("link", navigation.startLink(linking, intent));
    var ordinary = start();
    var parallel = resolver.resolve(ordinary);
    navigation.saveAuthorizationRequest(parallel, ordinary, response);
    var authorization = resolver.resolve(linking);
    navigation.saveAuthorizationRequest(authorization, linking, response);
    assertThat(authorization.getState()).isNotEqualTo(parallel.getState());
    assertThat(navigation.removeAuthorizationRequest(callback("wrong-state"), response)).isNull();

    var unrelated = callback(parallel.getState());
    assertThat(navigation.removeAuthorizationRequest(unrelated, response)).isNotNull();
    assertThat(ProviderNavigation.link(unrelated)).isNull();
    var linked = callback(authorization.getState());
    assertThat(navigation.removeAuthorizationRequest(linked, response)).isNotNull();
    assertThat(ProviderNavigation.link(linked)).isEqualTo(intent);
    assertThat(navigation.removeAuthorizationRequest(callback(authorization.getState()), response))
        .isNull();
    assertThatThrownBy(
            () -> navigation.saveAuthorizationRequest(resolver.resolve(linking), linking, response))
        .isInstanceOf(OAuth2AuthorizationException.class);
  }

  @Test
  void unrelatedRequestsCannotBindAnIntentAndAnUnstartedIntentCannotLink() {
    var resolver = resolver();
    var linking = start();
    var intent = intent(Instant.now());
    linking.setParameter("link", navigation.startLink(linking, intent));
    var unrelated = new MockHttpServletRequest("GET", "/unrelated");
    unrelated.setServletPath("/unrelated");
    unrelated.setSession(session);
    assertThat(resolver.resolve(unrelated)).isNull();
    assertThat(navigation.removeAuthorizationRequest(callback("arbitrary-state"), response))
        .isNull();
    var auth = resolver.resolve(linking);
    navigation.saveAuthorizationRequest(auth, linking, response);
    var callback = callback(auth.getState());
    assertThat(navigation.removeAuthorizationRequest(callback, response)).isNotNull();
    assertThat(ProviderNavigation.link(callback)).isEqualTo(intent);
  }

  @Test
  void explicitRegistrationResolutionUsesTheSameStateBinding() {
    var resolver = resolver();
    var linking = start();
    var intent = intent(Instant.now());
    linking.setParameter("link", navigation.startLink(linking, intent));
    var auth = resolver.resolve(linking, "upstream");
    navigation.saveAuthorizationRequest(auth, linking, response);
    var result = callback(auth.getState());
    assertThat(navigation.removeAuthorizationRequest(result, response)).isNotNull();
    assertThat(ProviderNavigation.link(result)).isEqualTo(intent);
  }

  @Test
  void expiredAndChangedActorHandlesCannotStartLinking() {
    var resolver = resolver();
    var linking = start();
    var expired = intent(Instant.now().minusSeconds(301));
    linking.setParameter("link", navigation.startLink(linking, expired));
    assertThatThrownBy(
            () -> navigation.saveAuthorizationRequest(resolver.resolve(linking), linking, response))
        .isInstanceOf(OAuth2AuthorizationException.class);
    var current = intent(Instant.now());
    linking.setParameter("link", navigation.startLink(linking, current));
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "other", null, List.of(SessionSecurity.authority(current.proof().epoch()))));
    assertThatThrownBy(
            () -> navigation.saveAuthorizationRequest(resolver.resolve(linking), linking, response))
        .isInstanceOf(OAuth2AuthorizationException.class);
  }

  private ExternalIdentityService.LinkIntent intent(Instant now) {
    var proof =
        new BrowserSession.Proof(UUID.randomUUID(), UUID.randomUUID(), "fixture-key", now, now);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "alice", null, List.of(SessionSecurity.authority(proof.epoch()))));
    return new ExternalIdentityService.LinkIntent(
        proof.account(),
        "alice",
        now,
        org.busystack.caldav.security.BrowserFlowStore.fingerprint(session.getId()),
        proof);
  }

  private DefaultOAuth2AuthorizationRequestResolver resolver() {
    var client =
        ClientRegistration.withRegistrationId("upstream")
            .clientId("fixture")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationUri("https://issuer.example/authorize")
            .tokenUri("https://issuer.example/token")
            .redirectUri("https://calendar.example/login/oauth2/code/upstream")
            .scope("openid")
            .build();
    return new DefaultOAuth2AuthorizationRequestResolver(
        new InMemoryClientRegistrationRepository(client), "/oauth2/authorization");
  }

  private MockHttpServletRequest start() {
    var request = new MockHttpServletRequest("GET", "/oauth2/authorization/upstream");
    request.setServletPath("/oauth2/authorization/upstream");
    request.setSession(session);
    return request;
  }

  private MockHttpServletRequest callback(String state) {
    var request = new MockHttpServletRequest("GET", "/login/oauth2/code/upstream");
    request.setSession(session);
    request.setParameter("state", state);
    return request;
  }
}
