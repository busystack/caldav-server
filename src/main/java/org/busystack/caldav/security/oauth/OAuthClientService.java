package org.busystack.caldav.security.oauth;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.RecentAuthentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrator-controlled registration; no dynamic registration endpoint is exposed. */
@Service
public class OAuthClientService {
  public record Client(String id, String name, String redirects) {}

  private final RegisteredClientRepository clients;
  private final AccountService accounts;
  private final JdbcTemplate jdbc;
  private final org.busystack.caldav.security.session.SessionSecurity sessions;
  private final OAuthClientLock clientLocks;

  public OAuthClientService(
      RegisteredClientRepository clients,
      AccountService accounts,
      JdbcTemplate jdbc,
      org.busystack.caldav.security.session.SessionSecurity sessions,
      OAuthClientLock clientLocks) {
    this.clients = clients;
    this.accounts = accounts;
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.clientLocks = clientLocks;
  }

  public List<Client> list(Authentication authentication) {
    return list(actor(authentication));
  }

  @Transactional
  public void registerNative(
      Authentication authentication, String clientId, String name, Set<String> redirects) {
    RecentAuthentication.requireMfa(authentication);
    clientLocks.lock(clientId);
    registerNative(actor(authentication), clientId, name, redirects);
  }

  @Transactional
  public void remove(Authentication authentication, String clientId) {
    RecentAuthentication.requireMfa(authentication);
    clientLocks.lock(clientId);
    remove(actor(authentication), clientId);
  }

  List<Client> list(UUID administrator) {
    requireAdministrator(administrator);
    return jdbc.query(
        "SELECT client_id,client_name,redirect_uris FROM oauth2_registered_client ORDER BY client_name,client_id",
        (rs, row) -> new Client(rs.getString(1), rs.getString(2), rs.getString(3)));
  }

  @Transactional
  void registerNative(UUID administrator, String clientId, String name, Set<String> redirects) {
    clientLocks.lock(clientId);
    requireAdministrator(administrator);
    if (clientId == null
        || !clientId.matches("[A-Za-z0-9._-]{1,100}")
        || name == null
        || name.isBlank()
        || name.length() > 100
        || redirects == null
        || redirects.isEmpty()
        || redirects.size() > 5
        || redirects.stream().mapToInt(String::length).sum() > 950)
      throw new IllegalArgumentException("Invalid OAuth client registration");
    redirects.forEach(OAuthClientService::validateRedirect);
    if (clients.findByClientId(clientId) != null)
      throw new IllegalArgumentException("OAuth client already exists");
    var builder =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)
            .clientName(name)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .scope("dav")
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(true)
                    .build())
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                    .accessTokenTimeToLive(Duration.ofMinutes(10))
                    .refreshTokenTimeToLive(Duration.ofDays(30))
                    .reuseRefreshTokens(false)
                    .build());
    redirects.forEach(builder::redirectUri);
    clients.save(builder.build());
  }

  @Transactional
  void remove(UUID administrator, String clientId) {
    clientLocks.lock(clientId);
    requireAdministrator(administrator);
    RegisteredClient client = clients.findByClientId(clientId);
    if (client == null) return;
    jdbc.update(
        "UPDATE oauth_refresh_families SET revoked_at=COALESCE(revoked_at,now()) WHERE client_id=?",
        clientId);
    jdbc.update(
        "DELETE FROM oauth2_authorization_consent WHERE registered_client_id=?", client.getId());
    jdbc.update("DELETE FROM oauth2_authorization WHERE registered_client_id=?", client.getId());
    jdbc.update("DELETE FROM oauth2_registered_client WHERE id=?", client.getId());
  }

  static void validateRedirect(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid OAuth redirect URI");
    }
    if (!uri.isAbsolute()
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || uri.getScheme() == null
        || uri.getRawPath() == null
        || !uri.getRawPath().startsWith("/"))
      throw new IllegalArgumentException(
          "OAuth redirect must be an absolute URI without credentials or fragment");
    if ("https".equals(uri.getScheme()) && uri.getHost() != null) return;
    if ("http".equals(uri.getScheme())
        && ("127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()))
        && uri.getPort() > 0) return;
    if (uri.getScheme().matches("[a-z][a-z0-9+.-]*\\.[a-z0-9+.-]+")
        && uri.getRawAuthority() == null) return;
    throw new IllegalArgumentException(
        "Native redirect requires HTTPS, an IP loopback HTTP URI, or an application-owned reverse-domain scheme");
  }

  private UUID actor(Authentication authentication) {
    sessions.assertCurrent(authentication);
    if (authentication == null || !authentication.isAuthenticated())
      throw new AccessDeniedException("Account authentication required");
    var account = accounts.find(authentication.getName());
    if (account == null || !account.enabled() || !account.administrator())
      throw new AccessDeniedException("Administrator privilege required");
    return account.id();
  }

  private void requireAdministrator(UUID id) {
    var account = accounts.find(id);
    if (account == null || !account.enabled() || !account.administrator())
      throw new AccessDeniedException("Administrator privilege required");
  }
}
