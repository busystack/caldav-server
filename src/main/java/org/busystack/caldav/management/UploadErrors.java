package org.busystack.caldav.management;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Multipart admission can fail before Spring resolves the portal controller. */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class UploadErrors {
  private final PortalPageAdvice pages;

  public UploadErrors(PortalPageAdvice pages) {
    this.pages = pages;
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  String excessive(
      MaxUploadSizeExceededException failure,
      Authentication auth,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    if (!request.getServletPath().equals("/portability/preview")) throw failure;
    pages.layout(auth, request, model);
    response.setStatus(413);
    response.setHeader("Cache-Control", "no-store");
    // Render directly: an error dispatch would attempt multipart admission a second time.
    return "error/413";
  }
}
