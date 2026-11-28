package org.busystack.caldav.management;

import java.util.UUID;
import org.busystack.caldav.storage.StoreException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
final class SharingReviewController {
  private static final String ROUTE =
      "/collections/{id}/sharing/{kind:account|group}/{target}/remove";
  private final SharingManagementService sharing;

  SharingReviewController(SharingManagementService sharing) {
    this.sharing = sharing;
  }

  @GetMapping(ROUTE)
  String confirm(
      Authentication auth,
      @PathVariable UUID id,
      @PathVariable String kind,
      @PathVariable String target,
      @RequestParam(defaultValue = "0") int directPage,
      @RequestParam(defaultValue = "0") int groupPage,
      @RequestParam(defaultValue = "0") int effectivePage,
      Model model) {
    return review(
        sharing, auth, id, kind, target, directPage, groupPage, effectivePage, null, model);
  }

  static String review(
      SharingManagementService sharing,
      Authentication auth,
      UUID id,
      String kind,
      String target,
      int directPage,
      int groupPage,
      int effectivePage,
      String expected,
      Model model) {
    boolean group = kind.equals("group");
    var grant =
        group
            ? sharing.groupRemoval(auth, id, UUID.fromString(target))
            : sharing.directRemoval(auth, id, account(target));
    if (expected != null) PortalService.checkState(expected, grant.permission());
    String back = sharingPath(id, directPage, groupPage, effectivePage);
    String path = removalPath(id, kind, target, directPage, groupPage, effectivePage);
    model.addAttribute(
        "actionTitle",
        "Remove " + (group ? "group" : "direct") + " access for " + grant.target() + "?");
    model.addAttribute(
        "consequence",
        "This removes the "
            + (grant.permission().equals("WRITE") ? "read and write" : "read-only")
            + " grant to "
            + grant.collection()
            + ". "
            + grant.consequence());
    model.addAttribute("actionLabel", group ? "Remove group grant" : "Remove direct grant");
    model.addAttribute("actionPath", path);
    model.addAttribute("cancelPath", back);
    model.addAttribute("expectedRevision", grant.permission());
    return "confirm-action";
  }

  @PostMapping(ROUTE)
  String remove(
      Authentication auth,
      @PathVariable UUID id,
      @PathVariable String kind,
      @PathVariable String target,
      @RequestParam(defaultValue = "0") int directPage,
      @RequestParam(defaultValue = "0") int groupPage,
      @RequestParam(defaultValue = "0") int effectivePage,
      @RequestParam(defaultValue = "false") boolean confirmed,
      @RequestParam(defaultValue = "") String expected,
      RedirectAttributes flash) {
    if (!confirmed)
      return "redirect:" + removalPath(id, kind, target, directPage, groupPage, effectivePage);
    if (!java.util.Set.of("READ", "WRITE").contains(expected))
      throw new StoreException(StoreException.Kind.PRECONDITION);
    boolean group = kind.equals("group");
    if (group) sharing.group(auth, id, UUID.fromString(target), "REVOKE", expected);
    else sharing.direct(auth, id, account(target), "REVOKE", expected);
    flash.addFlashAttribute(
        "notice",
        (group ? "Group" : "Direct")
            + " grant removed. Other grants continue to apply. Review effective permissions below.");
    return "redirect:" + sharingPath(id, directPage, groupPage, effectivePage);
  }

  private static String sharingPath(UUID id, int directPage, int groupPage, int effectivePage) {
    return "/collections/" + id + "/sharing" + pages(directPage, groupPage, effectivePage);
  }

  private static String removalPath(
      UUID id, String kind, String target, int directPage, int groupPage, int effectivePage) {
    String safeTarget = kind.equals("group") ? UUID.fromString(target).toString() : account(target);
    return "/collections/"
        + id
        + "/sharing/"
        + kind
        + "/"
        + safeTarget
        + "/remove"
        + pages(directPage, groupPage, effectivePage);
  }

  private static String pages(int directPage, int groupPage, int effectivePage) {
    PortalPage.offset(directPage);
    PortalPage.offset(groupPage);
    PortalPage.offset(effectivePage);
    if (directPage == 0 && groupPage == 0 && effectivePage == 0) return "";
    return "?directPage="
        + directPage
        + "&groupPage="
        + groupPage
        + "&effectivePage="
        + effectivePage;
  }

  private static String account(String username) {
    if (!username.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))
      throw new StoreException(StoreException.Kind.NOT_FOUND);
    return username;
  }
}
