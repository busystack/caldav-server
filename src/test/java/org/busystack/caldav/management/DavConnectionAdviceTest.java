package org.busystack.caldav.management;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;

class DavConnectionAdviceTest {
  @Test
  void gnomeAddressRetainsConfiguredOriginSubpathAndOnlyTheAccountUsername() {
    for (String address :
        java.util.List.of(
            "https://calendar.example/dav/",
            "https://calendar.example:8443/team/dav/",
            "https://[::1]:8443/team/dav/")) {
      var expected = URI.create(address);
      var connection = new DavConnectionAdvice.Connection(address, "family+one@example", "", true);
      var actual = URI.create(connection.gnomeUrl());
      assertThat(actual.getScheme()).isEqualTo(expected.getScheme());
      assertThat(actual.getHost()).isEqualTo(expected.getHost());
      assertThat(actual.getPort()).isEqualTo(expected.getPort());
      assertThat(actual.getPath()).isEqualTo(expected.getPath());
      assertThat(actual.getUserInfo()).isEqualTo("family+one@example");
      assertThat(actual.getQuery()).isNull();
      assertThat(actual.getFragment()).isNull();
    }
  }
}
