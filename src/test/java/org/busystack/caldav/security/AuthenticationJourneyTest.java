package org.busystack.caldav.security;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.util.List;
import org.busystack.caldav.config.DeploymentProperties;
import org.junit.jupiter.api.Test;

class AuthenticationJourneyTest {
  @Test
  void validatedContinuationSurvivesPrimarySessionRotationWithoutKeepingOrdinaryState() {
    var base = URI.create("https://calendar.example/team");
    var journey = new AuthenticationJourney(new DeploymentProperties(base, base, false));
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    request.setRequestedSessionIdValid(true);
    request.getSession().setAttribute("ordinary-state", "discard me");
    String previous = request.getSession().getId();
    journey.remember(request, "/team/connections?client=gnome");
    var strategy =
        new org.springframework.security.web.authentication.session
            .SessionFixationProtectionStrategy();
    strategy.setMigrateSessionAttributes(false);
    strategy.onAuthentication(
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            .authenticated("fixture", null, List.of()),
        request,
        response);
    assertThat(request.getSession().getId()).isNotEqualTo(previous);
    assertThat(request.getSession().getAttribute("ordinary-state")).isNull();
    assertThat(journey.destination(request)).isEqualTo("/team/connections?client=gnome");
  }

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
