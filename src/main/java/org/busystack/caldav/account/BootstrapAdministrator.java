package org.busystack.caldav.account;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Offline operator-only command: no network setup endpoint and no generated default password. */
@Component
@ConditionalOnProperty(name = "busystack.bootstrap", havingValue = "true")
public class BootstrapAdministrator implements ApplicationRunner {
  private final AccountService accounts;
  private final JdbcTemplate jdbc;
  private final Environment environment;

  public BootstrapAdministrator(
      AccountService accounts, JdbcTemplate jdbc, Environment environment) {
    this.accounts = accounts;
    this.jdbc = jdbc;
    this.environment = environment;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception {
    if (!"none".equals(environment.getProperty("spring.main.web-application-type")))
      throw new IllegalArgumentException(
          "Bootstrap requires --spring.main.web-application-type=none");
    jdbc.execute("LOCK TABLE accounts IN EXCLUSIVE MODE");
    if (jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class) != 0)
      throw new IllegalStateException("Bootstrap is permitted only on an empty account database");
    String username = environment.getRequiredProperty("busystack.bootstrap-username");
    Path file = Path.of(environment.getRequiredProperty("busystack.bootstrap-password-file"));
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
    if (permissions.stream()
        .anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
      throw new IllegalArgumentException("Password file must be accessible only to its owner");
    String password = Files.readString(file).stripTrailing();
    accounts.provision(username, username, password, true);
  }
}
