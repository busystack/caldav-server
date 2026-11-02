package org.busystack.caldav.security.oauth;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.config.DeploymentProperties;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Service;

/**
 * Validates local opaque access grants on every request, including account and family revocation.
 */
@Service
public class OAuthDavIntrospector implements OpaqueTokenIntrospector {
  private final OAuth2AuthorizationService authorizations;
  private final RefreshFamilyStore families;
  private final AccountService accounts;
  private final String audience;
  private final String issuer;

  public OAuthDavIntrospector(
      OAuth2AuthorizationService authorizations,
      RefreshFamilyStore families,
      AccountService accounts,
      DeploymentProperties deployment) {
    this.authorizations = authorizations;
    this.families = families;
    this.accounts = accounts;
    this.audience = deployment.publicBaseUrl() + "/dav";
    this.issuer = deployment.issuer().toString();
  }

  @Override
  public OAuth2AuthenticatedPrincipal introspect(String token) {
    if (token == null || token.length() > 4096) throw invalid();
    var authorization = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
    if (authorization == null
        || authorization.getAccessToken() == null
        || !authorization.getAccessToken().isActive()
        || !authorization.getAuthorizedScopes().contains("dav")
        || !families.isActive(authorization.getId())) throw invalid();
    String accountId = authorization.getAttribute(PrincipalOAuthAuthorizationService.ACCOUNT_ID);
    Map<String, Object> claims = authorization.getAccessToken().getClaims();
    if (accountId == null
        || claims == null
        || !issuer.equals(String.valueOf(claims.get("iss")))
        || !accountId.equals(claims.get("sub"))
        || !(claims.get("aud") instanceof List<?> audiences)
        || !audiences.contains(audience)) throw invalid();
    AccountService.Account account;
    try {
      account = accounts.find(UUID.fromString(accountId));
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
    String capturedEpoch =
        authorization.getAttribute(PrincipalOAuthAuthorizationService.ACCOUNT_EPOCH);
    if (account == null
        || !account.enabled()
        || !accounts.isClientAccessCurrent(account.id())
        || capturedEpoch == null
        || !capturedEpoch.equals(account.securityEpoch().toString())) throw invalid();
    return new DefaultOAuth2AuthenticatedPrincipal(
        accountId,
        claims,
        List.of(new SimpleGrantedAuthority("DAV"), new SimpleGrantedAuthority("SCOPE_dav")));
  }

  private static BadOpaqueTokenException invalid() {
    return new BadOpaqueTokenException("Invalid access token");
  }
}
