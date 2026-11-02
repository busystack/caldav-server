package org.busystack.caldav.calendar;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.busystack.caldav.calendar.parser.ParameterInput;
import org.busystack.caldav.calendar.parser.ParsingLimitsHandler;
import org.springframework.stereotype.Component;

/**
 * Lossless framing only; the existing parser and admission service decide all calendar semantics.
 */
@Component
public final class CalendarInterchange {
  public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
  public static final int MAX_ITEMS = 1000;

  public record Item(
      int number, String uid, String component, String label, byte[] content, String rejection) {
    public Item {
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }

    @Override
    public String toString() {
      return "ImportItem[number=" + number + ", content=<private>]";
    }
  }

  private record Line(byte[] raw, String value) {}

  private record Frame(byte[] original, byte[] header, byte[] closing, List<byte[]> components) {}

  private final CalendarAdmission admission;

  public CalendarInterchange(CalendarAdmission admission) {
    this.admission = admission;
  }

  public List<Item> read(byte[] file, String destinationComponent) {
    if (file == null || file.length == 0 || file.length > MAX_FILE_BYTES) throw invalid();
    List<Item> result = new ArrayList<>();
    var budget = new Budget();
    long retainedBytes = 0;
    for (Frame frame : frames(file)) {
      var model = index(frame.original(), budget);
      if (model.size() != frame.components().size()) throw invalid();
      ByteArrayOutputStream zones = new ByteArrayOutputStream();
      Map<String, List<Integer>> groups = new LinkedHashMap<>();
      for (int n = 0; n < model.size(); n++) {
        var c = model.get(n);
        if (c.type().equals(net.fortuna.ical4j.model.Component.VTIMEZONE))
          zones.writeBytes(frame.components().get(n));
        else {
          String uid = c.uid();
          groups.computeIfAbsent(c.type() + "\u0000" + uid, k -> new ArrayList<>()).add(n);
        }
      }
      if (groups.isEmpty()) throw invalid();
      for (var group : groups.values()) {
        var first = model.get(group.getFirst());
        String uid = first.uid();
        String label = first.label();
        long size = frame.header().length + (long) zones.size() + frame.closing().length;
        for (int index : group) size += frame.components().get(index).length;
        if (size <= CalendarContent.MAX_BYTES && retainedBytes + size > 16L * 1024 * 1024)
          throw invalid();
        byte[] content = new byte[0];
        if (size <= CalendarContent.MAX_BYTES) {
          var bytes = new ByteArrayOutputStream();
          bytes.writeBytes(frame.header());
          bytes.writeBytes(zones.toByteArray());
          for (int index : group) bytes.writeBytes(frame.components().get(index));
          bytes.writeBytes(frame.closing());
          content = groups.size() == 1 ? frame.original() : bytes.toByteArray();
          retainedBytes += content.length;
        }
        String rejection = "";
        if (!java.util.Set.of("VEVENT", "VTODO").contains(first.type()))
          rejection = "Unsupported component type";
        else if (!first.type().equals(destinationComponent))
          rejection = "Does not match the destination collection type";
        else if (size > CalendarContent.MAX_BYTES)
          rejection = "UID series exceeds the 1 MiB resource limit";
        else {
          try {
            var accepted = admission.admit(content, destinationComponent);
            content = accepted.content();
            uid = accepted.parsed().uid();
          } catch (IllegalArgumentException
              | org.busystack.caldav.calendar.query.QueryException invalid) {
            rejection = "Invalid or unsupported calendar data in this UID series";
          }
        }
        result.add(
            new Item(
                result.size() + 1,
                uid,
                first.type(),
                label,
                rejection.isEmpty() ? content : new byte[0],
                rejection));
        if (result.size() > MAX_ITEMS) throw invalid();
      }
    }
    // Separate VCALENDAR objects must not turn repeated UIDs into an accidental overwrite.
    Map<String, Long> counts =
        result.stream()
            .filter(i -> i.rejection().isEmpty())
            .collect(
                java.util.stream.Collectors.groupingBy(
                    Item::uid, java.util.stream.Collectors.counting()));
    return result.stream()
        .map(
            i ->
                counts.getOrDefault(i.uid(), 0L) > 1
                    ? new Item(
                        i.number(),
                        i.uid(),
                        i.component(),
                        i.label(),
                        i.content(),
                        "UID occurs in multiple calendar objects; combine its recurrence series before importing")
                    : i)
        .toList();
  }

  private record Indexed(String type, String uid, String label) {}

  private static final class Budget {
    int components, properties, parameters;
  }

  private static List<Indexed> index(byte[] bytes, Budget budget) {
    var handler = new IndexHandler(budget);
    try {
      String text = CalendarParsingInput.decode(bytes);
      CalendarTextProfile.validate(text);
      ParameterInput.parse(CalendarParsingInput.reader(text), new ParsingLimitsHandler(handler));
      return List.copyOf(handler.items);
    } catch (java.io.IOException
        | net.fortuna.ical4j.data.ParserException
        | IllegalArgumentException failure) {
      throw invalid();
    }
  }

