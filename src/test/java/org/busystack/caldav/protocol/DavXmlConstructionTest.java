package org.busystack.caldav.protocol;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DavXmlConstructionTest {
  @Test
  void invalidLegacyLabelTextFailsBeforeAttachingAProperty() {
    for (String invalid : java.util.List.of("Invalid\uFFFE", "Invalid\uFFFF", "Invalid\uD800")) {
      var document = DavXml.document();
      var root = DavXml.add(document, DavXml.d("prop"));
      assertThatThrownBy(() -> DavXml.text(root, DavXml.d("displayname"), invalid))
          .isInstanceOf(DavException.class)
          .extracting(failure -> ((DavException) failure).status())
          .isEqualTo(500);
      assertThat(root.hasChildNodes()).isFalse();
    }
    var document = DavXml.document();
    var root = DavXml.add(document, DavXml.d("prop"));
    String valid = "Valid\uFDD0" + new String(Character.toChars(0x1FFFE));
    DavXml.text(root, DavXml.d("displayname"), valid);
    assertThat(DavXml.parse(DavXml.bytes(document)).getTextContent()).isEqualTo(valid);
  }

  @Test
  void constructionRejectsOversizedTextBeforeAttachingIt() {
    var document = DavXml.document();
    var root = DavXml.add(document, DavXml.d("multistatus"));
    assertThatThrownBy(
            () -> DavXml.text(root, DavXml.d("value"), "&".repeat(DavXml.MAX_RESPONSE / 5 + 1)))
        .isInstanceOf(DavException.class);
    assertThat(root.getTextContent()).isEmpty();
  }

  @Test
  void copiedSubtreesAreChargedBeforeAllocationAndFailedCopyIsNotAttached() {
    var target = DavXml.document();
    var root = DavXml.add(target, DavXml.d("multistatus"));
    var source = DavXml.document();
    var value = source.createElementNS("urn:test", "X:property");
    value.setTextContent("&".repeat(DavXml.MAX_RESPONSE / 5 + 1));
    assertThatThrownBy(() -> DavXml.appendCopy(root, value)).isInstanceOf(DavException.class);
    assertThat(root.hasChildNodes()).isFalse();
  }

  @Test
  void detachedPropertyRetainsEffectiveLanguageNamespacesAndWhitespaceWithoutMutatingInput() {
    var request =
        DavXml.parse(
            ("<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:test' xmlns:unused='urn:qname' xml:lang='fr'><D:set xml:lang='de'><D:prop><X:label>  before <X:em xml:lang='ja'>雪</X:em> after  </X:label></D:prop></D:set></D:propertyupdate>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var value = (org.w3c.dom.Element) request.getElementsByTagNameNS("urn:test", "label").item(0);
    var stored = DavXml.parse(DavXml.propertyBytes(value));
    assertThat(stored.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")).isEqualTo("de");
    assertThat(stored.lookupNamespaceURI("unused")).isEqualTo("urn:qname");
    assertThat(stored.getTextContent()).isEqualTo("  before 雪 after  ");
    assertThat(value.hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")).isFalse();
    assertThat(
            ((org.w3c.dom.Element) stored.getElementsByTagNameNS("urn:test", "em").item(0))
                .getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
        .isEqualTo("ja");
    value.setAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "xml:lang", "");
    assertThat(
            DavXml.parse(DavXml.propertyBytes(value))
                .hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
        .isTrue();
    assertThat(
            DavXml.parse(DavXml.propertyBytes(value))
                .getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
        .isEmpty();
  }
}
