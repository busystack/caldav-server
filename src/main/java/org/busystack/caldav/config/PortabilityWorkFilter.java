package org.busystack.caldav.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound concurrent multipart parsing, interchange work and private download files together. */
final class PortabilityWorkFilter extends OncePerRequestFilter {
  private final Semaphore work = new Semaphore(2);

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !path.startsWith("/portability/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!work.tryAcquire()) {
      response.setHeader("Retry-After", "1");
      response.sendError(503);
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      work.release();
    }
  }
}
