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
}
