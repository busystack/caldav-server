package org.busystack.caldav.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class IssuancePages {
  @ExceptionHandler(IssuanceIntents.Unavailable.class)
  String unavailable(
      IssuanceIntents.Unavailable failure,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    response.setStatus(failure.busy() ? 503 : 409);
    protect(response);
    model.addAttribute("issuanceMessage", failure.getMessage());
    model.addAttribute(
        "recoveryIssuance", request.getServletPath().startsWith("/security/recovery"));
    return "issuance-receipt";
  }

  public static void protect(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }
}
