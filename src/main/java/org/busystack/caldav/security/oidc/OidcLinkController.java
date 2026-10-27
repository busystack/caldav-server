package org.busystack.caldav.security.oidc;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@ConditionalOnProperty(name = "busystack.oidc.enabled", havingValue = "true")
public class OidcLinkController {
  private final ExternalIdentityService identities;

  public OidcLinkController(ExternalIdentityService identities) {
    this.identities = identities;
  }

  @PostMapping("/security/identities/link")
  String link(Authentication authentication, HttpServletRequest request) {
    var intent = identities.prepareLink(authentication);
    request.getSession().setAttribute(LinkingAuthorizationRequestResolver.PENDING_LINK, intent);
    return "redirect:/oauth2/authorization/upstream";
  }
}
