package org.busystack.caldav.security.passkey;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.transaction.annotation.Transactional;

/** Resolves mutable login names through stable internal account IDs. */
public class AccountPasskeyUsers implements PublicKeyCredentialUserEntityRepository {
  private final JdbcTemplate jdbc;

  public AccountPasskeyUsers(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PublicKeyCredentialUserEntity findById(Bytes id) {
    return find("u.id=?", id.toBase64UrlString());
  }

  @Override
  public PublicKeyCredentialUserEntity findByUsername(String username) {
    return find("a.username=?", username);
  }

  private PublicKeyCredentialUserEntity find(String predicate, String value) {
    return jdbc
        .query(
            "SELECT u.id,a.username,a.display_name FROM user_entities u JOIN accounts a ON a.id=u.account_id WHERE "
                + predicate,
            (rs, row) ->
                ImmutablePublicKeyCredentialUserEntity.builder()
                    .id(Bytes.fromBase64(rs.getString("id")))
                    .name(rs.getString("username"))
                    .displayName(rs.getString("display_name"))
                    .build(),
            value)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  @Transactional
  public void save(PublicKeyCredentialUserEntity entity) {
    List<UUID> accounts =
        jdbc.queryForList(
            "SELECT id FROM accounts WHERE username=? AND enabled FOR UPDATE",
            UUID.class,
            entity.getName());
    if (accounts.size() != 1) throw new DisabledException("Account unavailable");
    var existing = findByUsername(entity.getName());
    if (existing != null && !existing.getId().equals(entity.getId()))
      throw new IllegalStateException("Passkey enrollment changed; start again");
    jdbc.update(
        "INSERT INTO user_entities(id,account_id,name,display_name) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,display_name=EXCLUDED.display_name",
        entity.getId().toBase64UrlString(),
        accounts.getFirst(),
        entity.getName(),
        entity.getDisplayName());
  }

  @Override
  @Transactional
  public void delete(Bytes id) {
    jdbc.update("DELETE FROM user_entities WHERE id=?", id.toBase64UrlString());
  }

  public boolean hasCredentials(String username) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM user_credentials c JOIN user_entities u ON u.id=c.user_entity_user_id JOIN accounts a ON a.id=u.account_id WHERE a.username=?)",
            Boolean.class,
            username));
  }
}
