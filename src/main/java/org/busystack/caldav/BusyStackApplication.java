package org.busystack.caldav;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BusyStackApplication {
  public static void main(String[] args) {
    var application = new SpringApplication(BusyStackApplication.class);
    application.addListeners(
        (org.springframework.context.ApplicationListener<
                org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent>)
            event -> {
              var environment = event.getEnvironment();
              long operations =
                  java.util.stream.Stream.of(
                          "busystack.bootstrap",
                          "busystack.recover-account",
                          "busystack.recover-external-account")
                      .filter(name -> environment.getProperty(name, Boolean.class, false))
                      .count();
              if (operations > 1
                  || (operations == 1
                      && !"none"
                          .equalsIgnoreCase(
                              environment.getProperty("spring.main.web-application-type"))))
                throw new IllegalArgumentException(
                    "Choose one offline account operation and set spring.main.web-application-type=none");
            });
    var context = application.run(args);
    if ("none"
        .equalsIgnoreCase(context.getEnvironment().getProperty("spring.main.web-application-type")))
      context.close();
  }
}
