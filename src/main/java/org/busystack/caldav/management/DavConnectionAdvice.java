package org.busystack.caldav.management;

import org.busystack.caldav.config.DavCompatibilityProperties;
import org.busystack.caldav.config.DeploymentProperties;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {ManagementController.class, PortalController.class})
public final class DavConnectionAdvice {
  public record Connection(String url, String username, String home, boolean compatibility) {
    /**
     * GNOME 50 persists its authentication user from the URI, separately from discovery
     * credentials.
     */
    public String gnomeUrl() {
      var configured = java.net.URI.create(url);
      try {
        return new java.net.URI(
                configured.getScheme(),
                username,
                configured.getHost(),
                configured.getPort(),
                configured.getPath(),
                null,
                null)
            .toASCIIString();
      } catch (java.net.URISyntaxException invalid) {
        throw new IllegalStateException("Invalid configured DAV connection address", invalid);
      }
    }
  }

  private final ManagementService management;
  private final DeploymentProperties deployment;
  private final DavCompatibilityProperties compatibility;

  public DavConnectionAdvice(
      ManagementService management,
      DeploymentProperties deployment,
      DavCompatibilityProperties compatibility) {
    this.management = management;
    this.deployment = deployment;
    this.compatibility = compatibility;
  }

  @ModelAttribute("davConnection")
  Connection connection(Authentication authentication) {
    var account = management.current(authentication);
    return new Connection(
        deployment.davRoot(),
        account.username(),
        deployment.davRoot() + "calendars/" + account.id() + "/",
        compatibility.allowSyncDepthOne());
  }
}
