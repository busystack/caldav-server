package org.busystack.caldav.investigation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

import jakarta.servlet.http.HttpServletRequest;
import org.bedework.access.CurrentAccess;
import org.bedework.caldav.server.CalDAVCollection;
import org.bedework.caldav.server.CaldavBWServlet;
import org.bedework.caldav.server.sysinterface.CalDAVAuthProperties;
import org.bedework.caldav.server.sysinterface.CalDAVSystemProperties;
import org.bedework.caldav.server.sysinterface.SysIntf;
import org.bedework.webdav.servlet.shared.UrlHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/** Compatibility investigation only. The mock backend has no production path. */
@SpringBootTest(classes = BedeworkHttpTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN"})
public class BedeworkHttpTest {
  public static SysIntf backend;
  static volatile String lastPrincipal;
  static CalDAVCollection<?> probeCollection;
  @LocalServerPort int port;

  @BeforeAll static void backendAdapter() throws Exception {
    backend = mock(SysIntf.class);
    when(backend.init(any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), nullable(String.class)))
        .thenAnswer(call -> {
          HttpServletRequest request = call.getArgument(0);
          lastPrincipal = request.getUserPrincipal().getName();
          return lastPrincipal;
        });
    when(backend.getSystemProperties()).thenReturn(mock(CalDAVSystemProperties.class));
    when(backend.getAuthProperties()).thenReturn(mock(CalDAVAuthProperties.class));
    when(backend.getUrlHandler()).thenReturn(new UrlHandler("https://calendar.example.test", "/dav", false));
    when(backend.getDefaultContentType()).thenReturn("text/calendar");
    doReturn(List.of()).when(backend).getEvents(any(), any(), any(), any());
    CalDAVCollection<?> collection = mock(CalDAVCollection.class);
    probeCollection = collection;
    doReturn(collection).when(collection).resolveAlias(anyBoolean());
    when(collection.getPath()).thenReturn("/");
    when(collection.getName()).thenReturn("probe");
    when(collection.getCalType()).thenReturn(CalDAVCollection.calTypeCalendarCollection);
    when(collection.getSupportedComponents()).thenReturn(List.of("VEVENT", "VTODO"));
    doReturn(collection).when(backend).getCollection("/");
    doReturn(collection).when(backend).getCollection("/cal");
    CurrentAccess access = mock(CurrentAccess.class);
    when(access.getAccessAllowed()).thenReturn(true);
    when(backend.checkAccess(any(), anyInt(), anyBoolean())).thenReturn(access);

    // Bedework requires a public concrete class with a no-argument constructor.
    // Generate a transparent forwarding adapter, using reflection for its broad interface.
    Path output = Path.of("target/test-classes").toAbsolutePath();
    Path source = Path.of("target/generated-probe/ProbeBackend.java");
    Files.createDirectories(source.getParent());
    StringBuilder generated = new StringBuilder("package org.busystack.caldav.investigation; public class ProbeBackend implements org.bedework.caldav.server.sysinterface.SysIntf {");
    for (var method : SysIntf.class.getMethods()) {
      if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.isDefault()) continue;
      generated.append("public ").append(method.getReturnType().getCanonicalName()).append(' ').append(method.getName()).append('(');
      var parameters = method.getParameterTypes();
      for (int i = 0; i < parameters.length; i++) {
        if (i > 0) generated.append(',');
        generated.append(parameters[i].getCanonicalName()).append(" p").append(i);
      }
      generated.append(") {");
      if (method.getReturnType() != void.class) generated.append("return ");
      generated.append("BedeworkHttpTest.backend.").append(method.getName()).append('(');
      for (int i = 0; i < parameters.length; i++) {
        if (i > 0) generated.append(',');
        generated.append('p').append(i);
      }
      generated.append(");}");
    }
    Files.writeString(source, generated.append('}'));
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "-classpath", System.getProperty("java.class.path"), "-d", output.toString(), source.toString()));
  }

  @Test void realContainerAuthenticatesBeforeBedeworkAndPassesPrincipal() throws Exception {
    var unauthorized = request("OPTIONS", "/dav/", false, "");
    assertEquals(401, unauthorized.statusCode());
    assertTrue(unauthorized.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic"));
    var authorized = request("OPTIONS", "/dav/", true, "");
    assertEquals(200, authorized.statusCode(), authorized.body());
    assertEquals("probe-user", lastPrincipal);
    assertTrue(authorized.headers().firstValue("Allow").orElse("").contains("REPORT"));
    // This is an observed mismatch with the BusyStack profile, not desired product behavior.
    assertTrue(authorized.headers().firstValue("DAV").orElse("").contains("calendar-auto-schedule"));
  }

  @Test void propfindRunsThroughRealServletAndSecurityFirewall() throws Exception {
    var response = request("PROPFIND", "/dav/", true,
        "<d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop><d:displayname/><c:supported-calendar-component-set/></d:prop></d:propfind>");
    assertEquals(207, response.statusCode(), response.body());
    assertTrue(response.body().contains("VTODO"), response.body());
  }

  @Test void queryAndExpandPropertyReachReportHandlers() throws Exception {
    var query = request("REPORT", "/dav/", true,
        "<c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop><d:getetag/></d:prop><c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"VTODO\"/></c:comp-filter></c:filter></c:calendar-query>");
    assertEquals(207, query.statusCode(), query.body());
    var expand = request("REPORT", "/dav/", true,
        "<d:expand-property xmlns:d=\"DAV:\"><d:property name=\"displayname\" namespace=\"DAV:\"/></d:expand-property>");
    assertEquals(207, expand.statusCode(), expand.body());
  }

  @Test void failedProppatchStillCallsBackendUpdateInReleasedEngine() throws Exception {
    clearInvocations(backend, probeCollection);
    var response = request("PROPPATCH", "/dav/", true,
        "<d:propertyupdate xmlns:d=\"DAV:\"><d:set><d:prop><d:displayname>partial-change</d:displayname><d:getetag>forbidden</d:getetag></d:prop></d:set></d:propertyupdate>");
    assertEquals(207, response.statusCode(), response.body());
    assertTrue(response.body().contains("403"), response.body());
    // Records an upstream integration defect, not accepted BusyStack behavior:
    // the successful setter is followed by update despite the protected-property failure.
    verify(probeCollection).setDisplayName("partial-change");
    verify(backend).updateCollection(probeCollection);
    verify(backend, never()).rollback();
    assertFalse(response.body().contains("424"), response.body());
  }

  @Test void putEncodesQuotedEtagInReleasedEngine() throws Exception {
    var calendar = mock(org.bedework.caldav.server.SysiIcalendar.class);
    var event = mock(org.bedework.caldav.server.CalDAVEvent.class);
    when(event.isNew()).thenReturn(true);
    when(event.getName()).thenReturn("probe.ics");
    when(event.getUid()).thenReturn("probe-uid");
    when(event.getEtag()).thenReturn("\"probe-etag\"");
    doReturn(event).when(calendar).getOnlyEvent();
    when(backend.fromIcal(any(), any(java.io.Reader.class), anyString(), any(), anyBoolean())).thenReturn(calendar);
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dav/cal/probe.ics"))
        .timeout(java.time.Duration.ofSeconds(20))
        .header("Content-Type", "text/calendar")
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("probe-user:probe-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .PUT(HttpRequest.BodyPublishers.ofString("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n")).build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(201, response.statusCode(), response.body());
    // Records the header defect. An HTTP entity-tag must retain the surrounding quotes.
    assertEquals("%22probe-etag%22", response.headers().firstValue("ETag").orElse(""));
  }

  @Test void multigetDecodesLiteralPlusAsSpaceInReleasedEngine() throws Exception {
    clearInvocations(backend);
    var response = request("REPORT", "/dav/cal/", true,
        "<c:calendar-multiget xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop><d:getetag/></d:prop><d:href>/dav/cal/a+b.ics</d:href></c:calendar-multiget>");
    assertEquals(207, response.statusCode(), response.body());
    verify(backend).getCollection("/dav/cal/a b.ics");
    verify(backend, never()).getCollection("/dav/cal/a+b.ics");
  }

  HttpResponse<String> request(String method, String path, boolean authenticated, String body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(java.time.Duration.ofSeconds(20)).header("Depth", "0").header("Content-Type", "application/xml")
        .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (authenticated) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("probe-user:probe-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @SpringBootConfiguration @EnableAutoConfiguration
  static class Application {
    @Bean ServletRegistrationBean<CaldavBWServlet> bedework() {
      var registration = new ServletRegistrationBean<>(new CaldavBWServlet(), "/dav/*");
      registration.setInitParameters(Map.of("sysintfImpl", "org.busystack.caldav.investigation.ProbeBackend", "preserve-session", "true"));
      return registration;
    }
    @Bean UserDetailsService users() {
      var encoder = org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
      return new InMemoryUserDetailsManager(User.withUsername("probe-user").password(encoder.encode("probe-password")).roles("USER").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(a -> a.anyRequest().authenticated()).httpBasic(Customizer.withDefaults())
          .csrf(c -> c.ignoringRequestMatchers("/dav/**"))
          .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS)).build();
    }
    @Bean WebSecurityCustomizer methods() {
      var firewall = new StrictHttpFirewall();
      firewall.setAllowedHttpMethods(List.of("GET", "HEAD", "POST", "OPTIONS", "PUT", "DELETE", "PROPFIND", "PROPPATCH", "REPORT", "MKCALENDAR", "MKCOL", "COPY", "MOVE", "ACL"));
      return web -> web.httpFirewall(firewall);
    }
  }
}
