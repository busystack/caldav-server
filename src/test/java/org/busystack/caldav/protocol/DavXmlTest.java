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
    // Direct DOM mutation deliberately bypasses construction helpers to retain a test of
    // the serializer's independent final-output guard.
    var element = document.createElementNS(DavXml.DAV, "D:large");
    element.setTextContent("x".repeat(DavXml.MAX_RESPONSE + 1));
    document.appendChild(element);
    assertThatThrownBy(() -> DavXml.bytes(document))
        .isInstanceOf(DavException.class)
        .extracting(ex -> ((DavException) ex).status())
        .isEqualTo(507);
  }

  @Test
  void commandSelectorsIgnoreExtensionSubtreesWithoutPromotingTheirDescendants() {
    var command =
        DavXml.parse(
            ("<D:propertyupdate xmlns:D='DAV:' xmlns:X='urn:audit'>"
                    + "<X:ignored><D:set><D:prop><X:smuggled/></D:prop></D:set></X:ignored>"
                    + "<D:set><D:prop><X:kept/></D:prop></D:set></D:propertyupdate>")
                .getBytes(StandardCharsets.UTF_8));
    var operations = DavXml.children(command, DavXml.DAV, "set", "remove");
    assertThat(operations).hasSize(1);
    assertThat(DavXml.children(DavXml.required(operations.getFirst(), DavXml.DAV, "prop")))
        .singleElement()
        .extracting(DavXml::name)
        .isEqualTo(new javax.xml.namespace.QName("urn:audit", "kept"));
  }

  @Test
  void scalarValuesIgnoreExtensionTextWhilePropertyPayloadRemainsIntact() {
    var scalar =
        DavXml.parse(
            ("<D:href xmlns:D='DAV:' xmlns:X='urn:audit'>/team/"
                    + "<X:ignored><D:href>evil</D:href></X:ignored><![CDATA[dav/]]></D:href>")
                .getBytes(StandardCharsets.UTF_8));
    assertThat(DavXml.scalarText(scalar)).isEqualTo("/team/dav/");
    var copy = DavXml.parse(DavXml.propertyBytes(scalar));
    assertThat(copy.getTextContent()).isEqualTo("/team/evildav/");
    assertThat(copy.getElementsByTagNameNS("urn:audit", "ignored").getLength()).isEqualTo(1);
  }
}
