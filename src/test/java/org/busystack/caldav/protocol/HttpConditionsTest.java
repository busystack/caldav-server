package org.busystack.caldav.protocol;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import org.busystack.caldav.config.DeploymentProperties;
import org.busystack.caldav.storage.StoreException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HttpConditionsTest {
  final DavPaths paths =
      new DavPaths(
          new DeploymentProperties(
              URI.create("https://calendar.example/team"),
              URI.create("https://calendar.example/team"),
              false));

  private MockHttpServletRequest request(String name, String value) {
    var request = new MockHttpServletRequest();
    request.setRequestURI("/team/dav/calendars/user/events/item.ics");
    request.addHeader(name, value);
    return request;
  }

  @Test
  void strongIfMatchAndWeakIfNoneMatchUseDifferentComparisonFunctions() {
    assertThatThrownBy(
            () -> HttpConditions.write(request("If-Match", "W/\"a\""), paths).check("\"a\""))
        .isInstanceOf(StoreException.class);
    assertThatThrownBy(
            () ->
                HttpConditions.write(request("If-None-Match", "W/\"a\", \"b\""), paths)
                    .check("\"a\""))
        .isInstanceOf(StoreException.class);
    HttpConditions.write(request("If-Match", "\"b\", \"a\""), paths).check("\"a\"");
    var multiple = request("If-Match", "\"a\"");
    multiple.addHeader("If-Match", "\"b\"");
    HttpConditions.write(multiple, paths).check("\"b\"");
  }

  @Test
  void davBooleanListsAreEvaluatedAtomicallyAsTheCombinedEtagConstraint() {
    var request = request("If", "([\"a\"] Not [\"b\"]) ([\"c\"])");
    HttpConditions.write(request, paths).check("\"a\"");
    HttpConditions.write(request, paths).check("\"c\"");
    assertThatThrownBy(() -> HttpConditions.write(request, paths).check("\"b\""))
        .isInstanceOf(StoreException.class);
    var complement = request("If", "(Not [\"a\"]) ([\"b\"])");
    HttpConditions.write(complement, paths).check("\"c\"");
    assertThatThrownBy(() -> HttpConditions.write(complement, paths).check("\"a\""))
        .isInstanceOf(StoreException.class);
    HttpConditions.write(request("If", "(Not <urn:uuid:unknown-lock>)"), paths).check(null, false);
    assertThatThrownBy(
            () ->
                HttpConditions.write(request("If", "(<urn:uuid:unknown-lock>)"), paths)
                    .check("\"a\""))
        .isInstanceOf(StoreException.class);
  }

  @Test
  void taggedResourceConditionsAreGroupedAndAllResourcesMustMatch() {
    HttpConditions.write(
            request(
                "If",
                "<https://calendar.example/team/dav/calendars/user/events/item.ics> ([\"a\"])"),
            paths)
        .check("\"a\"");
    var tagged =
        DavIf.parse(
            "<https://calendar.example/team/dav/calendars/user/events/other.ics> ([\"a\"]) <https://calendar.example/team/dav/calendars/user/events/item.ics> ([\"b\"])",
            "/team/dav/calendars/user/events/item.ics",
            paths);
    assertThat(tagged.matchesAll(parts -> parts.getLast().equals("other.ics") ? "\"a\"" : "\"b\""))
        .isTrue();
    assertThat(tagged.matchesAll(parts -> "\"a\"")).isFalse();
    for (String malformed :
        new String[] {
          "()", "([\"a\"]", "Not [\"a\"]", "(garbage)", "([\"a\"]) trailing", "([\"a\"]) <urn:bad>"
        })
      assertThatThrownBy(() -> HttpConditions.write(request("If", malformed), paths))
          .isInstanceOf(DavException.class);
  }
}
