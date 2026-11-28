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
  private final org.busystack.caldav.security.IssuanceIntents intents;

  public PortalController(
      PortalService portal,
      GroupService groups,
      org.busystack.caldav.security.IssuanceIntents intents) {
    this.portal = portal;
    this.groups = groups;
    this.intents = intents;
  }

  @GetMapping("/collections")
  String collections(
      Authentication auth,
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    model.addAttribute("collectionPage", portal.collections(auth, scope, q, page));
    model.addAttribute("scope", scope);
    model.addAttribute("setup", portal.setup(auth));
    model.addAttribute("query", q);
    return "collections";
  }

  @GetMapping("/collections/new")
  String create(@RequestParam(defaultValue = "VEVENT") String component, Model model) {
    model.addAttribute(
        "selectedComponent",
        org.busystack.caldav.calendar.CalendarContent.supportsCollectionComponent(component)
            ? component
            : "VEVENT");
    return "collection-new";
  }

  @GetMapping("/collections/{id}")
  String collection(Authentication auth, @PathVariable UUID id, Model model) {
    model.addAttribute("collection", portal.collection(auth, id));
    model.addAttribute("hasItems", portal.hasItems(auth, id));
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
  String connections(
      Authentication auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(required = false) String client,
      @RequestParam(required = false) UUID resource,
      Model model) {
    if (client != null) model.addAttribute("client", client(client));
    connectionResource(portal, auth, resource, model);
    model.addAttribute("collectionPage", portal.collections(auth, "all", "", page));
    return "connections";
  }

  @GetMapping("/security")
  String security(Authentication auth, Model model) {
    model.addAttribute("setup", portal.setup(auth));
    return "security";
  }

  @GetMapping("/security/credentials")
  String credentials(
      Authentication auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "generic") String client,
      @RequestParam(defaultValue = "security") String from,
      @RequestParam(required = false) UUID resource,
      Model model,
      jakarta.servlet.http.HttpServletResponse response) {
    model.addAttribute("client", client(client));
    model.addAttribute("credentialOrigin", credentialOrigin(from));
    UUID selected = credentialResource(from, resource);
    connectionResource(portal, auth, selected, model);
    if (page == 0)
      model.addAttribute(
          "issuanceIntent",
          intents
              .prepare(
                  auth,
                  org.busystack.caldav.security.IssuanceIntents.Purpose.APPLICATION_PASSWORD,
                  issuanceContext(client, from, selected))
              .id());
    org.busystack.caldav.security.IssuancePages.protect(response);
    model.addAttribute("credentialPage", portal.credentials(auth, page));
    return "credentials";
  }

  static String credentialOrigin(String from) {
    return "connect".equals(from) ? "connect" : "security";
  }

  static UUID credentialResource(String from, UUID resource) {
    return "connect".equals(from) ? resource : null;
  }

  static void connectionResource(
      PortalService portal, Authentication auth, UUID resource, Model model) {
    if (resource == null) return;
    model.addAttribute("connectionResource", portal.collection(auth, resource));
    model.addAttribute("connectionResourceId", resource);
  }

  static String issuanceContext(String client, String from, UUID resource) {
    String context = client(client) + ":" + credentialOrigin(from);
    return resource == null ? context : context + ":" + resource;
  }

  static String client(String client) {
    return java.util.Set.of("thunderbird", "gnome", "generic").contains(client)
        ? client
        : "generic";
  }
}
