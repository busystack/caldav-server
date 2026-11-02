package org.busystack.caldav.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

class LinkingAuthorizationRequestResolverTest {
  @Test
  void aParallelLoginCannotConsumeOrApplyAnotherRequestsLinkingIntent() {
    var resolver = resolver();
    var session = new MockHttpSession();
    var parallel = resolver.resolve(start(session));
    var intent = new ExternalIdentityService.LinkIntent(UUID.randomUUID(), "alice", Instant.now());
    session.setAttribute(LinkingAuthorizationRequestResolver.PENDING_LINK, intent);
    var linking = resolver.resolve(start(session));
    assertThat(linking.getState()).isNotEqualTo(parallel.getState());
    assertThat(session.getAttribute(LinkingAuthorizationRequestResolver.PENDING_LINK)).isNull();

    assertThat(LinkingAuthorizationRequestResolver.consume(callback(session, parallel.getState())))
        .isNull();
    assertThat(LinkingAuthorizationRequestResolver.consume(callback(session, "wrong-state")))
        .isNull();
    assertThat(LinkingAuthorizationRequestResolver.consume(callback(session, linking.getState())))
        .isEqualTo(intent);
    assertThat(LinkingAuthorizationRequestResolver.consume(callback(session, linking.getState())))
        .isNull();
  }

  @Test
  void unrelatedRequestsCannotBindAnIntentAndAnUnstartedIntentCannotLink() {
    var resolver = resolver();
    var session = new MockHttpSession();
    var intent = new ExternalIdentityService.LinkIntent(UUID.randomUUID(), "alice", Instant.now());
    session.setAttribute(LinkingAuthorizationRequestResolver.PENDING_LINK, intent);
    var unrelated = new MockHttpServletRequest("GET", "/unrelated");
    unrelated.setServletPath("/unrelated");
    unrelated.setSession(session);
    assertThat(resolver.resolve(unrelated)).isNull();
    assertThat(session.getAttribute(LinkingAuthorizationRequestResolver.PENDING_LINK))
        .isEqualTo(intent);
    assertThat(LinkingAuthorizationRequestResolver.consume(callback(session, "arbitrary-state")))
        .isNull();
  }

  @Test
  void explicitRegistrationResolutionUsesTheSameStateBinding() {
    var resolver = resolver();
    var session = new MockHttpSession();
    var intent = new ExternalIdentityService.LinkIntent(UUID.randomUUID(), "alice", Instant.now());
    session.setAttribute(LinkingAuthorizationRequestResolver.PENDING_LINK, intent);
    var authorization = resolver.resolve(start(session), "upstream");
    assertThat(
            LinkingAuthorizationRequestResolver.consume(
                callback(session, authorization.getState())))
        .isEqualTo(intent);
  }

  private LinkingAuthorizationRequestResolver resolver() {
    var client =
        ClientRegistration.withRegistrationId("upstream")
            .clientId("fixture")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationUri("https://issuer.example/authorize")
            .tokenUri("https://issuer.example/token")
            .redirectUri("https://calendar.example/login/oauth2/code/upstream")
            .scope("openid")
            .build();
    return new LinkingAuthorizationRequestResolver(
        new DefaultOAuth2AuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(client), "/oauth2/authorization"));
  }

  private MockHttpServletRequest start(MockHttpSession session) {
    var request = new MockHttpServletRequest("GET", "/oauth2/authorization/upstream");
    request.setServletPath("/oauth2/authorization/upstream");
    request.setSession(session);
    return request;
  }

  private MockHttpServletRequest callback(MockHttpSession session, String state) {
    var request = new MockHttpServletRequest("GET", "/login/oauth2/code/upstream");
    request.setSession(session);
    request.addParameter("state", state);
    return request;
  }
}
