package org.busystack.caldav.security.password;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PasswordController {
  private final AccountPasswordService passwords;

  public PasswordController(AccountPasswordService passwords) {
    this.passwords = passwords;
  }

  @GetMapping("/security/password")
  String page() {
    return "password";
  }

  @PostMapping("/security/password")
  String change(
      Authentication authentication,
      @RequestParam String password,
      @RequestParam String confirmation,
      HttpServletRequest request) {
    if (!password.equals(confirmation))
      throw new IllegalArgumentException("The new password entries must match");
    passwords.change(authentication, password, request.getRemoteAddr());
    SecurityContextHolder.clearContext();
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    return "redirect:/login?password=changed";
  }

  @ExceptionHandler(IllegalArgumentException.class)
  String invalid(IllegalArgumentException failure, Model model, HttpServletResponse response) {
    response.setStatus(400);
    model.addAttribute("error", failure.getMessage());
    return "password";
  }
}
