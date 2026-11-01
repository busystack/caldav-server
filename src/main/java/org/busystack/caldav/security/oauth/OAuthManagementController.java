package org.busystack.caldav.security.oauth;

import jakarta.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OAuthManagementController {
  private final OAuthClientService clients;
  private final OAuthGrantService grants;

  public OAuthManagementController(OAuthClientService clients, OAuthGrantService grants) {
    this.clients = clients;
    this.grants = grants;
  }

  @GetMapping("/admin/oauth-clients")
  String clients(Authentication authentication, Model model) {
    model.addAttribute("clients", clients.list(authentication));
    return "oauth-clients";
  }

  @PostMapping("/admin/oauth-clients")
  String register(
      Authentication authentication,
      @RequestParam String clientId,
      @RequestParam String name,
      @RequestParam String redirects) {
    clients.registerNative(
        authentication,
        clientId,
        name,
        redirects
            .lines()
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toSet()));
    return "redirect:/admin/oauth-clients";
  }

  @PostMapping("/admin/oauth-clients/{id}/remove")
  String remove(Authentication authentication, @PathVariable String id) {
    clients.remove(authentication, id);
    return "redirect:/admin/oauth-clients";
  }

  @GetMapping("/oauth-grants")
  String grants(Authentication authentication, Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("grants", grants.list(authentication));
    return "oauth-grants";
  }

  @PostMapping("/oauth-grants/{id}/revoke")
  String revoke(Authentication authentication, @PathVariable String id) {
    grants.revoke(authentication, id);
    return "redirect:/oauth-grants";
  }

  @ExceptionHandler(IllegalArgumentException.class)
  String invalid(IllegalArgumentException failure, Model model, HttpServletResponse response) {
    response.setStatus(400);
    model.addAttribute("message", failure.getMessage());
    return "oauth-error";
  }
}
