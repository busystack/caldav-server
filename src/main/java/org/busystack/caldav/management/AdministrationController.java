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
  private final org.busystack.caldav.config.DeploymentProperties deployment;

  public AdministrationController(
      AdministrationService administration,
      org.busystack.caldav.config.DeploymentProperties deployment) {
    this.administration = administration;
    this.deployment = deployment;
  }

  @GetMapping("/admin/accounts")
  String accounts(
      Authentication auth,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(defaultValue = "all") String method,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    if (java.util.Set.of("local", "external").contains(status))
      return "redirect:/admin/accounts?method="
          + status
          + org.springframework.web.util.UriComponentsBuilder.newInstance()
              .queryParam("q", q)
              .queryParam("page", page)
              .build()
              .encode()
              .toUriString()
              .replaceFirst("^\\?", "&");
    model.addAttribute("accountPage", administration.accounts(auth, q, status, method, page));
    model.addAttribute("signInMethod", method);
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
    model.addAttribute("signInUrl", deployment.publicBaseUrl() + "/login");
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
    flash.addFlashAttribute("notice", "Account created.");
    flash.addFlashAttribute("newAccount", id);
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

  @GetMapping("/admin/accounts/{id}/disable")
  String confirmDisable(Authentication auth, @PathVariable UUID id, Model model) {
    var account = administration.account(auth, id);
    if (!account.enabled() || account.username().equals(auth.getName()))
      throw new org.springframework.security.access.AccessDeniedException(
          "This account cannot be disabled here.");
    model.addAttribute("account", account);
    return "account-disable";
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
