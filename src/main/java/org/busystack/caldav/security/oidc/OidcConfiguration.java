package org.busystack.caldav.security.oidc;

import jakarta.servlet.http.HttpServletRequest;
import org.busystack.caldav.account.AccountService;
import org.busystack.caldav.config.DeploymentProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;

@Configuration
public class OidcConfiguration {
  @Configuration
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnProperty(name = "busystack.oidc.enabled", havingValue = "true")
  static class EnabledOidc {
    @Bean
    ClientRegistrationRepository upstreamRegistration(
        OidcProperties properties, DeploymentProperties deployment) {
      return new ConfiguredOidcRegistrationRepository(properties, deployment);
    }

    @Bean
    OidcLoginSupport upstreamUsers(
        ExternalIdentityService identities,
        OidcProperties properties,
        ObjectProvider<HttpServletRequest> requests) {
      return new OidcLoginSupport(identities, properties, requests);
    }
  }

  @Bean
  ProviderNavigation providerNavigation(
      org.busystack.caldav.security.BrowserFlowStore store,
      org.busystack.caldav.security.AuthenticationJourney journey) {
    return new ProviderNavigation(store, journey);
  }

  public void configure(
      HttpSecurity http,
      ClientRegistrationRepository registrations,
      OidcLoginSupport users,
      org.busystack.caldav.security.BrowserFlowStore flows,
      org.busystack.caldav.security.AuthenticationJourney journey)
      throws Exception {
    var resolver =
        new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
    resolver.setAuthorizationRequestCustomizer(
        builder -> {
          OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
          builder.additionalParameters(
              parameters -> {
                parameters.put("prompt", "login");
                parameters.put("max_age", 0);
              });
        });
    var repository = new HttpSessionOAuth2AuthorizedClientRepository();
    http.oauth2Login(
        login ->
            login
                .loginPage("/login")
                .authorizationEndpoint(
                    endpoint ->
                        endpoint
                            .authorizationRequestResolver(resolver)
                            .authorizationRequestRepository(providerNavigation(flows, journey)))
                .userInfoEndpoint(endpoint -> endpoint.oidcUserService(users))
                .authorizedClientRepository(repository)
                .failureHandler(
                    (request, response, failure) -> {
                      if (ProviderNavigation.link(request) != null) linkEnded(request, response);
                      else journey.primaryFailure(request, response, failure);
                    })
                .successHandler(
                    (request, response, authentication) -> {
                      // This service uses upstream OIDC only for login, so discard access/refresh
                      // tokens.
                      repository.removeAuthorizedClient(
                          "upstream", authentication, request, response);
                      journey.primarySuccess(request, response, authentication);
                    })
                .withObjectPostProcessor(
                    new org.springframework.security.config.ObjectPostProcessor<
                        org.springframework.security.oauth2.client.web
                            .OAuth2AuthorizationRequestRedirectFilter>() {
                      @Override
                      public <
                              T extends
                                  org.springframework.security.oauth2.client.web
                                          .OAuth2AuthorizationRequestRedirectFilter>
                          T postProcess(T filter) {
                        filter.setAuthenticationFailureHandler(
                            (request, response, failure) -> {
                              if (request.getParameter("link") != null)
                                linkEnded(request, response);
                              else journey.primaryFailure(request, response, failure);
                            });
                        return filter;
                      }
                    }));
  }

  private static void linkEnded(
      HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response)
      throws java.io.IOException {
    response.setHeader("Cache-Control", "no-store");
    response.sendRedirect(request.getContextPath() + "/login?error&linkEnded");
  }

  /** Used only by WebAuthn; the form-password provider retains the local-only service. */
  public static UserDetailsService passkeyUsers(AccountService accounts) {
    return username -> {
      var account = accounts.find(username);
      if (account == null || !account.enabled() || !accounts.isClientAccessCurrent(account.id()))
        throw new UsernameNotFoundException("Account unavailable");
      return User.withUsername(account.username())
          .password(account.passwordHash() == null ? "" : account.passwordHash())
          .authorities(
              new org.springframework.security.core.authority.SimpleGrantedAuthority(
                  account.administrator() ? "ROLE_ADMIN" : "ROLE_USER"),
              org.busystack.caldav.security.session.SessionSecurity.authority(
                  account.securityEpoch()))
          .build();
    };
  }
}
