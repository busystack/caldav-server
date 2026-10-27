package org.busystack.caldav.security.oidc;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.busystack.caldav.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "busystack.public-base-url=https://calendar.example",
          "busystack.issuer=https://calendar.example",
      "busystack.oidc.enabled=true", "busystack.oidc.issuer=https://identity.example/realms/team",
      "busystack.oidc.client-id=fixture", "busystack.oidc.client-secret=fixture-only-secret",
      "busystack.oidc.allowed-users[0].subject=subject-one",
          "busystack.oidc.allowed-users[0].username=allowed",
      "busystack.oidc.allowed-users[0].display-name=Allowed user"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ExternalIdentityIT {
  private static final String ISSUER = "https://identity.example/realms/team";

  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired ExternalIdentityService identities;
  @Autowired ExternalAccessPolicy policy;
  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;

  @Autowired
  org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
      registrations;

  @BeforeEach
  void reset() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    if (registrations.findById("fixture-client") == null)
      registrations.save(
          org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId(
                  "fixture-client")
              .clientId("fixture-client")
              .clientAuthenticationMethod(
                  org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
              .authorizationGrantType(
                  org.springframework.security.oauth2.core.AuthorizationGrantType
                      .AUTHORIZATION_CODE)
              .authorizationGrantType(
                  org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN)
              .redirectUri("http://127.0.0.1:34567/callback")
              .scope("dav")
              .clientSettings(
                  org.springframework.security.oauth2.server.authorization.settings.ClientSettings
                      .builder()
                      .requireProofKey(true)
                      .requireAuthorizationConsent(true)
                      .build())
              .tokenSettings(
                  org.springframework.security.oauth2.server.authorization.settings.TokenSettings
                      .builder()
                      .accessTokenFormat(
                          org.springframework.security.oauth2.server.authorization.settings
                              .OAuth2TokenFormat.REFERENCE)
                      .reuseRefreshTokens(false)
                      .build())
              .build());
  }

  @Test
  void exactIssuerSubjectProvisioningIsClosedAndNeverAssignsAdministrator() {
    var account = identities.verifiedLogin(ISSUER, "subject-one", Instant.now(), null, null);
    assertThat(account.username()).isEqualTo("allowed");
    assertThat(account.administrator()).isFalse();
    assertThat(account.passwordHash()).isNull();
    assertThat(identities.verifiedLogin(ISSUER, "subject-one", Instant.now(), null, null).id())
        .isEqualTo(account.id());
    assertThatThrownBy(
            () ->
                identities.verifiedLogin(
                    "https://attacker.example/realms/team",
                    "subject-one",
                    Instant.now(),
                    null,
                    null))
        .isInstanceOf(OAuth2AuthenticationException.class);
    assertThatThrownBy(() -> identities.verifiedLogin(ISSUER, "unknown", Instant.now(), null, null))
        .isInstanceOf(OAuth2AuthenticationException.class);
    assertThatThrownBy(
            () ->
                identities.verifiedLogin(
                    ISSUER, "subject-one", Instant.now().minusSeconds(301), null, null))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  @Test
  void existingAccountNamesRequireExplicitRecentMfaLinking() {
    var id = accounts.provision("allowed", "Existing admin", "correct horse battery staple", true);
    assertThatThrownBy(
            () -> identities.verifiedLogin(ISSUER, "subject-one", Instant.now(), null, null))
        .isInstanceOf(OAuth2AuthenticationException.class);
    var auth = mfa("allowed");
    var link = identities.prepareLink(auth);
    var linked = identities.verifiedLogin(ISSUER, "subject-one", Instant.now(), link, auth);
    assertThat(linked.id()).isEqualTo(id);
    assertThat(linked.administrator()).isTrue();
    assertThat(linked.passwordHash()).isNull();
    assertThatThrownBy(
            () -> identities.verifiedLogin(ISSUER, "different-subject", Instant.now(), link, auth))
        .isInstanceOf(OAuth2AuthenticationException.class);
    assertThatThrownBy(
            () ->
                identities.verifiedLogin(
                    ISSUER,
                    "subject-one",
                    Instant.now(),
                    new ExternalIdentityService.LinkIntent(
                        id, "allowed", Instant.now().minusSeconds(301)),
                    auth))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  @Test
  void externalFreshnessAndLocalDisablingAreDurable() {
    var account = identities.verifiedLogin(ISSUER, "subject-one", Instant.now(), null, null);
    assertThat(policy.isCurrent(account.id())).isTrue();
    jdbc.update(
        "UPDATE external_identities SET last_verified_at=now()-interval '25 hours' WHERE account_id=?",
        account.id());
    assertThat(policy.isCurrent(account.id())).isFalse();
    identities.verifiedLogin(ISSUER, "subject-one", Instant.now(), null, null);
    assertThat(policy.isCurrent(account.id())).isTrue();
    accounts.disable(account.id());
    assertThatThrownBy(
            () -> identities.verifiedLogin(ISSUER, "subject-one", Instant.now(), null, null))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  @Test
  void externalRecoveryRequiresTheExistingPairAndRevokesRestoredAccessBeforeReauthentication() {
    var account = identities.verifiedLogin(ISSUER, "subject-one", Instant.now(), null, null);
    var credential =
        accounts.issueDavCredential(
            account.id(), "Recovery fixture", Instant.now().plusSeconds(3600));
    jdbc.update(
        "INSERT INTO oauth2_authorization(id,registered_client_id,principal_name,authorization_grant_type) VALUES ('recovery-fixture','fixture-client',?,'authorization_code')",
        account.username());
    jdbc.update(
        "INSERT INTO oauth2_authorization_consent(registered_client_id,principal_name,authorities) VALUES ('fixture-client',?,'SCOPE_dav')",
        account.username());
    jdbc.update(
        "INSERT INTO oauth_refresh_families(authorization_id,account_id,client_id,recovery_epoch,expires_at) SELECT 'recovery-fixture',?,'fixture-client',recovery_epoch,now()+interval '1 hour' FROM system_state WHERE id=1",
        account.id());
    jdbc.update(
        "INSERT INTO user_entities(id,account_id,name,display_name) VALUES ('cmVjb3ZlcnktdXNlcg',?,?,'Recovery fixture')",
        account.id(),
        account.username());
    jdbc.update(
        "INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,backup_state,label) VALUES ('cmVjb3Zlcnkta2V5','cmVjb3ZlcnktdXNlcg',decode('00','hex'),0,true,false,false,'Recovery fixture')");
    accounts.disable(account.id());
    assertThatThrownBy(
            () -> accounts.recoverExternalOffline(account.username(), ISSUER, "wrong-subject"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(accounts.find(account.id()).enabled()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM dav_credentials WHERE account_id=?",
                Long.class,
                account.id()))
        .isEqualTo(1);
    accounts.recoverExternalOffline(account.username(), ISSUER, "subject-one");
    assertThat(accounts.find(account.id()).enabled()).isTrue();
    assertThat(accounts.find(account.id()).passwordHash()).isNull();
    assertThat(policy.isCurrent(account.id())).isFalse();
    assertThat(accounts.verifyDav(account.username(), credential.secret())).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE id='recovery-fixture'",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization_consent WHERE principal_name=?",
                Long.class,
                account.username()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth_refresh_families WHERE authorization_id='recovery-fixture'",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_credentials WHERE credential_id='cmVjb3Zlcnkta2V5'",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM passkey_revocations WHERE credential_id='cmVjb3Zlcnkta2V5'",
                Long.class))
        .isEqualTo(1);
    assertThat(identities.verifiedLogin(ISSUER, "subject-one", Instant.now(), null, null).id())
        .isEqualTo(account.id());
    assertThat(policy.isCurrent(account.id())).isTrue();
  }

  private Authentication mfa(String username) {
    return UsernamePasswordAuthenticationToken.authenticated(
        username,
        null,
        List.of(
            FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY),
            FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY),
            org.busystack.caldav.security.session.SessionSecurity.authority(
                accounts.find(username).securityEpoch())));
  }
}
