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
  public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
  public static final int MAX_ITEMS = 10000;
  public static final int MAX_INDEX_BYTES = 32 * 1024 * 1024;
  public static final int MAX_PROCESSING_SECONDS = 600;

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

  /** Raw, bounded framing for export; callers first validate the stored resource. */
  public record ExportParts(byte[] header, byte[] zones, byte[] items, byte[] closing) {
    public ExportParts {
      header = header.clone();
      zones = zones.clone();
      items = items.clone();
      closing = closing.clone();
    }

    @Override
    public byte[] header() {
      return header.clone();
    }

    @Override
    public byte[] zones() {
      return zones.clone();
    }

    @Override
    public byte[] items() {
      return items.clone();
    }

    @Override
    public byte[] closing() {
      return closing.clone();
    }

    public String contextFingerprint() {
      try {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(header.length).array());
        digest.update(header);
        digest.update(zones);
        return java.util.HexFormat.of().formatHex(digest.digest());
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

  public static ExportParts exportParts(byte[] resource) {
    var framed = frames(resource);
    if (framed.size() != 1) throw invalid();
    var frame = framed.getFirst();
    var zones = new ByteArrayOutputStream();
    var items = new ByteArrayOutputStream();
    for (byte[] component : frame.components()) {
      String first = lines(component).getFirst().value();
      if (first.equalsIgnoreCase("BEGIN:VTIMEZONE")) zones.writeBytes(component);
      else items.writeBytes(component);
    }
    return new ExportParts(
        frame.header(), zones.toByteArray(), items.toByteArray(), frame.closing());
  }

  private final CalendarAdmission admission;

  public CalendarInterchange(CalendarAdmission admission) {
    this.admission = admission;
  }

  /**
   * File offsets and linked segments keep bodies and arbitrarily interleaved exceptions on disk.
   */
  public static final class IndexedFile implements AutoCloseable {
    private final java.io.RandomAccessFile source, index;
    private final List<Series> series = new ArrayList<>();
    private boolean duplicatesResolved;
    private final long deadline;

    private IndexedFile(java.nio.file.Path source, java.nio.file.Path index, long deadline)
        throws java.io.IOException {
      this.deadline = deadline;
      this.source = new java.io.RandomAccessFile(source.toFile(), "r");
      try {
        this.index = new java.io.RandomAccessFile(index.toFile(), "rw");
        this.index.setLength(0);
      } catch (java.io.IOException | RuntimeException | Error failure) {
        this.source.close();
        throw failure;
      }
    }

    private void resolveDuplicates(String destination, CalendarAdmission admission)
        throws java.io.IOException {
      if (duplicatesResolved) return;
      var candidates = new ArrayList<Integer>();
      for (int n = 0; n < series.size(); n++)
        if (series.get(n).duplicate) {
          candidates.add(n);
          series.get(n).duplicate = false;
        }
      Map<String, List<Integer>> valid = new java.util.HashMap<>();
      for (int n : candidates) {
        var candidate = item(n, destination, admission);
        if (candidate.rejection().isEmpty())
          valid.computeIfAbsent(candidate.uid(), uid -> new ArrayList<>()).add(n);
      }
      for (var matches : valid.values())
        if (matches.size() > 1) for (int n : matches) series.get(n).duplicate = true;
      duplicatesResolved = true;
    }

    public int size() {
      return series.size();
    }

    public void checkTime() {
      if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
        throw new org.busystack.caldav.storage.StoreException(
            org.busystack.caldav.storage.StoreException.Kind.LIMIT);
    }

    public Item item(int number, String destination, CalendarAdmission admission)
        throws java.io.IOException {
      checkTime();
      var s = series.get(number);
      long size = s.frame.header.size + s.frame.zones.size + s.parts.size + s.frame.closing.size;
      String rejection =
          s.duplicate
              ? "UID occurs in multiple calendar objects; combine its recurrence series before importing"
              : "";
      if (!java.util.Set.of("VEVENT", "VTODO").contains(s.type))
        rejection = "Unsupported component type";
      else if (!s.type.equals(destination))
        rejection = "Does not match the destination collection type";
      else if (size > CalendarContent.MAX_BYTES)
        rejection = "UID series exceeds the 1 MiB resource limit";
      byte[] content = new byte[0];
      String uid = s.uid;
      if (rejection.isEmpty()) {
        var out = new ByteArrayOutputStream((int) size);
        if (s.frame.groups == 1) {
          if (s.frame.end - s.frame.start > CalendarContent.MAX_BYTES)
            rejection = "UID series exceeds the 1 MiB resource limit";
          else {
            source.seek(s.frame.start);
            content = new byte[(int) (s.frame.end - s.frame.start)];
            source.readFully(content);
          }
        } else {
          copy(s.frame.header, out);
          copy(s.frame.zones, out);
          copy(s.parts, out);
          copy(s.frame.closing, out);
          content = out.toByteArray();
        }
        if (rejection.isEmpty()) {
          try {
            var accepted = admission.admit(content, destination);
            content = accepted.content();
            uid = accepted.parsed().uid();
          } catch (IllegalArgumentException
              | org.busystack.caldav.calendar.query.QueryException failure) {
            rejection = "Invalid or unsupported calendar data in this UID series";
          }
        }
      }
      return new Item(
          number + 1, uid, s.type, s.label, rejection.isEmpty() ? content : new byte[0], rejection);
    }

    private void copy(Chain chain, ByteArrayOutputStream output) throws java.io.IOException {
      long cursor = chain.first;
      byte[] buffer = new byte[8192];
      while (cursor >= 0) {
        index.seek(cursor);
        long offset = index.readLong();
        int length = index.readInt();
        cursor = index.readLong();
        source.seek(offset);
        while (length > 0) {
          int n = Math.min(length, buffer.length);
          source.readFully(buffer, 0, n);
          output.write(buffer, 0, n);
          length -= n;
        }
      }
    }

    private void append(Chain chain, long offset, int length) throws java.io.IOException {
      chain.size += length;
      if (chain.last >= 0) {
        index.seek(chain.last);
        long previous = index.readLong();
        int size = index.readInt();
        if (previous + size == offset) {
          index.seek(chain.last + 8);
          index.writeInt(size + length);
          return;
        }
      }
      long position = index.length();
      if (position + 20 > MAX_INDEX_BYTES) throw invalid();
      index.seek(position);
      index.writeLong(offset);
      index.writeInt(length);
      index.writeLong(-1);
      if (chain.last >= 0) {
        index.seek(chain.last + 12);
        index.writeLong(position);
      } else chain.first = position;
      chain.last = position;
    }

    @Override
    public void close() throws java.io.IOException {
      try {
        source.close();
      } finally {
        index.close();
      }
    }
  }

  private static final class Chain {
    long first = -1, last = -1, size;
  }

  private static final class FileFrame {
    long start, end;
    int groups;
    final Chain header = new Chain(), zones = new Chain(), closing = new Chain();
  }

  private static final class Series {
    final FileFrame frame;
    final String type, uid, label;
    final Chain parts = new Chain();
    boolean duplicate;

    Series(FileFrame frame, Indexed item) {
      this.frame = frame;
      type = item.type();
      uid = item.uid();
      label = item.label();
    }
  }

  /** Build a bounded disk index; never retain input or resulting resource bodies together. */
  public IndexedFile open(java.nio.file.Path input, java.nio.file.Path index)
      throws java.io.IOException {
    return open(
        input,
        index,
        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(MAX_PROCESSING_SECONDS));
  }

  public IndexedFile open(java.nio.file.Path input, java.nio.file.Path index, long deadline)
      throws java.io.IOException {
    long size = java.nio.file.Files.size(input);
    if (size == 0 || size > MAX_FILE_BYTES) throw invalid();
    var result = new IndexedFile(input, index, deadline);
    try (var raw = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(input));
        var stream = new java.io.PushbackInputStream(raw, 1)) {
      long offset = 0;
      int frames = 0;
      Map<String, Series> unique = new java.util.HashMap<>();
      FileFrame frame = null;
      Map<String, Series> groups = null;
      java.util.ArrayDeque<String> nesting = new java.util.ArrayDeque<>();
      long componentStart = 0;
      var component = new ByteArrayOutputStream();
      while (true) {
        result.checkTime();
        byte[] line = nextLine(stream);
        if (line == null) break;
        long start = offset;
        offset += line.length;
        String value;
        try {
          value = CalendarParsingInput.decode(line).replaceFirst("[\\r]?\\n$", "");
          CalendarTextProfile.validate(value);
        } catch (java.nio.charset.CharacterCodingException failure) {
          throw invalid();
        }
        if (frame == null) {
          if (!value.replaceFirst("^\uFEFF", "").equalsIgnoreCase("BEGIN:VCALENDAR")
              || ++frames > MAX_ITEMS) throw invalid();
          frame = new FileFrame();
          frame.start = start;
          groups = new LinkedHashMap<>();
          result.append(frame.header, start, line.length);
        } else if (nesting.isEmpty() && value.equalsIgnoreCase("END:VCALENDAR")) {
          result.append(frame.closing, start, line.length);
          frame.end = offset;
          frame.groups = groups.size();
          if (groups.isEmpty()) throw invalid();
          frame = null;
        } else {
          if (nesting.isEmpty() && !value.regionMatches(true, 0, "BEGIN:", 0, 6)) {
            if (value.regionMatches(true, 0, "END:", 0, 4)) throw invalid();
            result.append(frame.header, start, line.length);
            if (frame.header.size > CalendarContent.MAX_BYTES) throw invalid();
            continue;
          }
          if (nesting.isEmpty()) {
            componentStart = start;
            component.reset();
          }
          if (component.size() + line.length <= CalendarContent.MAX_BYTES)
            component.writeBytes(line);
          if (value.regionMatches(true, 0, "BEGIN:", 0, 6)) {
            String type = value.substring(6).toUpperCase(java.util.Locale.ROOT);
            if (type.equals("VCALENDAR") || nesting.size() >= 8) throw invalid();
            nesting.push(type);
          } else if (value.regionMatches(true, 0, "END:", 0, 4)) {
            if (nesting.isEmpty()
                || !nesting.pop().equals(value.substring(4).toUpperCase(java.util.Locale.ROOT)))
              throw invalid();
            if (nesting.isEmpty()) {
              int length = Math.toIntExact(offset - componentStart);
              var info =
                  length <= CalendarContent.MAX_BYTES
                      ? indexComponent(component.toByteArray())
                      : new Indexed("OVERSIZED", "", "Oversized component");
              if (info.type().equals("VTIMEZONE")) {
                result.append(frame.zones, componentStart, length);
                if (frame.zones.size > CalendarContent.MAX_BYTES) throw invalid();
              } else {
                String key = info.type() + "\u0000" + info.uid();
                Series series = groups.get(key);
                if (series == null) {
                  if (result.series.size() >= MAX_ITEMS) throw invalid();
                  series = new Series(frame, info);
                  groups.put(key, series);
                  result.series.add(series);
                  Series previous = unique.putIfAbsent(info.uid(), series);
                  if (previous != null) {
                    previous.duplicate = true;
                    series.duplicate = true;
                  }
                }
                result.append(series.parts, componentStart, length);
              }
            }
          }
        }
      }
      if (frame != null || !nesting.isEmpty() || result.series.isEmpty()) throw invalid();
      return result;
    } catch (java.io.IOException | RuntimeException | Error failure) {
      result.close();
      throw failure;
    }
  }

  public Item item(IndexedFile file, int number, String destination) throws java.io.IOException {
    file.resolveDuplicates(destination, admission);
    return file.item(number, destination, admission);
  }

  private static Indexed indexComponent(byte[] component) {
    var wrapped = new ByteArrayOutputStream();
    wrapped.writeBytes(
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack indexing//EN\r\n"
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    wrapped.writeBytes(component);
    if (component[component.length - 1] != '\n') wrapped.writeBytes(new byte[] {'\r', '\n'});
    wrapped.writeBytes("END:VCALENDAR\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    var entries = index(wrapped.toByteArray(), new Budget());
    if (entries.size() != 1) throw invalid();
    return entries.getFirst();
  }

  private static byte[] nextLine(java.io.PushbackInputStream stream) throws java.io.IOException {
    var bytes = new ByteArrayOutputStream();
    int value;
    while ((value = stream.read()) != -1) {
      bytes.write(value);
      if (bytes.size() > CalendarContent.MAX_BYTES) throw invalid();
      if (value == '\n') {
        int next = stream.read();
        if (next != -1) stream.unread(next);
        if (next != ' ' && next != '\t') break;
      }
    }
    return bytes.size() == 0 ? null : bytes.toByteArray();
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
      if (depth-- == 1)
        items.add(
            new Indexed(
                type,
                uid.length() > 1024 ? uid.substring(0, 1025) : uid,
                label.length() > 200 ? label.substring(0, 200) : label));
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
