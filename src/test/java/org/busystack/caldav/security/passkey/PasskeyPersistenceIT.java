package org.busystack.caldav.security.passkey;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.busystack.caldav.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.webauthn.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "busystack.public-base-url=https://calendar.example",
      "busystack.issuer=https://calendar.example"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class PasskeyPersistenceIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      org.busystack.caldav.testing.LoopbackContainers.postgres();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired AccountPasskeyUsers users;
  @Autowired AccountPasskeyCredentials credentials;
  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;
  PublicKeyCredentialUserEntity user;

  @BeforeEach
  void prepare() {
    org.busystack.caldav.testing.FixtureDatabase.clearAccounts(jdbc);
    jdbc.execute("TRUNCATE passkey_revocations");
    accounts.provision("owner", "Owner", "correct horse battery staple", false);
    user =
        ImmutablePublicKeyCredentialUserEntity.builder()
            .id(Bytes.random())
            .name("owner")
            .displayName("Owner")
            .build();
    users.save(user);
  }

  private CredentialRecord record() {
    var time = Instant.now().truncatedTo(ChronoUnit.MICROS);
    return ImmutableCredentialRecord.builder()
        .credentialId(Bytes.random())
        .userEntityUserId(user.getId())
        .publicKey(new ImmutablePublicKeyCose(new byte[] {1, 2, 3}))
        .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
        .signatureCount(1)
        .uvInitialized(true)
        .backupEligible(false)
        .backupState(false)
        .transports(Set.of(AuthenticatorTransport.INTERNAL))
        .label("Test key")
        .created(time)
        .lastUsed(time)
        .build();
  }

  @Test
  void jdbcPersistsKeyAndStableAccountLinkSurvivesUsernameChange() {
    var record = record();
    credentials.save(record);
    var reloaded = new AccountPasskeyCredentials(jdbc).findByCredentialId(record.getCredentialId());
    assertThat(reloaded.getPublicKey().getBytes()).containsExactly(1, 2, 3);
    assertThat(reloaded.getSignatureCount()).isEqualTo(1);
    jdbc.update("UPDATE accounts SET username='renamed' WHERE username='owner'");
    assertThat(new AccountPasskeyUsers(jdbc).findById(user.getId()).getName()).isEqualTo("renamed");
    assertThat(users.findByUsername("owner")).isNull();
    assertThat(users.findByUsername("renamed").getId()).isEqualTo(user.getId());
  }

  @Test
  void counterCannotRegressAndRemovedKeyCannotBeResurrectedByInflightAssertion() {
    var record = record();
    credentials.save(record);
    credentials.save(
        ImmutableCredentialRecord.fromCredentialRecord(record).signatureCount(5).build());
    assertThatThrownBy(() -> credentials.save(record)).isInstanceOf(BadCredentialsException.class);
    credentials.delete(record.getCredentialId());
    assertThat(credentials.findByCredentialId(record.getCredentialId())).isNull();
    assertThatThrownBy(() -> credentials.save(record)).isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void existingKeyCannotBeReboundToAnotherUserOrPublicKey() {
    var record = record();
    credentials.save(record);
    assertThatThrownBy(
            () ->
                credentials.save(
                    ImmutableCredentialRecord.fromCredentialRecord(record)
                        .publicKey(new ImmutablePublicKeyCose(new byte[] {4, 5, 6}))
                        .build()))
        .isInstanceOf(BadCredentialsException.class);
    assertThatThrownBy(
            () ->
                credentials.save(
                    ImmutableCredentialRecord.fromCredentialRecord(record)
                        .userEntityUserId(Bytes.random())
                        .build()))
        .isInstanceOf(BadCredentialsException.class);
    assertThatThrownBy(
            () ->
                credentials.save(
                    ImmutableCredentialRecord.fromCredentialRecord(record)
                        .backupEligible(true)
                        .build()))
        .isInstanceOf(BadCredentialsException.class);
    assertThatThrownBy(
            () ->
                credentials.save(
                    ImmutableCredentialRecord.fromCredentialRecord(record)
                        .uvInitialized(false)
                        .build()))
        .isInstanceOf(BadCredentialsException.class);
  }
}
