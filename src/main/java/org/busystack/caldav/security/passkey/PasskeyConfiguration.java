package org.busystack.caldav.security.passkey;

import java.util.Set;
import org.busystack.caldav.config.DeploymentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authorization.EnableMultiFactorAuthentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

@Configuration
@EnableMultiFactorAuthentication(authorities = {})
public class PasskeyConfiguration {
  @Bean
  AccountPasskeyUsers passkeyUsers(JdbcTemplate jdbc) {
    return new AccountPasskeyUsers(jdbc);
  }

  @Bean
  AccountPasskeyCredentials passkeyCredentials(JdbcTemplate jdbc) {
    return new AccountPasskeyCredentials(jdbc);
  }

  @Bean
  AccountRelyingPartyOperations relyingParty(
      AccountPasskeyUsers users,
      AccountPasskeyCredentials credentials,
      DeploymentProperties properties,
      org.busystack.caldav.account.AccountService accounts,
      org.busystack.caldav.security.session.SessionSecurity sessions) {
    var base = properties.publicBaseUrl();
    String origin = base.getScheme() + "://" + base.getRawAuthority();
    var updates = new VerifiedCredentialRepository(credentials);
    var operations =
        new Webauthn4JRelyingPartyOperations(
            users,
            updates,
            PublicKeyCredentialRpEntity.builder()
                .id(base.getHost())
                .name("BusyStack CalDAV")
                .build(),
            Set.of(origin));
    operations.setCustomizeCreationOptions(
        options ->
            options.authenticatorSelection(
                AuthenticatorSelectionCriteria.builder()
                    .residentKey(ResidentKeyRequirement.REQUIRED)
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .build()));
    operations.setCustomizeRequestOptions(
        options -> options.userVerification(UserVerificationRequirement.REQUIRED));
    return new AccountRelyingPartyOperations(
        operations,
        users,
        credentials,
        updates,
        org.busystack.caldav.security.oidc.OidcConfiguration.passkeyUsers(accounts),
        sessions);
  }

  public static void configure(
      HttpSecurity http,
      DeploymentProperties properties,
      AccountPasskeyUsers users,
      CeremonyStore ceremonies) {
    var base = properties.publicBaseUrl();
    http.webAuthn(
        web -> {
          web.rpId(base.getHost())
              .rpName("BusyStack CalDAV")
              .allowedOrigins(base.getScheme() + "://" + base.getRawAuthority())
              .disableDefaultRegistrationPage(true);
          web.addObjectPostProcessor(
              new ObjectPostProcessor<WebAuthnAuthenticationFilter>() {
                @Override
                public <O extends WebAuthnAuthenticationFilter> O postProcess(O filter) {
                  filter.setSessionAuthenticationStrategy(new SessionFixationProtectionStrategy());
                  return filter;
                }
              });
        });
    http.addFilterBefore(
        new PasskeyCeremonyGuard(users, ceremonies), UsernamePasswordAuthenticationFilter.class);
  }
}
