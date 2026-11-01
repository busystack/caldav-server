package org.busystack.caldav.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Useful correlation/latency without logging resource paths, identities, headers or bodies. */
final class RequestDiagnosticsFilter extends OncePerRequestFilter {
  private static final org.slf4j.Logger LOG =
      LoggerFactory.getLogger(RequestDiagnosticsFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString(), previous = MDC.get("request_id");
    response.setHeader("X-Request-ID", id);
    MDC.put("request_id", id);
    long start = System.nanoTime();
    boolean failed = false;
    try {
      chain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException ex) {
      failed = true;
      throw ex;
    } finally {
      String relative = request.getRequestURI().substring(request.getContextPath().length());
      String surface =
          relative.startsWith("/dav")
              ? "dav"
              : relative.startsWith("/oauth")
                  ? "oauth"
                  : relative.startsWith("/actuator") ? "health" : "browser";
      String method =
          java.util.Set.of(
                      "GET",
                      "HEAD",
                      "POST",
                      "PUT",
                      "DELETE",
                      "OPTIONS",
                      "PROPFIND",
                      "PROPPATCH",
                      "REPORT",
                      "MKCALENDAR",
                      "MKCOL",
                      "COPY",
                      "MOVE",
                      "ACL")
                  .contains(request.getMethod())
              ? request.getMethod()
              : "other";
      LOG.info(
          "event=http_request request_id={} surface={} method={} status={} duration_ms={}",
          id,
          surface,
          method,
          failed ? 500 : response.getStatus(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      if (previous == null) MDC.remove("request_id");
      else MDC.put("request_id", previous);
    }
  }
}
