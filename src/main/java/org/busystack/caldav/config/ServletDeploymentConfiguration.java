package org.busystack.caldav.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Context, cookies and generated browser links all use the configured external path. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServletDeploymentConfiguration {
  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<RequestDiagnosticsFilter>
      requestDiagnostics() {
    var registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new RequestDiagnosticsFilter());
    registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<CanonicalOriginFilter>
      canonicalOrigin(DeploymentProperties deployment) {
    var registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new CanonicalOriginFilter(deployment.publicBaseUrl()));
    registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> deploymentContext(
      DeploymentProperties deployment, Environment environment) {
    String path = deployment.publicBaseUrl().getPath();
    String explicit = environment.getProperty("server.servlet.context-path");
    if (explicit != null && !explicit.equals(path))
      throw new IllegalArgumentException(
          "server.servlet.context-path must match the path of busystack.public-base-url");
    return factory -> factory.setContextPath(path);
  }
}
