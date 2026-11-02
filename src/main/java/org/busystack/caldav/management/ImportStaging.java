package org.busystack.caldav.management;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.busystack.caldav.calendar.CalendarInterchange;
import org.busystack.caldav.storage.StoreException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Private, expiring confirmation files. No calendar bytes or paths are placed in browser state. */
@Component
public class ImportStaging {
  public record Staged(
      UUID token,
      UUID actor,
      UUID epoch,
      UUID destination,
      Instant expires,
      Path path,
      long size) {}

  private final Map<UUID, Staged> staged = new HashMap<>();
  private long bytes;

  public synchronized Staged save(UUID actor, UUID epoch, UUID destination, byte[] content)
      throws IOException {
    prune();
    for (var old : staged.values().stream().filter(s -> s.actor().equals(actor)).toList())
      remove(old);
    if (content.length > CalendarInterchange.MAX_FILE_BYTES
        || bytes + content.length > 32L * 1024 * 1024
        || staged.size() >= 32) throw new StoreException(StoreException.Kind.LIMIT);
    Path path = Files.createTempFile("busystack-import-", ".private");
    try {
      Files.write(path, content);
    } catch (IOException failure) {
      Files.deleteIfExists(path);
      throw failure;
    }
    var stage =
        new Staged(
            UUID.randomUUID(),
            actor,
            epoch,
            destination,
            Instant.now().plusSeconds(600),
            path,
            content.length);
    staged.put(stage.token(), stage);
    bytes += content.length;
    return stage;
  }

  public synchronized Staged owned(UUID token, UUID actor, UUID epoch) {
    prune();
    var result = staged.get(token);
    if (result == null || !result.actor().equals(actor) || !result.epoch().equals(epoch))
      throw new StoreException(StoreException.Kind.PRECONDITION);
    return result;
  }

  public synchronized byte[] take(UUID token, UUID actor, UUID epoch) throws IOException {
    var stage = owned(token, actor, epoch);
    try {
      return Files.readAllBytes(stage.path());
    } finally {
      remove(stage);
    }
  }

  public synchronized void cancel(UUID token, UUID actor, UUID epoch) {
    remove(owned(token, actor, epoch));
  }

  private void remove(Staged stage) {
    try {
      Files.deleteIfExists(stage.path());
    } catch (IOException failure) {
      throw new IllegalStateException("Temporary import cleanup failed");
    }
    if (staged.remove(stage.token()) != null) bytes -= stage.size();
  }

  @Scheduled(fixedDelay = 30000)
  public synchronized void prune() {
    for (var stage :
        staged.values().stream().filter(s -> !s.expires().isAfter(Instant.now())).toList())
      remove(stage);
  }

  @PreDestroy
  public synchronized void close() {
    for (var stage : staged.values().stream().toList()) remove(stage);
  }
}
