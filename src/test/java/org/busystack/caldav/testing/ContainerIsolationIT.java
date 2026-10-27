package org.busystack.caldav.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies actual daemon bindings, including the framework-created Docker-control sidecar. */
@Testcontainers
class ContainerIsolationIT {
  @Container static final PostgreSQLContainer POSTGRES = LoopbackContainers.postgres();

  @Test
  void databaseAndResourceReaperArePublishedOnlyOnLoopback() {
    assertThat(Boolean.parseBoolean(System.getenv("TESTCONTAINERS_RYUK_DISABLED")))
        .as("The fixture isolation policy retains framework-managed cleanup")
        .isFalse();
    var docker = DockerClientFactory.instance().client();
    LoopbackContainers.requireLoopbackBindings(
        docker.inspectContainerCmd(POSTGRES.getContainerId()).exec());
    LoopbackContainers.requireLoopbackBindings(
        docker.inspectContainerCmd("testcontainers-ryuk-" + DockerClientFactory.SESSION_ID).exec());
  }
}
