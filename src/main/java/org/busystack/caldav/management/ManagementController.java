package org.busystack.caldav.management;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ManagementController {
  private final ManagementService management;
  private final PortalService portal;
  private final SharingManagementService sharing;

  public ManagementController(
      ManagementService management, PortalService portal, SharingManagementService sharing) {
    this.management = management;
    this.portal = portal;
    this.sharing = sharing;
  }

  @GetMapping("/")
  String dashboard(Authentication auth, Model model) {
    model.addAttribute("account", management.current(auth));
    model.addAttribute("setup", portal.setup(auth));
    model.addAttribute("recentCollections", portal.collections(auth, "all", "", 0));
    return "dashboard";
  }

  @PostMapping("/welcome")
  String welcome(Authentication auth, @RequestParam String activity) {
    if (!java.util.Set.of("personal", "users").contains(activity))
      throw new IllegalArgumentException("Choose an activity");
    if (activity.equals("users")) management.requireAdministrator(auth);
    management.completeWelcome(auth);
    return activity.equals("users")
        ? "redirect:/admin/accounts"
        : "redirect:/collections?scope=all";
  }

  @PostMapping("/collections")
  String createCollection(
      Authentication auth,
      @RequestParam(defaultValue = "") String name,
      @RequestParam(defaultValue = "") String component,
      @RequestParam(defaultValue = "") String description,
      @RequestParam(defaultValue = "") String color,
      RedirectAttributes flash) {
    UUID id = portal.create(auth, name, component, description, color);
    flash.addFlashAttribute(
        "notice",
        switch (component) {
          case "VTODO" -> "Task list created.";
          case "VJOURNAL" -> "Journal created.";
          default -> "Calendar created.";
        });
    return "redirect:/collections/" + id;
  }

  @PostMapping("/shares")
  String share(
      Authentication auth,
      @RequestParam UUID collection,
      @RequestParam(defaultValue = "") String username,
      @RequestParam(defaultValue = "") String permission,
      @RequestParam String expected,
      RedirectAttributes flash) {
    if ("REVOKE".equals(permission)) {
      if (!username.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
        throw FormValidation.field("username", "Choose the exact username of the recipient.");
      return "redirect:/collections/" + collection + "/sharing/account/" + username + "/remove";
    }
    sharing.direct(auth, collection, username, permission, expected);
    flash.addFlashAttribute(
        "notice",
        "Direct grant saved. Other grants may continue to provide access; review effective permissions below.");
    return "redirect:/collections/" + collection + "/sharing";
  }

  @PostMapping("/credentials")
  String issue(
      Authentication auth,
      @RequestParam(defaultValue = "") String label,
      @RequestParam int days,
      @RequestParam(defaultValue = "generic") String client,
      @RequestParam(defaultValue = "security") String from,
      @RequestParam(required = false) UUID intent,
      @RequestParam(required = false) UUID resource,
      Model model,
      jakarta.servlet.http.HttpServletResponse response) {
    model.addAttribute("client", PortalController.client(client));
    model.addAttribute("label", label);
    model.addAttribute("credentialOrigin", PortalController.credentialOrigin(from));
    UUID selected = PortalController.credentialResource(from, resource);
    PortalController.connectionResource(portal, auth, selected, model);
    var result =
        management.issue(
            auth, label, days, intent, PortalController.issuanceContext(client, from, selected));
    org.busystack.caldav.security.IssuancePages.protect(response);
    if (result.secret() == null) {
      model.addAttribute("issuedId", result.credentialId());
      model.addAttribute(
          "issuanceMessage",
          "This request already created an application password. Its original value is only shown once. Review your passwords before creating a replacement.");
      return "issuance-receipt";
    }
    model.addAttribute("credential", result.secret());
    return "issued-credential";
  }

  @GetMapping("/credentials/{id}/revoke")
  String confirmRevoke(Authentication auth, @PathVariable UUID id, Model model) {
    var credential = management.credential(auth, id);
    model.addAttribute("actionTitle", "Revoke " + credential.label() + "?");
    model.addAttribute(
        "consequence",
        "Apps using this password will lose access the next time they connect. Other application passwords remain available.");
    model.addAttribute("actionLabel", "Revoke application password");
    model.addAttribute("actionPath", "/credentials/" + id + "/revoke");
    model.addAttribute("cancelPath", "/security/credentials");
    return "confirm-action";
  }

  @PostMapping("/credentials/{id}/revoke")
  String revoke(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "false") boolean confirmed,
      RedirectAttributes flash) {
    if (!confirmed) return "redirect:/credentials/" + id + "/revoke";
    management.revoke(auth, id);
    flash.addFlashAttribute(
        "notice",
        "DAV credential revoked. Configure a replacement credential in the affected application.");
    return "redirect:/security/credentials";
  }
}
