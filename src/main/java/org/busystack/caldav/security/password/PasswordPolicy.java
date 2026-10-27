package org.busystack.caldav.security.password;

import java.nio.charset.StandardCharsets;

/** Validate before BCrypt so Unicode length and its byte limit never silently weaken the policy. */
public final class PasswordPolicy {
  private PasswordPolicy() {}

  public static void validate(String password) {
    if (password == null
        || password.length() > 72
        || password.codePointCount(0, password.length()) < 14
        || !StandardCharsets.UTF_8.newEncoder().canEncode(password)
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException(
          "Password must contain at least fourteen characters and at most seventy-two UTF-8 bytes");
  }
}
