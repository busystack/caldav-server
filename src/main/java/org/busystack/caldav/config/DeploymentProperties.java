package org.busystack.caldav.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** URLs are configured by the operator; Host and forwarding headers are never an authority. */
@ConfigurationProperties("busystack")
public record DeploymentProperties(URI publicBaseUrl, URI issuer, boolean allowInsecureLocal) {
  public DeploymentProperties {
    validate(publicBaseUrl, "busystack.public-base-url", allowInsecureLocal);
    validate(issuer, "busystack.issuer", allowInsecureLocal);
    if (!publicBaseUrl.equals(issuer)) {
      throw new IllegalArgumentException("busystack.issuer must equal busystack.public-base-url");
    }
  }

  private static void validate(URI uri, String name, boolean allowLocal) {
    if (uri == null
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getRawPath().contains("%")
        || uri.getPath().contains("//")
        || uri.getPath().contains("..")
        || (!uri.getPath().isEmpty() && uri.getPath().endsWith("/"))) {
      throw new IllegalArgumentException(
          name
              + " must be an absolute canonical URL without trailing slash, credentials, query, fragment or encoded path");
    }
    boolean local =
        "localhost".equals(uri.getHost())
            || "127.0.0.1".equals(uri.getHost())
            || "[::1]".equals(uri.getHost());
    if (!"https".equals(uri.getScheme())
        && !(allowLocal && local && "http".equals(uri.getScheme()))) {
      throw new IllegalArgumentException(
          name
              + " must use HTTPS (HTTP is permitted only for explicitly enabled loopback development)");
    }
  }

  public String davRoot() {
    return publicBaseUrl + "/dav/";
  }
}
