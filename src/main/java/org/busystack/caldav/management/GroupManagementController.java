package org.busystack.caldav.management;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.busystack.caldav.sharing.GroupService;
import org.busystack.caldav.storage.StoreException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Group administration and owner-controlled sharing use separate service authorization rules. */
@Controller
public class GroupManagementController {
  public record GroupForm(UUID id, String name, String usernames, int memberCount) {}

  private final GroupService groups;

  public GroupManagementController(GroupService groups) {
    this.groups = groups;
  }

  @GetMapping("/admin/groups")
  String list(Authentication authentication, Model model) {
    model.addAttribute(
        "groups",
        groups.list(authentication).stream()
            .map(
                group ->
                    new GroupForm(
                        group.id(),
                        group.name(),
                        group.members().stream()
                            .map(GroupService.Member::username)
                            .collect(Collectors.joining("\n")),
                        group.members().size()))
            .toList());
    return "groups";
  }

  @PostMapping("/admin/groups")
  String create(
      Authentication authentication,
      @RequestParam String name,
      @RequestParam(defaultValue = "") String usernames) {
    groups.create(authentication, name, members(usernames));
    return "redirect:/admin/groups";
  }

  @PostMapping("/admin/groups/{id}")
  String update(
      Authentication authentication,
      @PathVariable UUID id,
      @RequestParam String name,
      @RequestParam(defaultValue = "") String usernames) {
    groups.update(authentication, id, name, members(usernames));
    return "redirect:/admin/groups";
  }

  @PostMapping("/admin/groups/{id}/delete")
  String delete(Authentication authentication, @PathVariable UUID id) {
    groups.delete(authentication, id);
    return "redirect:/admin/groups";
  }

  @PostMapping("/group-shares")
  String share(
      Authentication authentication,
      @RequestParam UUID collection,
      @RequestParam UUID group,
      @RequestParam String permission) {
    groups.grant(authentication, collection, group, permission);
    return "redirect:/";
  }

  @ExceptionHandler(IllegalArgumentException.class)
  String invalid(HttpServletResponse response, Model model) {
    response.setStatus(400);
    model.addAttribute(
        "message",
        "Check the group name and account usernames. Use a name of 1–200 characters and at most"
            + " 1,000 active account usernames, one per line. Sharing requires a valid owned"
            + " collection, group and access choice.");
    return "group-error";
  }

  @ExceptionHandler(StoreException.class)
  String storeFailure(StoreException error, HttpServletResponse response, Model model) {
    int status =
        switch (error.kind()) {
          case NOT_FOUND -> 404;
          case FORBIDDEN -> 403;
          case LIMIT -> 507;
          default -> 409;
        };
    response.setStatus(status);
    model.addAttribute(
        "message",
        switch (error.kind()) {
          case NOT_FOUND -> "The requested collection no longer exists.";
          case FORBIDDEN -> "Only the collection owner can change its group access.";
          case LIMIT -> "This request exceeds the supported group or sharing limits.";
          default ->
              "The request conflicts with the current collection or group state. Refresh the page"
                  + " before trying again.";
        });
    return "group-error";
  }

  private static Set<String> members(String usernames) {
    if (usernames.length() > 65536) throw new IllegalArgumentException("Member list too large");
    var result = new LinkedHashSet<String>();
    usernames
        .lines()
        .map(String::strip)
        .filter(value -> !value.isEmpty())
        .forEach(
            value -> {
              if (result.size() >= 1000 && !result.contains(value))
                throw new IllegalArgumentException("Too many group members");
              result.add(value);
            });
    return Set.copyOf(result);
  }
}
