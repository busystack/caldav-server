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
        "notice", "Collection created. Use Connections to configure your application.");
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
      Model model,
      jakarta.servlet.http.HttpServletResponse response) {
    model.addAttribute("credential", management.issue(auth, label, days));
    response.setHeader("Cache-Control", "no-store");
    return "issued-credential";
  }

  @PostMapping("/credentials/{id}/revoke")
  String revoke(Authentication auth, @PathVariable UUID id, RedirectAttributes flash) {
    management.revoke(auth, id);
    flash.addFlashAttribute(
        "notice",
        "DAV credential revoked. Configure a replacement credential in the affected application.");
    return "redirect:/security/credentials";
  }
}
