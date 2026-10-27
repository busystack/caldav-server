package org.busystack.caldav.security.passkey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.busystack.caldav.security.RecentAuthentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Recent factors, bounded bodies, and durable single-use ceremonies across session wrappers. */
public class PasskeyCeremonyGuard extends OncePerRequestFilter {
  private static final int MAX_JSON_BYTES = 65536;
  private final AccountPasskeyUsers users;
  private final CeremonyStore ceremonies;

  public PasskeyCeremonyGuard(AccountPasskeyUsers users, CeremonyStore ceremonies) {
    this.users = users;
    this.ceremonies = ceremonies;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    return !(path.startsWith("/webauthn/")
        || path.equals("/login/webauthn")
        || path.equals("/security/passkeys"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getServletPath();
    if (path.startsWith("/webauthn/register") || path.equals("/security/passkeys")) {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      try {
        RecentAuthentication.requirePrimary(authentication);
        if (users.hasCredentials(authentication.getName()))
          RecentAuthentication.requireMfa(authentication);
      } catch (AccessDeniedException ex) {
        if ("GET".equals(request.getMethod()))
          response.sendRedirect(request.getContextPath() + "/login?factor=required");
        else response.sendError(403, "Recent authentication is required");
        return;
      }
    }
    boolean post = "POST".equals(request.getMethod());
    if (post && (path.equals("/login/webauthn") || path.equals("/webauthn/register"))) {
      CeremonyStore.Purpose purpose =
          path.equals("/login/webauthn")
              ? CeremonyStore.Purpose.AUTHENTICATE
              : CeremonyStore.Purpose.REGISTER;
      var session = request.getSession(false);
      Object challenge =
          session == null
              ? null
              : session.getAttribute(ChallengeRecordingRequest.attribute(purpose));
      if (session == null
          || !(challenge instanceof String hash)
          || !ceremonies.consume(session.getId(), purpose, hash)) {
        response.sendError(
            path.equals("/login/webauthn") ? 401 : 400, "Challenge expired or already used");
        return;
      }
    }
    String encoding = request.getHeader("Content-Encoding");
    if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
      response.sendError(415, "Encoded authentication bodies are not supported");
      return;
    }
    if (request.getContentLengthLong() > MAX_JSON_BYTES) {
      response.sendError(413, "Authentication body exceeds 64 KiB");
      return;
    }
    String contentType = request.getContentType();
    String mediaType =
        contentType == null
            ? ""
            : contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
    boolean json =
        mediaType.equals("application/json")
            || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    HttpServletRequest processed = request;
    if (json) {
      byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
      if (body.length > MAX_JSON_BYTES) {
        response.sendError(413, "Authentication body exceeds 64 KiB");
        return;
      }
      processed = new BoundedJsonRequest(request, body);
    }
    if (post
        && (path.equals("/webauthn/authenticate/options")
            || path.equals("/webauthn/register/options")))
      processed = new ChallengeRecordingRequest(processed, ceremonies);
    chain.doFilter(processed, response);
  }
}
