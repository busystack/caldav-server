package org.busystack.caldav.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.web.AuthenticationEntryPoint;

/** RFC 6750 challenge for the selected opaque-token DAV profile, without optional metadata. */
final class DavBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {
  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
    int status = HttpServletResponse.SC_UNAUTHORIZED;
    List<String> parameters = new ArrayList<>();
    if (exception instanceof OAuth2AuthenticationException authentication) {
      var error = authentication.getError();
      parameter(parameters, "error", error.getErrorCode());
      parameter(parameters, "error_description", error.getDescription());
      parameter(parameters, "error_uri", error.getUri());
      if (error instanceof BearerTokenError bearer) {
        status = bearer.getHttpStatus().value();
        parameter(parameters, "scope", bearer.getScope());
      }
    }
    response.addHeader(
        HttpHeaders.WWW_AUTHENTICATE,
        parameters.isEmpty() ? "Bearer" : "Bearer " + String.join(", ", parameters));
    response.setStatus(status);
  }

  private static void parameter(List<String> parameters, String name, String value) {
    if (value == null || value.isBlank()) return;
    // These values originate in trusted protocol errors, not request-controlled header text.
    if (value
        .codePoints()
        .anyMatch(
            character ->
                character < 0x20
                    || character > 0x7e
                    || character == '"'
                    || character == '\\'
                    || name.equals("error_uri") && character == ' ')) return;
    parameters.add(name + "=\"" + value + "\"");
  }
}
