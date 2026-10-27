package org.busystack.caldav.account;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Trusted local operator recovery; stop every application instance before invoking. */
@Component
@ConditionalOnProperty(name = "busystack.recover-account", havingValue = "true")
public class OfflineAccountRecovery implements ApplicationRunner {
  private final AccountService accounts;
  private final Environment environment;

  public OfflineAccountRecovery(AccountService accounts, Environment environment) {
    this.accounts = accounts;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!"none".equals(environment.getProperty("spring.main.web-application-type"))
        || environment.getProperty("busystack.bootstrap", Boolean.class, false))
      throw new IllegalArgumentException(
          "Recovery requires a separate --spring.main.web-application-type=none operation");
    Path file = Path.of(environment.getRequiredProperty("busystack.recovery-password-file"));
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) > 1024
        || Files.getPosixFilePermissions(file).stream()
            .anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
      throw new IllegalArgumentException(
          "Recovery password file must be a small regular owner-only file");
    accounts.recoverOffline(
        environment.getRequiredProperty("busystack.recovery-username"),
        Files.readString(file).stripTrailing());
  }
}
