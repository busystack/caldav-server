package org.busystack.caldav.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;

class BrowserSessionTest {
  @Test
  void validProofExpiresExactlyAtEightHoursAndRequiresTheOriginalLiveKey() {
    var jdbc = mock(JdbcTemplate.class);
    var sessions = mock(SessionSecurity.class);
    var account = new SessionSecurity.Account(UUID.randomUUID(), "reader", UUID.randomUUID());
    var now = Instant.parse("2026-01-01T00:00:00Z");
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            account.username(),
            null,
            List.of(
                FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
                    .issuedAt(now)
                    .build()));
    when(sessions.assertCurrent(auth)).thenReturn(account);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("verified"), eq(account.id())))
        .thenReturn(true);
    var request = new MockHttpServletRequest();
    var initial = new BrowserSession(jdbc, sessions, Clock.fixed(now, ZoneOffset.UTC));
    assertThat(initial.state(request, auth)).isEqualTo(BrowserSession.State.INCOMPLETE);
    initial.complete(request, auth, "verified");
    assertThat(initial.overallDeadline(request)).isEqualTo(now.plus(BrowserSession.MAX_AGE));
    var before =
        new BrowserSession(
            jdbc,
            sessions,
            Clock.fixed(now.plus(BrowserSession.MAX_AGE).minusNanos(1), ZoneOffset.UTC));
    assertThat(before.state(request, auth)).isEqualTo(BrowserSession.State.CURRENT);
    var boundary =
        new BrowserSession(
            jdbc, sessions, Clock.fixed(now.plus(BrowserSession.MAX_AGE), ZoneOffset.UTC));
    assertThat(boundary.state(request, auth)).isEqualTo(BrowserSession.State.EXPIRED);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("verified"), eq(account.id())))
        .thenReturn(false);
    assertThat(before.state(request, auth)).isEqualTo(BrowserSession.State.KEY_REMOVED);
    assertThat(before.state(request, auth)).isEqualTo(BrowserSession.State.INCOMPLETE);
  }
}
