package org.busystack.caldav.account;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class InitializationController {
  private final InitializationService initialization;

  public InitializationController(InitializationService initialization) {
    this.initialization = initialization;
  }

  @GetMapping({"/setup", "/setup/administrator"})
  public String setup(
      jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response) {
    protect(response);
    if (!initialization.available()) return "redirect:/login";
    return request.getServletPath().endsWith("/administrator") ? "setup-administrator" : "setup";
  }

  @PostMapping("/setup/administrator")
  public String create(
      @RequestParam(defaultValue = "") String code,
      @RequestParam(defaultValue = "") String username,
      @RequestParam(defaultValue = "") String password,
      @RequestParam(defaultValue = "") String confirmation,
      Model model,
      HttpServletResponse response) {
    protect(response);
    var errors = initialization.initialize(code, username, password, confirmation);
    if (errors.isEmpty()) return "setup-complete";
    response.setStatus(400);
    model.addAttribute("errors", errors);
    model.addAttribute("username", username.length() <= 64 ? username : "");
    return "setup-administrator";
  }

  private static void protect(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }
}
