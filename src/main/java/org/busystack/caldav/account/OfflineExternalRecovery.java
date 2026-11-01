package org.busystack.caldav.account;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "busystack.recover-external-account", havingValue = "true")
public class OfflineExternalRecovery implements ApplicationRunner {
  private final AccountService accounts;
  private final Environment environment;

  public OfflineExternalRecovery(AccountService accounts, Environment environment) {
    this.accounts = accounts;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!"none".equals(environment.getProperty("spring.main.web-application-type"))
        || environment.getProperty("busystack.bootstrap", Boolean.class, false)
        || environment.getProperty("busystack.recover-account", Boolean.class, false))
      throw new IllegalArgumentException(
          "External recovery requires a separate --spring.main.web-application-type=none operation");
    accounts.recoverExternalOffline(
        environment.getRequiredProperty("busystack.recovery-username"),
        environment.getRequiredProperty("busystack.recovery-issuer"),
        environment.getRequiredProperty("busystack.recovery-subject"));
  }
}
