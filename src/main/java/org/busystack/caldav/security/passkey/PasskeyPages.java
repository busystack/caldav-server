package org.busystack.caldav.security.passkey;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PasskeyPages {
  private final AccountPasskeyUsers users;
  private final AccountPasskeyCredentials credentials;

  public PasskeyPages(AccountPasskeyUsers users, AccountPasskeyCredentials credentials) {
    this.users = users;
    this.credentials = credentials;
  }

  @GetMapping("/login")
  public String login() {
    return "login";
  }

  @GetMapping("/security/passkeys")
  public String passkeys(Authentication authentication, Model model) {
    var user = users.findByUsername(authentication.getName());
    List<CredentialRecord> records =
        user == null ? List.of() : credentials.findByUserId(user.getId());
    model.addAttribute("credentials", records);
    return "passkeys";
  }
}
