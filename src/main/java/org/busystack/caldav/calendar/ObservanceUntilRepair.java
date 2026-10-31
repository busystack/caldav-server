package org.busystack.caldav.calendar;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.*;

/**
 * Lossless, bounded content-line adapter for local observance UNTILs. This is not a calendar
 * validator: the complete resulting representation must still pass CalendarContent.parse().
 */
final class ObservanceUntilRepair {
  record Result(byte[] content, int rules) {}

  private record Line(int start, int end, String text, String ending) {}

  private static final DateTimeFormatter DATE =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .appendPattern("uuuuMMdd'T'HHmmss")
          .toFormatter(Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);

  private static final class Frame {
    final String name;
    final boolean observance;
    final Map<String, List<Line>> properties = new HashMap<>();

    Frame(String name, boolean observance) {
      this.name = name;
      this.observance = observance;
    }
  }

  private ObservanceUntilRepair() {}

  static Result apply(byte[] input) {
    if (input.length == 0 || input.length > CalendarContent.MAX_BYTES) invalid();
    List<Line> lines = lines(input);
    Deque<Frame> stack = new ArrayDeque<>();
    Map<Line, String> replacements = new HashMap<>();
    int components = 0, properties = 0;
    for (Line line : lines) {
      String text = line.text();
      if (line.start() == 0 && text.startsWith("\u00ef\u00bb\u00bf")) text = text.substring(3);
      int colon = colon(text);
      if (colon < 1) invalid();
      String head = text.substring(0, colon);
      String name = head.split(";", 2)[0].toUpperCase(Locale.ROOT);
      String value = text.substring(colon + 1);
      if (name.equals("BEGIN")) {
        if (!head.equalsIgnoreCase("BEGIN") || !value.matches("[A-Za-z0-9-]+")) invalid();
        String type = value.toUpperCase(Locale.ROOT);
        if (stack.isEmpty() && (!type.equals("VCALENDAR") || line.start() != 0)) invalid();
        boolean observance =
            Set.of("STANDARD", "DAYLIGHT").contains(type)
                && stack.size() == 2
                && stack.peek().name.equals("VTIMEZONE")
                && stack.getLast().name.equals("VCALENDAR");
        if (stack.size() >= 9 || ++components > 2001) invalid();
        stack.push(new Frame(type, observance));
      } else if (name.equals("END")) {
        if (!head.equalsIgnoreCase("END")
            || stack.isEmpty()
            || !stack.peek().name.equalsIgnoreCase(value)) invalid();
        Frame frame = stack.pop();
        if (frame.observance) repair(frame, replacements);
      } else {
        if (stack.isEmpty() || ++properties > 20000) invalid();
        if (stack.peek().observance)
          stack.peek().properties.computeIfAbsent(name, ignored -> new ArrayList<>()).add(line);
      }
    }
    if (!stack.isEmpty()) invalid();
    if (replacements.isEmpty()) return new Result(input, 0);
    var output = new ByteArrayOutputStream(input.length);
    for (Line line : lines) {
      String replacement = replacements.get(line);
      if (replacement == null) output.write(input, line.start(), line.end() - line.start());
      else {
        // RRULE content is ASCII; ISO-8859-1 also preserves opaque parameter octets exactly.
        byte[] bytes = replacement.getBytes(StandardCharsets.ISO_8859_1);
        int from = 0, width = 75;
        while (bytes.length - from > width) {
          output.write(bytes, from, width);
          output.writeBytes(new byte[] {'\r', '\n', ' '});
          from += width;
          width = 74;
        }
        output.write(bytes, from, bytes.length - from);
        output.writeBytes(line.ending().getBytes(StandardCharsets.US_ASCII));
      }
      if (output.size() > CalendarContent.MAX_BYTES) invalid();
    }
    return new Result(output.toByteArray(), replacements.size());
  }

