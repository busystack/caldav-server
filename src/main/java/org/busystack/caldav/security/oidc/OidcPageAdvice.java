package org.busystack.caldav.security.oidc;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(annotations = Controller.class)
public class OidcPageAdvice {
  private final OidcProperties properties;

  public OidcPageAdvice(OidcProperties properties) {
    this.properties = properties;
  }

  @ModelAttribute
  void upstreamLogin(Model model) {
    model.addAttribute("oidcEnabled", properties.enabled());
  }
}
