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
  @ExceptionHandler({
    IllegalArgumentException.class,
    StoreException.class,
    DataAccessException.class,
    org.busystack.caldav.calendar.CalendarValidationException.class,
    org.springframework.web.multipart.MaxUploadSizeExceededException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    AccessDeniedException.class,
    java.io.IOException.class
  })
  String failure(
      Exception failure, HttpServletRequest request, HttpServletResponse response, Model model) {
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
    } else if (failure instanceof DataAccessException || failure instanceof java.io.IOException) {
      status = 503;
      title = "Temporarily unavailable";
      message =
          "The database operation could not complete. Try again after the operator checks database connectivity and service logs using the request identifier.";
    } else if (failure
        instanceof org.springframework.web.multipart.MaxUploadSizeExceededException) {
      status = 413;
      title = "File too large";
      message = "Import files are limited to 8 MiB, with a 1 MiB maximum for each UID series.";
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
    else if (path.startsWith("/credentials")) retry = "/security/credentials";
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
    return "portal-error";
  }
}
