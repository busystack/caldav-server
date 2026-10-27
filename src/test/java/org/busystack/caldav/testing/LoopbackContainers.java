package org.busystack.caldav.testing;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Disposable fixtures never publish development credentials on external host interfaces. */
public final class LoopbackContainers {
  private static final String LOOPBACK = "127.0.0.1";
  private static final String POSTGRES =
      "postgres:18.6@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6";

  private LoopbackContainers() {}

  public static PostgreSQLContainer postgres() {
    return new LoopbackPostgres();
  }

  public static CreateContainerCmd bind(CreateContainerCmd command, int port) {
    var exposed = new LinkedHashSet<ExposedPort>();
    if (command.getExposedPorts() != null) exposed.addAll(Arrays.asList(command.getExposedPorts()));
    exposed.add(ExposedPort.tcp(port));
    command.withExposedPorts(exposed.toArray(ExposedPort[]::new));
    return loopback(command);
  }

  /** Public Testcontainers SPI also applies this policy to its otherwise private Ryuk container. */
  public static CreateContainerCmd loopback(CreateContainerCmd command) {
    HostConfig host = command.getHostConfig();
    if (host == null) host = new HostConfig();
    Map<ExposedPort, Ports.Binding[]> previous =
        host.getPortBindings() == null ? Map.of() : host.getPortBindings().getBindings();
    var exposed = new LinkedHashSet<>(previous.keySet());
    if (command.getExposedPorts() != null) exposed.addAll(Arrays.asList(command.getExposedPorts()));
    var bindings = new Ports();
    for (ExposedPort port : exposed) {
      Ports.Binding[] existing = previous.get(port);
      if (existing == null || existing.length == 0) {
        bindings.bind(port, Ports.Binding.bindIp(LOOPBACK));
      } else {
        for (Ports.Binding binding : existing) {
          bindings.bind(
              port,
              new Ports.Binding(LOOPBACK, binding == null ? null : binding.getHostPortSpec()));
        }
      }
    }
    command.withHostConfig(host.withPortBindings(bindings).withPublishAllPorts(false));
    var labels = new LinkedHashMap<String, String>();
    if (command.getLabels() != null) labels.putAll(command.getLabels());
    labels.put("org.busystack.caldav.test", "true");
    command.withLabels(labels);
    return command;
  }

  public static void requireLoopbackBindings(InspectContainerResponse container) {
    boolean published = false;
    for (Ports.Binding[] bindings :
        container.getNetworkSettings().getPorts().getBindings().values()) {
      if (bindings == null) continue;
      for (Ports.Binding binding : bindings) {
        published = true;
        if (binding == null || !LOOPBACK.equals(binding.getHostIp()))
          throw new IllegalStateException("Test fixture published a non-loopback port");
      }
    }
    if (!published) throw new IllegalStateException("Expected a published loopback fixture port");
  }

  private static final class LoopbackPostgres extends PostgreSQLContainer {
    private LoopbackPostgres() {
      super(DockerImageName.parse(POSTGRES).asCompatibleSubstituteFor("postgres"));
      withCreateContainerCmdModifier(command -> bind(command, 5432));
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse container) {
      super.containerIsStarted(container);
      requireLoopbackBindings(container);
    }
  }
}
