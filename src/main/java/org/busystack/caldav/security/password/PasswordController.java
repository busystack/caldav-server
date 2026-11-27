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
  private final org.busystack.caldav.management.PortalPageAdvice pages;

  public PasswordController(
      AccountPasswordService passwords, org.busystack.caldav.management.PortalPageAdvice pages) {
    this.passwords = passwords;
    this.pages = pages;
  }

  @GetMapping("/security/password")
  String page() {
    return "password";
  }

  @PostMapping("/security/password")
  String change(
      Authentication authentication,
      @RequestParam(defaultValue = "") String password,
      @RequestParam(defaultValue = "") String confirmation,
      HttpServletRequest request) {
    if (!password.equals(confirmation))
      throw org.busystack.caldav.management.FormValidation.field(
          "confirmation", "Enter the same password in both fields.");
    passwords.change(authentication, password, request.getRemoteAddr());
    SecurityContextHolder.clearContext();
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    return "redirect:/login?password=changed";
  }

  @ExceptionHandler(IllegalArgumentException.class)
  String invalid(
      IllegalArgumentException failure,
      Model model,
      HttpServletResponse response,
      HttpServletRequest request,
      Authentication auth) {
    response.setStatus(400);
    pages.layout(auth, request, model);
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute(
        "fieldErrors",
        failure instanceof org.busystack.caldav.management.FormValidation validation
            ? validation.errors()
            : java.util.Map.of(
                "password", "Use at least 14 Unicode characters and at most 72 UTF-8 bytes."));
    model.addAttribute("errorFormKey", "password-change");
    return "password";
  }
}
