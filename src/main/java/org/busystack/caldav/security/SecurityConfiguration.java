package org.busystack.caldav.security;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.busystack.caldav.account.AccountService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.StrictHttpFirewall;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
  @Bean
  UserDetailsService browserUsers(AccountService accounts) {
    return username -> {
      var account = accounts.find(username);
      if (account == null
          || account.passwordHash() == null
          || !account.enabled()
          || accounts.isExternallyManaged(account.id()))
        throw new UsernameNotFoundException("Invalid account");
      return User.withUsername(account.username())
          .password(account.passwordHash())
          .disabled(!account.enabled())
          .authorities(
              new SimpleGrantedAuthority(account.administrator() ? "ROLE_ADMIN" : "ROLE_USER"),
              org.busystack.caldav.security.session.SessionSecurity.authority(
                  account.securityEpoch()))
          .build();
    };
  }

  @Bean
  WebSecurityCustomizer firewall() {
    var firewall = new StrictHttpFirewall();
    // The DAV parser validates decoded segments once, including traversal and encoded separators.
    firewall.setAllowUrlEncodedPercent(true);
    firewall.setAllowUrlEncodedPeriod(true);
    firewall.setAllowedHttpMethods(
        List.of(
            "GET",
            "HEAD",
            "POST",
            "OPTIONS",
            "PUT",
            "DELETE",
            "PROPFIND",
            "PROPPATCH",
            "REPORT",
            "MKCALENDAR",
            "MKCOL",
            "COPY",
            "MOVE",
            "ACL"));
    return web -> web.httpFirewall(firewall);
  }

  @Bean
  @Order(1)
  SecurityFilterChain davSecurity(
      HttpSecurity http,
      AccountService accounts,
      org.busystack.caldav.config.DeploymentProperties deployment,
      org.springframework.beans.factory.ObjectProvider<
              org.springframework.security.oauth2.server.resource.introspection
                  .OpaqueTokenIntrospector>
          introspectors)
      throws Exception {
    AuthenticationProvider provider =
        new AuthenticationProvider() {
          @Override
          public Authentication authenticate(Authentication auth) {
            if (!accounts.verifyDav(auth.getName(), String.valueOf(auth.getCredentials())))
              throw new BadCredentialsException("Invalid DAV credential");
            var account = accounts.find(auth.getName());
            return UsernamePasswordAuthenticationToken.authenticated(
                account.id().toString(), null, List.of(new SimpleGrantedAuthority("DAV")));
          }

          @Override
          public boolean supports(Class<?> type) {
            return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
          }
        };
    http.securityMatcher("/dav/**")
        .authenticationManager(
            new org.springframework.security.authentication.ProviderManager(provider))
        .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("DAV"))
        .httpBasic(
            basic ->
                basic
                    .realmName("BusyStack DAV")
                    .authenticationEntryPoint(
                        (request, response, ex) -> {
                          response.setHeader(
                              "WWW-Authenticate",
                              "Basic realm=\"BusyStack DAV\", charset=\"UTF-8\"");
                          response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                        }))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        // Every DAV mutation also requires the non-browser Origin boundary below.
        .csrf(csrf -> csrf.disable());
    http.addFilterBefore(
        new DavOriginFilter(deployment),
        org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class);
    var introspector = introspectors.getIfAvailable();
    if (introspector != null)
      http.oauth2ResourceServer(
          oauth ->
              oauth
                  .authenticationEntryPoint(new DavBearerAuthenticationEntryPoint())
                  .opaqueToken(
                      opaque ->
                          opaque.authenticationManager(
                              new org.springframework.security.authentication.ProviderManager(
                                  new org.springframework.security.oauth2.server.resource
                                      .authentication.OpaqueTokenAuthenticationProvider(
                                      introspector)))));
    return http.build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain browserSecurity(
      HttpSecurity http,
      AccountService accounts,
      org.busystack.caldav.config.DeploymentProperties deployment,
      org.busystack.caldav.security.passkey.AccountPasskeyUsers passkeyUsers,
      org.busystack.caldav.security.passkey.CeremonyStore ceremonies,
      org.busystack.caldav.security.session.SessionSecurity sessions,
      org.busystack.caldav.security.throttle.LoginThrottleService throttle,
      org.springframework.beans.factory.ObjectProvider<
              org.busystack.caldav.security.oidc.OidcLoginSupport>
          upstreamUsers,
      org.springframework.beans.factory.ObjectProvider<
              org.springframework.security.oauth2.client.registration.ClientRegistrationRepository>
          upstreamRegistrations)
      throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/.well-known/caldav",
                        "/assets/**",
                        "/login",
                        "/login/webauthn",
                        "/oauth2/authorization/**",
                        "/login/oauth2/code/**",
                        "/webauthn/authenticate/options",
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/error")
                    .permitAll()
                    .requestMatchers("/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .formLogin(login -> login.loginPage("/login").permitAll())
        // This endpoint only returns a configured 308; it has no cookie-authenticated mutation.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/.well-known/caldav"))
        .logout(logout -> logout.invalidateHttpSession(true).deleteCookies("JSESSIONID"))
        .sessionManagement(session -> session.sessionFixation(fixation -> fixation.newSession()))
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    csp ->
                        csp.policyDirectives(
                            "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'")));
    http.addFilterBefore(
        new ActiveAccountFilter(accounts),
        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
    http.addFilterAfter(
        new org.busystack.caldav.security.session.SessionEpochFilter(sessions),
        org.springframework.security.web.context.SecurityContextHolderFilter.class);
    http.addFilterBefore(
        new org.busystack.caldav.security.throttle.LoginThrottleFilter(throttle),
        org.springframework.security.web.authentication.logout.LogoutFilter.class);
    http.setSharedObject(
        UserDetailsService.class,
        org.busystack.caldav.security.oidc.OidcConfiguration.passkeyUsers(accounts));
    org.busystack.caldav.security.passkey.PasskeyConfiguration.configure(
        http, deployment, passkeyUsers, ceremonies);
    var upstream = upstreamUsers.getIfAvailable();
    if (upstream != null)
      org.busystack.caldav.security.oidc.OidcConfiguration.configure(
          http, upstreamRegistrations.getObject(), upstream);
    return http.build();
  }
}
