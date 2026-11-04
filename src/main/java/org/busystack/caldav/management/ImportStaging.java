package org.busystack.caldav.management;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import org.busystack.caldav.storage.StoreException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Account/epoch-bound previews; filesystem cleanup is deliberately outside database atomicity. */
@Component
public class ImportStaging {
  public enum State {
    AVAILABLE,
    CONFIRMING,
    CONSUMED,
    EXPIRED,
    CANCELLED,
    UNAVAILABLE
  }

  public static final class Staged {
    private final UUID token = UUID.randomUUID();
    private final UUID actor, epoch, destination;
    private Instant expires;
    private final PortabilityStorage.PrivateFile file;
    private final long reportBytes;
    private State state = State.AVAILABLE;
    private PortabilityService.Report report;

    Staged(
        UUID actor,
        UUID epoch,
        UUID destination,
        PortabilityStorage.PrivateFile file,
        Instant now,
        long reportBytes) {
      this.expires = now.plusSeconds(600);
      this.reportBytes = reportBytes;
      this.actor = actor;
      this.epoch = epoch;
      this.destination = destination;
      this.file = file;
    }

    public UUID token() {
      return token;
    }

    public UUID destination() {
      return destination;
    }

    public Instant expires() {
      return expires;
    }

    public java.nio.file.Path path() {
      return file.path();
    }

    public State state() {
      return state;
    }

    public PortabilityService.Report report() {
      return report;
    }
  }

  private final java.time.Clock clock;

  public ImportStaging() {
    this(java.time.Clock.systemUTC());
  }

  ImportStaging(java.time.Clock clock) {
    this.clock = clock;
  }

  private final Map<UUID, Staged> stages = new HashMap<>();

  /** Called only after the replacement has been completely validated and staged. */
  public synchronized Staged save(
      UUID actor,
      UUID epoch,
      UUID destination,
      PortabilityStorage.PrivateFile file,
      long reportBytes,
      java.util.function.Function<Staged, PortabilityService.Report> prepareReport) {
    prune();
    var old = stages.values().stream().filter(s -> s.actor.equals(actor)).toList();
    if (old.stream().anyMatch(s -> s.state == State.CONFIRMING))
      throw new StoreException(StoreException.Kind.PRECONDITION);
    long retainedReportBytes =
        stages.values().stream().filter(s -> !old.contains(s)).mapToLong(s -> s.reportBytes).sum();
    if (reportBytes < 0
        || retainedReportBytes + reportBytes > 32L * 1024 * 1024
        || stages.size() - old.size() >= 32) throw new PortabilityStorage.CapacityException();
    var stage = new Staged(actor, epoch, destination, file, clock.instant(), reportBytes);
    stage.report = Objects.requireNonNull(prepareReport.apply(stage));
    for (var previous : old) {
      previous.state = State.CANCELLED;
      previous.file.close();
      stages.remove(previous.token);
    }
    stages.put(stage.token, stage);
    return stage;
  }

  public synchronized Staged owned(UUID token, UUID actor, UUID epoch) {
    prune();
    var stage = stages.get(token);
    if (stage == null
        || !stage.actor.equals(actor)
        || !stage.epoch.equals(epoch)
        || stage.state == State.CANCELLED
        || stage.state == State.EXPIRED
        || stage.state == State.UNAVAILABLE)
      throw new StoreException(StoreException.Kind.PRECONDITION);
    return stage;
  }

  public synchronized Staged current(UUID actor, UUID epoch) {
    prune();
    return stages.values().stream()
        .filter(
            stage ->
                stage.actor.equals(actor)
                    && stage.epoch.equals(epoch)
                    && (stage.state == State.AVAILABLE
                        || stage.state == State.CONFIRMING
                        || stage.state == State.CONSUMED))
        .findFirst()
        .orElse(null);
  }

  public synchronized Staged begin(UUID token, UUID actor, UUID epoch) {
    var stage = owned(token, actor, epoch);
    if (stage.state != State.AVAILABLE) throw new StoreException(StoreException.Kind.PRECONDITION);
    stage.state = State.CONFIRMING;
    return stage;
  }

  public synchronized void complete(
      Staged stage, int completion, PortabilityService.Report result) {
    if (stage.state != State.CONFIRMING) return;
    if (completion
        == org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED) {
      stage.state = State.CONSUMED;
      stage.expires = clock.instant().plusSeconds(600);
      stage.report = result;
      stage.file.close();
    } else if (completion
            == org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK
        && stage.expires.isAfter(clock.instant())) stage.state = State.AVAILABLE;
    else {
      stage.state =
          completion
                  == org.springframework.transaction.support.TransactionSynchronization
                      .STATUS_UNKNOWN
              ? State.UNAVAILABLE
              : State.EXPIRED;
      stage.file.close();
    }
  }

  public synchronized void cancel(UUID token, UUID actor, UUID epoch) {
    var stage = owned(token, actor, epoch);
    if (stage.state != State.AVAILABLE) throw new StoreException(StoreException.Kind.PRECONDITION);
    stage.state = State.CANCELLED;
    stage.file.close();
    stages.remove(token);
  }

  @Scheduled(fixedDelay = 30000)
  public synchronized void prune() {
    for (var stage : List.copyOf(stages.values())) {
      if (stage.state != State.CONFIRMING && !stage.expires.isAfter(clock.instant())) {
        stage.state = State.EXPIRED;
        stage.file.close();
        stages.remove(stage.token);
      }
    }
  }

  @PreDestroy
  public synchronized void close() {
    for (var stage : stages.values()) stage.file.close();
    stages.clear();
  }
}
