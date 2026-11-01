package org.busystack.caldav.security.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Standard SHA-256 for high-entropy credential verifiers and bounded diagnostic identifiers. */
public final class SecurityHashes {
  private SecurityHashes() {}

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("Required SHA-256 unavailable", ex);
    }
  }
}
