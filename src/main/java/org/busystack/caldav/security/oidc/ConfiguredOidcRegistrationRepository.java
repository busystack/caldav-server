package org.busystack.caldav.security.oidc;

import org.busystack.caldav.config.DeploymentProperties;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;

/**
 * Immutable operator configuration; discovery is deferred so outages do not block local startup.
 */
public class ConfiguredOidcRegistrationRepository implements ClientRegistrationRepository {
  private final OidcProperties properties;
  private final DeploymentProperties deployment;
  private volatile ClientRegistration registration;

  public ConfiguredOidcRegistrationRepository(
      OidcProperties properties, DeploymentProperties deployment) {
    this.properties = properties;
    this.deployment = deployment;
    var issuer = properties.issuer();
    boolean loopback =
        "localhost".equals(issuer.getHost())
            || "127.0.0.1".equals(issuer.getHost())
            || "[::1]".equals(issuer.getHost());
    if (!"https".equals(issuer.getScheme())
        && !(deployment.allowInsecureLocal() && loopback && "http".equals(issuer.getScheme())))
      throw new IllegalArgumentException(
          "OIDC issuer must use HTTPS; loopback HTTP requires explicit development mode");
  }

  @Override
  public ClientRegistration findByRegistrationId(String id) {
    if (!"upstream".equals(id)) return null;
    var current = registration;
    if (current != null) return current;
    synchronized (this) {
      if (registration == null)
        registration =
            ClientRegistrations.fromIssuerLocation(properties.issuer().toString())
                .registrationId("upstream")
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .scope("openid", "profile")
                .redirectUri(deployment.publicBaseUrl() + "/login/oauth2/code/upstream")
                .clientName("Organization sign-in")
                .build();
      return registration;
    }
  }
}
