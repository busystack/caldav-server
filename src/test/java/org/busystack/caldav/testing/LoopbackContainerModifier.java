package org.busystack.caldav.testing;

import com.github.dockerjava.api.command.CreateContainerCmd;
import org.testcontainers.core.CreateContainerCmdModifier;

/** Test-only ServiceLoader provider; includes the framework-created Ryuk control port. */
public final class LoopbackContainerModifier implements CreateContainerCmdModifier {
  @Override
  public CreateContainerCmd modify(CreateContainerCmd command) {
    return LoopbackContainers.loopback(command);
  }
}
