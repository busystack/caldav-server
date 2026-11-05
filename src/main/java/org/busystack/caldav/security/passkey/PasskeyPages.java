package org.busystack.caldav.security.passkey;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PasskeyPages {
  private final AccountPasskeyUsers users;
  private final AccountPasskeyCredentials credentials;
  private final org.busystack.caldav.account.InitializationService initialization;
  private final org.busystack.caldav.security.AuthenticationJourney journey;

  public PasskeyPages(
      AccountPasskeyUsers users,
      AccountPasskeyCredentials credentials,
      org.busystack.caldav.account.InitializationService initialization,
      org.busystack.caldav.security.AuthenticationJourney journey) {
    this.users = users;
    this.credentials = credentials;
    this.initialization = initialization;
    this.journey = journey;
  }

  @GetMapping("/login")
  public String login(
      Authentication auth,
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    if (initialization.available()) return "redirect:/setup";
    journey.capture(request, response);
    if (org.busystack.caldav.security.RecentAuthentication.primaryCurrent(auth))
      return "redirect:/auth/continue";
    return "login";
  }

  @GetMapping("/auth/continue")
  public String progress(
      Authentication auth,
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response,
      Model model,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String next) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    journey.capture(request, response);
    if (next != null) journey.remember(request, next);
    if (!org.busystack.caldav.security.RecentAuthentication.primaryCurrent(auth))
      return "redirect:/login";
    boolean enrolled = users.hasCredentials(auth.getName());
    model.addAttribute("enrolled", enrolled);
    model.addAttribute("destination", journey.destination(request));
    return !enrolled
        ? "enroll-passkey"
        : org.busystack.caldav.security.RecentAuthentication.mfaCurrent(auth)
            ? "authentication-complete"
            : "verify-passkey";
  }

  @GetMapping("/auth/state")
  @org.springframework.web.bind.annotation.ResponseBody
  public java.util.Map<String, Boolean> state(
      Authentication auth, jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    org.busystack.caldav.security.RecentAuthentication.requirePrimary(auth);
    return java.util.Map.of(
        "enrolled",
        users.hasCredentials(auth.getName()),
        "verified",
        org.busystack.caldav.security.RecentAuthentication.mfaCurrent(auth));
  }

  @GetMapping("/security/passkeys")
  public String passkeys(Authentication authentication, Model model) {
    var user = users.findByUsername(authentication.getName());
    List<CredentialRecord> records =
        user == null ? List.of() : credentials.findByUserId(user.getId());
    model.addAttribute("credentials", records);
    return records.isEmpty() ? "enroll-passkey" : "passkeys";
  }
}
