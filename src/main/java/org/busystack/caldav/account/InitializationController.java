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

  @GetMapping("/setup")
  public String setup(HttpServletResponse response) {
    protect(response);
    if (!initialization.available()) return "redirect:/login";
    return "setup";
  }

  @GetMapping("/setup/administrator")
  public String legacy() {
    return "redirect:/setup";
  }

  @GetMapping("/help/setup")
  public String help() {
    return "setup-help";
  }

  @PostMapping("/setup/administrator")
  public String create(
      @RequestParam(defaultValue = "") String code,
      @RequestParam(defaultValue = "") String username,
      @RequestParam(defaultValue = "") String password,
      @RequestParam(defaultValue = "") String confirmation,
      Model model,
      HttpServletResponse response,
      org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
    protect(response);
    var errors = initialization.initialize(code, username, password, confirmation);
    if (errors.isEmpty()) {
      flash.addFlashAttribute("notice", "Administrator account created. Sign in to continue.");
      flash.addFlashAttribute("initialUsername", username);
      return "redirect:/login";
    }
    response.setStatus(400);
    model.addAttribute("fieldErrors", errors);
    model.addAttribute("errorFormKey", "setup");
    model.addAttribute("username", username.length() <= 64 ? username : "");
    return "setup";
  }

  private static void protect(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }
}
