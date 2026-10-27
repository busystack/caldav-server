package org.busystack.caldav.management;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice(assignableTypes = ManagementController.class)
public final class IndividualSharingAdvice {
  private final ManagementService management;

  public IndividualSharingAdvice(ManagementService management) {
    this.management = management;
  }

  @ModelAttribute("individualShares")
  ManagementService.SharePage shares(
      Authentication authentication,
      @RequestParam(name = "shares-page", defaultValue = "0") int page) {
    return management.individualShares(authentication, page);
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  String conflict() {
    return "account-error";
  }

  @ExceptionHandler(org.busystack.caldav.storage.StoreException.class)
  String storeFailure(
      org.busystack.caldav.storage.StoreException failure,
      jakarta.servlet.http.HttpServletResponse response) {
    response.setStatus(
        switch (failure.kind()) {
          case NOT_FOUND -> 404;
          case FORBIDDEN -> 403;
          case LIMIT -> 507;
          case PRECONDITION -> 412;
          default -> 409;
        });
    return "account-error";
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  String invalidInput() {
    return "account-error";
  }
}
