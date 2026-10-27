package org.busystack.caldav.protocol;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DavXmlTest {
  @Test
  void namespaceAndNestedXmlArePreservedByEstablishedParserAndSerializer() {
    var parsed =
        DavXml.parse(
            "<X:property xmlns:X='urn:extension' xml:lang='ja'><X:value>雪 &amp; ice</X:value></X:property>"
                .getBytes(StandardCharsets.UTF_8));
    var reread = DavXml.parse(DavXml.bytes(parsed));
    assertThat(reread.getNamespaceURI()).isEqualTo("urn:extension");
    assertThat(reread.getTextContent()).isEqualTo("雪 & ice");
    assertThat(reread.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")).isEqualTo("ja");
  }

  @Test
  void dtdExternalEntitiesDeepTreesAndOversizedBodiesFailClosed() {
    for (String xml :
        new String[] {
          "<!DOCTYPE x SYSTEM 'https://attacker.invalid/a'><x/>",
          "<!DOCTYPE x [<!ENTITY a 'expanded'>]><x>&a;</x>",
          "<x>".repeat(33) + "</x>".repeat(33),
          "<x>",
          "x".repeat(DavXml.MAX_REQUEST + 1)
        })
      assertThatThrownBy(() -> DavXml.parse(xml.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(DavException.class);
  }

  @Test
  void serializationStopsAtTheResponseLimit() {
    var document = DavXml.document();
    DavXml.text(document, DavXml.d("large"), "x".repeat(DavXml.MAX_RESPONSE + 1));
    assertThatThrownBy(() -> DavXml.bytes(document))
        .isInstanceOf(DavException.class)
        .extracting(ex -> ((DavException) ex).status())
        .isEqualTo(507);
  }
}
