package org.busystack.caldav.security.oauth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.security.session.SessionSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/** Adds stable internal account identity before framework grant state reaches PostgreSQL. */
final class PrincipalOAuthAuthorizationService implements OAuth2AuthorizationService {
  static final String ACCOUNT_ID = "busystack.account_id";
  static final String ACCOUNT_EPOCH = "busystack.account_epoch";
  static final String ABSOLUTE_EXPIRY = "busystack.refresh_absolute_expiry";
  private final JdbcOAuth2AuthorizationService delegate;
  private final AccountService accounts;
  private final OAuthClientLock clients;
  private final org.springframework.transaction.support.TransactionTemplate transaction;
  private final org.springframework.transaction.support.TransactionTemplate readSnapshot;

  PrincipalOAuthAuthorizationService(
      JdbcOAuth2AuthorizationService delegate,
      AccountService accounts,
      OAuthClientLock clients,
      org.springframework.transaction.PlatformTransactionManager transactions) {
    this.delegate = delegate;
    this.accounts = accounts;
    this.clients = clients;
    this.transaction =
        new org.springframework.transaction.support.TransactionTemplate(transactions);
    this.transaction.setTimeout(15);
    this.readSnapshot =
        new org.springframework.transaction.support.TransactionTemplate(transactions);
    this.readSnapshot.setReadOnly(true);
    this.readSnapshot.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.readSnapshot.setTimeout(15);
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    transaction.executeWithoutResult(
        status -> {
          if (!clients.registrationExists(authorization.getRegisteredClientId()))
            throw new org.springframework.security.oauth2.server.authorization.authentication
                .OAuth2AuthorizationCodeRequestAuthenticationException(
                new org.springframework.security.oauth2.core.OAuth2Error(
                    org.springframework.security.oauth2.core.OAuth2ErrorCodes.INVALID_CLIENT),
                null);
          saveLocked(authorization);
        });
  }

  private void saveLocked(OAuth2Authorization authorization) {
    if (authorization.getAttribute(ACCOUNT_ID) == null) {
      var account = accounts.find(authorization.getPrincipalName());
      if (account == null) {
        try {
          account = accounts.find(UUID.fromString(authorization.getPrincipalName()));
        } catch (IllegalArgumentException ex) {
          /* Not an internal UUID principal. */
        }
      }
      if (account == null || !account.enabled())
        throw new IllegalStateException("OAuth principal is unavailable");
      authorization =
          OAuth2Authorization.from(authorization)
              .attribute(ACCOUNT_ID, account.id().toString())
              .attribute(ABSOLUTE_EXPIRY, Instant.now().plus(30, ChronoUnit.DAYS).toString())
              .build();
    }
    if (authorization.getAttribute(ACCOUNT_EPOCH) == null) {
      Object principal = authorization.getAttribute(java.security.Principal.class.getName());
      UUID captured =
          principal instanceof Authentication authentication
              ? SessionSecurity.capturedEpoch(authentication.getAuthorities())
              : null;
      if (captured == null)
        throw new IllegalStateException("OAuth authorization has no captured account generation");
      authorization =
          OAuth2Authorization.from(authorization)
              .attribute(ACCOUNT_EPOCH, captured.toString())
              .build();
    }
    delegate.save(authorization);
  }

  @Override
  public void remove(OAuth2Authorization authorization) {
    delegate.remove(authorization);
  }

  @Override
  public OAuth2Authorization findById(String id) {
    // REQUIRED joins grant writes so their uncommitted state remains visible. Standalone reads
    // keep the authorization row and its separately loaded registration in one MVCC snapshot.
    return readSnapshot.execute(status -> delegate.findById(id));
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
    return readSnapshot.execute(status -> delegate.findByToken(token, type));
  }
}
