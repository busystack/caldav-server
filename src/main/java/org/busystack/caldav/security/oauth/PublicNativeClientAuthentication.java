package org.busystack.caldav.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Public-client identification; possession and binding of the grant are checked by grant providers.
 */
final class PublicNativeClientAuthentication {
  private PublicNativeClientAuthentication() {}

  private static final class PublicClientToken extends OAuth2ClientAuthenticationToken {
    PublicClientToken(String clientId) {
      super(clientId, ClientAuthenticationMethod.NONE, null, null);
    }
  }

  static final class Converter implements AuthenticationConverter {
    @Override
    public Authentication convert(HttpServletRequest request) {
      if (!"POST".equals(request.getMethod())
          || request.getHeader("Authorization") != null
          || request.getParameter("client_secret") != null) return null;
      boolean refresh =
          "/oauth2/token".equals(request.getServletPath())
              && "refresh_token".equals(request.getParameter("grant_type"));
      boolean revoke = "/oauth2/revoke".equals(request.getServletPath());
      if (!refresh && !revoke) return null;
      String[] ids = request.getParameterValues("client_id");
      String[] grants = request.getParameterValues("grant_type");
      if (ids == null
          || ids.length != 1
          || ids[0].isBlank()
          || ids[0].length() > 100
          || (refresh && (grants == null || grants.length != 1)))
        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
      return new PublicClientToken(ids[0]);
    }
  }

  static final class Provider implements AuthenticationProvider {
    private final RegisteredClientRepository clients;

    Provider(RegisteredClientRepository clients) {
      this.clients = clients;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
      RegisteredClient client = clients.findByClientId(authentication.getName());
      if (client == null
          || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
          || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN))
        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
      return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> type) {
      return PublicClientToken.class.isAssignableFrom(type);
    }
  }

  /** Terminal failure mapping after the supported public-client providers have declined a token. */
  static final class RejectUnsupported implements AuthenticationProvider {
    @Override
    public Authentication authenticate(Authentication authentication) {
      if (ClientAuthenticationMethod.NONE.equals(
          ((OAuth2ClientAuthenticationToken) authentication).getClientAuthenticationMethod()))
        return null; // Preserve a supported public provider's precise PKCE/grant failure.
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
    }

    @Override
    public boolean supports(Class<?> type) {
      return OAuth2ClientAuthenticationToken.class.isAssignableFrom(type);
    }
  }
}
