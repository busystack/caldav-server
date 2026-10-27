package org.busystack.caldav.config;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;

class DeploymentPropertiesTest {
  @Test
  void usesConfiguredCanonicalSubpath() {
    URI uri = URI.create("https://calendar.example/team");
    assertThat(new DeploymentProperties(uri, uri, false).davRoot())
        .isEqualTo("https://calendar.example/team/dav/");
  }

  @Test
  void rejectsInsecureAndAmbiguousConfiguration() {
    for (String value :
        new String[] {
          "http://calendar.example",
          "https://calendar.example/",
          "https://user@calendar.example",
          "https://calendar.example/%2e%2e",
          "https://calendar.example/a/../b",
          "https://calendar.example?evil=1",
          "https://calendar.example/#fragment"
        }) {
      URI uri = URI.create(value);
      assertThatThrownBy(() -> new DeploymentProperties(uri, uri, false))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new DeploymentProperties(null, null, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DeploymentProperties(
                    URI.create("http://example.com"), URI.create("http://example.com"), true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void explicitlyPermitsLoopbackDevelopment() {
    URI uri = URI.create("http://127.0.0.1:8080");
    assertThat(new DeploymentProperties(uri, uri, true).davRoot())
        .isEqualTo("http://127.0.0.1:8080/dav/");
  }
}
