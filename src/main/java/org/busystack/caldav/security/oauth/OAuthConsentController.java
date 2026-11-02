package org.busystack.caldav.security.oauth;

import org.busystack.caldav.security.RecentAuthentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Local consent UI reads the pending framework request, never trusts requested display values. */
@Controller
public class OAuthConsentController {
  private final OAuth2AuthorizationService authorizations;
  private final RegisteredClientRepository clients;

  public OAuthConsentController(
      OAuth2AuthorizationService authorizations, RegisteredClientRepository clients) {
    this.authorizations = authorizations;
    this.clients = clients;
  }

  @GetMapping("/oauth/consent")
  String consent(
      Authentication authentication,
      @RequestParam("client_id") String clientId,
      @RequestParam String state,
      Model model) {
    RecentAuthentication.requireMfa(authentication);
    if (state.length() > 1024 || clientId.length() > 200)
      throw new AccessDeniedException("Invalid consent request");
    var pending = authorizations.findByToken(state, new OAuth2TokenType("state"));
    var client = clients.findByClientId(clientId);
    if (pending == null
        || client == null
        || !client.getId().equals(pending.getRegisteredClientId())
        || !authentication.getName().equals(pending.getPrincipalName()))
      throw new AccessDeniedException("Invalid consent request");
    OAuth2AuthorizationRequest request =
        pending.getAttribute(OAuth2AuthorizationRequest.class.getName());
    if (request == null || !java.util.Set.of("dav").containsAll(request.getScopes()))
      throw new AccessDeniedException("Unsupported consent scope");
    model.addAttribute("clientName", client.getClientName());
    model.addAttribute("clientId", clientId);
    model.addAttribute("state", state);
    return "oauth-consent";
  }
}
