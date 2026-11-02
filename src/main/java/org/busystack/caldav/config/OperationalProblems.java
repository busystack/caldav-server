package org.busystack.caldav.config;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import org.springframework.stereotype.Component;

/** Process-local, bounded operational failures; no request paths, principals or contents. */
@Component
public final class OperationalProblems {
  public record Problem(
      Instant time, String requestId, String surface, String method, int status) {}

  private final ArrayDeque<Problem> recent = new ArrayDeque<>();

  public synchronized void record(String requestId, String surface, String method, int status) {
    if (status < 500) return;
    if (recent.size() == 50) recent.removeLast();
    recent.addFirst(new Problem(Instant.now(), requestId, surface, method, status));
  }

  public synchronized List<Problem> recent() {
    return List.copyOf(recent);
  }
}
