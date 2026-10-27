package org.busystack.caldav.management;

import jakarta.servlet.http.HttpServletRequest;
import org.busystack.caldav.sharing.GroupService;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** The primary-authenticated dashboard needs only group names and the user's owned grants. */
@ControllerAdvice(assignableTypes = ManagementController.class)
public class GroupSharingAdvice {
  private final GroupService groups;

  public GroupSharingAdvice(GroupService groups) {
    this.groups = groups;
  }

  @ModelAttribute
  void sharing(Authentication authentication, HttpServletRequest request, Model model) {
    if ("GET".equals(request.getMethod()) && "/".equals(request.getServletPath())) {
      model.addAttribute("groupChoices", groups.choices(authentication));
      model.addAttribute("groupGrants", groups.ownedGrants(authentication));
    }
  }
}
