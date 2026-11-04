package org.busystack.caldav.management;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortabilityStorageTest {
  @TempDir Path temporary;

  @Test
  void fileCountIsBoundedEvenWhenFilesAreTiny() throws Exception {
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var files = new java.util.ArrayList<PortabilityStorage.PrivateFile>();
      for (int n = 0; n < 72; n++) files.add(storage.create(1));
      assertThatThrownBy(() -> storage.create(1))
          .isInstanceOf(PortabilityStorage.CapacityException.class);
      for (var file : files) file.close();
      assertThat(storage.reservedBytes()).isZero();
    }
  }

  @Test
  void reservationsCoverRetainedFilesDownloadsAndMultipartBeforeAdmission() throws Exception {
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var preview = storage.create(PortabilityStorage.FILE_BYTES);
      var download = storage.create(PortabilityStorage.FILE_BYTES);
      assertThat(storage.reservedBytes()).isEqualTo(128 * PortabilityStorage.MIB);
      assertThatThrownBy(() -> storage.begin(true))
          .isInstanceOf(PortabilityStorage.CapacityException.class);
      assertThat(storage.reservedBytes()).isEqualTo(128 * PortabilityStorage.MIB);
      download.close();
      try (var upload = storage.begin(true)) {
        assertThat(storage.reservedBytes()).isEqualTo(225 * PortabilityStorage.MIB);
        try (var file = storage.create(PortabilityStorage.FILE_BYTES);
            var index = storage.create(PortabilityStorage.INDEX_BYTES)) {
          assertThat(storage.reservedBytes()).isEqualTo(225 * PortabilityStorage.MIB);
          Files.writeString(file.path(), "private");
          file.compact();
          assertThat(storage.reservedBytes()).isEqualTo(161 * PortabilityStorage.MIB + 7);
        }
      }
      preview.close();
      assertThat(storage.reservedBytes()).isZero();
    }
  }

  @Test
  void operationGateAndByteLedgerAreSharedAcrossThreads() throws Exception {
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString());
        var pool = Executors.newFixedThreadPool(2)) {
      var acquired = new CountDownLatch(2);
      var release = new CountDownLatch(1);
      var task =
          (Callable<Void>)
              () -> {
                try (var work = storage.begin(false)) {
                  acquired.countDown();
                  release.await(10, TimeUnit.SECONDS);
                }
                return null;
              };
      var one = pool.submit(task);
      var two = pool.submit(task);
      assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(() -> storage.begin(false))
            .isInstanceOf(PortabilityStorage.CapacityException.class);
      } finally {
        release.countDown();
      }
      one.get();
      two.get();
      assertThat(storage.reservedBytes()).isZero();
    }
  }

  @Test
  void privateFilesAndOrphanCleanupNeverFollowLinksOrDeleteUnrelatedFiles() throws Exception {
    Path root = temporary.resolve("private");
    try (var storage = new PortabilityStorage(root.toString())) {
      var file = storage.create(1024);
      assertThat(Files.getPosixFilePermissions(root))
          .isEqualTo(PosixFilePermissions.fromString("rwx------"));
      assertThat(Files.getPosixFilePermissions(file.path()))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      file.close();
      assertThat(Files.exists(file.path())).isFalse();
      storage.multipartDirectory();
    }
    Path orphan = root.resolve("data-" + UUID.randomUUID() + ".private");
    Files.writeString(orphan, "calendar remnants");
    Path multipart = root.resolve("multipart/upload_fixture_00000001.tmp");
    Files.writeString(multipart, "upload remnants");
    Path unrelated = root.resolve("operator-notes");
    Files.writeString(unrelated, "preserve");
    try (var restarted = new PortabilityStorage(root.toString())) {
      restarted.multipartDirectory();
      assertThat(orphan).doesNotExist();
      assertThat(multipart).doesNotExist();
      assertThat(unrelated).hasContent("preserve");
    }
    Path outside = temporary.resolve("outside");
    Files.writeString(outside, "preserve");
    Files.createSymbolicLink(orphan, outside);
    assertThatThrownBy(() -> new PortabilityStorage(root.toString()))
        .isInstanceOf(java.io.IOException.class);
    assertThat(outside).hasContent("preserve");
  }

  @Test
  void failedCleanupKeepsReservationUntilItCanBeRetried() throws Exception {
    try (var storage = new PortabilityStorage(temporary.resolve("private").toString())) {
      var file = storage.create(1024);
      Files.delete(file.path());
      Files.createDirectory(file.path());
      Files.writeString(file.path().resolve("block"), "x");
      file.close();
      assertThat(storage.reservedBytes()).isEqualTo(1024);
      Files.delete(file.path().resolve("block"));
      storage.cleanup();
      assertThat(storage.reservedBytes()).isZero();
    }
  }
}
