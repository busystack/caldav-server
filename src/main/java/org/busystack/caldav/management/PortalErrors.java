package org.busystack.caldav.management;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.busystack.caldav.storage.StoreException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** Safe browser errors; submitted secrets and parser/SQL exception messages never enter pages. */
@ControllerAdvice(
    assignableTypes = {
      ManagementController.class,
      PortalController.class,
      AdministrationController.class,
      GroupManagementController.class,
      PortabilityController.class
    })
public class PortalErrors {
  private final FormRecovery forms;

  public PortalErrors(FormRecovery forms) {
    this.forms = forms;
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    StoreException.class,
    DataAccessException.class,
    org.springframework.transaction.TransactionException.class,
    org.busystack.caldav.calendar.CalendarValidationException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    AccessDeniedException.class,
    java.io.IOException.class,
    PortabilityStorage.CapacityException.class
  })
  String failure(
      Exception failure,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model,
      org.springframework.security.core.Authentication auth) {
    Map<String, String> errors = null;
    if (failure instanceof FormValidation invalid) errors = invalid.errors();
    else if (failure instanceof org.busystack.caldav.calendar.CalendarValidationException
        && request.getServletPath().equals("/portability/preview"))
      errors =
          Map.of(
              "file",
              "Choose a well-formed iCalendar file within the supported parsing limits. Any previous valid preview is preserved.");
    else if (failure
        instanceof
        org.springframework.web.multipart.support.MissingServletRequestPartException missing)
      errors =
          Map.of(missing.getRequestPartName(), "Choose an iCalendar file to validate and preview.");
    else if (failure
        instanceof org.springframework.web.bind.MissingServletRequestParameterException missing)
      errors = Map.of(missing.getParameterName(), "Complete this field.");
    else if (failure
        instanceof
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException mismatch)
      errors = Map.of(mismatch.getName(), "Choose a valid value for this field.");
    if (errors != null && (errors.containsKey("expected") || errors.containsKey("state"))) {
      errors = null;
      failure = new StoreException(StoreException.Kind.PRECONDITION);
    }
    if (errors != null) {
      try {
        String view = forms.render(auth, request, model, errors);
        if (view != null) {
          response.setStatus(
              failure instanceof FormValidation invalid && invalid.conflict() ? 409 : 400);
          response.setHeader("Cache-Control", "no-store");
          return view;
        }
      } catch (AccessDeniedException | StoreException | DataAccessException authorization) {
        failure = authorization;
      } catch (IllegalArgumentException invalidContext) {
        /* Malformed resource identifiers use the safe generic response. */
      }
    }
    int status = 400;
    String title = "Check the submitted details";
    String message =
        "Review the field limits, destination and access choices shown by the workflow, then try again.";
    if (failure instanceof AccessDeniedException) {
      status = 403;
      title = "Action not allowed";
      message =
          "This action requires current permission and primary sign-in plus passkey verification within ten minutes. Verify your factors, then return to the page.";
    } else if (failure instanceof StoreException error) {
      status =
          switch (error.kind()) {
            case NOT_FOUND -> 404;
            case FORBIDDEN -> 403;
            case PRECONDITION -> 412;
            case LIMIT -> 413;
            default -> 409;
          };
      title =
          switch (status) {
            case 404 -> "Resource unavailable";
            case 403 -> "Permission required";
            case 412 -> "This form is out of date";
            case 413 -> "Supported limit exceeded";
            default -> "The action conflicts with current data";
          };
      message =
          switch (status) {
            case 404, 403 ->
                "The resource is unavailable to this account. An administrator role does not grant access to private collections.";
            case 412 ->
                "The resource or access changed since this form was opened. Reload its management page and review the current state before trying again.";
            case 413 ->
                "The request exceeds a supported size, count or processing limit. Use a smaller file or selection.";
            default ->
                "Existing data has been preserved. Review current names, permissions and UID conflicts before trying again.";
          };
    } else if (failure instanceof org.springframework.dao.DataIntegrityViolationException) {
      status = 409;
      title = "Conflicting details";
      message =
          "A name or identifier is already in use, or another request changed the resource. Review the current state and try again.";
    } else if (failure instanceof DataAccessException
        || failure instanceof org.springframework.transaction.TransactionException
        || failure instanceof java.io.IOException
        || failure instanceof PortabilityStorage.CapacityException) {
      status = 503;
      title = "Temporarily unavailable";
      message =
          "The database operation could not complete. Try again after the operator checks database connectivity and service logs using the request identifier.";
    }
    if (failure instanceof java.io.IOException) {
      message =
          "Temporary calendar storage could not complete the operation. Return to the workflow and retry while the preview remains valid; the operator can check private storage and the request identifier.";
    }
    if (failure instanceof PortabilityStorage.CapacityException) {
      response.setHeader("Retry-After", "5");
      message =
          "Temporary portability capacity is busy. Retry later or discard an unused preview. Existing data and previews are preserved.";
    }
    response.setStatus(status);
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("errorTitle", title);
    model.addAttribute("message", message);
    Map<String, String> submitted = new LinkedHashMap<>();
    for (String name :
        new String[] {"name", "displayName", "description", "color", "username", "label"}) {
      String value = request.getParameter(name);
      if (value != null && value.length() <= 8000) submitted.put(name, value);
    }
    model.addAttribute("submitted", submitted);
    String path = request.getServletPath();
    String retry = "/";
    if (path.equals("/collections")) retry = "/collections/new";
    else if (path.startsWith("/collections/")) retry = path.replaceFirst("/(edit|delete)$", "");
    else if (path.equals("/admin/accounts")) retry = "/admin/accounts/new";
    else if (path.startsWith("/admin/accounts/")) retry = path.replaceFirst("/(edit|disable)$", "");
    else if (path.startsWith("/admin/groups/"))
      retry = path.replaceFirst("/members.*$", "").replaceFirst("/delete$", "");
    else if (path.equals("/admin/groups")) retry = path;
    else if (path.startsWith("/credentials"))
      retry =
          "/security/credentials?client="
              + PortalController.client(
                  java.util.Objects.requireNonNullElse(request.getParameter("client"), "generic"));
    else if (path.matches("/portability/import/[a-f0-9-]{36}")) retry = path;
    else if (path.startsWith("/portability")) retry = "/portability";
    else if (path.equals("/shares") || path.equals("/group-shares")) {
      try {
        retry =
            "/collections/"
                + java.util.UUID.fromString(
                    java.util.Objects.requireNonNullElse(request.getParameter("collection"), ""))
                + "/sharing";
      } catch (IllegalArgumentException ignored) {
        retry = "/collections";
      }
    }
    model.addAttribute("retryPath", retry);
    model.addAttribute("authenticationRetry", request.getContextPath() + retry);
    return "portal-error";
  }
}
