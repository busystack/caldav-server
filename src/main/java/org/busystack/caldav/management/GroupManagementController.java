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

  public GroupManagementController(GroupService groups, SharingManagementService sharing) {
    this.groups = groups;
    this.sharing = sharing;
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

  @GetMapping("/admin/groups/{id}")
  String detail(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    model.addAttribute("group", groups.detail(auth, id));
    model.addAttribute("memberPage", groups.members(auth, id, q, page));
    model.addAttribute("query", q);
    return "group-detail";
  }

  @PostMapping("/admin/groups")
  String create(Authentication auth, @RequestParam String name, RedirectAttributes flash) {
    UUID id = groups.create(auth, name, Set.of());
    flash.addFlashAttribute("notice", "Group created. Add members individually below.");
    return "redirect:/admin/groups/" + id;
  }

  @PostMapping("/admin/groups/{id}")
  String rename(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam String expected,
      @RequestParam String name,
      RedirectAttributes flash) {
    groups.rename(auth, id, expected, name);
    flash.addFlashAttribute("notice", "Group renamed.");
    return "redirect:/admin/groups/" + id;
  }

  @PostMapping("/admin/groups/{id}/members")
  String add(
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam String username,
      @RequestParam(defaultValue = "false") boolean confirmed,
      RedirectAttributes flash) {
    groups.changeMember(auth, id, username, null, true, confirmed);
    flash.addFlashAttribute(
        "notice", "Member added. Existing group grants now apply to this account.");
    return "redirect:/admin/groups/" + id;
  }

  @PostMapping("/admin/groups/{id}/members/{account}/remove")
  String remove(
      Authentication auth,
      @PathVariable UUID id,
      @PathVariable UUID account,
      @RequestParam(defaultValue = "false") boolean confirmed,
      RedirectAttributes flash) {
    groups.changeMember(auth, id, null, account, false, confirmed);
    flash.addFlashAttribute(
        "notice",
        "Group membership removed. Other direct or group grants may still provide access.");
    return "redirect:/admin/groups/" + id;
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
      @RequestParam String permission,
      @RequestParam String expected,
      RedirectAttributes flash) {
    sharing.group(auth, collection, group, permission, expected);
    flash.addFlashAttribute(
        "notice", "Group grant saved. Review effective permissions for remaining access.");
    return "redirect:/collections/" + collection + "/sharing";
  }
}
