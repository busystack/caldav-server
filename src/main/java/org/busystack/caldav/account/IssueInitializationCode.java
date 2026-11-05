package org.busystack.caldav.account;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Only activated by the local, non-web operator invocation. */
@Component
@ConditionalOnProperty(name = "busystack.issue-initialization-code", havingValue = "true")
public class IssueInitializationCode implements ApplicationRunner {
  private final InitializationService initialization;
  private final org.springframework.core.env.Environment environment;

  public IssueInitializationCode(
      InitializationService initialization, org.springframework.core.env.Environment environment) {
    this.initialization = initialization;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!"none".equals(environment.getProperty("spring.main.web-application-type")))
      throw new IllegalArgumentException(
          "Initialization code issuance requires an offline process");
    String code = initialization.issueCode(); // Transaction commits before disclosure.
    System.out.println("Initialization code (single use, valid for 15 minutes):");
    System.out.println(code);
  }
}
