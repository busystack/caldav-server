package org.busystack.caldav.protocol;

import org.busystack.caldav.config.DeploymentProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class DiscoveryController {
  private final DeploymentProperties deployment;

  public DiscoveryController(DeploymentProperties deployment) {
    this.deployment = deployment;
  }

  @RequestMapping("/.well-known/caldav")
  ResponseEntity<Void> discover() {
    return ResponseEntity.status(308).location(java.net.URI.create(deployment.davRoot())).build();
  }
}
