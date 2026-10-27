package org.busystack.caldav.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class OidcPropertiesTest {
  @Test
  void allowlistDisplayNamesMustBeXmlRepresentableBeforeIdentityProvisioning() {
    for (String invalid :
        List.of("Invalid\uFFFE", "Invalid\uFFFF", "Invalid\u0001", "Invalid\uD800")) {
      assertThatThrownBy(() -> new OidcProperties.AllowedUser("subject", "alice", invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String valid :
        List.of(
            "Valid\uFDD0",
            "Valid" + new String(Character.toChars(0x1FFFE)),
            "Line\nName\tAllowed",
            "DEL\u007fC1\u0085")) {
      assertThat(new OidcProperties.AllowedUser("subject", "alice", valid).displayName())
          .isEqualTo(valid);
    }
  }
}
