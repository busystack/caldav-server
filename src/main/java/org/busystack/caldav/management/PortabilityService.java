package org.busystack.caldav.management;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.busystack.caldav.calendar.CalendarInterchange;
import org.busystack.caldav.security.RecentAuthentication;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.security.session.SessionSecurity;
import org.busystack.caldav.storage.CalendarStore;
import org.busystack.caldav.storage.DavStore;
import org.busystack.caldav.storage.StoreException;
import org.busystack.caldav.storage.WriteCondition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortabilityService {
  public record ItemResult(
      int number, String label, String uid, String status, String explanation) {}

  public record Report(
      UUID token,
      String collectionName,
      Instant expires,
      List<ItemResult> items,
      long imported,
      long ready,
      long skipped,
      long conflicts,
      long rejected) {
    public Report {
      items = List.copyOf(items);
    }
  }

  public record Export(Path path, long length, boolean stream) {}

  private final PortalService portal;
  private final SessionSecurity sessions;
  private final CalendarStore calendars;
  private final DavStore dav;
  private final CalendarInterchange interchange;
  private final ImportStaging staging;
  private final JdbcTemplate jdbc;
  private final SecurityAudit audit;
  private final java.util.concurrent.Semaphore exports = new java.util.concurrent.Semaphore(2);

  public PortabilityService(
      PortalService portal,
      SessionSecurity sessions,
      CalendarStore calendars,
      DavStore dav,
      CalendarInterchange interchange,
      ImportStaging staging,
      JdbcTemplate jdbc,
      SecurityAudit audit) {
    this.portal = portal;
    this.sessions = sessions;
    this.calendars = calendars;
    this.dav = dav;
    this.interchange = interchange;
    this.staging = staging;
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
  public Report preview(Authentication auth, UUID destination, byte[] file) throws IOException {
    RecentAuthentication.requireMfa(auth);
    var actor = sessions.assertCurrent(auth);
    var collection = portal.collection(auth, destination);
    if (!collection.writable()) throw new StoreException(StoreException.Kind.FORBIDDEN);
    var items = interchange.read(file, collection.component());
    var results = classify(actor.id(), destination, items, false);
    var stage = staging.save(actor.id(), actor.epoch(), destination, file);
    return report(stage.token(), collection.name(), stage.expires(), results);
  }

  @Transactional(timeout = 30, rollbackFor = IOException.class)
  public Report confirm(Authentication auth, UUID token, boolean confirmed) throws IOException {
    RecentAuthentication.requireMfa(auth);
    var actor = sessions.assertCurrent(auth);
    var stage = staging.owned(token, actor.id(), actor.epoch());
    if (!confirmed) throw new IllegalArgumentException("Confirm importing the ready items");
    dav.lockCollection(actor.id(), stage.destination());
    var collection = portal.collection(auth, stage.destination());
    if (!collection.writable()) throw new StoreException(StoreException.Kind.FORBIDDEN);
    var items =
        interchange.read(staging.take(token, actor.id(), actor.epoch()), collection.component());
    var results = classify(actor.id(), stage.destination(), items, true);
    audit.record(
        actor.id(), SecurityAudit.Action.COLLECTION_IMPORTED, SecurityAudit.Outcome.SUCCESS, null);
    return report(null, collection.name(), null, results);
  }

  public void cancel(Authentication auth, UUID token) {
    var actor = sessions.assertCurrent(auth);
    staging.cancel(token, actor.id(), actor.epoch());
  }

  private List<ItemResult> classify(
      UUID actor, UUID destination, List<CalendarInterchange.Item> items, boolean write) {
    List<ItemResult> result = new ArrayList<>();
    for (var item : items) {
      String status, explanation;
      if (!item.rejection().isEmpty()) {
        status = "Rejected";
        explanation = item.rejection();
      } else {
        var existing =
            jdbc.queryForList(
                "SELECT name FROM calendar_resources WHERE collection_id=? AND uid=?",
                String.class,
                destination,
                item.uid());
        if (!existing.isEmpty()) {
          var stored = calendars.get(actor, destination, existing.getFirst());
          boolean same = Arrays.equals(stored.content(), item.content());
          status = same ? "Skipped" : "Conflicting";
          explanation =
              same
                  ? "Identical stored resource already exists"
                  : "This UID already exists with different contents; existing data is preserved";
        } else {
          status = write ? "Imported" : "Ready";
          explanation = "New UID series; recurrence components remain together";
          if (write)
            dav.putResource(
                actor,
                destination,
                "import-" + UUID.randomUUID() + ".ics",
                item.content(),
                WriteCondition.create());
        }
      }
      result.add(new ItemResult(item.number(), item.label(), item.uid(), status, explanation));
    }
    return List.copyOf(result);
  }

  private static Report report(UUID token, String name, Instant expires, List<ItemResult> results) {
    return new Report(
        token,
        name,
        expires,
        results,
        count(results, "Imported"),
        count(results, "Ready"),
        count(results, "Skipped"),
        count(results, "Conflicting"),
        count(results, "Rejected"));
  }

  private static long count(List<ItemResult> results, String status) {
    return results.stream().filter(i -> i.status().equals(status)).count();
  }

  /** Build the complete download under one committed snapshot before sending success headers. */
  @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 60)
  public Export export(Authentication auth, UUID id) throws IOException {
    RecentAuthentication.requireMfa(auth);
    UUID actor = sessions.assertCurrent(auth).id();
    var collection = calendars.collection(actor, id);
    if (!exports.tryAcquire()) throw new StoreException(StoreException.Kind.LIMIT);
    Path file = null;
    try {
      file = Files.createTempFile("busystack-export-", ".private");
      long length = 0;
      int count = 0;
      String after = "";
      CalendarInterchange.ExportParts first = null;
      String context = null;
      boolean compatible = true;
      try (var output = Files.newOutputStream(file)) {
        while (true) {
          var batch = calendars.resourceBatch(actor, id, after);
          if (batch.isEmpty()) break;
          for (var resource : batch) {
            byte[] body = resource.content();
            // Reuse strict validation for legacy invalid resources; never omit them silently.
            org.busystack.caldav.calendar.CalendarContent.parse(body, collection.component());
            var parts = CalendarInterchange.exportParts(body);
            if (first == null) {
              first = parts;
              context = parts.contextFingerprint();
            } else if (!context.equals(parts.contextFingerprint())) compatible = false;
            if (++count > 10000 || length + body.length + 2 > 64L * 1024 * 1024)
              throw new StoreException(StoreException.Kind.LIMIT);
            output.write(body);
            length += body.length;
            if (body[body.length - 1] != '\n') {
              output.write('\r');
              output.write('\n');
              length += 2;
            }
            after = resource.name();
          }
        }
      }
      if (compatible && count > 1) {
        // One VCALENDAR is consumed by more migration tools. Combine only identical calendar
        // contexts: no property, timezone, component or parameter needs rewriting or dropping.
        after = "";
        try (var output = Files.newOutputStream(file)) {
          output.write(first.header());
          output.write(first.zones());
          while (true) {
            var batch = calendars.resourceBatch(actor, id, after);
            if (batch.isEmpty()) break;
            for (var resource : batch) {
              output.write(CalendarInterchange.exportParts(resource.content()).items());
              after = resource.name();
            }
          }
          output.write(first.closing());
        }
        length = Files.size(file);
      }
      return new Export(file, length, !compatible);
    } catch (IOException | RuntimeException failure) {
      if (file != null) Files.deleteIfExists(file);
      throw failure;
    } finally {
      exports.release();
    }
  }
}
