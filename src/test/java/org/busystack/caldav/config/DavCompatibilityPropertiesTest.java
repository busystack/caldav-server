package org.busystack.caldav.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class DavCompatibilityPropertiesTest {
  @Test
  void omittedConfigurationEnablesNarrowDepthOneAccommodation() {
    assertThat(bind(Map.of()).allowSyncDepthOne()).isTrue();
    assertThat(bind(Map.of()).repairLocalObservanceUntil()).isTrue();
  }

  @Test
  void observanceRepairCanBeDisabledIndependently() {
    var configured = bind(Map.of("busystack.dav.repair-local-observance-until", "false"));
    assertThat(configured.repairLocalObservanceUntil()).isFalse();
    assertThat(configured.allowSyncDepthOne()).isTrue();
  }

  @Test
  void explicitFalseSelectsStrictMode() {
    assertThat(bind(Map.of("busystack.dav.allow-sync-depth-one", "false")).allowSyncDepthOne())
        .isFalse();
  }

  @Test
  void explicitTruePreservesCompatibilityMode() {
    assertThat(bind(Map.of("busystack.dav.allow-sync-depth-one", "true")).allowSyncDepthOne())
        .isTrue();
  }

  private DavCompatibilityProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("busystack.dav", Bindable.of(DavCompatibilityProperties.class));
  }
}
