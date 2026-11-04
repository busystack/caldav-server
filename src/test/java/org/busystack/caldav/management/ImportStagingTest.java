package org.busystack.caldav.management;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.transaction.support.TransactionSynchronization.*;

import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportStagingTest {
  @TempDir Path temporary;
  UUID actor = UUID.randomUUID(), epoch = UUID.randomUUID(), destination = UUID.randomUUID();

  private ImportStaging.Staged save(
      ImportStaging staging,
      UUID actor,
      UUID epoch,
      UUID destination,
      PortabilityStorage.PrivateFile file,
      long bytes) {
    return staging.save(
        actor,
        epoch,
        destination,
        file,
        bytes,
        stage ->
            new PortabilityService.Report(
                stage.token(), "Fixture", stage.expires(), java.util.List.of(), 0, 0, 0, 0, 0));
  }

  @Test
  void failedReportPreparationCannotReplaceThePreviousValidPreview() throws Exception {
    var staging = new ImportStaging(new Time());
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var original = storage.create(1024);
      var previous = save(staging, actor, epoch, destination, original, 1024);
      try (var replacement = storage.create(1024)) {
        assertThatThrownBy(
                () ->
                    staging.save(
                        actor,
                        epoch,
                        destination,
                        replacement,
                        1024,
                        stage -> {
                          throw new OutOfMemoryError("injected report preparation failure");
                        }))
            .isInstanceOf(OutOfMemoryError.class);
      }
      assertThat(staging.current(actor, epoch)).isSameAs(previous);
      assertThat(staging.owned(previous.token(), actor, epoch).report()).isNotNull();
      assertThat(original.path()).exists();
      staging.close();
      assertThat(storage.reservedBytes()).isZero();
    }
  }

  static final class Time extends Clock {
    Instant now = Instant.parse("2026-11-03T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void retainedMetadataHasAnAggregateBudgetAndFailedReplacementPreservesPreview() throws Exception {
    var staging = new ImportStaging(new Time());
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var original = storage.create(1024);
      var first = save(staging, actor, epoch, destination, original, 24L * 1024 * 1024);
      try (var rejected = storage.create(1024)) {
        assertThatThrownBy(
                () ->
                    save(
                        staging,
                        UUID.randomUUID(),
                        epoch,
                        destination,
                        rejected,
                        16L * 1024 * 1024))
            .isInstanceOf(PortabilityStorage.CapacityException.class);
        assertThatThrownBy(
                () -> save(staging, actor, epoch, destination, rejected, 33L * 1024 * 1024))
            .isInstanceOf(PortabilityStorage.CapacityException.class);
      }
      assertThat(staging.owned(first.token(), actor, epoch).state())
          .isEqualTo(ImportStaging.State.AVAILABLE);
      assertThat(original.path()).exists();
      staging.close();
      assertThat(storage.reservedBytes()).isZero();
    }
  }

  @Test
  void rollbackCanRetryCommitIsSingleUseAndSecurityEpochAndOwnerRemainRequired() throws Exception {
    var clock = new Time();
    var staging = new ImportStaging(clock);
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var file = storage.create(1024);
      var stage = save(staging, actor, epoch, destination, file, 1024);
      assertThatThrownBy(() -> staging.begin(stage.token(), UUID.randomUUID(), epoch))
          .isInstanceOf(org.busystack.caldav.storage.StoreException.class);
      assertThatThrownBy(() -> staging.begin(stage.token(), actor, UUID.randomUUID()))
          .isInstanceOf(org.busystack.caldav.storage.StoreException.class);
      assertThat(staging.begin(stage.token(), actor, epoch).state())
          .isEqualTo(ImportStaging.State.CONFIRMING);
      assertThatThrownBy(() -> staging.begin(stage.token(), actor, epoch))
          .isInstanceOf(org.busystack.caldav.storage.StoreException.class);
      assertThatThrownBy(() -> staging.cancel(stage.token(), actor, epoch))
          .isInstanceOf(org.busystack.caldav.storage.StoreException.class);
      staging.complete(stage, STATUS_ROLLED_BACK, null);
      assertThat(stage.state()).isEqualTo(ImportStaging.State.AVAILABLE);
      assertThat(file.path()).exists();
      staging.begin(stage.token(), actor, epoch);
      staging.complete(stage, STATUS_COMMITTED, null);
      assertThat(stage.state()).isEqualTo(ImportStaging.State.CONSUMED);
      assertThat(file.path()).doesNotExist();
      assertThatThrownBy(() -> staging.begin(stage.token(), actor, epoch))
          .isInstanceOf(org.busystack.caldav.storage.StoreException.class);
      assertThat(storage.reservedBytes()).isZero();
    }
  }

  @Test
  void committedResultsRemainReviewableWhenLongConfirmationOutlivesPreviewExpiry()
      throws Exception {
    var time = new Time();
    var staging = new ImportStaging(time);
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var file = storage.create(1024);
      var stage = save(staging, actor, epoch, destination, file, 1024);
      time.now = time.now.plusSeconds(590);
      staging.begin(stage.token(), actor, epoch);
      time.now = time.now.plusSeconds(100);
      staging.prune();
      assertThat(stage.state()).isEqualTo(ImportStaging.State.CONFIRMING);
      staging.complete(stage, STATUS_COMMITTED, null);
      assertThat(staging.owned(stage.token(), actor, epoch).state())
          .isEqualTo(ImportStaging.State.CONSUMED);
      assertThat(file.path()).doesNotExist();
      time.now = time.now.plusSeconds(601);
      staging.prune();
      assertThatThrownBy(() -> staging.owned(stage.token(), actor, epoch))
          .isInstanceOf(org.busystack.caldav.storage.StoreException.class);
    }
  }

  @Test
  void cancellationExpiryUnknownOutcomesAndExpiredRollbacksDiscardData() throws Exception {
    for (String action : new String[] {"cancel", "expire", "unknown", "expired-rollback"}) {
      var time = new Time();
      var staging = new ImportStaging(time);
      try (var storage = new PortabilityStorage(temporary.resolve(action).toString())) {
        var file = storage.create(1024);
        var stage = save(staging, actor, epoch, destination, file, 1024);
        if (action.equals("cancel")) staging.cancel(stage.token(), actor, epoch);
        else if (action.equals("expire")) {
          time.now = time.now.plusSeconds(601);
          staging.prune();
        } else {
          staging.begin(stage.token(), actor, epoch);
          if (action.equals("expired-rollback")) time.now = time.now.plusSeconds(601);
          staging.complete(
              stage, action.equals("unknown") ? STATUS_UNKNOWN : STATUS_ROLLED_BACK, null);
        }
        assertThat(file.path()).doesNotExist();
        assertThat(storage.reservedBytes()).isZero();
        assertThatThrownBy(() -> staging.begin(stage.token(), actor, epoch))
            .isInstanceOf(org.busystack.caldav.storage.StoreException.class);
      }
    }
  }
}
