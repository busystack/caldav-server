package org.busystack.caldav.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** All application URL builders, including security saved requests, use operator configuration. */
final class CanonicalOriginFilter extends OncePerRequestFilter {
  private static final Set<String> FORWARDING_HEADERS =
      Set.of(
          "forwarded",
          "x-forwarded-for",
          "x-forwarded-host",
          "x-forwarded-proto",
          "x-forwarded-port",
          "x-forwarded-prefix",
          "x-forwarded-server",
          "x-forwarded-ssl",
          "x-real-ip");
  private final URI base;

  CanonicalOriginFilter(URI base) {
    this.base = base;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public String getScheme() {
            return base.getScheme();
          }

          @Override
          public boolean isSecure() {
            return "https".equals(base.getScheme());
          }

          @Override
          public String getServerName() {
            return base.getHost();
          }

          @Override
          public int getServerPort() {
            return base.getPort() >= 0 ? base.getPort() : (isSecure() ? 443 : 80);
          }

          @Override
          public StringBuffer getRequestURL() {
            return new StringBuffer(
                base.getScheme() + "://" + base.getRawAuthority() + getRequestURI());
          }

          @Override
          public String getHeader(String name) {
            if ("host".equalsIgnoreCase(name)) return base.getRawAuthority();
            return hidden(name) ? null : super.getHeader(name);
          }

          @Override
          public java.util.Enumeration<String> getHeaders(String name) {
            if ("host".equalsIgnoreCase(name))
              return Collections.enumeration(java.util.List.of(base.getRawAuthority()));
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
          }

          @Override
          public java.util.Enumeration<String> getHeaderNames() {
            return Collections.enumeration(
                Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !hidden(name))
                    .toList());
          }
        },
        new HttpServletResponseWrapper(response) {
          private String redirect(String location) {
            URI reference = URI.create(location);
            if (reference.getRawAuthority() != null && !reference.isAbsolute())
              throw new IllegalArgumentException("Network-path redirects are not supported");
            if (reference.isAbsolute()) return location;
            URI current =
                URI.create(
                    base.getScheme() + "://" + base.getRawAuthority() + request.getRequestURI());
            return current.resolve(reference).toASCIIString();
          }

          @Override
          public void sendRedirect(String location) throws IOException {
            super.sendRedirect(redirect(location));
          }

          @Override
          public void sendRedirect(String location, int status, boolean clearBuffer)
              throws IOException {
            super.sendRedirect(redirect(location), status, clearBuffer);
          }

          @Override
          public void setHeader(String name, String value) {
            super.setHeader(
                name, "Location".equalsIgnoreCase(name) && value != null ? redirect(value) : value);
          }

          @Override
          public void addHeader(String name, String value) {
            super.addHeader(
                name, "Location".equalsIgnoreCase(name) && value != null ? redirect(value) : value);
          }
        });
  }

  private static boolean hidden(String name) {
    return name != null && FORWARDING_HEADERS.contains(name.toLowerCase(Locale.ROOT));
  }
}
