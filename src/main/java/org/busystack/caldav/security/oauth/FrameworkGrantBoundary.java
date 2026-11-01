package org.busystack.caldav.security.oauth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Transaction/replay boundaries around Spring Security's grant implementations. */
final class FrameworkGrantBoundary implements AuthenticationProvider {
  enum Kind {
    CODE,
    REFRESH,
    REVOCATION
  }

  private record Outcome(Authentication response, OAuth2AuthenticationException error) {}

  private final AuthenticationProvider delegate;
  private final Kind kind;
  private final OAuth2AuthorizationService authorizations;
  private final RefreshFamilyStore families;
  private final OAuthClientLock clients;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  FrameworkGrantBoundary(
      AuthenticationProvider delegate,
      Kind kind,
      OAuth2AuthorizationService authorizations,
      RefreshFamilyStore families,
      OAuthClientLock clients,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions) {
    this.delegate = delegate;
    this.kind = kind;
    this.authorizations = authorizations;
    this.families = families;
    this.clients = clients;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(15);
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    try {
      return switch (kind) {
        case CODE -> exchange((OAuth2AuthorizationCodeAuthenticationToken) authentication);
        case REFRESH -> refresh((OAuth2RefreshTokenAuthenticationToken) authentication);
        case REVOCATION -> revoke((OAuth2TokenRevocationAuthenticationToken) authentication);
      };
    } catch (RefreshRejectedException ex) {
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
    }
  }

  private Authentication exchange(OAuth2AuthorizationCodeAuthenticationToken request) {
    OAuth2Authorization existing =
        authorizations.findByToken(
            request.getCode(), new OAuth2TokenType(OAuth2ParameterNames.CODE));
    if (existing == null) return delegate.authenticate(request);
    Outcome result =
        transaction.execute(
            status -> {
              OAuth2ClientAuthenticationToken identified =
                  (OAuth2ClientAuthenticationToken) request.getPrincipal();
              var registration = Objects.requireNonNull(identified.getRegisteredClient());
              if (!clients.matches(registration.getClientId(), registration.getId())
                  || !registration.getId().equals(existing.getRegisteredClientId()))
                return new Outcome(
                    null, new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT));
              families.lockAuthorization(existing.getId());
              String accountId =
                  existing.getAttribute(PrincipalOAuthAuthorizationService.ACCOUNT_ID);
              String accountEpoch =
                  existing.getAttribute(PrincipalOAuthAuthorizationService.ACCOUNT_EPOCH);
              if (accountId == null
                  || accountEpoch == null
                  || jdbc
                      .query(
                          "SELECT enabled AND security_epoch=? FROM accounts WHERE id=? FOR SHARE",
                          (rs, row) -> rs.getBoolean(1),
                          UUID.fromString(accountEpoch),
                          UUID.fromString(accountId))
                      .stream()
                      .noneMatch(Boolean::booleanValue)
                  || !families.accountCurrent(UUID.fromString(accountId)))
                return new Outcome(
                    null, new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT));
              jdbc.query(
                  "SELECT id FROM oauth2_authorization WHERE id=? FOR UPDATE",
                  rs -> {},
                  existing.getId());
              try {
                Authentication authentication = delegate.authenticate(request);
                if (!(authentication instanceof OAuth2AccessTokenAuthenticationToken response)
                    || response.getRefreshToken() == null)
                  throw new IllegalStateException("Native grant did not issue a refresh token");
                OAuth2Authorization stored =
                    Objects.requireNonNull(authorizations.findById(existing.getId()));
                OAuth2ClientAuthenticationToken client =
                    (OAuth2ClientAuthenticationToken) request.getPrincipal();
                Instant absoluteExpiry =
                    Instant.parse(
                        stored.getAttribute(PrincipalOAuthAuthorizationService.ABSOLUTE_EXPIRY));
                families.register(
                    new RefreshFamilyStore.Family(
                        stored.getId(),
                        UUID.fromString(accountId),
                        Objects.requireNonNull(client.getRegisteredClient()).getClientId(),
                        absoluteExpiry),
                    response.getRefreshToken().getTokenValue(),
                    Objects.requireNonNull(response.getRefreshToken().getExpiresAt()),
                    () -> {});
                return new Outcome(response, null);
              } catch (OAuth2AuthenticationException ex) {
                OAuth2Authorization changed = authorizations.findById(existing.getId());
                if (changed != null
                    && changed.getRefreshToken() != null
                    && !changed.getRefreshToken().isActive())
                  families.invalidateAuthorization(changed.getId());
                // Preserve the framework's deliberate code-replay invalidation before returning its
                // error.
                return new Outcome(null, ex);
              }
            });
    if (result == null) throw new IllegalStateException("Code transaction returned no outcome");
    if (result.error() != null) throw result.error();
    return result.response();
  }

  private Authentication refresh(OAuth2RefreshTokenAuthenticationToken request) {
    OAuth2ClientAuthenticationToken client =
        (OAuth2ClientAuthenticationToken) request.getPrincipal();
    return families.refresh(
        request.getRefreshToken(),
        Objects.requireNonNull(client.getRegisteredClient()).getClientId(),
        client.getRegisteredClient().getId(),
        family -> {
          Authentication authentication = delegate.authenticate(request);
          if (!(authentication instanceof OAuth2AccessTokenAuthenticationToken response)
              || response.getRefreshToken() == null)
            throw new IllegalStateException("Framework refresh did not rotate the token");
          return new RefreshFamilyStore.Rotation<>(
              response,
              response.getRefreshToken().getTokenValue(),
              Objects.requireNonNull(response.getRefreshToken().getExpiresAt()));
        });
  }

  private Authentication revoke(OAuth2TokenRevocationAuthenticationToken request) {
    OAuth2Authorization existing = authorizations.findByToken(request.getToken(), null);
    if (existing == null) return delegate.authenticate(request);
    return transaction.execute(
        status -> {
          OAuth2ClientAuthenticationToken identified =
              (OAuth2ClientAuthenticationToken) request.getPrincipal();
          var registration = Objects.requireNonNull(identified.getRegisteredClient());
          if (!clients.matches(registration.getClientId(), registration.getId()))
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
          families.lockAuthorization(existing.getId());
          String accountId = existing.getAttribute(PrincipalOAuthAuthorizationService.ACCOUNT_ID);
          if (accountId != null)
            // Match code/refresh/reset ordering: account, then grant/family rows. Revocation
            // remains permitted when the account has already been disabled.
            jdbc.query(
                "SELECT id FROM accounts WHERE id=? FOR SHARE",
                rs -> {},
                UUID.fromString(accountId));
          Authentication response = delegate.authenticate(request);
          OAuth2ClientAuthenticationToken client =
              (OAuth2ClientAuthenticationToken) request.getPrincipal();
          if (Objects.requireNonNull(client.getRegisteredClient())
              .getId()
              .equals(existing.getRegisteredClientId()))
            families.invalidateAuthorization(existing.getId());
          return response;
        });
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }
}
