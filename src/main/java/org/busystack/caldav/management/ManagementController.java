package org.busystack.caldav.management;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ManagementController {
  private final ManagementService management;

  public ManagementController(ManagementService management) {
    this.management = management;
  }

  @GetMapping("/")
  String dashboard(Authentication auth, Model model) {
    model.addAttribute("account", management.current(auth));
    model.addAttribute("externallyManaged", management.externallyManaged(auth));
    model.addAttribute("collections", management.collections(auth));
    model.addAttribute("credentials", management.credentials(auth));
    return "dashboard";
  }

  @PostMapping("/collections")
  String createCollection(
      Authentication auth, @RequestParam String name, @RequestParam String component) {
    management.createCollection(auth, name, component);
    return "redirect:/";
  }

  @PostMapping("/shares")
  String share(
      Authentication auth,
      @RequestParam UUID collection,
      @RequestParam String username,
      @RequestParam String permission) {
    management.share(auth, collection, username, permission);
    return "redirect:/";
  }

  @PostMapping("/credentials")
  String issue(
      Authentication auth,
      @RequestParam String label,
      @RequestParam int days,
      Model model,
      jakarta.servlet.http.HttpServletResponse response) {
    model.addAttribute("credential", management.issue(auth, label, days));
    response.setHeader("Cache-Control", "no-store");
    return "issued-credential";
  }

  @PostMapping("/credentials/{id}/revoke")
  String revoke(Authentication auth, @PathVariable UUID id) {
    management.revoke(auth, id);
    return "redirect:/";
  }

  @GetMapping("/admin")
  String admin(Authentication auth, Model model) {
    model.addAttribute("accounts", management.users(auth));
    return "administration";
  }

  @PostMapping("/admin/accounts")
  String provision(
      Authentication auth,
      @RequestParam String username,
      @RequestParam String displayName,
      @RequestParam String password) {
    management.provision(auth, username, displayName, password);
    return "redirect:/admin";
  }

  @PostMapping("/admin/accounts/{id}/disable")
  String disable(Authentication auth, @PathVariable UUID id) {
    management.disable(auth, id);
    return "redirect:/admin";
  }
}
