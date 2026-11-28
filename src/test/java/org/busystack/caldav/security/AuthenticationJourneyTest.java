package org.busystack.caldav.security;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.util.List;
import org.busystack.caldav.config.DeploymentProperties;
import org.junit.jupiter.api.Test;

class AuthenticationJourneyTest {
  @Test
  void handlesKeepIndependentDestinationsAndRejectReplayAndChangedAccounts() {
    var base = URI.create("https://calendar.example/team");
    var journey = journey(base);
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    var actor = browserIdentity("owner");
    var other = browserIdentity("other");
    String connection = journey.start(request, "/team/connections?client=gnome", actor);
    String collections = journey.start(request, "/team/collections?scope=shared", actor);
    assertThat(journey.consume(request, actor, connection))
        .isEqualTo("/team/connections?client=gnome");
    assertThat(journey.consume(request, actor, connection)).isEqualTo("/team/?interrupted");
    assertThat(journey.consume(request, other, collections)).isEqualTo("/team/?interrupted");
    assertThat(journey.safe("/team-other/collections")).isEqualTo("/team/");
    assertThat(journey.safe("/team/connections?client=gnome&password=ignore"))
        .isEqualTo("/team/connections?client=gnome");
    assertThat(
            journey.safe(
                "/team/oauth2/authorize?redirect_uri=https%3A%2F%2Fapp.example%2Fcallback&state=a%3Db"))
        .isEqualTo(
            "/team/oauth2/authorize?redirect_uri=https%3A%2F%2Fapp.example%2Fcallback&state=a%3Db");
    String oldest = journey.start(request, "/team/security", actor);
    for (int index = 0; index < 8; index++)
      journey.start(request, "/team/collections?page=" + index, actor);
    assertThat(journey.consume(request, actor, oldest)).isEqualTo("/team/?interrupted");
  }

  private static org.springframework.security.core.Authentication browserIdentity(String name) {
    return org.springframework.security.authentication.UsernamePasswordAuthenticationToken
        .authenticated(
            name,
            null,
            List.of(
                org.busystack.caldav.security.session.SessionSecurity.authority(
                    java.util.UUID.randomUUID())));
  }

  @Test
  void validatedContinuationSurvivesPrimarySessionRotationWithoutKeepingOrdinaryState() {
    var base = URI.create("https://calendar.example/team");
    var journey = journey(base);
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
      var journey = journey(base);
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

  private static AuthenticationJourney journey(URI base) {
    return new AuthenticationJourney(
        new DeploymentProperties(base, base, false), null, FlowStoreFixture.create());
  }
}
