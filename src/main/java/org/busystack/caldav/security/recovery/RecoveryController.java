package org.busystack.caldav.security.recovery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RecoveryController {
  private final RecoveryService recovery;
  private final org.busystack.caldav.security.IssuanceIntents intents;

  public RecoveryController(
      RecoveryService recovery, org.busystack.caldav.security.IssuanceIntents intents) {
    this.recovery = recovery;
    this.intents = intents;
  }

  @GetMapping("/security/recovery")
  String page(Authentication authentication, Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("status", recovery.status(authentication));
    return "recovery";
  }

  @GetMapping("/security/recovery/codes/new")
  String prepare(Authentication auth, Model model, HttpServletResponse response) {
    model.addAttribute("status", recovery.status(auth));
    model.addAttribute(
        "issuanceIntent",
        intents
            .prepare(auth, org.busystack.caldav.security.IssuanceIntents.Purpose.RECOVERY_CODES, "")
            .id());
    org.busystack.caldav.security.IssuancePages.protect(response);
    return "recovery-generate";
  }

  @PostMapping("/security/recovery/codes")
  String issue(
      Authentication authentication,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestParam(required = false) java.util.UUID intent,
      @RequestParam(defaultValue = "false") boolean replace) {
    var issued = recovery.issue(authentication, request.getRemoteAddr(), intent, replace);
    org.busystack.caldav.security.IssuancePages.protect(response);
    if (issued == null) {
      model.addAttribute("recoveryIssuance", true);
      model.addAttribute(
          "issuanceMessage",
          "This request already generated recovery codes. Reach the current settings to review how many remain or deliberately replace them.");
      return "issuance-receipt";
    }
    model.addAttribute("issued", issued);
    return "recovery-codes";
  }

  @GetMapping("/security/recovery/use")
  String useForm(Authentication auth, HttpServletResponse response) {
    if (!org.busystack.caldav.security.RecentAuthentication.primaryCurrent(auth))
      return "redirect:/login?reauthenticate";
    org.busystack.caldav.security.IssuancePages.protect(response);
    return "recovery-use";
  }

  @PostMapping("/security/recovery/use")
  String use(
      Authentication authentication,
      @RequestParam String code,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    try {
      recovery.recover(authentication, code, request.getRemoteAddr());
    } catch (RecoveryService.RejectedCode invalid) {
      if (!org.busystack.caldav.security.RecentAuthentication.primaryCurrent(authentication))
        return "redirect:/login?reauthenticate";
      response.setStatus(400);
      org.busystack.caldav.security.IssuancePages.protect(response);
      model.addAttribute(
          "fieldErrors",
          java.util.Map.of("code", "Enter an unused recovery code for this account."));
      model.addAttribute("errorFormKey", "recovery-use");
      return "recovery-use";
    }
    SecurityContextHolder.clearContext();
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    return "redirect:/login?recovered";
  }
}
