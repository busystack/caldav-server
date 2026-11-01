package org.busystack.caldav.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One namespace budget after /dav/; the deployment prefix has its own validated boundary. */
public final class DavNamespace {
  public static final int MAX_SEGMENTS = 20;
  public static final int MAX_ENCODED_LENGTH = 4096;
  private static final String PRINCIPAL = "00000000-0000-0000-0000-000000000000";
  // Every accepted 255 UTF-16-unit leaf fits: BMP non-ASCII requires at most nine URI bytes/unit.
  private static final String RESERVED_LEAF = "\u0800".repeat(255);

  private DavNamespace() {}

  public static void segment(String value) {
    if (value == null
        || value.isBlank()
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
                        || Character.isISOControl(c)
                        || c >= 0xD800 && c <= 0xDFFF))
      throw new IllegalArgumentException("Invalid DAV path segment");
  }

  public static void parts(List<String> parts) {
    if (parts.size() > MAX_SEGMENTS) throw new IllegalArgumentException("DAV path is too deep");
    int length = Math.max(0, parts.size() - 1);
    for (String part : parts) {
      segment(part);
      length += encodedLength(part);
      if (length > MAX_ENCODED_LENGTH)
        throw new IllegalArgumentException("Encoded DAV path is too long");
    }
  }

  public static void binding(String path, boolean calendar) {
    var parts = bindingParts(path);
    if (calendar) parts.add(RESERVED_LEAF);
    parts(parts);
  }

  public static void resource(String path, String name) {
    var parts = bindingParts(path);
    parts.add(name);
    parts(parts);
  }

  /** Call after authorization and the collection lock, before modifying a resource. */
  static void resourceBindings(
      org.springframework.jdbc.core.JdbcTemplate jdbc, java.util.UUID collection, String name) {
    jdbc.query(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "SELECT path FROM dav_bindings WHERE collection_id=? ORDER BY home_id,path");
          statement.setObject(1, collection);
          statement.setFetchSize(128);
          return statement;
        },
        (org.springframework.jdbc.core.RowCallbackHandler) rs -> resource(rs.getString(1), name));
  }

  private static ArrayList<String> bindingParts(String path) {
    if (path == null) throw new IllegalArgumentException("Invalid DAV path");
    var result = new ArrayList<>(List.of("calendars", PRINCIPAL));
    if (!path.isEmpty()) result.addAll(List.of(path.split("/", -1)));
    return result;
  }

  private static int encodedLength(String text) {
    int length = 0;
    for (byte item : text.getBytes(StandardCharsets.UTF_8)) {
      int c = item & 255;
      length +=
          (c >= 'a' && c <= 'z'
                  || c >= 'A' && c <= 'Z'
                  || c >= '0' && c <= '9'
                  || c == '-'
                  || c == '.'
                  || c == '_'
                  || c == '~')
              ? 1
              : 3;
    }
    return length;
  }
}
