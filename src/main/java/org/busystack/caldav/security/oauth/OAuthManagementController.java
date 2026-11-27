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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class OAuthManagementController {
  private final OAuthClientService clients;
  private final OAuthGrantService grants;
  private final org.busystack.caldav.management.PortalPageAdvice pages;

  public OAuthManagementController(
      OAuthClientService clients,
      OAuthGrantService grants,
      org.busystack.caldav.management.PortalPageAdvice pages) {
    this.clients = clients;
    this.grants = grants;
    this.pages = pages;
  }

  @GetMapping("/admin/oauth-clients")
  String clients(Authentication authentication, Model model) {
    model.addAttribute("clients", clients.list(authentication));
    return "oauth-clients";
  }

  @PostMapping("/admin/oauth-clients")
  String register(
      Authentication auth,
      @RequestParam(defaultValue = "") String clientId,
      @RequestParam(defaultValue = "") String name,
      @RequestParam(defaultValue = "") String redirects,
      RedirectAttributes flash,
      Model model,
      jakarta.servlet.http.HttpServletRequest request,
      HttpServletResponse response) {
    try {
      clients.registerNative(
          auth,
          clientId,
          name,
          redirects
              .lines()
              .map(String::strip)
              .filter(value -> !value.isEmpty())
              .collect(Collectors.toSet()));
    } catch (org.busystack.caldav.management.FormValidation invalid) {
      response.setStatus(invalid.conflict() ? 409 : 400);
      pages.layout(auth, request, model);
      model.addAttribute("fieldErrors", invalid.errors());
      model.addAttribute("errorFormKey", "native-app");
      model.addAttribute("clientId", clientId.length() <= 100 ? clientId : "");
      model.addAttribute("clientName", name.length() <= 100 ? name : "");
      model.addAttribute("redirects", redirects.length() <= 950 ? redirects : "");
      return clients(auth, model);
    }
    flash.addFlashAttribute("notice", "Application registration saved.");
    return "redirect:/admin/oauth-clients";
  }

  @GetMapping("/admin/oauth-clients/{id}/remove")
  String confirmRemove(Authentication auth, @PathVariable String id, Model model) {
    var client = clients.registration(auth, id);
    model.addAttribute("actionTitle", "Remove " + client.name() + "?");
    model.addAttribute(
        "consequence",
        "All user grants for this application will be revoked. Existing connections using its authorization will stop working.");
    model.addAttribute("actionLabel", "Remove application registration");
    model.addAttribute("actionPath", "/admin/oauth-clients/" + id + "/remove");
    model.addAttribute("cancelPath", "/admin/oauth-clients");
    model.addAttribute("expectedRevision", client.revision());
    return "confirm-action";
  }

  @PostMapping("/admin/oauth-clients/{id}/remove")
  String remove(
      Authentication auth,
      @PathVariable String id,
      RedirectAttributes flash,
      @RequestParam(defaultValue = "false") boolean confirmed,
      @RequestParam(defaultValue = "") String expected) {
    if (!confirmed) return "redirect:/admin/oauth-clients/" + id + "/remove";
    clients.remove(auth, id, expected);
    flash.addFlashAttribute(
        "notice", "Application registration removed. All associated grants were revoked.");
    return "redirect:/admin/oauth-clients";
  }

  @GetMapping("/oauth-grants")
  String grants(Authentication authentication, Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("grants", grants.list(authentication));
    return "oauth-grants";
  }

  @GetMapping("/oauth-grants/{id}/revoke")
  String confirmRevoke(Authentication auth, @PathVariable String id, Model model) {
    var grant =
        grants.list(auth).stream()
            .filter(item -> item.id().equals(id))
            .findFirst()
            .orElseThrow(
                () ->
                    new org.busystack.caldav.storage.StoreException(
                        org.busystack.caldav.storage.StoreException.Kind.NOT_FOUND));
    model.addAttribute("actionTitle", "Revoke access for " + grant.clientName() + "?");
    model.addAttribute(
        "consequence",
        "This grant will stop working. To connect again, approve access from the application.");
    model.addAttribute("actionLabel", "Revoke app access");
    model.addAttribute("actionPath", "/oauth-grants/" + id + "/revoke");
    model.addAttribute("cancelPath", "/oauth-grants");
    return "confirm-action";
  }

  @PostMapping("/oauth-grants/{id}/revoke")
  String revoke(
      Authentication authentication,
      @PathVariable String id,
      RedirectAttributes flash,
      @RequestParam(defaultValue = "false") boolean confirmed) {
    if (!confirmed) return "redirect:/oauth-grants/" + id + "/revoke";
    grants.revoke(authentication, id);
    flash.addFlashAttribute("notice", "Matching owned application grants are revoked.");
    return "redirect:/oauth-grants";
  }

  @ExceptionHandler(IllegalArgumentException.class)
  String invalid(IllegalArgumentException failure, Model model, HttpServletResponse response) {
    response.setStatus(400);
    model.addAttribute("message", failure.getMessage());
    return "oauth-error";
  }
}
