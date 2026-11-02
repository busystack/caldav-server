package org.busystack.caldav.management;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.busystack.caldav.config.DeploymentProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Administrative aggregate diagnostics never load or reveal calendar content. */
@Controller
public final class OperationalDiagnostics {
  private final ManagementService management;
  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;
  private final DeploymentProperties deployment;
  private final org.busystack.caldav.config.OperationalProblems problems;
  private final org.springframework.beans.factory.ObjectProvider<
          org.springframework.boot.info.BuildProperties>
      builds;

  public OperationalDiagnostics(
      ManagementService management,
      JdbcTemplate jdbc,
      MeterRegistry meters,
      DeploymentProperties deployment,
      org.busystack.caldav.config.OperationalProblems problems,
      org.springframework.beans.factory.ObjectProvider<
              org.springframework.boot.info.BuildProperties>
          builds) {
    this.management = management;
    this.jdbc = jdbc;
    this.meters = meters;
    this.deployment = deployment;
    this.problems = problems;
    this.builds = builds;
  }

  @GetMapping({"/admin", "/admin/diagnostics"})
  String diagnostics(
      Authentication authentication,
      Model model,
      jakarta.servlet.http.HttpServletResponse response,
      jakarta.servlet.http.HttpServletRequest request) {
    management.requireAdministrator(authentication);
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("baseUrl", deployment.publicBaseUrl().toString());
    model.addAttribute("issuer", deployment.issuer().toString());
    model.addAttribute("javaVersion", Runtime.version().toString());
    var build = builds.getIfAvailable();
    model.addAttribute(
        "applicationVersion", build == null ? "Unknown (development build)" : build.getVersion());
    model.addAttribute("problems", problems.recent());
    model.addAttribute(
        "databaseVersion",
        jdbc.queryForObject("SELECT current_setting('server_version')", String.class));
    model.addAttribute(
        "migration",
        jdbc.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
            String.class));
    model.addAttribute(
        "enabledAccounts",
        jdbc.queryForObject("SELECT count(*) FROM accounts WHERE enabled", Long.class));
    model.addAttribute(
        "disabledAccounts",
        jdbc.queryForObject("SELECT count(*) FROM accounts WHERE NOT enabled", Long.class));
    model.addAttribute(
        "eventCollections",
        jdbc.queryForObject(
            "SELECT count(*) FROM collections WHERE component='VEVENT'", Long.class));
    model.addAttribute(
        "taskCollections",
        jdbc.queryForObject(
            "SELECT count(*) FROM collections WHERE component='VTODO'", Long.class));
    model.addAttribute("databaseStatus", "Available — database queries succeeded for this request");
    model.addAttribute(
        "collections", jdbc.queryForObject("SELECT count(*) FROM collections", Long.class));
    model.addAttribute(
        "resources", jdbc.queryForObject("SELECT count(*) FROM calendar_resources", Long.class));
    long requests = 0, serverErrors = 0;
    double total = 0, max = 0;
    for (var timer : meters.find("http.server.requests").timers()) {
      requests += timer.count();
      total += timer.totalTime(TimeUnit.MILLISECONDS);
      max = Math.max(max, timer.max(TimeUnit.MILLISECONDS));
      String status = timer.getId().getTag("status");
      if (status != null && status.startsWith("5")) serverErrors += timer.count();
    }
    model.addAttribute("requests", requests);
    model.addAttribute("serverErrors", serverErrors);
    model.addAttribute("meanMillis", requests == 0 ? 0 : Math.round(total / requests));
    model.addAttribute("maxMillis", Math.round(max));
    return request.getServletPath().equals("/admin") ? "administration" : "diagnostics";
  }
}
