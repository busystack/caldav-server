package org.busystack.caldav.storage;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DavNamespaceTest {
  @Test
  void calendarDepthReservesBothFixedComponentsAndTheEventualLeaf() {
    String usable = "f/".repeat(16) + "calendar";
    assertThatCode(() -> DavNamespace.binding(usable, true)).doesNotThrowAnyException();
    assertThatCode(() -> DavNamespace.resource(usable, "\u0800".repeat(255)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> DavNamespace.binding("f/".repeat(17) + "calendar", true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void encodedPathBudgetAndMalformedSegmentsCannotProduceUnaddressableBindings() {
    assertThatThrownBy(() -> DavNamespace.binding("中".repeat(100) + "/" + "中".repeat(100), true))
        .isInstanceOf(IllegalArgumentException.class);
    for (String name :
        List.of(
            "\uD800",
            "\u2000",
            "..",
            "x/y",
            "x\\y",
            "x;y",
            "x\u2028y",
            "x\u2029y",
            "x\uFFFEy",
            "x\uFFFFy"))
      assertThatThrownBy(() -> DavNamespace.segment(name))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> DavNamespace.resource("safe", "valid\uFDD0.ics"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                DavNamespace.resource(
                    "safe", "valid" + new String(Character.toChars(0x1FFFE)) + ".ics"))
        .doesNotThrowAnyException();
    var path = new ArrayList<>(List.of("calendars", "00000000-0000-0000-0000-000000000000"));
    for (int i = 0; i < 18; i++) path.add("f");
    assertThatCode(() -> DavNamespace.parts(path)).doesNotThrowAnyException();
    path.add("resource.ics");
    assertThatThrownBy(() -> DavNamespace.parts(path)).isInstanceOf(IllegalArgumentException.class);
  }
}
