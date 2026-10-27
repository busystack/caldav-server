package org.busystack.caldav.security.oauth;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.config.DeploymentProperties;
import org.busystack.caldav.security.ActiveAccountFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.PublicClientAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class OAuthConfiguration {
  @Bean
  RegisteredClientRepository registeredClients(JdbcTemplate jdbc) {
    return new JdbcRegisteredClientRepository(jdbc);
  }

  @Bean
  OAuth2AuthorizationService authorizations(
      JdbcTemplate jdbc,
      RegisteredClientRepository clients,
      AccountService accounts,
      OAuthClientLock clientLocks,
      PlatformTransactionManager transactions) {
    return new PrincipalOAuthAuthorizationService(
        OAuthJdbcSerialization.service(jdbc, clients), accounts, clientLocks, transactions);
  }

  @Bean
  OAuth2AuthorizationConsentService consents(
      JdbcTemplate jdbc, RegisteredClientRepository clients) {
    return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
  }

  @Bean
  AuthorizationServerSettings authorizationSettings(DeploymentProperties deployment) {
    return AuthorizationServerSettings.builder().issuer(deployment.issuer().toString()).build();
  }

  @Bean
  OAuth2TokenGenerator<?> oauthTokens(DeploymentProperties deployment) {
    OAuth2AccessTokenGenerator access = new OAuth2AccessTokenGenerator();
    access.setAccessTokenCustomizer(
        context -> {
          String accountId =
              context
                  .getAuthorization()
                  .getAttribute(PrincipalOAuthAuthorizationService.ACCOUNT_ID);
          if (accountId == null)
            throw new IllegalStateException("OAuth grant has no internal principal");
          context
              .getClaims()
              .subject(accountId)
              .audience(new java.util.ArrayList<>(List.of(deployment.publicBaseUrl() + "/dav")));
        });
    Base64StringKeyGenerator refreshKeys =
        new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);
    OAuth2TokenGenerator<OAuth2RefreshToken> refresh =
        context -> {
          if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) return null;
          if (context.getAuthorization() == null)
            throw new IllegalStateException("Refresh token has no persisted authorization");
          String absolute =
              context
                  .getAuthorization()
                  .getAttribute(PrincipalOAuthAuthorizationService.ABSOLUTE_EXPIRY);
          if (absolute == null)
            throw new IllegalStateException("Refresh family has no absolute expiry");
          Instant now = Instant.now();
          Instant limit = Instant.parse(absolute);
          Instant configured =
              now.plus(
                  context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
          return new OAuth2RefreshToken(
              refreshKeys.generateKey(), now, configured.isBefore(limit) ? configured : limit);
        };
    return new DelegatingOAuth2TokenGenerator(access, refresh);
  }

  @Bean
  @Order(2)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  SecurityFilterChain authorizationServerSecurity(
      HttpSecurity http,
      OAuth2AuthorizationService authorizations,
      RegisteredClientRepository clients,
      OAuth2AuthorizationConsentService consents,
      OAuth2TokenGenerator<?> tokens,
      RefreshFamilyStore families,
      OAuthClientLock clientLocks,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      org.busystack.caldav.security.session.SessionSecurity sessions,
      AccountService accounts)
      throws Exception {
    http.oauth2AuthorizationServer(
            server -> {
              http.securityMatcher(server.getEndpointsMatcher());
              server
                  .authorizationService(authorizations)
                  .registeredClientRepository(clients)
                  .authorizationConsentService(consents)
                  .tokenGenerator(tokens)
                  .authorizationEndpoint(endpoint -> endpoint.consentPage("/oauth/consent"))
                  .clientAuthentication(
                      client ->
                          client
                              .authenticationConverter(
                                  new PublicNativeClientAuthentication.Converter())
                              .authenticationProvider(
                                  new PublicNativeClientAuthentication.Provider(clients))
                              .authenticationProviders(
                                  providers -> {
                                    providers.removeIf(
                                        provider ->
                                            !(provider
                                                    instanceof PublicClientAuthenticationProvider)
                                                && !(provider
                                                    instanceof
                                                    PublicNativeClientAuthentication.Provider));
                                    providers.add(
                                        new PublicNativeClientAuthentication.RejectUnsupported());
                                  }))
                  .tokenEndpoint(
                      endpoint ->
                          endpoint.authenticationProviders(
                              providers -> {
                                providers.removeIf(
                                    provider ->
                                        !(provider
                                                instanceof
                                                OAuth2AuthorizationCodeAuthenticationProvider)
                                            && !(provider
                                                instanceof
                                                OAuth2RefreshTokenAuthenticationProvider));
                                providers.replaceAll(
                                    provider ->
                                        new FrameworkGrantBoundary(
                                            provider,
                                            provider
                                                    instanceof
                                                    OAuth2AuthorizationCodeAuthenticationProvider
                                                ? FrameworkGrantBoundary.Kind.CODE
                                                : FrameworkGrantBoundary.Kind.REFRESH,
                                            authorizations,
                                            families,
                                            clientLocks,
                                            jdbc,
                                            transactions));
                              }))
                  .tokenRevocationEndpoint(
                      endpoint ->
                          endpoint.authenticationProviders(
                              providers ->
                                  providers.replaceAll(
                                      provider ->
                                          provider
                                                  instanceof
                                                  OAuth2TokenRevocationAuthenticationProvider
                                              ? new FrameworkGrantBoundary(
                                                  provider,
                                                  FrameworkGrantBoundary.Kind.REVOCATION,
                                                  authorizations,
                                                  families,
                                                  clientLocks,
                                                  jdbc,
                                                  transactions)
                                              : provider)))
                  .authorizationServerMetadataEndpoint(
                      endpoint ->
                          endpoint.authorizationServerMetadataCustomizer(
                              metadata ->
                                  metadata.claims(
                                      claims -> {
                                        claims.put(
                                            "grant_types_supported",
                                            List.of("authorization_code", "refresh_token"));
                                        claims.put("response_types_supported", List.of("code"));
                                        claims.put("response_modes_supported", List.of("query"));
                                        claims.put(
                                            "token_endpoint_auth_methods_supported",
                                            List.of("none"));
                                        claims.put(
                                            "revocation_endpoint_auth_methods_supported",
                                            List.of("none"));
                                        claims.put(
                                            "code_challenge_methods_supported", List.of("S256"));
                                        claims.put("scopes_supported", List.of("dav"));
                                        claims.remove("jwks_uri");
                                        claims.remove("tls_client_certificate_bound_access_tokens");
                                        claims.remove("dpop_signing_alg_values_supported");
                                        claims.remove("introspection_endpoint");
                                        claims.remove(
                                            "introspection_endpoint_auth_methods_supported");
                                        claims.remove("device_authorization_endpoint");
                                        claims.remove("pushed_authorization_request_endpoint");
                                      })));
            })
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
    // Before client authentication replaces a browser principal with the public client's identity.
    http.addFilterAfter(
        new ActiveAccountFilter(accounts),
        org.springframework.security.web.context.SecurityContextHolderFilter.class);
    http.addFilterAfter(
        new org.busystack.caldav.security.session.SessionEpochFilter(sessions),
        org.springframework.security.web.context.SecurityContextHolderFilter.class);
    http.addFilterBefore(
        new RecentMfaAuthorizationFilter(),
        org.springframework.security.web.authentication.logout.LogoutFilter.class);
    // Framework CSRF protection for the cookie-authenticated consent form. Token exchanges
    // retain the authorization server's protocol authentication and do not require a cookie.
    var consentCsrf =
        new org.springframework.security.web.csrf.CsrfFilter(
            new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository());
    consentCsrf.setBeanName("busyStackConsentCsrf");
    consentCsrf.setRequireCsrfProtectionMatcher(
        request ->
            "POST".equals(request.getMethod())
                && "/oauth2/authorize".equals(request.getServletPath()));
    http.addFilterBefore(
        consentCsrf, org.springframework.security.web.authentication.logout.LogoutFilter.class);
    http.headers(
        headers ->
            headers.contentSecurityPolicy(
                csp ->
                    csp.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'")));
    return http.build();
  }
}
