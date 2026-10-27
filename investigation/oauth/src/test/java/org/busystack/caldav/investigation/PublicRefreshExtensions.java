package org.busystack.caldav.investigation;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Base64;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Deliberately incomplete TEST fixture: demonstrates supported framework extension points only.
 * It lacks persistent replay-family enforcement and MUST NOT be used by the production server.
 */
final class PublicRefreshExtensions {
  private PublicRefreshExtensions() {}

  static final class Generator implements OAuth2TokenGenerator<OAuth2RefreshToken> {
    private final Base64StringKeyGenerator keys = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

    @Override public OAuth2RefreshToken generate(OAuth2TokenContext context) {
      if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) return null;
      Instant now = Instant.now();
      return new OAuth2RefreshToken(keys.generateKey(), now, now.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive()));
    }
  }

  static final class ClientToken extends OAuth2ClientAuthenticationToken {
    ClientToken(String clientId) { super(clientId, ClientAuthenticationMethod.NONE, null, null); }
  }

  static final class Converter implements AuthenticationConverter {
    @Override public Authentication convert(HttpServletRequest request) {
      if (!"POST".equals(request.getMethod()) || !"refresh_token".equals(request.getParameter("grant_type")) || request.getHeader("Authorization") != null) return null;
      String[] clientIds = request.getParameterValues("client_id");
      String[] grants = request.getParameterValues("grant_type");
      if (clientIds == null || clientIds.length != 1 || clientIds[0].isBlank() || grants == null || grants.length != 1) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
      if (request.getParameter("client_secret") != null) return null;
      return new ClientToken(clientIds[0]);
    }
  }

  static final class Provider implements AuthenticationProvider {
    private final RegisteredClientRepository clients;
    Provider(RegisteredClientRepository clients) { this.clients = clients; }
    @Override public Authentication authenticate(Authentication authentication) {
      RegisteredClient client = clients.findByClientId(authentication.getName());
      if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE) || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
      // NONE identifies a public client; the framework grant provider validates token binding.
      return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }
    @Override public boolean supports(Class<?> type) { return ClientToken.class.isAssignableFrom(type); }
  }
}
