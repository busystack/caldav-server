package org.busystack.caldav.security.oidc;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Maps an already framework-verified OIDC identity to an application principal. */
public class OidcLoginSupport implements OAuth2UserService<OidcUserRequest, OidcUser> {
  public static final String TRUSTED_UPSTREAM_AUTHORITY = "BUSYSTACK_TRUSTED_UPSTREAM";
  private static final String LOCAL_NAME = "busystack.internal.username";
  private final OidcUserService delegate = new OidcUserService();
  private final ExternalIdentityService identities;
  private final OidcProperties properties;
  private final ObjectProvider<HttpServletRequest> requests;

  public OidcLoginSupport(
      ExternalIdentityService identities,
      OidcProperties properties,
      ObjectProvider<HttpServletRequest> requests) {
    this.identities = identities;
    this.properties = properties;
    this.requests = requests;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    if (!"upstream".equals(request.getClientRegistration().getRegistrationId())
        || !properties
            .issuer()
            .toString()
            .equals(request.getClientRegistration().getProviderDetails().getIssuerUri()))
      throw ExternalIdentityService.denied();
    OidcUser verified = delegate.loadUser(request);
    var servletRequest = requests.getObject();
    var session = servletRequest.getSession(false);
    var link = LinkingAuthorizationRequestResolver.consume(servletRequest);
    Instant authenticationTime = verified.getClaimAsInstant("auth_time");
    org.busystack.caldav.account.AccountService.Account account;
    try {
      account =
          identities.verifiedLogin(
              verified.getIssuer().toString(),
              verified.getSubject(),
              authenticationTime,
              link,
              SecurityContextHolder.getContext().getAuthentication());
    } catch (DuplicateKeyException ex) {
      throw ExternalIdentityService.denied();
    }
    if (link != null) {
      // Linking changes the security generation and deletes existing sessions.
      // Start a fresh login before framework MFA merging can retain obsolete factors.
      if (session != null) session.invalidate();
      SecurityContextHolder.clearContext();
    }
    var authorities =
        List.of(
            new SimpleGrantedAuthority(account.administrator() ? "ROLE_ADMIN" : "ROLE_USER"),
            new SimpleGrantedAuthority(TRUSTED_UPSTREAM_AUTHORITY),
            FactorGrantedAuthority.withAuthority(
                    FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
                .issuedAt(authenticationTime)
                .build(),
            org.busystack.caldav.security.session.SessionSecurity.authority(
                account.securityEpoch()));
    // The raw ID token is unchanged. This local principal view adds an application-only
    // name after verified claims; no upstream role or username claim selects an account.
    var claims = new HashMap<String, Object>(verified.getIdToken().getClaims());
    claims.put(LOCAL_NAME, account.username());
    var view =
        new OidcIdToken(
            verified.getIdToken().getTokenValue(),
            verified.getIdToken().getIssuedAt(),
            verified.getIdToken().getExpiresAt(),
            claims);
    return new DefaultOidcUser(authorities, view, LOCAL_NAME);
  }
}
