package org.busystack.caldav.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;

/** Allowlist by form route. In particular, multipart bodies and credential fields are excluded. */
final class BrowserFormDrafts {
  private static final String ID = "[a-f0-9-]{36}";

  private BrowserFormDrafts() {}

  static AuthenticationJourney.Draft capture(HttpServletRequest request) {
    String path = request.getServletPath();
    List<String> fields;
    if (path.equals("/collections")) fields = List.of("name", "component", "description", "color");
    else if (path.matches("/collections/" + ID + "/edit"))
      fields = List.of("state", "name", "description", "color");
    else if (path.equals("/admin/accounts")) fields = List.of("username", "displayName");
    else if (path.matches("/admin/accounts/" + ID + "/edit"))
      fields = List.of("expected", "displayName");
    else if (path.equals("/admin/groups")) fields = List.of("name");
    else if (path.matches("/admin/groups/" + ID)) fields = List.of("name", "expected");
    else if (path.matches("/admin/groups/" + ID + "/members"))
      fields = List.of("username", "memberPage", "memberQuery", "expectedGrants");
    else if (path.equals("/shares"))
      fields =
          List.of(
              "collection",
              "username",
              "permission",
              "expected",
              "directPage",
              "groupPage",
              "effectivePage");
    else if (path.equals("/group-shares"))
      fields =
          List.of(
              "collection",
              "group",
              "permission",
              "expected",
              "directPage",
              "groupPage",
              "effectivePage");
    else if (path.equals("/credentials"))
      fields = List.of("label", "days", "client", "from", "intent", "resource");
    else return null;

    var values = new LinkedHashMap<String, String>();
    for (String name : fields) {
      String value = request.getParameter(name);
      if (value != null && value.length() <= 8000) values.put(name, value);
    }
    return new AuthenticationJourney.Draft(path, values);
  }

  static String returnPath(HttpServletRequest request) {
    String path = request.getServletPath();
    if (path.equals("/portability/preview")) {
      String query = request.getQueryString();
      if (query != null && query.matches("uploadTarget=" + ID))
        return "/portability?destination=" + query.substring("uploadTarget=".length());
      return "/portability";
    }
    if (path.endsWith("/cancel") && path.startsWith("/portability/import/"))
      return path.substring(0, path.length() - "/cancel".length());
    if (path.equals("/security/recovery/codes")) return "/security/recovery/codes/new";
    if (path.matches("/credentials/" + ID + "/revoke")) return path;
    if (path.equals("/security/identities/link")) return "/security";
    if (path.matches("/admin/accounts/" + ID + "/disable")) return path;
    if (path.matches("/admin/oauth-clients/[^/]+/remove")) return path;
    if (path.equals("/admin/oauth-clients")
        || path.equals("/security/password")
        || path.endsWith("/delete")
        || path.startsWith("/portability/import/")) return path;
    if (path.startsWith("/security/sessions/")) return path;
    if (path.startsWith("/oauth-grants/")) return path;
    return null;
  }
}
