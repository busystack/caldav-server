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
  private final AdministrationService administration;

  public GroupManagementController(
      GroupService groups, SharingManagementService sharing, AdministrationService administration) {
    this.groups = groups;
    this.sharing = sharing;
    this.administration = administration;
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
  String createForm(@RequestParam(required = false) UUID returnCollection, Model model) {
    model.addAttribute("returnCollection", returnCollection);
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
    if (returnCollection != null) return "redirect:/collections/" + returnCollection + "/sharing";
    return "redirect:/admin/groups/" + id;
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
    model.addAttribute("lookupPage", administration.accounts(auth, q, "enabled", "all", page));
    model.addAttribute("query", q);
    return "group-member-lookup";
  }

  @PostMapping("/admin/groups/{id}/members")
  String add(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "") String username,
      @RequestParam(defaultValue = "false") boolean confirmed,
      @RequestParam(required = false) Long expectedGrants,
      RedirectAttributes flash) {
    var group = groups.detail(auth, id);
    groups.changeMember(
        auth,
        id,
        username,
        null,
        true,
        confirmed || group.grants() == 0,
        expectedGrants == null ? group.grants() : expectedGrants);
    flash.addFlashAttribute("notice", "Added " + username + " to " + group.name() + ".");
    return "redirect:/admin/groups/" + id;
  }

  @GetMapping("/admin/groups/{id}/members/{account}/remove")
  String confirmRemove(
      Authentication auth, @PathVariable UUID id, @PathVariable UUID account, Model model) {
    var group = groups.detail(auth, id);
    var member = groups.member(auth, id, account);
    model.addAttribute(
        "actionTitle", "Remove " + member.username() + " from " + group.name() + "?");
    model.addAttribute(
        "consequence",
        "Access granted by this group may end. Other direct or group grants continue to apply.");
    model.addAttribute("actionLabel", "Remove member");
    model.addAttribute("actionPath", "/admin/groups/" + id + "/members/" + account + "/remove");
    model.addAttribute("cancelPath", "/admin/groups/" + id);
    model.addAttribute("expectedGrants", group.grants());
    return "confirm-action";
  }

  @PostMapping("/admin/groups/{id}/members/{account}/remove")
  String remove(
      Authentication auth,
      @PathVariable UUID id,
      @PathVariable UUID account,
      @RequestParam(defaultValue = "false") boolean confirmed,
      @RequestParam(required = false) Long expectedGrants,
      RedirectAttributes flash) {
    var group = groups.detail(auth, id);
    var member = groups.member(auth, id, account);
    groups.changeMember(
        auth,
        id,
        null,
        account,
        false,
        confirmed,
        expectedGrants == null ? group.grants() : expectedGrants);
    flash.addFlashAttribute(
        "notice", "Removed " + member.username() + " from " + group.name() + ".");
    return "redirect:/admin/groups/" + id;
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
    sharing.group(auth, collection, group, permission, expected);
    flash.addFlashAttribute(
        "notice", "Group grant saved. Review effective permissions for remaining access.");
    return "redirect:/collections/" + collection + "/sharing";
  }
}
