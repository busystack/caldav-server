package org.busystack.caldav.management;

import java.util.Set;
import java.util.UUID;
import org.busystack.caldav.sharing.GroupService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class GroupManagementController {
  private final GroupService groups;
  private final SharingManagementService sharing;
  private final PortalService portal;

  public GroupManagementController(
      GroupService groups, SharingManagementService sharing, PortalService portal) {
    this.groups = groups;
    this.sharing = sharing;
    this.portal = portal;
  }

  @GetMapping("/admin/groups")
  String list(
      Authentication auth,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    model.addAttribute("groupPage", groups.search(auth, q, page));
    model.addAttribute("query", q);
    return "groups";
  }

  @GetMapping("/admin/groups/new")
  String createForm(
      Authentication auth, @RequestParam(required = false) UUID returnCollection, Model model) {
    renderReturn(portal, auth, returnCollection, model);
    return "group-new";
  }

  @GetMapping("/admin/groups/{id}")
  String detail(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String member,
      Model model) {
    model.addAttribute("selectedMember", member.length() <= 64 ? member : "");
    model.addAttribute("group", groups.detail(auth, id));
    model.addAttribute("memberPage", groups.members(auth, id, q, page));
    model.addAttribute("query", q);
    return "group-detail";
  }

  @PostMapping("/admin/groups")
  String create(
      Authentication auth,
      @RequestParam(defaultValue = "") String name,
      @RequestParam(required = false) UUID returnCollection,
      RedirectAttributes flash) {
    UUID id = groups.create(auth, name, Set.of());
    flash.addFlashAttribute("notice", "Group created.");
    if (returnCollection != null) {
      var destination = sharingDestination(portal, auth, returnCollection);
      if (destination != null) return "redirect:/collections/" + destination.id() + "/sharing";
      flash.addFlashAttribute(
          "notice", "Group created. The original resource is no longer available for sharing.");
    }
    return "redirect:/admin/groups/" + id;
  }

  static void renderReturn(PortalService portal, Authentication auth, UUID id, Model model) {
    if (id == null) return;
    var collection = sharingDestination(portal, auth, id);
    if (collection == null) model.addAttribute("sharingReturnUnavailable", true);
    else {
      model.addAttribute("returnCollection", collection.id());
      model.addAttribute("returnCollectionName", collection.name());
    }
  }

  private static PortalService.CollectionView sharingDestination(
      PortalService portal, Authentication auth, UUID id) {
    try {
      return portal.owner(auth, id);
    } catch (org.busystack.caldav.storage.StoreException unavailable) {
      if (unavailable.kind() == org.busystack.caldav.storage.StoreException.Kind.FORBIDDEN
          || unavailable.kind() == org.busystack.caldav.storage.StoreException.Kind.NOT_FOUND)
        return null;
      throw unavailable;
    }
  }

  @PostMapping("/admin/groups/{id}")
  String rename(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam String expected,
      @RequestParam(defaultValue = "") String name,
      RedirectAttributes flash) {
    groups.rename(auth, id, expected, name);
    flash.addFlashAttribute("notice", "Group renamed.");
    return "redirect:/admin/groups/" + id;
  }

  @GetMapping("/admin/groups/{id}/members/new")
  String lookup(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    model.addAttribute("group", groups.detail(auth, id));
    model.addAttribute("lookupPage", groups.eligibleMembers(auth, id, q, page));
    model.addAttribute("query", q);
    return "group-member-lookup";
  }

  @PostMapping("/admin/groups/{id}/members")
  String add(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "") String username,
      @RequestParam(defaultValue = "false") boolean confirmed,
      @RequestParam(defaultValue = "") String expectedGrants,
      RedirectAttributes flash) {
    var group = groups.detail(auth, id);
    groups.changeMember(
        auth, id, username, null, true, confirmed || group.grants() == 0, expectedGrants);
    flash.addFlashAttribute("notice", "Added " + username + " to " + group.name() + ".");
    return "redirect:/admin/groups/" + id;
  }

  @GetMapping("/admin/groups/{id}/members/{account}/remove")
  String confirmRemove(
      Authentication auth,
      @PathVariable UUID id,
      @PathVariable UUID account,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    return memberRemoval(groups, auth, id, account, q, page, null, model);
  }

  static String memberRemoval(
      GroupService groups,
      Authentication auth,
      UUID id,
      UUID account,
      String q,
      int page,
      String expected,
      Model model) {
    var group = groups.detail(auth, id);
    var member = groups.member(auth, id, account);
    if (expected != null && !expected.equals(group.grantState()))
      throw new org.busystack.caldav.storage.StoreException(
          org.busystack.caldav.storage.StoreException.Kind.PRECONDITION);
    String parameters = memberSearch(q, page);
    model.addAttribute(
        "actionTitle", "Remove " + member.username() + " from " + group.name() + "?");
    model.addAttribute(
        "consequence",
        "Access granted by this group may end. Other direct or group grants continue to apply.");
    model.addAttribute("actionLabel", "Remove member");
    model.addAttribute(
        "actionPath", "/admin/groups/" + id + "/members/" + account + "/remove" + parameters);
    model.addAttribute("cancelPath", "/admin/groups/" + id + parameters);
    model.addAttribute("expectedGrants", group.grantState());
    return "confirm-action";
  }

  @PostMapping("/admin/groups/{id}/members/{account}/remove")
  String remove(
      Authentication auth,
      @PathVariable UUID id,
      @PathVariable UUID account,
      @RequestParam(defaultValue = "false") boolean confirmed,
      @RequestParam(defaultValue = "") String expectedGrants,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(required = false) String memberQuery,
      @RequestParam(required = false) Integer memberPage,
      RedirectAttributes flash) {
    var group = groups.detail(auth, id);
    var member = groups.member(auth, id, account);
    String parameters =
        memberSearch(memberQuery == null ? q : memberQuery, memberPage == null ? page : memberPage);
    if (!confirmed)
      return "redirect:/admin/groups/" + id + "/members/" + account + "/remove" + parameters;
    groups.changeMember(auth, id, null, account, false, true, expectedGrants);
    flash.addFlashAttribute(
        "notice", "Removed " + member.username() + " from " + group.name() + ".");
    return "redirect:/admin/groups/" + id + parameters;
  }

  private static String memberSearch(String query, int page) {
    PortalService.checkQuery(query);
    PortalPage.offset(page);
    var parameters = org.springframework.web.util.UriComponentsBuilder.newInstance();
    if (!query.isEmpty()) parameters.queryParam("q", query);
    if (page > 0) parameters.queryParam("page", page);
    return parameters.build().encode().toUriString();
  }

  @GetMapping("/admin/groups/{id}/delete")
  String confirmDelete(Authentication auth, @PathVariable UUID id, Model model) {
    var group = groups.detail(auth, id);
    model.addAttribute("actionTitle", "Delete " + group.name() + "?");
    model.addAttribute(
        "consequence",
        "This removes the group, its memberships and grants. The underlying resources remain. Access through other grants continues.");
    model.addAttribute("actionLabel", "Delete group");
    model.addAttribute("actionPath", "/admin/groups/" + id + "/delete");
    model.addAttribute("cancelPath", "/admin/groups/" + id);
    return "confirm-action";
  }

  @PostMapping("/admin/groups/{id}/delete")
  String delete(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "false") boolean confirmed,
      RedirectAttributes flash) {
    if (!confirmed)
      throw new IllegalArgumentException("Confirm deletion of the group and all its grants");
    groups.delete(auth, id);
    flash.addFlashAttribute(
        "notice", "Group and its collection grants deleted. Other grants remain effective.");
    return "redirect:/admin/groups";
  }

  @PostMapping("/group-shares")
  String share(
      Authentication auth,
      @RequestParam UUID collection,
      @RequestParam UUID group,
      @RequestParam(defaultValue = "") String permission,
      @RequestParam String expected,
      RedirectAttributes flash) {
    if ("REVOKE".equals(permission))
      return "redirect:/collections/" + collection + "/sharing/group/" + group + "/remove";
    sharing.group(auth, collection, group, permission, expected);
    flash.addFlashAttribute(
        "notice", "Group grant saved. Review effective permissions for remaining access.");
    return "redirect:/collections/" + collection + "/sharing";
  }
}
