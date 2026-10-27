package org.busystack.caldav.security;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class RecentAuthenticationTest {
  @Test
  void acceptsOnlyVerifiedUpstreamPrimaryProof() {
    var code =
        FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
    var passkey = FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY);
    var marker = new SimpleGrantedAuthority("BUSYSTACK_TRUSTED_UPSTREAM");
    assertThatCode(
            () ->
                RecentAuthentication.requireMfa(
                    UsernamePasswordAuthenticationToken.authenticated(
                        "user", null, List.of(code, marker, passkey))))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                RecentAuthentication.requireMfa(
                    UsernamePasswordAuthenticationToken.authenticated(
                        "user", null, List.of(code, passkey))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                RecentAuthentication.requireMfa(
                    UsernamePasswordAuthenticationToken.authenticated(
                        "user",
                        null,
                        List.of(
                            new SimpleGrantedAuthority(
                                FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY),
                            marker,
                            passkey))))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void requiresBothRealRecentFactors() {
    var password = FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY);
    var passkey = FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY);
    var complete =
        UsernamePasswordAuthenticationToken.authenticated("user", null, List.of(password, passkey));
    assertThatCode(() -> RecentAuthentication.requireMfa(complete)).doesNotThrowAnyException();
    var passwordOnly =
        UsernamePasswordAuthenticationToken.authenticated("user", null, List.of(password));
    assertThatThrownBy(() -> RecentAuthentication.requireMfa(passwordOnly))
        .isInstanceOf(AccessDeniedException.class);
    var fake =
        UsernamePasswordAuthenticationToken.authenticated(
            "user",
            null,
            List.of(
                password, new SimpleGrantedAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)));
    assertThatThrownBy(() -> RecentAuthentication.requireMfa(fake))
        .isInstanceOf(AccessDeniedException.class);
    var expired =
        FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
            .issuedAt(Instant.now().minusSeconds(601))
            .build();
    assertThatThrownBy(
            () ->
                RecentAuthentication.requireMfa(
                    UsernamePasswordAuthenticationToken.authenticated(
                        "user", null, List.of(password, expired))))
        .isInstanceOf(AccessDeniedException.class);
    var future =
        FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
            .issuedAt(Instant.now().plusSeconds(60))
            .build();
    assertThatThrownBy(
            () ->
                RecentAuthentication.requireMfa(
                    UsernamePasswordAuthenticationToken.authenticated(
                        "user", null, List.of(password, future))))
        .isInstanceOf(AccessDeniedException.class);
  }
}
