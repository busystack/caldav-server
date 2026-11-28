package org.busystack.caldav.management;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.busystack.caldav.security.AuthenticationJourney;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ReviewFormController {
  private static final String RECOVERED_PATH = ReviewFormController.class.getName() + ".path";
  private final AuthenticationJourney journey;
  private final FormRecovery forms;
  private final PortalPageAdvice pages;
  private final DavConnectionAdvice connections;

  public ReviewFormController(
      AuthenticationJourney journey,
      FormRecovery forms,
      PortalPageAdvice pages,
      DavConnectionAdvice connections) {
    this.journey = journey;
    this.forms = forms;
    this.pages = pages;
    this.connections = connections;
  }

  @GetMapping("/forms/{handle}")
  String review(
      Authentication auth,
      HttpServletRequest request,
      HttpServletResponse response,
      @PathVariable UUID handle,
      Model model) {
    response.setHeader("Cache-Control", "no-store");
    var draft = journey.takeDraft(request, auth, handle.toString());
    if (draft == null) {
      response.setStatus(410);
      model.addAttribute("errorTitle", "This form is no longer available");
      model.addAttribute("message", "Open the task again to review the current details.");
      model.addAttribute("retryPath", "/");
      return "portal-error";
    }
    request.setAttribute(RECOVERED_PATH, draft.path());
    var original =
        new HttpServletRequestWrapper(request) {
          @Override
          public String getServletPath() {
            return draft.path();
          }

          @Override
          public String getParameter(String name) {
            return draft.fields().get(name);
          }
        };
    String view = forms.render(auth, original, model, Map.of());
    pages.layout(auth, original, model);
    if ("credentials".equals(view))
      model.addAttribute("davConnection", connections.connection(auth));
    model.addAttribute(
        "notice",
        "Sign-in confirmed. Review your details, re-enter any required secret and submit again.");
    model.addAttribute("noticeSeverity", "info");
    return view;
  }

  static String route(HttpServletRequest request) {
    return request.getAttribute(RECOVERED_PATH) instanceof String path
        ? path
        : request.getServletPath();
  }
}
