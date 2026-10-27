package org.busystack.caldav.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = NativeOAuthRiskTest.ProbeApplication.class,
    properties = {"probe.custom-refresh=true", "spring.main.banner-mode=off", "logging.level.root=WARN"})
class CustomizedRefreshRiskTest {
  @LocalServerPort int port;

  @Test
  void supportedExtensionsIssueAndRotateButStockProviderDoesNotRevokeFamilyOnReplay() throws Exception {
    NativeOAuthRiskTest probe = new NativeOAuthRiskTest();
    probe.port = port;
    var initial = probe.post(Map.of("grant_type", "authorization_code", "client_id", "native-probe", "redirect_uri", NativeOAuthRiskTest.CALLBACK, "code", probe.authorizationCode(), "code_verifier", NativeOAuthRiskTest.VERIFIER));
    assertThat(initial.statusCode()).isEqualTo(200);
    String first = token(initial.body());
    var rotation = probe.post(Map.of("grant_type", "refresh_token", "client_id", "native-probe", "refresh_token", first));
    assertThat(rotation.statusCode()).isEqualTo(200);
    String second = token(rotation.body());
    assertThat(second).isNotEqualTo(first);

    var replay = probe.post(Map.of("grant_type", "refresh_token", "client_id", "native-probe", "refresh_token", first));
    assertThat(replay.statusCode()).isEqualTo(400);
    assertThat(replay.body()).contains("invalid_grant");

    // This PASS proves the missing security control: the descendant remains usable after replay.
    var descendant = probe.post(Map.of("grant_type", "refresh_token", "client_id", "native-probe", "refresh_token", second));
    assertThat(descendant.statusCode()).isEqualTo(200);
    assertThat(token(descendant.body())).isNotEqualTo(second);
  }

  private static String token(String body) {
    String value = JsonMapper.builder().build().readTree(body).path("refresh_token").asString();
    assertThat(value).isNotBlank();
    return value;
  }
}
