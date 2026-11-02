package org.busystack.caldav.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.ui.ExtendedModelMap;

class PortalPageAdviceTest {
  @Test
  void protocolRequestsDoNotLoadBrowserPresentationOrAccountMetadata() {
    var management = mock(ManagementService.class);
    var advice = new PortalPageAdvice(management);
    for (String path : List.of("/dav", "/dav/calendars/fixture/", "/.well-known/caldav")) {
      var request = new MockHttpServletRequest();
      request.setServletPath(path);
      var model = new ExtendedModelMap();
      advice.layout(
          UsernamePasswordAuthenticationToken.authenticated("fixture", null, List.of()),
          request,
          model);
      assertThat(model).isEmpty();
    }
    verifyNoInteractions(management);
  }

  @Test
  void databaseFailureKeepsTheSafeErrorLayoutAvailableWithoutIdentityDetails() {
    var management = mock(ManagementService.class);
    var auth = UsernamePasswordAuthenticationToken.authenticated("fixture", null, List.of());
    when(management.current(auth))
        .thenThrow(new DataAccessResourceFailureException("private SQL must not reach the view"));
    var request = new MockHttpServletRequest();
    request.setServletPath("/error");
    var model = new ExtendedModelMap();
    new PortalPageAdvice(management).layout(auth, request, model);
    assertThat(model.get("viewer")).isNull();
    assertThat(model.values().toString()).doesNotContain("private SQL", "fixture");
    verify(management).current(auth);
    verifyNoMoreInteractions(management);
  }
}
