package org.busystack.caldav.xml;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XmlCharactersTest {
  @Test
  void acceptsExactlyXml10CharacterRangesIncludingValidNoncharacters() {
    for (int codePoint :
        new int[] {
          0x9, 0xA, 0xD, 0x20, 0x7F, 0x85, 0xD7FF, 0xE000, 0xFDD0, 0xFFFD, 0x10000, 0x1FFFE,
          0x10FFFF
        }) {
      assertThat(XmlCharacters.valid("Before" + new String(Character.toChars(codePoint)) + "After"))
          .as("XML character U+%X", codePoint)
          .isTrue();
    }
    assertThat(XmlCharacters.valid("")).isTrue();
  }

  @Test
  void rejectsInvalidControlsUnpairedSurrogatesAndExcludedBmpValues() {
    for (int codePoint : new int[] {0, 1, 8, 0xB, 0xC, 0x1F, 0xD800, 0xDFFF, 0xFFFE, 0xFFFF}) {
      assertThat(XmlCharacters.valid("Before" + (char) codePoint + "After"))
          .as("Invalid XML character U+%X", codePoint)
          .isFalse();
    }
    assertThat(XmlCharacters.valid(null)).isFalse();
  }
}
