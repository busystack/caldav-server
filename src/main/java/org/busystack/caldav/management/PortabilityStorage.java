package org.busystack.caldav.management;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One private filesystem, byte reservation ledger and work gate for portability. */
@Component
public final class PortabilityStorage implements AutoCloseable {
  public static final long MIB = 1024L * 1024;
  public static final long FILE_BYTES =
      org.busystack.caldav.calendar.CalendarInterchange.MAX_FILE_BYTES;
  public static final long INDEX_BYTES =
      org.busystack.caldav.calendar.CalendarInterchange.MAX_INDEX_BYTES;
  public static final long UPLOAD_BYTES = 65 * MIB;
  public static final long TOTAL_BYTES = 272 * MIB;
  private final Path root;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final Semaphore operations = new Semaphore(2);
  private final Set<PrivateFile> files = new HashSet<>();
  private final ThreadLocal<Work> current = new ThreadLocal<>();
  private long reserved, multipartPending;
  private int uploads;

  public PortabilityStorage(
      @Value("${busystack.portability.directory:${java.io.tmpdir}/busystack-portability}")
          String directory)
      throws IOException {
    root = Path.of(directory).toAbsolutePath().normalize();
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
      Files.createDirectory(
          root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    if (!root.equals(root.toRealPath())
        || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        || !Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS)
            .equals(PosixFilePermissions.fromString("rwx------"))
        || !Files.getOwner(root, LinkOption.NOFOLLOW_LINKS)
            .equals(
                root.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"))))
      throw new IOException(
          "Portability directory must be private and owned by the application user");
    lockChannel =
        FileChannel.open(
            root.resolve("instance.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    Files.setPosixFilePermissions(
        root.resolve("instance.lock"), PosixFilePermissions.fromString("rw-------"));
    lock = lockChannel.tryLock();
    if (lock == null) {
      lockChannel.close();
      throw new IOException("Portability directory is already in use");
    }
    try {
      try (var entries = Files.newDirectoryStream(root, "data-*.private")) {
        for (Path entry : entries) {
          if (!entry.getFileName().toString().matches("data-[0-9a-f-]{36}\\.private")) continue;
          if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unexpected portability staging entry");
          Files.delete(entry);
        }
      }
    } catch (IOException | RuntimeException failure) {
      lock.release();
      lockChannel.close();
      throw failure;
    }
  }

  public Path multipartDirectory() throws IOException {
    Path directory = root.resolve("multipart");
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
      Files.createDirectory(
          directory,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || !Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
            .equals(PosixFilePermissions.fromString("rwx------")))
      throw new IOException("Invalid multipart directory");
    try (var entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
            || !entry.getFileName().toString().matches("upload_[A-Za-z0-9_-]+\\.tmp"))
          throw new IOException("Unexpected multipart staging entry");
        Files.delete(entry);
      }
    }
    return directory;
  }

  public Work begin(boolean upload) {
    if (current.get() != null) return new Work(false, 0, false);
    long bytes = upload ? UPLOAD_BYTES + FILE_BYTES + INDEX_BYTES : FILE_BYTES;
    if (!operations.tryAcquire()) throw new CapacityException();
    synchronized (this) {
      if (reserved + bytes > TOTAL_BYTES) {
        operations.release();
        throw new CapacityException();
      }
      reserved += bytes;
      if (upload) uploads++;
    }
    var work = new Work(true, bytes, upload);
    current.set(work);
    return work;
  }

  public final class Work implements AutoCloseable {
    private final boolean owner, upload;
    private long remaining;
    private boolean finished;

    private Work(boolean owner, long remaining, boolean upload) {
      this.owner = owner;
      this.remaining = remaining;
      this.upload = upload;
    }

    @Override
    public void close() {
      if (!owner) return;
      synchronized (PortabilityStorage.this) {
        if (finished) return;
        finished = true;
        if (upload) {
          uploads--;
          long retained = Math.min(UPLOAD_BYTES, remaining);
          multipartPending += retained;
          reserved -= remaining - retained;
          remaining = 0;
          cleanupMultipart();
        } else {
          reserved -= remaining;
          remaining = 0;
        }
      }
      current.remove();
      operations.release();
    }
  }

  public synchronized PrivateFile create(long maximum) throws IOException {
    if (maximum <= 0 || maximum > FILE_BYTES || files.size() >= 72) throw new CapacityException();
    var work = current.get();
    if (work != null && work.remaining >= maximum) work.remaining -= maximum;
    else {
      if (reserved + maximum > TOTAL_BYTES) throw new CapacityException();
      reserved += maximum;
    }
    try {
      Path path = root.resolve("data-" + UUID.randomUUID() + ".private");
      Files.createFile(
          path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      var file = new PrivateFile(path, maximum);
      files.add(file);
      return file;
    } catch (IOException | RuntimeException | Error failure) {
      reserved -= maximum;
      throw failure;
    }
  }

  public final class PrivateFile implements AutoCloseable {
    private final Path path;
    private long reservation;
    private boolean deleting, closed;

    private PrivateFile(Path path, long reservation) {
      this.path = path;
      this.reservation = reservation;
    }

    public Path path() {
      return path;
    }

    public void compact() throws IOException {
      synchronized (PortabilityStorage.this) {
        long size = Files.size(path);
        if (size > reservation) throw new IOException("Portability file exceeded its reservation");
        reserved -= reservation - size;
        reservation = size;
      }
    }

    @Override
    public void close() {
      synchronized (PortabilityStorage.this) {
        if (closed) return;
        deleting = true;
        try {
          Files.deleteIfExists(path);
          reserved -= reservation;
          reservation = 0;
          closed = true;
          files.remove(this);
        } catch (IOException failure) {
          // Keep the reservation until deletion succeeds. A committed import never becomes
          // retryable.
          org.slf4j.LoggerFactory.getLogger(PortabilityStorage.class)
              .warn("Private portability cleanup pending");
        }
      }
    }
  }

  /** No request can still read a part once all synchronous upload work has returned. */
  private void cleanupMultipart() {
    if (uploads != 0 || multipartPending == 0) return;
    Path directory = root.resolve("multipart");
    try {
      if (!Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Invalid multipart directory");
        try (var entries = Files.newDirectoryStream(directory)) {
          for (Path entry : entries) {
            if (Files.notExists(entry, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!entry.getFileName().toString().matches("upload_[A-Za-z0-9_-]+\\.tmp")
                || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
              throw new IOException("Unexpected multipart entry");
            Files.deleteIfExists(entry);
          }
        }
      }
      reserved -= multipartPending;
      multipartPending = 0;
    } catch (IOException | RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(PortabilityStorage.class)
          .warn("Private multipart cleanup pending");
    }
  }

  /** Qualification/operator observation contains counts only, never file names or contents. */
  public synchronized long usedBytes() throws IOException {
    long total = 0;
    for (var file : files)
      if (Files.isRegularFile(file.path, LinkOption.NOFOLLOW_LINKS)) total += Files.size(file.path);
    Path multipart = root.resolve("multipart");
    if (Files.isDirectory(multipart, LinkOption.NOFOLLOW_LINKS)) {
      try (var entries = Files.newDirectoryStream(multipart)) {
        for (Path entry : entries)
          if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) total += Files.size(entry);
      }
    }
    return total;
  }

  public synchronized long reservedBytes() {
    return reserved;
  }

  @Scheduled(fixedDelay = 30000)
  public synchronized void cleanup() {
    for (var file : List.copyOf(files)) if (file.deleting) file.close();
    cleanupMultipart();
  }

  @PreDestroy
  public synchronized void close() throws IOException {
    for (var file : List.copyOf(files)) file.close();
    cleanupMultipart();
    lock.release();
    lockChannel.close();
  }

  public static final class CapacityException extends RuntimeException {
    public CapacityException() {
      super("Temporary portability capacity is busy; retry or discard an unused preview");
    }
  }
}
