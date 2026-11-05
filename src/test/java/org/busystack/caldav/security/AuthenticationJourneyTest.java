package org.busystack.caldav.security;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.util.List;
import org.busystack.caldav.config.DeploymentProperties;
import org.junit.jupiter.api.Test;

class AuthenticationJourneyTest {
  @Test
  void continuationsStayInsideRootAndPrefixedApplications() {
    for (String prefix : List.of("", "/team")) {
      var base = URI.create("https://calendar.example" + prefix);
      var journey = new AuthenticationJourney(new DeploymentProperties(base, base, false));
      assertThat(journey.safe(prefix + "/connections?client=gnome"))
          .isEqualTo(prefix + "/connections?client=gnome");
      for (String unsafe :
          List.of(
              "https://attacker.invalid/",
              "//attacker.invalid/",
              prefix + "/../outside",
              prefix + "/%2f%2fattacker.invalid",
              prefix + "/login",
              prefix + "/logout",
              prefix + "/auth/continue",
              prefix + "/connections#fragment",
              prefix + "/\\attacker.invalid",
              prefix + "/\r\nLocation: attacker"))
        assertThat(journey.safe(unsafe)).as(unsafe).isEqualTo(prefix + "/");
    }
  }
}
