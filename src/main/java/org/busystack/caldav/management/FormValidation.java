package org.busystack.caldav.management;

import java.util.*;
import org.busystack.caldav.xml.XmlCharacters;

/** Fixed field messages; neither exception text nor submitted secrets become browser output. */
public final class FormValidation extends IllegalArgumentException {
  private final Map<String, String> errors;
  private final boolean conflict;

  public FormValidation(Map<String, String> errors, boolean conflict) {
    super("Invalid form fields");
    this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    this.conflict = conflict;
  }

  public Map<String, String> errors() {
    return errors;
  }

  public boolean conflict() {
    return conflict;
  }

  public static FormValidation field(String field, String message) {
    return new FormValidation(Map.of(field, message), false);
  }

  public static FormValidation conflict(String field, String message) {
    return new FormValidation(Map.of(field, message), true);
  }

  public static final class Checks {
    private final Map<String, String> errors = new LinkedHashMap<>();

    public Checks require(String field, boolean valid, String message) {
      if (!valid) errors.put(field, message);
      return this;
    }

    public void finish() {
      if (!errors.isEmpty()) throw new FormValidation(errors, false);
    }
  }

  public static boolean displayName(String value) {
    return value != null
        && !value.isBlank()
        && value.codePointCount(0, value.length()) <= 200
        && XmlCharacters.valid(value)
        && value.codePoints().noneMatch(Character::isISOControl);
  }

  public static void account(String username, String displayName, String password) {
    var checks = new Checks();
    checks.require(
        "username",
        username != null && username.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"),
        "Use 1–64 letters, digits, dots, underscores or hyphens, starting with a letter or digit.");
    checks.require(
        "displayName",
        displayName(displayName),
        "Use a display name of 1–200 characters without control characters.");
    try {
      org.busystack.caldav.security.password.PasswordPolicy.validate(password);
    } catch (IllegalArgumentException invalid) {
      checks.require(
          "password",
          false,
          "Use at least fourteen characters and at most seventy-two UTF-8 bytes. Enter the password again.");
    }
    checks.finish();
  }
}
