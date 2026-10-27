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
  void davBooleanListsDistinguishStateTokensFromEtags() {
    String target = "/team/dav/calendars/user/events/item.ics";
    var disjunction = DavIf.parse("([\"a\"] Not [\"b\"]) ([\"c\"])", target, paths);
    assertThat(disjunction.matches("\"a\"")).isTrue();
    assertThat(disjunction.matches("\"c\"")).isTrue();
    assertThat(disjunction.matches("\"b\"")).isFalse();
    var complement = DavIf.parse("(Not [\"a\"]) ([\"b\"])", target, paths);
    assertThat(complement.matches("\"c\"")).isTrue();
    assertThat(complement.matches("\"a\"")).isFalse();
    var state =
        DavIf.parse(
            "(<urn:busystack:sync:current>) (Not <urn:uuid:unknown-lock> [\"a\"])", target, paths);
    assertThat(
            state.matchesStates(
                parts -> new DavIf.ResourceState(null, "urn:busystack:sync:current")))
        .isTrue();
    assertThat(state.matchesStates(parts -> new DavIf.ResourceState("\"a\"", null))).isTrue();
    assertThat(state.matchesStates(parts -> DavIf.ResourceState.ABSENT)).isFalse();
    assertThat(
            DavIf.parse("(Not <urn:uuid:unknown-lock>)", target, paths)
                .matchesStates(parts -> DavIf.ResourceState.ABSENT))
        .isTrue();
  }

  @Test
  void taggedResourceConditionsAreGroupedAndAllResourcesMustMatch() {
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
      assertThatThrownBy(
              () -> DavIf.parse(malformed, "/team/dav/calendars/user/events/item.ics", paths))
          .isInstanceOf(DavException.class);
  }

  @Test
  void entityTagConditionsTakePrecedenceOverTheirDateAlternatives() {
    var modified = java.time.Instant.parse("2020-01-01T12:00:00Z");
    var match = request("If-Match", "\"current\"");
    match.addHeader("If-Unmodified-Since", HttpDateValues.format(modified.minusSeconds(1)));
    assertThat(HttpConditions.notModified(match, "\"current\"", modified, paths)).isFalse();
    assertThat(HttpConditions.write(match, paths).unmodifiedSince()).isNull();
    var none = request("If-None-Match", "\"different\"");
    none.addHeader("If-Modified-Since", HttpDateValues.format(modified.plusSeconds(1)));
    assertThat(HttpConditions.notModified(none, "\"current\"", modified, paths)).isFalse();
  }

  @Test
  void unmodifiedPreconditionsPrecedeCacheValidationAndDatelessNodesIgnoreDates() {
    var modified = java.time.Instant.parse("2020-01-01T12:00:00Z");
    var stale = request("If-Unmodified-Since", HttpDateValues.format(modified.minusSeconds(1)));
    stale.addHeader("If-None-Match", "*");
    assertThatThrownBy(() -> HttpConditions.notModified(stale, "\"current\"", modified, paths))
        .isInstanceOf(DavException.class)
        .extracting("status")
        .isEqualTo(412);
    assertThat(
            HttpConditions.notModified(
                request("If-Modified-Since", HttpDateValues.format(modified)),
                "\"current\"",
                modified,
                paths))
        .isTrue();
    var dates = request("If-Unmodified-Since", HttpDateValues.format(modified.minusSeconds(1)));
    dates.addHeader("If-Modified-Since", HttpDateValues.format(modified.plusSeconds(1)));
    assertThat(HttpConditions.notModified(dates, null, null, paths)).isFalse();
  }
}
