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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SessionController {
  private final SessionService sessions;
  private final BrowserSession browserSessions;

  public SessionController(SessionService sessions, BrowserSession browserSessions) {
    this.sessions = sessions;
    this.browserSessions = browserSessions;
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

  @PostMapping("/session/stay")
  @org.springframework.web.bind.annotation.ResponseBody
  java.util.Map<String, Long> stay(HttpServletRequest request, HttpServletResponse response) {
    var session = request.getSession(false);
    var overall = browserSessions.overallDeadline(request);
    if (!BrowserSession.completed(request) || session == null || overall == null)
      throw new org.springframework.security.access.AccessDeniedException(
          "Completed browser session required");
    response.setHeader("Cache-Control", "no-store");
    long now = System.currentTimeMillis();
    return java.util.Map.of(
        "serverTime",
        now,
        "idleDeadline",
        session.getLastAccessedTime() + session.getMaxInactiveInterval() * 1000L,
        "overallDeadline",
        overall.toEpochMilli());
  }

  @GetMapping("/security/sessions/{id}/revoke")
  String confirmRevoke(
      Authentication auth, @PathVariable UUID id, HttpServletRequest request, Model model) {
    var session = request.getSession(false);
    var selected =
        sessions.list(auth, session == null ? null : session.getId()).stream()
            .filter(item -> item.id().equals(id))
            .findFirst()
            .orElseThrow(
                () ->
                    new org.busystack.caldav.storage.StoreException(
                        org.busystack.caldav.storage.StoreException.Kind.NOT_FOUND));
    model.addAttribute(
        "actionTitle",
        selected.current() ? "End this browser session?" : "End this other browser session?");
    model.addAttribute(
        "consequence",
        selected.current()
            ? "This signs you out of this browser. Sign in again to return."
            : "The other browser will need to sign in again. This browser stays signed in.");
    model.addAttribute("actionLabel", "End browser session");
    model.addAttribute("actionPath", "/security/sessions/" + id + "/revoke");
    model.addAttribute("cancelPath", "/security/sessions");
    return "confirm-action";
  }

  @PostMapping("/security/sessions/{id}/revoke")
  String revoke(
      Authentication authentication,
      @PathVariable UUID id,
      HttpServletRequest request,
      RedirectAttributes flash,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false")
          boolean confirmed) {
    if (!confirmed) return "redirect:/security/sessions/" + id + "/revoke";
    var session = request.getSession(false);
    if (sessions.revoke(
        authentication, id, session == null ? null : session.getId(), request.getRemoteAddr())) {
      SecurityContextHolder.clearContext();
      if (session != null) session.invalidate();
      return "redirect:/login?session=ended";
    }
    flash.addFlashAttribute(
        "notice", "The requested owned session has ended or is already unavailable.");
    return "redirect:/security/sessions";
  }
}