  private static void repair(Frame frame, Map<Line, String> replacements) {
    for (Line line : frame.properties.getOrDefault("RRULE", List.of())) {
      int colon = colon(line.text());
      String rule = line.text().substring(colon + 1);
      if (!rule.chars().allMatch(c -> c < 128)) invalid();
      RecurrenceRuleSyntax.validate(rule); // Before any typed model can erase duplicate parts.
      int position = 0;
      for (String part : rule.split(";", -1)) {
        int equals = part.indexOf('=');
        String value = part.substring(equals + 1);
        if (part.substring(0, equals).equalsIgnoreCase("UNTIL")
            && !value.toUpperCase(Locale.ROOT).endsWith("Z")) {
          // Required singleton properties must be unambiguous even before strict validation.
          for (String required : List.of("DTSTART", "TZOFFSETFROM", "TZOFFSETTO"))
            if (frame.properties.getOrDefault(required, List.of()).size() != 1) invalid();
          String start = value(frame.properties.get("DTSTART").getFirst());
          local(start);
          long seconds = offset(value(frame.properties.get("TZOFFSETFROM").getFirst()));
          offset(value(frame.properties.get("TZOFFSETTO").getFirst()));
          LocalDateTime utc;
          try {
            utc = local(value).minusSeconds(seconds);
          } catch (DateTimeException | ArithmeticException ex) {
            throw invalid();
          }
          if (utc.getYear() < 1 || utc.getYear() > 9999) invalid();
          int begin = colon + 1 + position + equals + 1;
          replacements.put(
              line,
              line.text().substring(0, begin)
                  + DATE.format(utc)
                  + "Z"
                  + line.text().substring(begin + value.length()));
        }
        position += part.length() + 1;
      }
    }
  }

  private static String value(Line line) {
    return line.text().substring(colon(line.text()) + 1);
  }

  private static LocalDateTime local(String value) {
    if (!value.matches("[0-9]{8}[Tt][0-9]{6}")) throw invalid();
    try {
      LocalDateTime result = LocalDateTime.parse(value, DATE);
      if (result.getYear() == 0) throw invalid();
      return result;
    } catch (DateTimeException ex) {
      throw invalid();
    }
  }

  private static long offset(String value) {
    if (!value.matches("[+-][0-9]{4}(?:[0-9]{2})?")) throw invalid();
    int hours = Integer.parseInt(value.substring(1, 3));
    int minutes = Integer.parseInt(value.substring(3, 5));
    int seconds = value.length() == 7 ? Integer.parseInt(value.substring(5, 7)) : 0;
    if (hours > 23 || minutes > 59 || seconds > 59 || value.matches("-0{4}(?:00)?"))
      throw invalid();
    long total = hours * 3600L + minutes * 60L + seconds;
    return value.charAt(0) == '-' ? -total : total;
  }

  private static int colon(String text) {
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '"') quoted = !quoted;
      else if (text.charAt(i) == ':' && !quoted) return i;
    }
    return -1;
  }

  private static List<Line> lines(byte[] input) {
    List<Line> result = new ArrayList<>();
    for (int start = 0; start < input.length; ) {
      int end = start;
      var text = new ByteArrayOutputStream();
      String ending;
      do {
        int from = end == start ? end : end + 1; // Drop exactly one folding whitespace octet.
        end = from;
        while (end < input.length && input[end] != '\n') end++;
        int contentEnd = end;
        if (contentEnd > from && input[contentEnd - 1] == '\r') contentEnd--;
        text.write(input, from, contentEnd - from);
        ending = end == input.length ? "" : contentEnd == end ? "\n" : "\r\n";
        if (end < input.length) end++;
      } while (end < input.length && (input[end] == ' ' || input[end] == '\t'));
      result.add(new Line(start, end, text.toString(StandardCharsets.ISO_8859_1), ending));
      if (result.size() > 24002) invalid();
      start = end;
    }
    return result;
  }

  private static CalendarValidationException invalid() {
    throw new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
  }
}
