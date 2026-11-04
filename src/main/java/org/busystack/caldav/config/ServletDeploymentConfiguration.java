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
      requestDiagnostics(OperationalProblems problems) {
    var registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new RequestDiagnosticsFilter(problems));
    registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1);
    registration.setDispatcherTypes(
        jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ERROR);
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
  org.springframework.boot.web.servlet.FilterRegistrationBean<PortabilityWorkFilter>
      portabilityWork(org.busystack.caldav.management.PortabilityStorage storage) {
    var registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new PortabilityWorkFilter(storage));
    registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 2);
    return registration;
  }

  @Bean
  jakarta.servlet.MultipartConfigElement multipartConfig(
      org.busystack.caldav.management.PortabilityStorage storage) throws java.io.IOException {
    return new jakarta.servlet.MultipartConfigElement(
        storage.multipartDirectory().toString(),
        org.busystack.caldav.management.PortabilityStorage.FILE_BYTES,
        org.busystack.caldav.management.PortabilityStorage.UPLOAD_BYTES,
        0);
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
