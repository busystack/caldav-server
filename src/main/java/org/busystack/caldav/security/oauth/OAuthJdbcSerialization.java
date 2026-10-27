package org.busystack.caldav.security.oauth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/** Add the framework's WebAuthn module to its JDBC allowlist through supported mapper hooks. */
final class OAuthJdbcSerialization {
  private OAuthJdbcSerialization() {}

  static JdbcOAuth2AuthorizationService service(
      JdbcTemplate jdbc, RegisteredClientRepository clients) {
    var webauthn = new WebauthnJacksonModule();
    var validator = BasicPolymorphicTypeValidator.builder();
    webauthn.configurePolymorphicTypeValidator(validator);
    var modules =
        SecurityJacksonModules.getModules(OAuthJdbcSerialization.class.getClassLoader(), validator);
    var mapper = JsonMapper.builder().addModules(modules).addModule(webauthn).build();
    var service = new JdbcOAuth2AuthorizationService(jdbc, clients);
    service.setAuthorizationRowMapper(
        new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(clients, mapper));
    service.setAuthorizationParametersMapper(
        new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper(mapper));
    return service;
  }
}
