package org.busystack.caldav.xml;

/** XML 1.0 section 2.2 character data, independent of application naming policies. */
public final class XmlCharacters {
  private XmlCharacters() {}

  public static boolean valid(String value) {
    return value != null
        && value
            .codePoints()
            .allMatch(
                codePoint ->
                    codePoint == 0x9
                        || codePoint == 0xA
                        || codePoint == 0xD
                        || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                        || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                        || (codePoint >= 0x10000 && codePoint <= 0x10FFFF));
  }
}
