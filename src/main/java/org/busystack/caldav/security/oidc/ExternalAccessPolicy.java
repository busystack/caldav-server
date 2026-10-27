package org.busystack.caldav.security.oidc;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Durable per-account boundary for independently issued DAV and OAuth credentials. */
@Service
public class ExternalAccessPolicy {
  private final JdbcTemplate jdbc;
  private final OidcProperties properties;

  public ExternalAccessPolicy(JdbcTemplate jdbc, OidcProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  public boolean isCurrent(UUID accountId) {
    var mappings =
        jdbc.query(
            "SELECT issuer,subject,source,last_verified_at FROM external_identities WHERE account_id=?",
            (rs, row) ->
                new Mapping(
                    rs.getString("issuer"),
                    rs.getString("subject"),
                    rs.getString("source"),
                    rs.getTimestamp("last_verified_at").toInstant()),
            accountId);
    if (mappings.isEmpty()) return true;
    if (!properties.enabled()) return false;
    var mapping = mappings.getFirst();
    return properties.issuer().toString().equals(mapping.issuer())
        && (!mapping.source().equals("ALLOWLIST")
            || properties.allowedUsers().stream()
                .anyMatch(user -> user.subject().equals(mapping.subject())))
        && mapping
            .lastVerified()
            .isAfter(Instant.now().minus(properties.maximumAuthenticationAge()));
  }

  public boolean isExternal(UUID accountId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM external_identities WHERE account_id=?)",
            Boolean.class,
            accountId));
  }

  private record Mapping(String issuer, String subject, String source, Instant lastVerified) {}
}
