package org.busystack.caldav.security.password;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
  @Test
  void countsUnicodeCodePointsAndEnforcesTheBcryptByteBoundary() {
    assertThatCode(() -> PasswordPolicy.validate("😀".repeat(14))).doesNotThrowAnyException();
    assertThatThrownBy(() -> PasswordPolicy.validate("😀".repeat(7)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> PasswordPolicy.validate("é".repeat(36))).doesNotThrowAnyException();
    assertThatThrownBy(() -> PasswordPolicy.validate("é".repeat(37)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> PasswordPolicy.validate("a".repeat(72))).doesNotThrowAnyException();
    assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(73)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PasswordPolicy.validate("valid length prefix" + '\uD800'))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
