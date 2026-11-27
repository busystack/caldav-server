package org.busystack.caldav.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.busystack.caldav.security.session.BrowserSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Browser admission, independently from service-level target authorization. */
public final class BrowserAccessFilter extends OncePerRequestFilter {
  private static final Set<String> SIGN_IN_PATHS =
      Set.of(
          "/login",
          "/logout",
          "/auth/continue",
          "/auth/state",
          "/login/webauthn",
          "/webauthn/authenticate/options",
          "/webauthn/register/options",
          "/webauthn/register",
          "/security/recovery/use");
  private static final Set<String> PUBLIC_PATHS =
      Set.of(
          "/setup",
          "/setup/administrator",
          "/help/setup",
          "/.well-known/caldav",
          "/error",
          "/actuator/health",
          "/actuator/health/liveness",
          "/actuator/health/readiness");

  private enum Admission {
    OPEN,
    COMPLETED,
    FRESH
  }

  private final BrowserSession sessions;
  private final AuthenticationJourney journey;

  public BrowserAccessFilter(BrowserSession sessions, AuthenticationJourney journey) {
    this.sessions = sessions;
    this.journey = journey;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getServletPath();
    if (PUBLIC_PATHS.contains(path) || path.startsWith("/assets/")) {
      chain.doFilter(request, response);
      return;
    }
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      chain.doFilter(request, response);
      return;
    }
    var state = sessions.state(request, auth);
    if (state == BrowserSession.State.EXPIRED) {
      SecurityContextHolder.clearContext();
      request.getSession(false).invalidate();
      response.setHeader("Cache-Control", "no-store");
      if ("GET".equals(request.getMethod())) {
        String flow = journey.start(request, destination(request), null);
        response.sendRedirect(request.getContextPath() + "/login?session&flow=" + flow);
      } else {
        response.setHeader("X-BusyStack-Auth-State", "session-ended");
        response.sendError(403, "Your browser session ended. Sign in again.");
      }
      return;
    }
    boolean completed = state == BrowserSession.State.CURRENT;
    request.setAttribute(BrowserSession.CURRENT, completed);
    Admission admission = admission(request, path);
    if (admission == Admission.OPEN
        || completed
            && (admission == Admission.COMPLETED || RecentAuthentication.mfaCurrent(auth))) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    response.setHeader(
        "X-BusyStack-Auth-State",
        RecentAuthentication.primaryCurrent(auth) ? "verification-required" : "primary-required");
    if ("GET".equals(request.getMethod())) {
      String flow = journey.start(request, destination(request), auth);
      response.sendRedirect(request.getContextPath() + "/auth/continue?flow=" + flow);
    } else if (completed && navigate(request) && "POST".equals(request.getMethod())) {
      var draft = BrowserFormDrafts.capture(request);
      String target = BrowserFormDrafts.returnPath(request);
      if (draft != null || target != null) {
        String flow =
            journey.start(
                request, request.getContextPath() + (target == null ? "/" : target), auth, draft);
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", request.getContextPath() + "/auth/continue?flow=" + flow);
      } else response.sendError(403, "Confirm your sign-in, then return to review this action.");
    } else {
      response.sendError(403, "Confirm your sign-in, then return to review this action.");
    }
  }

  private static Admission admission(HttpServletRequest request, String path) {
    if (SIGN_IN_PATHS.contains(path)
        || path.startsWith("/oauth2/authorization/")
        || path.startsWith("/login/oauth2/code/")) return Admission.OPEN;
    boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    if (path.equals("/session/stay") || path.equals("/welcome")) return Admission.COMPLETED;
    if (!read
        || path.startsWith("/portability/import/")
        || path.endsWith("/export")
        || path.equals("/oauth/consent")
        || path.equals("/security/identities/link")
        || path.equals("/portability/verify")
        || path.equals("/security/passkeys/add")
        || path.startsWith("/security/passkeys/remove/")) return Admission.FRESH;
    return Admission.COMPLETED;
  }

  private static boolean navigate(HttpServletRequest request) {
    String accept = request.getHeader("Accept");
    return "navigate".equals(request.getHeader("Sec-Fetch-Mode"))
        || accept != null && accept.contains("text/html");
  }

  public static String destination(HttpServletRequest request) {
    String query = request.getQueryString();
    return request.getRequestURI() + (query == null ? "" : "?" + query);
  }
}
