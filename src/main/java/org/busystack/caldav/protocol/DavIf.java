package org.busystack.caldav.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** DAV If lists are Boolean conditions, not a comma-separated HTTP entity-tag field. */
final class DavIf {
  record ResourceState(String etag, String syncToken) {
    static final ResourceState ABSENT = new ResourceState(null, null);
  }

  record Test(boolean negate, String etag, String stateToken) {
    boolean matches(ResourceState existing) {
      boolean value =
          etag != null ? etag.equals(existing.etag()) : stateToken.equals(existing.syncToken());
      return negate ? !value : value;
    }
  }

  private final Map<List<String>, List<List<Test>>> groups;
  private final List<String> request;

  private DavIf(Map<List<String>, List<List<Test>>> groups, List<String> request) {
    Map<List<String>, List<List<Test>>> copy = new LinkedHashMap<>();
    groups.forEach((key, value) -> copy.put(List.copyOf(key), List.copyOf(value)));
    this.groups = java.util.Collections.unmodifiableMap(copy);
    this.request = request;
  }

  static DavIf parse(String input, String requestUri, DavPaths paths) {
    if (input.length() > 8192) throw new DavException(400, null);
    var parser = new Parser(input, requestUri, paths);
    return parser.parse();
  }

  boolean matches(String existing) {
    return !groups.containsKey(request)
        || matches(groups.get(request), new ResourceState(existing, null));
  }

  boolean matchesAll(Function<List<String>, String> etags) {
    return matchesStates(parts -> new ResourceState(etags.apply(parts), null));
  }

  boolean matchesStates(Function<List<String>, ResourceState> resources) {
    return groups.entrySet().stream()
        .allMatch(group -> matches(group.getValue(), resources.apply(group.getKey())));
  }

  private static boolean matches(List<List<Test>> lists, ResourceState existing) {
    return lists.stream().anyMatch(list -> list.stream().allMatch(test -> test.matches(existing)));
  }

  private static final class Parser {
    final String input, request;
    final DavPaths paths;
    int position;
    boolean tagged, untagged;
    int tokens;
    final Map<List<String>, List<List<Test>>> groups = new LinkedHashMap<>();

    Parser(String input, String request, DavPaths paths) {
      this.input = input;
      this.request = request;
      this.paths = paths;
    }

    DavIf parse() {
      whitespace();
      List<String> target = paths.parse(request);
      while (position < input.length()) {
        if (input.charAt(position) == '<') {
          if (untagged) bad();
          tagged = true;
          String href = angle();
          target = paths.parse(href);
          whitespace();
          if (position == input.length() || input.charAt(position) != '(') bad();
        } else if (!tagged) untagged = true;
        while (position < input.length() && input.charAt(position) == '(') {
          groups.computeIfAbsent(target, ignored -> new ArrayList<>()).add(list());
          whitespace();
        }
        if (position < input.length() && input.charAt(position) != '<') bad();
      }
      if (groups.isEmpty()) bad();
      return new DavIf(groups, paths.parse(request));
    }

    List<Test> list() {
      position++;
      whitespace();
      List<Test> list = new ArrayList<>();
      while (position < input.length() && input.charAt(position) != ')') {
        if (++tokens > 100) bad();
        boolean negate = false;
        if (input.startsWith("Not", position)) {
          position += 3;
          if (position >= input.length() || !Character.isWhitespace(input.charAt(position))) bad();
          whitespace();
          negate = true;
        }
        String etag = null, stateToken = null;
        if (position < input.length() && input.charAt(position) == '<') stateToken = angle();
        else if (position < input.length() && input.charAt(position) == '[') {
          position++;
          if (input.startsWith("W/", position)) position += 2;
          if (position >= input.length() || input.charAt(position) != '"') bad();
          int start = position++;
          while (position < input.length() && input.charAt(position) != '"') {
            if (input.charAt(position) < 0x21 || input.charAt(position) == 0x7f) bad();
            position++;
          }
          if (position >= input.length()) bad();
          position++;
          etag = input.substring(start, position);
          if (position >= input.length() || input.charAt(position++) != ']') bad();
        } else bad();
        list.add(new Test(negate, etag, stateToken));
        whitespace();
      }
      if (list.isEmpty() || position >= input.length() || input.charAt(position++) != ')') bad();
      return List.copyOf(list);
    }

    String angle() {
      if (input.charAt(position++) != '<') bad();
      int start = position;
      while (position < input.length() && input.charAt(position) != '>') {
        if (Character.isWhitespace(input.charAt(position))) bad();
        position++;
      }
      if (position >= input.length() || start == position) bad();
      String result = input.substring(start, position++);
      try {
        if (!java.net.URI.create(result).isAbsolute() && !result.startsWith("/")) bad();
      } catch (IllegalArgumentException ex) {
        bad();
      }
      return result;
    }

    void whitespace() {
      while (position < input.length()
          && (input.charAt(position) == ' ' || input.charAt(position) == '\t')) position++;
    }

    void bad() {
      throw new DavException(400, null);
    }
  }
}
