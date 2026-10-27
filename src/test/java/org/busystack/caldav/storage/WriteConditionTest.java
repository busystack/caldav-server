package org.busystack.caldav.storage;

import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WriteConditionTest {
  @Test
  void ifNoneMatchUsesWeakComparisonWithoutChangingIfMatch() {
    var excluded = new WriteCondition(false, false, Set.of(), Set.of("W/\"same\""));
    assertThatThrownBy(() -> excluded.check("\"same\"")).isInstanceOf(StoreException.class);
    assertThatCode(() -> excluded.check("\"other\"")).doesNotThrowAnyException();
    assertThatThrownBy(() -> WriteCondition.match("W/\"same\"").check("\"same\""))
        .isInstanceOf(StoreException.class);
  }

  @Test
  void existenceConditionsWorkForCollectionsWithoutAnAdvertisedEntityTag() {
    var exists = new WriteCondition(false, true, Set.of());
    assertThatCode(() -> exists.check(null, true)).doesNotThrowAnyException();
    assertThatThrownBy(() -> exists.check(null, false)).isInstanceOf(StoreException.class);
    assertThatThrownBy(() -> WriteCondition.create().check(null, true))
        .isInstanceOf(StoreException.class);
  }

  @Test
  void dateConditionsUseWholeSecondsAndIgnoreUnavailableDates() {
    var cutoff = java.time.Instant.parse("2020-01-01T12:00:00Z");
    var condition = new WriteCondition(false, false, Set.of(), Set.of(), cutoff);
    assertThatCode(() -> condition.check("\"same\"", true, cutoff.plusNanos(999999999)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> condition.check("\"same\"", true, cutoff.plusSeconds(1)))
        .isInstanceOf(StoreException.class)
        .extracting("kind")
        .isEqualTo(StoreException.Kind.PRECONDITION);
    assertThatCode(() -> condition.check(null, true, null)).doesNotThrowAnyException();
    assertThatCode(() -> condition.check(null, false, null)).doesNotThrowAnyException();
  }

  @Test
  void effectiveDatesAreConsistentAndNeverLaterThanResponseOrigination() {
    var origin = java.time.Instant.parse("2026-10-27T12:00:00.900Z");
    assertThat(ModificationDates.effective(origin.plusSeconds(86400), origin))
        .isEqualTo(java.time.Instant.parse("2026-10-27T12:00:00Z"));
    assertThat(ModificationDates.effective(origin.minusSeconds(3600), origin))
        .isEqualTo(java.time.Instant.parse("2026-10-27T11:00:00Z"));
    assertThat(ModificationDates.effective(null, origin)).isNull();
  }
}
