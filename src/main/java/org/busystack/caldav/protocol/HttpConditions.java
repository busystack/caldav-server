package org.busystack.caldav.protocol;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Set;
import org.busystack.caldav.storage.WriteCondition;

/** RFC 9110 entity tags; evaluation occurs again under the storage mutation lock. */
final class HttpConditions {
  private HttpConditions() {}

  static WriteCondition write(HttpServletRequest request, DavPaths paths) {
    String match = header(request, "If-Match", ","), none = header(request, "If-None-Match", ",");
    boolean create = none != null && none.trim().equals("*"),
        exists = match != null && match.trim().equals("*");
    Set<String>
        matches = new LinkedHashSet<>(match == null || exists ? Set.of() : tags(match, false)),
        excludes = new LinkedHashSet<>(none == null || create ? Set.of() : tags(none, true));
    String dav = header(request, "If", " ");
    if (dav != null) {
      DavIf condition = DavIf.parse(dav, request.getRequestURI(), paths);
      if (condition.defaultMatch()) {
        for (String tag : condition.tags()) if (!condition.matches(tag)) excludes.add(tag);
      } else {
        Set<String> accepted = new LinkedHashSet<>();
        for (String tag : condition.tags()) if (condition.matches(tag)) accepted.add(tag);
        if (matches.isEmpty()) matches.addAll(accepted);
        else matches.retainAll(accepted);
        if (matches.isEmpty()) matches.add("\"busystack-impossible-dav-validator\"");
      }
    }
    return new WriteCondition(create, exists || match != null, matches, excludes);
  }

  static boolean notModified(HttpServletRequest request, String etag, DavPaths paths) {
    String dav = header(request, "If", " ");
    if (dav != null && !DavIf.parse(dav, request.getRequestURI(), paths).matches(etag))
      throw new DavException(412, null);
    String match = header(request, "If-Match", ",");
    if (match != null && !match.trim().equals("*") && !tags(match, false).contains(etag))
      throw new DavException(412, null);
    String none = header(request, "If-None-Match", ",");
    return none != null && (none.trim().equals("*") || tags(none, true).contains(etag));
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
