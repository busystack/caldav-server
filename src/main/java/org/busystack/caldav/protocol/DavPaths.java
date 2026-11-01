package org.busystack.caldav.protocol;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.busystack.caldav.config.DeploymentProperties;

/** URI segments use percent encoding, never application/x-www-form-urlencoded decoding. */
public final class DavPaths {
  private final DeploymentProperties deployment;
  private final String rawPrefix;

  public DavPaths(DeploymentProperties deployment) {
    this.deployment = deployment;
    rawPrefix = deployment.publicBaseUrl().getRawPath() + "/dav/";
  }

  public List<String> parse(String href) {
    final URI uri;
    try {
      uri = URI.create(href);
    } catch (IllegalArgumentException ex) {
      throw new DavException(400, null);
    }
    if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null)
      throw new DavException(400, null);
    if (uri.isAbsolute()) {
      URI base = deployment.publicBaseUrl();
      if (!base.getScheme().equalsIgnoreCase(uri.getScheme())
          || !base.getHost().equalsIgnoreCase(uri.getHost() == null ? "" : uri.getHost())
          || effectivePort(base) != effectivePort(uri)) throw new DavException(403, null);
    } else if (uri.getRawAuthority() != null) throw new DavException(400, null);
    String path = uri.getRawPath();
    if (path.equals(rawPrefix.substring(0, rawPrefix.length() - 1))) return List.of();
    if (!path.startsWith(rawPrefix)) throw new DavException(403, null);
    String relative = path.substring(rawPrefix.length());
    if (relative.length() > org.busystack.caldav.storage.DavNamespace.MAX_ENCODED_LENGTH + 1)
      throw new DavException(414, null);
    if (relative.endsWith("/")) relative = relative.substring(0, relative.length() - 1);
    if (relative.isEmpty()) return List.of();
    List<String> segments = new ArrayList<>();
    for (String segment : relative.split("/", -1)) segments.add(decode(segment));
    try {
      org.busystack.caldav.storage.DavNamespace.parts(segments);
    } catch (IllegalArgumentException invalid) {
      throw new DavException(414, null);
    }
    return List.copyOf(segments);
  }

  public String href(List<String> parts, boolean collection) {
    org.busystack.caldav.storage.DavNamespace.parts(parts);
    return deployment.davRoot()
        + String.join("/", parts.stream().map(DavPaths::encode).toList())
        + (!parts.isEmpty() && collection ? "/" : "");
  }

  public static String encode(String part) {
    StringBuilder result = new StringBuilder();
    for (byte b : part.getBytes(StandardCharsets.UTF_8)) {
      int value = b & 255;
      if (value >= 'a' && value <= 'z'
          || value >= 'A' && value <= 'Z'
          || value >= '0' && value <= '9'
          || value == '-'
          || value == '.'
          || value == '_'
          || value == '~') result.append((char) value);
      else
        result
            .append('%')
            .append(Character.toUpperCase(Character.forDigit(value >>> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(value & 15, 16)));
    }
    return result.toString();
  }

  private static String decode(String part) {
    var output = new ByteArrayOutputStream();
    for (int i = 0; i < part.length(); ) {
      char c = part.charAt(i);
      if (c == '%') {
        if (i + 2 >= part.length()) throw new DavException(400, null);
        int high = Character.digit(part.charAt(i + 1), 16),
            low = Character.digit(part.charAt(i + 2), 16);
        if (high < 0 || low < 0) throw new DavException(400, null);
        output.write((high << 4) | low);
        i += 3;
      } else {
        int cp = part.codePointAt(i);
        output.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
        i += Character.charCount(cp);
      }
    }
    try {
      String value =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(output.toByteArray()))
              .toString();
      if (value.isBlank()
          || value.length() > 255
          || value.equals(".")
          || value.equals("..")
          || value
              .codePoints()
              .anyMatch(
                  c ->
                      c == '/'
                          || c == ';'
                          || c == 0x2028
                          || c == 0x2029
                          || c == 0xFFFE
                          || c == 0xFFFF
                          || c == '\\'
                          || Character.isISOControl(c))) throw new DavException(400, null);
      return value;
    } catch (java.nio.charset.CharacterCodingException ex) {
      throw new DavException(400, null);
    }
  }

  private static int effectivePort(URI uri) {
    return uri.getPort() != -1
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
