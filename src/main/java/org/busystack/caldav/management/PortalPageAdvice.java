package org.busystack.caldav.management;

import jakarta.servlet.http.HttpServletRequest;
import org.busystack.caldav.security.RecentAuthentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Shared presentation only: authorization remains in the domain services. */
@ControllerAdvice(annotations = Controller.class)
public class PortalPageAdvice {
  private final ManagementService management;
  private org.busystack.caldav.security.session.BrowserSession browserSessions;

  public PortalPageAdvice(ManagementService management) {
    this.management = management;
  }

  @org.springframework.beans.factory.annotation.Autowired
  void configureBrowserSessions(
      org.busystack.caldav.security.session.BrowserSession browserSessions) {
    this.browserSessions = browserSessions;
  }

  @ModelAttribute
  public void layout(Authentication auth, HttpServletRequest request, Model model) {
    String path = request.getServletPath();
    if (path.equals("/dav") || path.startsWith("/dav/") || path.startsWith("/.well-known/")) return;
    model.addAttribute("portalPath", path);
    model.addAttribute(
        "authenticationNext",
        request.getContextPath()
            + path
            + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
    model.addAttribute("administrationArea", path.equals("/admin") || path.startsWith("/admin/"));
    model.addAttribute("section", section(path, request.getParameter("from")));
    model.addAttribute("applicationShell", false);
    model.addAttribute("viewer", null);
    model.addAttribute("primaryCurrent", RecentAuthentication.primaryCurrent(auth));
    model.addAttribute("mfaCurrent", RecentAuthentication.mfaCurrent(auth));
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      try {
        model.addAttribute("viewer", management.current(auth));
        boolean authPage =
            path.equals("/login")
                || path.startsWith("/auth/")
                || path.equals("/security/recovery/use")
                || path.startsWith("/setup")
                || path.equals("/help/setup");
        model.addAttribute(
            "applicationShell",
            !authPage && org.busystack.caldav.security.session.BrowserSession.completed(request));
        if (browserSessions != null
            && org.busystack.caldav.security.session.BrowserSession.completed(request)) {
          var session = request.getSession(false);
          var overall = browserSessions.overallDeadline(request);
          if (session != null && overall != null) {
            model.addAttribute(
                "idleDeadline",
                java.time.Instant.ofEpochMilli(session.getLastAccessedTime())
                    .plusSeconds(session.getMaxInactiveInterval())
                    .toEpochMilli());
            model.addAttribute("overallDeadline", overall.toEpochMilli());
            model.addAttribute("serverTime", System.currentTimeMillis());
          }
        }
        model.addAttribute("externallyManaged", management.externallyManaged(auth));
        model.addAttribute("hasPasskeys", management.hasPasskeys(auth));
      } catch (org.springframework.security.access.AccessDeniedException
          | org.springframework.dao.DataAccessException unavailable) {
        // Safe error layouts remain renderable after account revocation or a database outage.
      }
    }
  }

  private static String section(String path, String origin) {
    if (path.equals("/admin")) return "admin-overview";
    if (path.startsWith("/admin/accounts")) return "accounts";
    if (path.startsWith("/admin/groups")) return "groups";
    if (path.startsWith("/admin/audit") || path.startsWith("/admin/oauth-clients")) return "audit";
    if (path.startsWith("/admin")) return "diagnostics";
    if (path.startsWith("/collections")
        || path.startsWith("/portability")
        || path.equals("/shares")
        || path.equals("/group-shares")) return "collections";
    if (path.startsWith("/connections")
        || path.startsWith("/credentials") && "connect".equals(origin)
        || path.startsWith("/security/credentials") && "connect".equals(origin))
      return "connections";
    return path.equals("/") ? "overview" : "security";
  }
}
