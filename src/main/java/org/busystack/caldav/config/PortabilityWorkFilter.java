package org.busystack.caldav.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.busystack.caldav.management.PortabilityStorage;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound concurrent multipart parsing, interchange work and private download files together. */
final class PortabilityWorkFilter extends OncePerRequestFilter {
  private final PortabilityStorage storage;

  PortabilityWorkFilter(PortabilityStorage storage) {
    this.storage = storage;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !path.startsWith("/portability/") && !multipart(request);
  }

  private static boolean multipart(HttpServletRequest request) {
    String type = request.getContentType();
    return type != null && type.stripLeading().regionMatches(true, 0, "multipart/", 0, 10);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (multipart(request)
        && !("POST".equals(request.getMethod())
            && "/portability/preview".equals(request.getServletPath()))) {
      response.sendError(415);
      return;
    }
    PortabilityStorage.Work work;
    try {
      work = storage.begin(request.getServletPath().equals("/portability/preview"));
    } catch (PortabilityStorage.CapacityException busy) {
      response.setHeader("Retry-After", "5");
      response.sendError(503);
      return;
    }
    try (work) {
      chain.doFilter(request, response);
    }
  }
}