  /** Parser callbacks locate series; every emitted resource still passes complete admission. */
  private static final class IndexHandler
      implements org.busystack.caldav.calendar.parser.ParameterListHandler {
    final Budget budget;
    final List<Indexed> items = new ArrayList<>();
    int depth;
    String type, uid, label, property;

    IndexHandler(Budget budget) {
      this.budget = budget;
    }

    @Override
    public void startCalendar() {}

    @Override
    public void endCalendar() {}

    @Override
    public void startComponent(String name) {
      if (++budget.components > 2000) throw invalid();
      if (++depth == 1) {
        type = name.toUpperCase(java.util.Locale.ROOT);
        uid = "";
        label = "Untitled item";
      }
    }

    @Override
    public void endComponent(String name) {
      if (depth-- == 1) items.add(new Indexed(type, uid, label));
    }

    @Override
    public void startProperty(String name) {
      if (++budget.properties > 20000) throw invalid();
      property = name.toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public void endProperty(String name) {}

    @Override
    public void propertyValue(String value) {
      if (depth != 1) return;
      if (property.equals("UID") || property.equals("SUMMARY")) {
        try {
          CalendarTextValues.scalar(value);
          String decoded = CalendarTextValues.list(value).getFirst();
          if (property.equals("UID")) uid = decoded;
          else label = decoded;
        } catch (IllegalArgumentException ignored) {
          if (property.equals("UID")) uid = value;
        }
      }
    }

    @Override
    public void parameter(String name, String value) {
      parameterValues(name, List.of(value));
    }

    @Override
    public void parameterValues(String name, List<String> values) {
      budget.parameters += values.size();
      if (budget.parameters > 40000) throw invalid();
    }
  }

  private static List<Frame> frames(byte[] file) {
    var lines = lines(file);
    List<Frame> result = new ArrayList<>();
    int cursor = 0;
    while (cursor < lines.size()) {
      int start = cursor;
      if (!lines.get(cursor).value().replace("\uFEFF", "").equalsIgnoreCase("BEGIN:VCALENDAR"))
        throw invalid();
      cursor++;
      var header = new ByteArrayOutputStream();
      header.writeBytes(lines.get(start).raw());
      List<byte[]> components = new ArrayList<>();
      while (cursor < lines.size()
          && !lines.get(cursor).value().equalsIgnoreCase("END:VCALENDAR")) {
        Line line = lines.get(cursor);
        if (line.value().regionMatches(true, 0, "BEGIN:", 0, 6)) {
          int depth = 1, from = cursor++;
          while (cursor < lines.size() && depth > 0) {
            String value = lines.get(cursor++).value();
            if (value.regionMatches(true, 0, "BEGIN:", 0, 6)) depth++;
            else if (value.regionMatches(true, 0, "END:", 0, 4)) depth--;
            if (depth > 8) throw invalid();
          }
          if (depth != 0) throw invalid();
          components.add(join(lines, from, cursor));
          if (components.size() > 2000) throw invalid();
        } else {
          header.writeBytes(line.raw());
          cursor++;
        }
      }
      if (cursor >= lines.size()) throw invalid();
      byte[] closing = lines.get(cursor++).raw();
      result.add(
          new Frame(
              join(lines, start, cursor), header.toByteArray(), closing, List.copyOf(components)));
      if (result.size() > MAX_ITEMS) throw invalid();
    }
    if (result.isEmpty()) throw invalid();
    return result;
  }

  private static byte[] join(List<Line> lines, int from, int until) {
    var result = new ByteArrayOutputStream();
    for (int i = from; i < until; i++) result.writeBytes(lines.get(i).raw());
    return result.toByteArray();
  }

  private static List<Line> lines(byte[] file) {
    List<Line> lines = new ArrayList<>();
    int from = 0;
    while (from < file.length) {
      int end = from;
      do {
        while (end < file.length && file[end] != '\n') end++;
        if (end < file.length) end++;
      } while (end < file.length && (file[end] == ' ' || file[end] == '\t'));
      byte[] raw = Arrays.copyOfRange(file, from, end);
      try {
        String logical = CalendarParsingInput.decode(raw);
        if (logical.endsWith("\n")) logical = logical.substring(0, logical.length() - 1);
        if (logical.endsWith("\r")) logical = logical.substring(0, logical.length() - 1);
        lines.add(new Line(raw, logical));
      } catch (java.nio.charset.CharacterCodingException failure) {
        throw invalid();
      }
      if (lines.size() > 50000) throw invalid();
      from = end;
    }
    return lines;
  }

  private static CalendarValidationException invalid() {
    return new CalendarValidationException(CalendarValidationException.Reason.INVALID_DATA);
  }
}
