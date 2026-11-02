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

  public PortalPageAdvice(ManagementService management) {
    this.management = management;
  }

  @ModelAttribute
  void layout(Authentication auth, HttpServletRequest request, Model model) {
    String path = request.getServletPath();
    model.addAttribute("portalPath", path);
    model.addAttribute("administrationArea", path.startsWith("/admin"));
    model.addAttribute("viewer", null);
    model.addAttribute("primaryCurrent", RecentAuthentication.primaryCurrent(auth));
    model.addAttribute("mfaCurrent", RecentAuthentication.mfaCurrent(auth));
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      try {
        model.addAttribute("viewer", management.current(auth));
        model.addAttribute("externallyManaged", management.externallyManaged(auth));
      } catch (org.springframework.security.access.AccessDeniedException unavailable) {
        // Error pages must remain renderable after a disabled or ended session.
      }
    }
  }
}
