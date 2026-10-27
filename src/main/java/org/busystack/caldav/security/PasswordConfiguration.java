package org.busystack.caldav.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfiguration {
  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }
}
