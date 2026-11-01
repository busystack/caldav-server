package org.busystack.caldav.security.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class SessionController {
  private final SessionService sessions;

  public SessionController(SessionService sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/security/sessions")
  String list(
      Authentication authentication,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response) {
    var session = request.getSession(false);
    model.addAttribute(
        "sessions", sessions.list(authentication, session == null ? null : session.getId()));
    response.setHeader("Cache-Control", "no-store");
    return "sessions";
  }

  @PostMapping("/security/sessions/{id}/revoke")
  String revoke(Authentication authentication, @PathVariable UUID id, HttpServletRequest request) {
    var session = request.getSession(false);
    if (sessions.revoke(
        authentication, id, session == null ? null : session.getId(), request.getRemoteAddr())) {
      SecurityContextHolder.clearContext();
      if (session != null) session.invalidate();
      return "redirect:/login?session=ended";
    }
    return "redirect:/security/sessions";
  }
}
