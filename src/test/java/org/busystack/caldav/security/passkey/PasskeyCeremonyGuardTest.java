package org.busystack.caldav.security.passkey;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PasskeyCeremonyGuardTest {
  @Test
  void streamedJsonIsBoundedAndEncodedBodiesAreRejected() throws Exception {
    var guard =
        new PasskeyCeremonyGuard(mock(AccountPasskeyUsers.class), mock(CeremonyStore.class));
    var request = request("/webauthn/authenticate/options", new MockHttpSession());
    request.setContentType("application/json");
    request.setContent(new byte[65537]);
    request.addHeader("Transfer-Encoding", "chunked");
    var chunked =
        new jakarta.servlet.http.HttpServletRequestWrapper(request) {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };
    var oversized = new MockHttpServletResponse();
    var calls = new AtomicInteger();
    guard.doFilter(chunked, oversized, (q, s) -> calls.incrementAndGet());
    assertThat(oversized.getStatus()).isEqualTo(413);
    assertThat(calls).hasValue(0);
    request.setContent(new byte[0]);
    request.addHeader("Content-Encoding", "gzip");
    var encoded = new MockHttpServletResponse();
    guard.doFilter(request, encoded, (q, s) -> calls.incrementAndGet());
    assertThat(encoded.getStatus()).isEqualTo(415);
    assertThat(calls).hasValue(0);
  }

  @Test
  void frameworkCanReadBoundedJsonAndFormsKeepTheirParameters() throws Exception {
    var guard =
        new PasskeyCeremonyGuard(mock(AccountPasskeyUsers.class), mock(CeremonyStore.class));
    var request = request("/webauthn/authenticate/options", new MockHttpSession());
    request.setContentType("application/json");
    request.setContent(
        "{\"value\":\"calendar\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    guard.doFilter(
        request,
        new MockHttpServletResponse(),
        (q, s) -> assertThat(q.getReader().readLine()).isEqualTo("{\"value\":\"calendar\"}"));
    var form = request("/webauthn/authenticate/options", new MockHttpSession());
    form.setContentType("application/x-www-form-urlencoded");
    form.setParameter("_csrf", "test-csrf");
    guard.doFilter(
        form,
        new MockHttpServletResponse(),
        (q, s) -> {
          assertThat(q.getParameter("_csrf")).isEqualTo("test-csrf");
        });
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void firstEnrollmentRequiresPasswordAndSubsequentEnrollmentRequiresBothFactors()
      throws Exception {
    var users = mock(AccountPasskeyUsers.class);
    var guard = new PasskeyCeremonyGuard(users, mock(CeremonyStore.class));
    var calls = new AtomicInteger();
    var request = request("/webauthn/register/options", new MockHttpSession());
    var denied = new MockHttpServletResponse();
    guard.doFilter(request, denied, (q, s) -> calls.incrementAndGet());
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(calls).hasValue(0);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "owner",
                null,
                List.of(
                    FactorGrantedAuthority.fromAuthority(
                        FactorGrantedAuthority.PASSWORD_AUTHORITY))));
    var first = new MockHttpServletResponse();
    guard.doFilter(request, first, (q, s) -> calls.incrementAndGet());
    assertThat(calls).hasValue(1);
    when(users.hasCredentials("owner")).thenReturn(true);
    var additional = new MockHttpServletResponse();
    guard.doFilter(request, additional, (q, s) -> calls.incrementAndGet());
    assertThat(additional.getStatus()).isEqualTo(403);
    assertThat(calls).hasValue(1);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "owner",
                null,
                List.of(
                    FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY),
                    FactorGrantedAuthority.fromAuthority(
                        FactorGrantedAuthority.WEBAUTHN_AUTHORITY))));
    guard.doFilter(request, new MockHttpServletResponse(), (q, s) -> calls.incrementAndGet());
    assertThat(calls).hasValue(2);
  }

  @Test
  void frameworkChallengeIsRecordedAndDurableConsumeDecisionGatesAssertion() throws Exception {
    var store = mock(CeremonyStore.class);
    var guard = new PasskeyCeremonyGuard(mock(AccountPasskeyUsers.class), store);
    var session = new MockHttpSession();
    var challenge = org.springframework.security.web.webauthn.api.Bytes.random();
    var options =
        org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions.builder()
            .challenge(challenge)
            .rpId("calendar.example")
            .build();
    guard.doFilter(
        request("/webauthn/authenticate/options", session),
        new MockHttpServletResponse(),
        (q, s) ->
            ((jakarta.servlet.http.HttpServletRequest) q)
                .getSession()
                .setAttribute("framework.requestOptions", options));
    String hash =
        org.busystack.caldav.security.audit.SecurityHashes.sha256(
            java.util.Base64.getEncoder().encodeToString(challenge.getBytes()));
    verify(store).issue(session.getId(), CeremonyStore.Purpose.AUTHENTICATE, hash, null);
    when(store.consume(session.getId(), CeremonyStore.Purpose.AUTHENTICATE, hash))
        .thenReturn(true, false);
    var assertions = new AtomicInteger();
    guard.doFilter(
        request("/login/webauthn", session),
        new MockHttpServletResponse(),
        (q, s) -> assertions.incrementAndGet());
    var replay = new MockHttpServletResponse();
    guard.doFilter(
        request("/login/webauthn", session), replay, (q, s) -> assertions.incrementAndGet());
    assertThat(replay.getStatus()).isEqualTo(401);
    assertThat(assertions).hasValue(1);
  }

  private MockHttpServletRequest request(String path, MockHttpSession session) {
    var request = new MockHttpServletRequest("POST", path);
    request.setServletPath(path);
    request.setSession(session);
    return request;
  }
}
