package org.busystack.caldav.protocol;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.busystack.caldav.storage.WriteCondition;

/** RFC 9110 validators; mutation conditions are evaluated again under the storage lock. */
final class HttpConditions {
  private HttpConditions() {}

  static WriteCondition write(HttpServletRequest request, DavPaths paths) {
    String match = header(request, "If-Match", ","), none = header(request, "If-None-Match", ",");
    boolean create = none != null && none.trim().equals("*"),
        exists = match != null && match.trim().equals("*");
    Set<String>
        matches = new LinkedHashSet<>(match == null || exists ? Set.of() : tags(match, false)),
        excludes = new LinkedHashSet<>(none == null || create ? Set.of() : tags(none, true));
    // The controller evaluates the complete DAV If expression under one snapshot/global
    // mutation lock. Refining it with only this resource's ETag would discard sync state tokens.
    return new WriteCondition(
        create,
        exists || match != null,
        matches,
        excludes,
        match == null ? HttpDateValues.parse(request, "If-Unmodified-Since") : null);
  }

  static boolean notModified(HttpServletRequest request, String etag, DavPaths paths) {
    return notModified(request, etag, null, paths);
  }

  static boolean notModified(
      HttpServletRequest request, String etag, Instant modifiedAt, DavPaths paths) {
    String match = header(request, "If-Match", ",");
    if (match != null && !match.trim().equals("*")) {
      Set<String> accepted = tags(match, false);
      if (etag == null || !accepted.contains(etag)) throw new DavException(412, null);
    }
    if (match == null) {
      Instant unmodified = HttpDateValues.parse(request, "If-Unmodified-Since");
      if (unmodified != null && modifiedAt != null && modifiedAt.isAfter(unmodified))
        throw new DavException(412, null);
    }
    String none = header(request, "If-None-Match", ",");
    if (none != null) {
      if (none.trim().equals("*")) return true;
      Set<String> excluded = tags(none, true);
      return etag != null && excluded.contains(etag);
    }
    Instant modified = HttpDateValues.parse(request, "If-Modified-Since");
    return modified != null && modifiedAt != null && !modifiedAt.isAfter(modified);
  }

  private static String header(HttpServletRequest request, String name, String separator) {
    var values = java.util.Collections.list(request.getHeaders(name));
    return values.isEmpty() ? null : String.join(separator, values);
  }

  private static Set<String> tags(String header, boolean weak) {
    if (header.length() > 8192) throw new DavException(400, null);
    Set<String> result = new LinkedHashSet<>();
    int i = 0;
    while (i < header.length()) {
      while (i < header.length() && (header.charAt(i) == ' ' || header.charAt(i) == '\t')) i++;
      boolean isWeak = header.startsWith("W/", i);
      if (isWeak) i += 2;
      if (i >= header.length() || header.charAt(i) != '"') throw new DavException(400, null);
      int start = i++;
      while (i < header.length() && header.charAt(i) != '"') {
        char c = header.charAt(i++);
        if (c < 0x21 || c == 0x7f) throw new DavException(400, null);
      }
      if (i >= header.length()) throw new DavException(400, null);
      i++;
      if (weak || !isWeak) result.add(header.substring(start, i));
      while (i < header.length() && (header.charAt(i) == ' ' || header.charAt(i) == '\t')) i++;
      if (i < header.length()) {
        if (header.charAt(i++) != ',' || i == header.length()) throw new DavException(400, null);
      }
    }
    // A syntactically valid weak-only If-Match must fail, never become an unconditional write.
    if (result.isEmpty() && !weak) result.add("\"busystack-impossible-weak-validator\"");
    if (header.isBlank()) throw new DavException(400, null);
    return Set.copyOf(result);
  }
}
