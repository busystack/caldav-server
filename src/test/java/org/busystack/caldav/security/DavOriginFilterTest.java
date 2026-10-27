package org.busystack.caldav.security;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import org.busystack.caldav.config.DeploymentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DavOriginFilterTest {
  final DavOriginFilter filter =
      new DavOriginFilter(
          new DeploymentProperties(
              URI.create("https://calendar.example/team"),
              URI.create("https://calendar.example/team"),
              false));

  private boolean allowed(String origin, String site) throws Exception {
    var request = new MockHttpServletRequest("PROPFIND", "/team/dav/");
    if (origin != null) request.addHeader("Origin", origin);
    if (site != null) request.addHeader("Sec-Fetch-Site", site);
    var response = new MockHttpServletResponse();
    var passed = new java.util.concurrent.atomic.AtomicBoolean();
    filter.doFilter(request, response, (req, res) -> passed.set(true));
    return passed.get();
  }

  @Test
  void ordinaryAndNativeSystemRequestsAndConfiguredSameOriginAreAllowed() throws Exception {
    assertThat(allowed(null, null)).isTrue();
    assertThat(allowed(null, "none")).isTrue();
    assertThat(allowed("https://calendar.example", "same-origin")).isTrue();
    assertThat(allowed("https://CALENDAR.example:443", null)).isTrue();
  }

  @Test
  void foreignOpaqueSiblingOriginsAndCrossSiteMetadataAreRejected() throws Exception {
    for (String origin :
        new String[] {
          "https://attacker.invalid",
          "https://sibling.calendar.example",
          "https://calendar.example:444",
          "http://calendar.example",
          "null",
          "https://calendar.example/path"
        }) assertThat(allowed(origin, "none")).as(origin).isFalse();
    for (String site : new String[] {"same-site", "cross-site", "invalid"})
      assertThat(allowed("https://calendar.example", site)).as(site).isFalse();
  }
}
