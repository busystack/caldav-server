package org.busystack.caldav.management;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.ui.ExtendedModelMap;

class PortalErrorsTest {
  @Test
  void transactionDeadlineFailureReturnsSafeRecoveryToTheExistingPreview() {
    var request = new MockHttpServletRequest();
    String path = "/portability/import/11111111-1111-4111-8111-111111111111";
    request.setServletPath(path);
    var response = new MockHttpServletResponse();
    var model = new ExtendedModelMap();
    var errors = new PortalErrors(mock(FormRecovery.class));
    String view =
        errors.failure(
            new TransactionTimedOutException("private internal deadline"),
            request,
            response,
            model,
            null);
    assertThat(view).isEqualTo("portal-error");
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(model.get("retryPath")).isEqualTo(path);
    assertThat(model.get("message").toString()).doesNotContain("private internal deadline");
  }
}
