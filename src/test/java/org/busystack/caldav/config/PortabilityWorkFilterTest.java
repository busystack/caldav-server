package org.busystack.caldav.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.busystack.caldav.management.PortabilityStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PortabilityWorkFilterTest {
  @TempDir Path temporary;

  @Test
  void earlySecurityRejectionRemovesParsedMultipartBuffersBeforeReleasingCapacity()
      throws Exception {
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var multipart = storage.multipartDirectory().resolve("upload_rejected_0001.tmp");
      var filter = new PortabilityWorkFilter(storage);
      var response = new MockHttpServletResponse();
      filter.doFilter(
          request("/team", "/portability/preview", "multipart/form-data; boundary=fixture"),
          response,
          (req, res) -> {
            java.nio.file.Files.writeString(multipart, "private rejected upload");
            ((jakarta.servlet.http.HttpServletResponse) res).sendError(403);
          });
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(multipart).doesNotExist();
      assertThat(storage.reservedBytes()).isZero();
    }
  }

  @Test
  void unsupportedMultipartSurfacesNeverReachParsingOrAuthentication() throws Exception {
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var filter = new PortabilityWorkFilter(storage);
      for (String prefix : new String[] {"", "/team"}) {
        for (String route :
            new String[] {
              "/login",
              "/login/webauthn",
              "/oauth2/token",
              "/collections",
              "/admin/groups",
              "/dav/calendars/example/object.ics"
            }) {
          var request = request(prefix, route, "MuLtIpArT/form-data; boundary=fixture");
          var response = new MockHttpServletResponse();
          var parsed = new AtomicBoolean();
          filter.doFilter(request, response, (req, res) -> parsed.set(true));
          assertThat(response.getStatus()).isEqualTo(415);
          assertThat(parsed.get()).isFalse();
          assertThat(storage.reservedBytes()).isZero();
        }
      }
    }
  }

  @Test
  void previewReservesMultipartAndBothPrivateProcessingFilesBeforeParsing() throws Exception {
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var filter = new PortabilityWorkFilter(storage);
      var response = new MockHttpServletResponse();
      var parsed = new AtomicBoolean();
      filter.doFilter(
          request("/team", "/portability/preview", "multipart/form-data; boundary=fixture"),
          response,
          (req, res) -> {
            assertThat(storage.reservedBytes()).isEqualTo(161 * PortabilityStorage.MIB);
            parsed.set(true);
          });
      assertThat(parsed.get()).isTrue();
      assertThat(storage.reservedBytes()).isZero();
    }
  }

  @Test
  void ordinaryDavAndAuthenticationBodiesKeepTheirExistingHandlers() throws Exception {
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var filter = new PortabilityWorkFilter(storage);
      for (String route :
          new String[] {"/dav/calendars/example/object.ics", "/login", "/login/webauthn"}) {
        var parsed = new AtomicBoolean();
        filter.doFilter(
            request("", route, "application/json"),
            new MockHttpServletResponse(),
            (req, res) -> parsed.set(true));
        assertThat(parsed.get()).isTrue();
        assertThat(storage.reservedBytes()).isZero();
      }
    }
  }

  private MockHttpServletRequest request(String prefix, String route, String type) {
    var request = new MockHttpServletRequest("POST", prefix + route);
    request.setContextPath(prefix);
    request.setServletPath(route);
    request.setContentType(type);
    return request;
  }
}
