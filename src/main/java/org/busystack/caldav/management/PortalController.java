package org.busystack.caldav.management;

import java.util.UUID;
import org.busystack.caldav.sharing.GroupService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PortalController {
  private final PortalService portal;
  private final GroupService groups;

  public PortalController(PortalService portal, GroupService groups) {
    this.portal = portal;
    this.groups = groups;
  }

  @GetMapping("/collections")
  String collections(
      Authentication auth,
      @RequestParam(defaultValue = "owned") String scope,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    model.addAttribute("collectionPage", portal.collections(auth, scope, q, page));
    model.addAttribute("scope", scope);
    model.addAttribute("query", q);
    return "collections";
  }

  @GetMapping("/collections/new")
  String create() {
    return "collection-new";
  }

  @GetMapping("/collections/{id}")
  String collection(Authentication auth, @PathVariable UUID id, Model model) {
    model.addAttribute("collection", portal.collection(auth, id));
    return "collection";
  }

  @PostMapping("/collections/{id}/edit")
  String edit(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam String state,
      @RequestParam(defaultValue = "") String name,
      @RequestParam(defaultValue = "") String description,
      @RequestParam(defaultValue = "") String color,
      RedirectAttributes flash) {
    portal.edit(auth, id, state, name, description, color);
    flash.addFlashAttribute(
        "notice",
        "Collection details saved. Existing client addresses and contents are preserved.");
    return "redirect:/collections/" + id;
  }

  @GetMapping("/collections/{id}/delete")
  String confirmDelete(Authentication auth, @PathVariable UUID id, Model model) {
    model.addAttribute("collection", portal.owner(auth, id));
    return "collection-delete";
  }

  @PostMapping("/collections/{id}/delete")
  String delete(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam String state,
      @RequestParam(defaultValue = "false") boolean confirmed,
      RedirectAttributes flash) {
    portal.delete(auth, id, state, confirmed);
    flash.addFlashAttribute(
        "notice", "Collection, its contents and its sharing access were deleted.");
    return "redirect:/collections";
  }

  @GetMapping("/collections/{id}/sharing")
  String sharing(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int directPage,
      @RequestParam(defaultValue = "0") int groupPage,
      @RequestParam(defaultValue = "0") int effectivePage,
      Model model) {
    model.addAttribute("collection", portal.owner(auth, id));
    model.addAttribute("directGrants", portal.direct(auth, id, directPage));
    model.addAttribute("groupGrants", portal.groupGrants(auth, id, groupPage));
    model.addAttribute("effectiveMembers", portal.effective(auth, id, effectivePage));
    model.addAttribute("groupChoices", groups.choices(auth));
    return "collection-sharing";
  }

  @GetMapping("/connections")
  String connections(Authentication auth, @RequestParam(defaultValue = "0") int page, Model model) {
    model.addAttribute("collectionPage", portal.collections(auth, "all", "", page));
    return "connections";
  }

  @GetMapping("/security")
  String security(Authentication auth, Model model) {
    model.addAttribute("setup", portal.setup(auth));
    return "security";
  }

  @GetMapping("/security/credentials")
  String credentials(Authentication auth, @RequestParam(defaultValue = "0") int page, Model model) {
    model.addAttribute("credentialPage", portal.credentials(auth, page));
    return "credentials";
  }
}
