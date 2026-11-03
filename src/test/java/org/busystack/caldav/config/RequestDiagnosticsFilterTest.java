package org.busystack.caldav.config;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestDiagnosticsFilterTest {
  @Test
  void recordsFinalErrorStatusWithOriginalCoarseMetadataAndCorrelation() throws Exception {
    var problems = new OperationalProblems();
    var filter = new RequestDiagnosticsFilter(problems);
    var request = new MockHttpServletRequest("REPORT", "/team/dav/private-resource");
    request.setContextPath("/team");
    var first = new MockHttpServletResponse();
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    first,
                    (r, s) -> {
                      throw new ServletException("Private contents must never become a diagnostic");
                    }))
        .isInstanceOf(ServletException.class);
    assertThat(problems.recent()).isEmpty();
    request.setMethod("GET");
    request.setRequestURI("/team/error");
    request.setDispatcherType(DispatcherType.ERROR);
    var error = new MockHttpServletResponse();
    filter.doFilter(request, error, (r, s) -> error.setStatus(503));
    assertThat(error.getHeader("X-Request-ID")).isEqualTo(first.getHeader("X-Request-ID"));
    assertThat(problems.recent())
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.requestId()).isEqualTo(first.getHeader("X-Request-ID"));
              assertThat(p.surface()).isEqualTo("dav");
              assertThat(p.method()).isEqualTo("REPORT");
              assertThat(p.status()).isEqualTo(503);
              assertThat(p.toString()).doesNotContain("private-resource", "Private contents");
            });
  }

  @Test
  void handledClientErrorsNeverBecomeOperationalServerFailures() throws Exception {
    var problems = new OperationalProblems();
    var filter = new RequestDiagnosticsFilter(problems);
    var request = new MockHttpServletRequest("POST", "/portability/preview");
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (r, s) -> {
                      throw new ServletException("Upload rejected before MVC");
                    }))
        .isInstanceOf(ServletException.class);
    request.setDispatcherType(DispatcherType.ERROR);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (r, s) -> response.setStatus(413));
    assertThat(problems.recent()).isEmpty();
  }

  @Test
  void finalDispatchReplacesEarlierStatusAndProblemRetentionRemainsBounded() {
    var problems = new OperationalProblems();
    for (int n = 0; n < 60; n++) problems.record("request-" + n, "browser", "POST", 503);
    assertThat(problems.recent()).hasSize(50);
    problems.record("request-59", "browser", "POST", 500);
    assertThat(problems.recent()).hasSize(50);
    assertThat(problems.recent().getFirst().status()).isEqualTo(500);
    problems.record("request-59", "browser", "POST", 413);
    assertThat(problems.recent()).hasSize(49).noneMatch(p -> p.requestId().equals("request-59"));
  }
}
