package org.busystack.caldav.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;

class DavBearerAuthenticationEntryPointTest {
  @Test
  void statusAndRequiredErrorParametersAreRetainedWithoutUnimplementedOptionalMetadata()
      throws Exception {
    var response = new MockHttpServletResponse();
    new DavBearerAuthenticationEntryPoint()
        .commence(
            new MockHttpServletRequest(),
            response,
            new OAuth2AuthenticationException(
                new BearerTokenError(
                    "invalid_request",
                    HttpStatus.BAD_REQUEST,
                    "Duplicate credential",
                    "https://example.test/error",
                    "dav")));
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getHeader("WWW-Authenticate"))
        .isEqualTo(
            "Bearer error=\"invalid_request\", error_description=\"Duplicate credential\", error_uri=\"https://example.test/error\", scope=\"dav\"");
    var ordinary = new MockHttpServletResponse();
    new DavBearerAuthenticationEntryPoint()
        .commence(
            new MockHttpServletRequest(),
            ordinary,
            new BadCredentialsException("untrusted detail"));
    assertThat(ordinary.getStatus()).isEqualTo(401);
    assertThat(ordinary.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
  }

  @Test
  void exactConfiguredFrameworkAccessDeniedHandlerDoesNotAdvertiseResourceMetadata()
      throws Exception {
    var request = new MockHttpServletRequest();
    List<org.springframework.security.core.GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("SCOPE_dav"));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal("actor", Map.of("sub", "actor"), authorities);
    request.setUserPrincipal(
        new BearerTokenAuthentication(
            principal,
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "fixture",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Set.of("dav")),
            authorities));
    var response = new MockHttpServletResponse();
    new BearerTokenAccessDeniedHandler()
        .handle(request, response, new AccessDeniedException("Denied"));
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("WWW-Authenticate"))
        .contains("insufficient_scope")
        .doesNotContain("resource_metadata");
  }
}
