package org.busystack.caldav.management;

import java.util.UUID;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdministrationController {
  private final AdministrationService administration;

  public AdministrationController(AdministrationService administration) {
    this.administration = administration;
  }

  @GetMapping("/admin/accounts")
  String accounts(
      Authentication auth,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    model.addAttribute("accountPage", administration.accounts(auth, q, status, page));
    model.addAttribute("query", q);
    model.addAttribute("filter", status);
    return "accounts";
  }

  @GetMapping("/admin/accounts/new")
  String create() {
    return "account-new";
  }

  @GetMapping("/admin/accounts/{id}")
  String account(Authentication auth, @PathVariable UUID id, Model model) {
    model.addAttribute("account", administration.account(auth, id));
    return "account-detail";
  }

  @PostMapping("/admin/accounts")
  String provision(
      Authentication auth,
      @RequestParam(defaultValue = "") String username,
      @RequestParam(defaultValue = "") String displayName,
      @RequestParam(defaultValue = "") String password,
      RedirectAttributes flash) {
    UUID id = administration.create(auth, username, displayName, password);
    flash.addFlashAttribute(
        "notice",
        "Ordinary account created. Transfer the initial password securely; the user must enroll and verify their own passkey.");
    return "redirect:/admin/accounts/" + id;
  }

  @PostMapping("/admin/accounts/{id}/edit")
  String edit(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam String expected,
      @RequestParam(defaultValue = "") String displayName,
      RedirectAttributes flash) {
    administration.edit(auth, id, expected, displayName);
    flash.addFlashAttribute("notice", "Display name saved.");
    return "redirect:/admin/accounts/" + id;
  }

  @PostMapping("/admin/accounts/{id}/disable")
  String disable(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "false") boolean confirmed,
      RedirectAttributes flash) {
    administration.disable(auth, id, confirmed);
    flash.addFlashAttribute(
        "notice", "Account disabled. Browser sessions and client access were revoked.");
    return "redirect:/admin/accounts/" + id;
  }

  @GetMapping("/admin/audit")
  String audit(
      Authentication auth,
      @RequestParam(defaultValue = "") String action,
      @RequestParam(defaultValue = "") String outcome,
      @RequestParam(defaultValue = "") String username,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    model.addAttribute("auditPage", administration.audit(auth, action, outcome, username, page));
    model.addAttribute("actions", SecurityAudit.Action.values());
    model.addAttribute("outcomes", SecurityAudit.Outcome.values());
    model.addAttribute("action", action);
    model.addAttribute("outcome", outcome);
    model.addAttribute("username", username);
    return "audit";
  }
}
