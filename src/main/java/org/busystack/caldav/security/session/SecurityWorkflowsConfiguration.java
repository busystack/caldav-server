package org.busystack.caldav.security.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

@Configuration
@EnableScheduling
public class SecurityWorkflowsConfiguration {
  @Bean
  SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionAttributeConcurrency() {
    // Supported PostgreSQL customization: concurrent attribute creation must not fail a session
    // save.
    return repository ->
        repository.setCreateSessionAttributeQuery(
            "INSERT INTO %TABLE_NAME%_ATTRIBUTES (SESSION_PRIMARY_ID,ATTRIBUTE_NAME,ATTRIBUTE_BYTES) VALUES (?,?,?) ON CONFLICT(SESSION_PRIMARY_ID,ATTRIBUTE_NAME) DO UPDATE SET ATTRIBUTE_BYTES=EXCLUDED.ATTRIBUTE_BYTES");
  }
}
