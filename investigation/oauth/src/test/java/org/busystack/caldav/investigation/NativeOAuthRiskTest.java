package org.busystack.caldav.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Test-only in-memory stock framework probe; this is not the application account system. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = NativeOAuthRiskTest.ProbeApplication.class,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN"})
class NativeOAuthRiskTest {
  static final String VERIFIER = "a-valid-pkce-code-verifier-for-native-client-0123456789";
  static final String CALLBACK = "http://127.0.0.1:34567/callback";
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
  @LocalServerPort int port;

  @Test
  void publicS256CodeExchangeWorksButStockFrameworkDoesNotIssueRefreshToken() throws Exception {
    String code = authorizationCode();
    HttpResponse<String> tokens = post(Map.of("grant_type", "authorization_code", "client_id", "native-probe", "redirect_uri", CALLBACK, "code", code, "code_verifier", VERIFIER));
    assertThat(tokens.statusCode()).isEqualTo(200);
    assertThat(tokens.body()).contains("\"access_token\"", "\"token_type\":\"Bearer\"").doesNotContain("\"refresh_token\"");

    HttpResponse<String> replay = post(Map.of("grant_type", "authorization_code", "client_id", "native-probe", "redirect_uri", CALLBACK, "code", code, "code_verifier", VERIFIER));
    assertThat(replay.statusCode()).isEqualTo(400);
    assertThat(replay.body()).contains("invalid_grant");
  }

  @Test
  void wrongPkceVerifierIsRejected() throws Exception {
    HttpResponse<String> response = post(Map.of("grant_type", "authorization_code", "client_id", "native-probe", "redirect_uri", CALLBACK, "code", authorizationCode(), "code_verifier", "wrong-pkce-code-verifier-012345678901234567890123456789"));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("invalid_grant");
  }

  @Test
  void stockClientAuthenticationDoesNotRecognizePublicRefreshRequest() throws Exception {
    HttpResponse<String> response = post(Map.of("grant_type", "refresh_token", "client_id", "native-probe", "refresh_token", "not-a-real-token"));
    assertThat(response.statusCode()).isEqualTo(401);
  }

  String authorizationCode() throws Exception {
    browserLogin();
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    String state = UUID.randomUUID().toString();
    String query = form(Map.of("response_type", "code", "client_id", "native-probe", "redirect_uri", CALLBACK, "scope", "dav", "state", state, "code_challenge", challenge, "code_challenge_method", "S256"));
    HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/oauth2/authorize?" + query)).GET().build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(302);
    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(CALLBACK).contains("state=" + state);
    return java.util.Arrays.stream(URI.create(location).getRawQuery().split("&")).filter(p -> p.startsWith("code=")).map(p -> URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8)).findFirst().orElseThrow(() -> new AssertionError(location));
  }

  HttpResponse<String> post(Map<String, String> parameters) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(base() + "/oauth2/token")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build(), HttpResponse.BodyHandlers.ofString());
  }

  private void browserLogin() throws Exception {
    var page = client.send(HttpRequest.newBuilder(URI.create(base() + "/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(page.statusCode()).isEqualTo(200);
    var csrf = new java.util.concurrent.atomic.AtomicReference<String>();
    new javax.swing.text.html.parser.ParserDelegator().parse(new java.io.StringReader(page.body()), new javax.swing.text.html.HTMLEditorKit.ParserCallback() {
      @Override public void handleSimpleTag(javax.swing.text.html.HTML.Tag tag, javax.swing.text.MutableAttributeSet attributes, int position) {
        if (tag == javax.swing.text.html.HTML.Tag.INPUT && "_csrf".equals(attributes.getAttribute(javax.swing.text.html.HTML.Attribute.NAME))) csrf.set((String) attributes.getAttribute(javax.swing.text.html.HTML.Attribute.VALUE));
      }
    }, true);
    assertThat(csrf.get()).isNotBlank();
    var result = client.send(HttpRequest.newBuilder(URI.create(base() + "/login")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", "probe", "password", "probe-test-password", "_csrf", csrf.get())))).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(302);
    assertThat(result.headers().firstValue("Location").orElseThrow()).doesNotContain("error");
  }

  private String base() { return "http://127.0.0.1:" + port; }
  private static String form(Map<String, String> parameters) {
    return parameters.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class ProbeApplication {
    @Bean @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http, @org.springframework.beans.factory.annotation.Value("${probe.custom-refresh:false}") boolean customRefresh, RegisteredClientRepository clients) throws Exception {
      http.oauth2AuthorizationServer(server -> {
        http.securityMatcher(server.getEndpointsMatcher());
        server.tokenGenerator(tokenGenerator(customRefresh));
        if (customRefresh) {
          server.clientAuthentication(auth -> auth
              .authenticationConverter(new PublicRefreshExtensions.Converter())
              .authenticationProvider(new PublicRefreshExtensions.Provider(clients)));
        }
      }).authorizeHttpRequests(requests -> requests.anyRequest().authenticated()).httpBasic(Customizer.withDefaults());
      return http.build();
    }

    @Bean @Order(2)
    SecurityFilterChain other(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated()).formLogin(Customizer.withDefaults()).build();
    }

    @Bean UserDetailsService users() {
      return new InMemoryUserDetailsManager(User.withUsername("probe").password("{bcrypt}" + new BCryptPasswordEncoder().encode("probe-test-password")).roles("USER").build());
    }

    @Bean RegisteredClientRepository clients() {
      return new InMemoryRegisteredClientRepository(RegisteredClient.withId("native-probe-id").clientId("native-probe").clientAuthenticationMethod(ClientAuthenticationMethod.NONE).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN).redirectUri(CALLBACK).scope("dav").clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build()).tokenSettings(TokenSettings.builder().reuseRefreshTokens(false).accessTokenFormat(OAuth2TokenFormat.REFERENCE).build()).build());
    }

    @Bean OAuth2AuthorizationService authorizations() { return new InMemoryOAuth2AuthorizationService(); }
    @Bean AuthorizationServerSettings settings() { return AuthorizationServerSettings.builder().issuer("https://probe.invalid").build(); }
    @Bean OAuth2TokenGenerator<?> tokenGenerator(@org.springframework.beans.factory.annotation.Value("${probe.custom-refresh:false}") boolean customRefresh) {
      return new DelegatingOAuth2TokenGenerator(new OAuth2AccessTokenGenerator(), customRefresh ? new PublicRefreshExtensions.Generator() : new OAuth2RefreshTokenGenerator());
    }
  }
}
