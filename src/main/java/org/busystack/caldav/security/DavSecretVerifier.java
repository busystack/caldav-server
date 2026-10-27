package org.busystack.caldav.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.busystack.caldav.security.audit.SecurityHashes;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Only for framework-generated 256-bit DAV secrets. Human passwords retain adaptive hashing. */
@Component
public final class DavSecretVerifier {
  public static final String PREFIX = "{dav-sha256-v1}";
  private final PasswordEncoder legacy;

  public DavSecretVerifier(PasswordEncoder legacy) {
    this.legacy = legacy;
  }

  public String encode(UUID credential, String secret) {
    return PREFIX
        + SecurityHashes.sha256("BusyStack DAV credential v1:" + credential + ":" + secret);
  }

  public boolean current(String verifier) {
    return verifier != null && verifier.startsWith(PREFIX);
  }

  public boolean matches(UUID credential, String secret, String verifier) {
    if (verifier == null) return false;
    if (current(verifier)) {
      String hash = verifier.substring(PREFIX.length());
      if (hash.length() != 64
          || !hash.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f'))
        return false;
      return MessageDigest.isEqual(
          verifier.getBytes(StandardCharsets.US_ASCII),
          encode(credential, secret).getBytes(StandardCharsets.US_ASCII));
    }
    return verifier.length() == 60
        && (verifier.startsWith("$2a$")
            || verifier.startsWith("$2b$")
            || verifier.startsWith("$2y$"))
        && legacy.matches(secret, verifier);
  }
}
