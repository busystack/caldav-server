package org.busystack.caldav.security.oidc;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.busystack.caldav.xml.XmlCharacters;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("busystack.oidc")
public record OidcProperties(
    boolean enabled,
    URI issuer,
    String clientId,
    String clientSecret,
    List<AllowedUser> allowedUsers,
    Duration maximumAuthenticationAge) {
  public record AllowedUser(String subject, String username, String displayName) {
    public AllowedUser {
      if (subject == null
          || subject.isBlank()
          || subject.length() > 255
          || username == null
          || !username.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
          || displayName == null
          || displayName.isBlank()
          || displayName.length() > 200
          || !XmlCharacters.valid(displayName))
        throw new IllegalArgumentException(
            "OIDC allowed users require a subject, valid local username and display name");
    }
  }

  public OidcProperties {
    allowedUsers = allowedUsers == null ? List.of() : List.copyOf(allowedUsers);
    maximumAuthenticationAge =
        maximumAuthenticationAge == null ? Duration.ofHours(24) : maximumAuthenticationAge;
    if (maximumAuthenticationAge.compareTo(Duration.ofMinutes(1)) < 0
        || maximumAuthenticationAge.compareTo(Duration.ofHours(24)) > 0)
      throw new IllegalArgumentException(
          "OIDC maximum authentication age must be between one minute and 24 hours");
    if (enabled
        && (issuer == null
            || issuer.getHost() == null
            || issuer.getUserInfo() != null
            || issuer.getQuery() != null
            || issuer.getFragment() != null
            || clientId == null
            || clientId.isBlank()
            || clientSecret == null
            || clientSecret.isBlank()))
      throw new IllegalArgumentException(
          "Enabled OIDC requires a canonical issuer, client ID and client secret");
    if (allowedUsers.stream().map(AllowedUser::subject).distinct().count() != allowedUsers.size()
        || allowedUsers.stream().map(AllowedUser::username).distinct().count()
            != allowedUsers.size())
      throw new IllegalArgumentException("OIDC allowlist subjects and usernames must be unique");
  }

  @Override
  public String toString() {
    return "OidcProperties[enabled=" + enabled + ", issuer=" + issuer + ", clientSecret=REDACTED]";
  }
}
