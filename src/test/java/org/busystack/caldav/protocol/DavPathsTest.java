package org.busystack.caldav.protocol;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.util.List;
import org.busystack.caldav.config.DeploymentProperties;
import org.junit.jupiter.api.Test;

class DavPathsTest {
  final DavPaths paths =
      new DavPaths(
          new DeploymentProperties(
              URI.create("https://calendar.example/team"),
              URI.create("https://calendar.example/team"),
              false));

  @Test
  void uriEncodingPreservesLiteralPlusAndUnicodeWithoutFormDecoding() {
    List<String> segments = List.of("calendars", "user", "雪 + café", "100%.ics");
    assertThat(paths.parse(paths.href(segments, false))).isEqualTo(segments);
    assertThat(paths.parse("/team/dav/calendars/user/a+b.ics")).endsWith("a+b.ics");
    assertThat(paths.parse("https://calendar.example:443/team/dav/")).isEmpty();
    for (String permitted :
        List.of("valid\uFDD0.ics", "valid" + new String(Character.toChars(0x1FFFE)) + ".ics")) {
      var valid = List.of("calendars", "user", permitted);
      assertThat(paths.parse(paths.href(valid, false))).isEqualTo(valid);
    }
  }

  @Test
  void traversalWrongAuthorityAndInvalidUtf8AreRejected() {
    for (String href :
        List.of(
            "/team/dav/a//b",
            "/team/dav/%2e%2e/",
            "/team/dav/a%2fb",
            "/team/dav/a%5cb",
            "/team/dav/%ff",
            "/team/dav/bad%EF%BF%BEleaf",
            "/team/dav/bad%EF%BF%BFleaf",
            "/team/dav/a?query",
            "//attacker.invalid/team/dav/",
            "https://attacker.invalid/team/dav/",
            "https://calendar.example:444/team/dav/",
            "/other/dav/"))
      assertThatThrownBy(() -> paths.parse(href)).as(href).isInstanceOf(DavException.class);
  }
}
