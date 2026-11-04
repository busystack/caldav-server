package org.busystack.caldav.management;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.sharing.GroupService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/** Rebuild the original authorized form; only allowlisted nonsensitive values are redisplayed. */
@Component
public final class FormRecovery {
  private final ManagementService management;
  private final PortalService portal;
  private final AdministrationService administration;
  private final GroupService groups;
  private final PortabilityService portability;

  public FormRecovery(
      ManagementService management,
      PortalService portal,
      AdministrationService administration,
      GroupService groups,
      PortabilityService portability) {
    this.management = management;
    this.portal = portal;
    this.administration = administration;
    this.groups = groups;
    this.portability = portability;
  }

  private static int page(HttpServletRequest request, String name) {
    int page = Integer.parseInt(Objects.requireNonNullElse(request.getParameter(name), "0"));
    PortalPage.offset(page);
    return page;
  }

  public String render(
      Authentication auth, HttpServletRequest request, Model model, Map<String, String> errors) {
    RecentAuthentication.requireMfa(auth);
    management.current(auth);
    String path = request.getServletPath();
    String view;
    if (path.equals("/admin/accounts")) {
      management.requireAdministrator(auth);
      view = "account-new";
    } else if (path.matches("/admin/accounts/[a-f0-9-]{36}/edit")) {
      model.addAttribute(
          "account", administration.account(auth, UUID.fromString(path.split("/")[3])));
      view = "account-detail";
    } else if (path.equals("/collections")) view = "collection-new";
    else if (path.matches("/collections/[a-f0-9-]{36}/edit")) {
      var collection = portal.collection(auth, UUID.fromString(path.split("/")[2]));
      if (!collection.writable())
        throw new org.busystack.caldav.storage.StoreException(
            org.busystack.caldav.storage.StoreException.Kind.FORBIDDEN);
      model.addAttribute("collection", collection);
      view = "collection";
    } else if (path.equals("/admin/groups")) {
      model.addAttribute("groupPage", groups.search(auth, "", 0));
      model.addAttribute("query", "");
      view = "groups";
    } else if (path.matches("/admin/groups/[a-f0-9-]{36}(/members(/[a-f0-9-]{36}/remove)?)?")) {
      UUID id = UUID.fromString(path.split("/")[3]);
      model.addAttribute("group", groups.detail(auth, id));
      String query = Objects.requireNonNullElse(request.getParameter("memberQuery"), "");
      PortalService.checkQuery(query);
      model.addAttribute(
          "memberPage", groups.members(auth, id, query, page(request, "memberPage")));
      model.addAttribute("query", query);
      view = "group-detail";
    } else if (path.equals("/shares") || path.equals("/group-shares")) {
      UUID id = UUID.fromString(Objects.requireNonNullElse(request.getParameter("collection"), ""));
      model.addAttribute("collection", portal.owner(auth, id));
      model.addAttribute("directGrants", portal.direct(auth, id, page(request, "directPage")));
      model.addAttribute("groupGrants", portal.groupGrants(auth, id, page(request, "groupPage")));
      model.addAttribute("effectiveMembers", portal.effective(auth, id, 0));
      model.addAttribute("groupChoices", groups.choices(auth));
      view = "collection-sharing";
    } else if (path.equals("/credentials")) {
      model.addAttribute("credentialPage", portal.credentials(auth, 0));
      view = "credentials";
    } else if (path.equals("/portability/preview")) {
      UUID id =
          UUID.fromString(Objects.requireNonNullElse(request.getParameter("destination"), ""));
      var selected = portal.collection(auth, id);
      if (!selected.writable())
        throw new org.busystack.caldav.storage.StoreException(
            org.busystack.caldav.storage.StoreException.Kind.FORBIDDEN);
      model.addAttribute("collectionPage", new PortalPage<>(List.of(selected), 0, false));
      model.addAttribute("selectedDestination", id.toString());
      model.addAttribute("currentImportToken", portability.currentToken(auth));
      view = "portability";
    } else if (path.matches("/portability/import/[a-f0-9-]{36}")) {
      UUID token = UUID.fromString(path.split("/")[3]);
      var report = portability.review(auth, token, 0);
      model.addAttribute("report", report);
      model.addAttribute("reportId", token);
      model.addAttribute("reportPage", 0);
      model.addAttribute("hasNextItems", report.items().size() > 50);
      model.addAttribute(
          "itemPage", report.items().subList(0, Math.min(50, report.items().size())));
      view = "import-report";
    } else return null;
    String key =
        switch (view) {
          case "account-new" -> "account-create";
          case "account-detail" -> "account-edit";
          case "collection-new" -> "collection-create";
          case "collection" -> "collection-edit";
          case "groups" -> "group-create";
          case "group-detail" ->
              path.endsWith("/remove")
                  ? "member-remove-" + path.split("/")[5]
                  : path.endsWith("/members") ? "member-add" : "group-rename";
          case "credentials" -> "credential-create";
          case "portability" -> "import-file-" + request.getParameter("destination");
          case "import-report" -> "import-confirm";
          case "collection-sharing" ->
              path.equals("/shares")
                  ? ("NONE".equals(request.getParameter("expected"))
                      ? "direct-add"
                      : "direct-grant-" + request.getParameter("username"))
                  : ("NONE".equals(request.getParameter("expected"))
                      ? "group-add"
                      : "group-grant-" + request.getParameter("group"));
          default -> throw new IllegalStateException("Unknown form");
        };
    model.addAttribute("errorFormKey", key);
    Map<String, String> values = new LinkedHashMap<>();
    for (String field :
        List.of(
            "username",
            "displayName",
            "name",
            "description",
            "color",
            "component",
            "label",
            "days",
            "permission",
            "group",
            "expected",
            "state")) {
      String value = request.getParameter(field);
      if (value != null) values.put(field, value.substring(0, Math.min(value.length(), 8001)));
    }
    model.addAttribute("formValues", values);
    model.addAttribute("fieldErrors", errors);
    model.addAttribute("errorFormPath", path);
    return view;
  }
}
