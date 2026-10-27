package org.busystack.caldav.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Namespace-aware XML with no DTDs, entity expansion, external access or recursive unbounded input.
 */
public final class DavXml {
  public static final String DAV = "DAV:";
  public static final String CAL = "urn:ietf:params:xml:ns:caldav";
  public static final int MAX_REQUEST = 1048576;
  public static final int MAX_RESPONSE = 16777216;

  private DavXml() {}

  private static DocumentBuilderFactory factory()
      throws javax.xml.parsers.ParserConfigurationException {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setAttribute("jdk.xml.maxElementDepth", "32");
    factory.setAttribute("jdk.xml.elementAttributeLimit", "100");
    factory.setAttribute("jdk.xml.maxXMLNameLimit", "256");
    return factory;
  }

  public static Document document() {
    try {
      return factory().newDocumentBuilder().newDocument();
    } catch (javax.xml.parsers.ParserConfigurationException ex) {
      throw new IllegalStateException("XML parser unavailable", ex);
    }
  }

  public static Element parse(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_REQUEST)
      throw new DavException(bytes.length == 0 ? 400 : 413, null);
    try {
      var builder = factory().newDocumentBuilder();
      builder.setErrorHandler(
          new DefaultHandler() {
            @Override
            public void error(org.xml.sax.SAXParseException ex) throws SAXException {
              throw ex;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException ex) throws SAXException {
              throw ex;
            }
          });
      Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
      if (count(root) > 20000) throw new DavException(413, null);
      return root;
    } catch (org.xml.sax.SAXException | java.io.IOException ex) {
      throw new DavException(400, null);
    } catch (javax.xml.parsers.ParserConfigurationException ex) {
      throw new IllegalStateException("XML parser unavailable", ex);
    }
  }

  private static int count(Element root) {
    int total = 1;
    for (Element child : children(root)) total += count(child);
    return total;
  }

  public static List<Element> children(Element parent) {
    List<Element> result = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element element) result.add(element);
    return result;
  }

  public static QName name(Element element) {
    return new QName(
        element.getNamespaceURI() == null ? "" : element.getNamespaceURI(),
        element.getLocalName() == null ? element.getTagName() : element.getLocalName());
  }

  public static QName d(String local) {
    return new QName(DAV, local);
  }

  public static QName c(String local) {
    return new QName(CAL, local);
  }

  public static boolean is(Element element, String namespace, String local) {
    return name(element).equals(new QName(namespace, local));
  }

  public static Element child(Element parent, String namespace, String local) {
    List<Element> found = children(parent).stream().filter(e -> is(e, namespace, local)).toList();
    if (found.size() > 1) throw new DavException(400, null);
    return found.isEmpty() ? null : found.getFirst();
  }

  public static Element required(Element parent, String namespace, String local) {
    Element child = child(parent, namespace, local);
    if (child == null) throw new DavException(400, null);
    return child;
  }

  public static Element add(Node parent, QName name) {
    Document document = parent instanceof Document d ? d : parent.getOwnerDocument();
    String prefix =
        DAV.equals(name.getNamespaceURI()) ? "D" : CAL.equals(name.getNamespaceURI()) ? "C" : "P";
    Element result =
        document.createElementNS(
            name.getNamespaceURI().isEmpty() ? null : name.getNamespaceURI(),
            name.getNamespaceURI().isEmpty()
                ? name.getLocalPart()
                : prefix + ":" + name.getLocalPart());
    parent.appendChild(result);
    return result;
  }

  public static Element text(Node parent, QName name, String value) {
    Element result = add(parent, name);
    result.setTextContent(value);
    return result;
  }

  public static byte[] bytes(Node node) {
    try {
      var factory = TransformerFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.setOutputProperty(
          OutputKeys.OMIT_XML_DECLARATION, node instanceof Document ? "no" : "yes");
      var output =
          new ByteArrayOutputStream() {
            private void bound(int additional) {
              if (additional > MAX_RESPONSE - count)
                throw new DavException(507, d("number-of-matches-within-limits"));
            }

            @Override
            public synchronized void write(int value) {
              bound(1);
              super.write(value);
            }

            @Override
            public synchronized void write(byte[] value, int offset, int length) {
              bound(length);
              super.write(value, offset, length);
            }
          };
      transformer.transform(new DOMSource(node), new StreamResult(output));
      if (output.size() > MAX_RESPONSE)
        throw new DavException(507, d("number-of-matches-within-limits"));
      return output.toByteArray();
    } catch (javax.xml.transform.TransformerException ex) {
      for (Throwable cause = ex; cause != null; cause = cause.getCause())
        if (cause instanceof DavException failure) throw failure;
      throw new IllegalStateException("XML serialization failed", ex);
    }
  }
}
