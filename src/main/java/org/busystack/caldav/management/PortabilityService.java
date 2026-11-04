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

  public record Export(PortabilityStorage.PrivateFile file, long length, boolean stream)
      implements AutoCloseable {
    public Path path() {
      return file.path();
    }

    @Override
    public void close() {
      file.close();
    }
  }

  private final PortalService portal;
  private final SessionSecurity sessions;
  private final CalendarStore calendars;
  private final DavStore dav;
  private final CalendarInterchange interchange;
  private final ImportStaging staging;
  private final JdbcTemplate jdbc;
  private final SecurityAudit audit;
  private final PortabilityStorage storage;

  public PortabilityService(
      PortalService portal,
      SessionSecurity sessions,
      CalendarStore calendars,
      DavStore dav,
      CalendarInterchange interchange,
      ImportStaging staging,
      JdbcTemplate jdbc,
      SecurityAudit audit,
      PortabilityStorage storage) {
    this.portal = portal;
    this.sessions = sessions;
    this.calendars = calendars;
    this.dav = dav;
    this.interchange = interchange;
    this.staging = staging;
    this.jdbc = jdbc;
    this.audit = audit;
    this.storage = storage;
  }

  @Transactional(
      isolation = Isolation.REPEATABLE_READ,
      timeout = CalendarInterchange.MAX_PROCESSING_SECONDS,
      rollbackFor = IOException.class)
  public Report preview(Authentication auth, UUID destination, java.io.InputStream input)
      throws IOException {
    long deadline =
        System.nanoTime()
            + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                CalendarInterchange.MAX_PROCESSING_SECONDS);
    RecentAuthentication.requireMfa(auth);
    var actor = sessions.assertCurrent(auth);
    var collection = portal.collection(auth, destination);
    if (!collection.writable()) throw new StoreException(StoreException.Kind.FORBIDDEN);
    try (var work = storage.begin(true)) {
      var file = storage.create(PortabilityStorage.FILE_BYTES);
      boolean retained = false;
      try (var index = storage.create(PortabilityStorage.INDEX_BYTES)) {
        try (var output = Files.newOutputStream(file.path())) {
          byte[] buffer = new byte[8192];
          long length = 0;
          int n;
          while ((n = input.read(buffer)) != -1) {
            length += n;
            if (length > CalendarInterchange.MAX_FILE_BYTES || System.nanoTime() > deadline)
              throw new StoreException(StoreException.Kind.LIMIT);
            output.write(buffer, 0, n);
          }
        }
        List<ItemResult> results;
        try (var items = interchange.open(file.path(), index.path(), deadline)) {
          results = classify(actor.id(), destination, items, collection.component(), false);
        }
        file.compact();
        long reportBytes =
            results.stream()
                .mapToLong(
                    i ->
                        256L
                            + 2L
                                * (i.uid().length()
                                    + i.label().length()
                                    + i.explanation().length()))
                .sum();
        var stage =
            staging.save(
                actor.id(),
                actor.epoch(),
                destination,
                file,
                reportBytes,
                prepared ->
                    report(prepared.token(), collection.name(), prepared.expires(), results));
        retained = true;
        return stage.report();
      } finally {
        if (!retained) file.close();
      }
    }
  }

  // Convenience for small internal fixtures; HTTP always streams from the multipart file.
  @Transactional(
      isolation = Isolation.REPEATABLE_READ,
      timeout = CalendarInterchange.MAX_PROCESSING_SECONDS,
      rollbackFor = IOException.class)
  public Report preview(Authentication auth, UUID destination, byte[] file) throws IOException {
    return preview(auth, destination, new java.io.ByteArrayInputStream(file));
  }

  @Transactional(
      timeout = CalendarInterchange.MAX_PROCESSING_SECONDS,
      rollbackFor = IOException.class)
  public Report confirm(Authentication auth, UUID token, boolean confirmed) throws IOException {
    long deadline =
        System.nanoTime()
            + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                CalendarInterchange.MAX_PROCESSING_SECONDS);
    RecentAuthentication.requireMfa(auth);
    var actor = sessions.assertCurrent(auth);
    var available = staging.owned(token, actor.id(), actor.epoch());
    if (!confirmed)
      throw FormValidation.field(
          "confirmed", "Confirm importing the ready items before continuing.");
    dav.lockCollection(actor.id(), available.destination());
    var collection = portal.collection(auth, available.destination());
    if (!collection.writable()) throw new StoreException(StoreException.Kind.FORBIDDEN);
    var stage = staging.begin(token, actor.id(), actor.epoch());
    var outcome = new java.util.concurrent.atomic.AtomicReference<Report>();
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                staging.complete(stage, status, outcome.get());
              }
            });
    try (var work = storage.begin(false);
        var index = storage.create(PortabilityStorage.INDEX_BYTES);
        var items = interchange.open(stage.path(), index.path(), deadline)) {
      var results = classify(actor.id(), stage.destination(), items, collection.component(), true);
      audit.record(
          actor.id(),
          SecurityAudit.Action.COLLECTION_IMPORTED,
          SecurityAudit.Outcome.SUCCESS,
          null);
      var report = report(null, collection.name(), null, results);
      outcome.set(report);
      return report;
    }
  }

  public UUID currentToken(Authentication auth) {
    if (!RecentAuthentication.mfaCurrent(auth)) return null;
    var actor = sessions.assertCurrent(auth);
    var stage = staging.current(actor.id(), actor.epoch());
    if (stage == null) return null;
    try {
      portal.collection(auth, stage.destination());
    } catch (StoreException unavailable) {
      if (unavailable.kind() == StoreException.Kind.FORBIDDEN
          || unavailable.kind() == StoreException.Kind.NOT_FOUND) return null;
      throw unavailable;
    }
    return stage.token();
  }

  public Report review(Authentication auth, UUID token, int page) {
    RecentAuthentication.requireMfa(auth);
    var actor = sessions.assertCurrent(auth);
    var stage = staging.owned(token, actor.id(), actor.epoch());
    // Never redisplay private preview metadata after destination access is revoked.
    portal.collection(auth, stage.destination());
    return stage.report();
  }

  public void cancel(Authentication auth, UUID token) {
    var actor = sessions.assertCurrent(auth);
    staging.cancel(token, actor.id(), actor.epoch());
  }

  private List<ItemResult> classify(
      UUID actor,
      UUID destination,
      CalendarInterchange.IndexedFile items,
      String component,
      boolean write)
      throws IOException {
    List<ItemResult> result = new ArrayList<>();
    long resourceBytes = 0;
    for (int n = 0; n < items.size(); n++) {
      var item = interchange.item(items, n, component);
      if (item.rejection().isEmpty()) {
        resourceBytes += item.content().length;
        if (resourceBytes > PortabilityStorage.FILE_BYTES)
          throw new StoreException(StoreException.Kind.LIMIT);
      }
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
      result.add(
          new ItemResult(
              item.number(),
              item.label(),
              item.uid().substring(0, Math.min(100, item.uid().length())),
              status,
              explanation));
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
  @Transactional(
      isolation = Isolation.REPEATABLE_READ,
      timeout = CalendarInterchange.MAX_PROCESSING_SECONDS)
  public Export export(Authentication auth, UUID id) throws IOException {
    RecentAuthentication.requireMfa(auth);
    UUID actor = sessions.assertCurrent(auth).id();
    var collection = calendars.collection(actor, id);
    PortabilityStorage.PrivateFile managed = null;
    boolean transferred = false;
    try (var work = storage.begin(false)) {
      managed = storage.create(PortabilityStorage.FILE_BYTES);
      Path file = managed.path();
      long deadline =
          System.nanoTime()
              + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                  CalendarInterchange.MAX_PROCESSING_SECONDS);
      long length = 0;
      int count = 0;
      String after = "";
      CalendarInterchange.ExportParts first = null;
      String context = null;
      boolean compatible = true;
      try (var output = Files.newOutputStream(file)) {
        while (true) {
          if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
            throw new StoreException(StoreException.Kind.LIMIT);
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
            if (++count > CalendarInterchange.MAX_ITEMS
                || length + body.length + 2 > PortabilityStorage.FILE_BYTES)
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
            if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
              throw new StoreException(StoreException.Kind.LIMIT);
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
      var result = new Export(managed, length, !compatible);
      transferred = true;
      return result;
    } finally {
      if (!transferred && managed != null) managed.close();
    }
  }
}
