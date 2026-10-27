package org.busystack.caldav.security.recovery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RecoveryController {
  private final RecoveryService recovery;

  public RecoveryController(RecoveryService recovery) {
    this.recovery = recovery;
  }

  @GetMapping("/security/recovery")
  String page(Authentication authentication, Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("status", recovery.status(authentication));
    return "recovery";
  }

  @PostMapping("/security/recovery/codes")
  String issue(
      Authentication authentication,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response) {
    model.addAttribute("issued", recovery.issue(authentication, request.getRemoteAddr()));
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    return "recovery-codes";
  }

  @PostMapping("/security/recovery/use")
  String use(Authentication authentication, @RequestParam String code, HttpServletRequest request) {
    recovery.recover(authentication, code, request.getRemoteAddr());
    SecurityContextHolder.clearContext();
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    return "redirect:/login?recovered";
  }
}
