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
import org.busystack.caldav.xml.XmlCharacters;
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
  public static final int MAX_RESPONSE_WORK = MAX_RESPONSE;

  private static final String BUDGET_KEY = DavXml.class.getName() + ".budget";
  private static final int MAX_RESPONSE_NODES = 50000;

  private static final class Budget {
    long work;
    int nodes;

    void reserve(long additional, int addedNodes) {
      if (additional > MAX_RESPONSE - work || addedNodes > MAX_RESPONSE_NODES - nodes)
        throw new DavException(507, d("number-of-matches-within-limits"));
      work += additional;
      nodes += addedNodes;
    }
  }

  private static Budget budget(Node parent) {
    Document document = parent instanceof Document d ? d : parent.getOwnerDocument();
    Budget budget = (Budget) document.getUserData(BUDGET_KEY);
    if (budget == null) {
      budget = new Budget();
      document.setUserData(BUDGET_KEY, budget, null);
    }
    return budget;
  }

  /** Conservative DOM and serialized-text work: UTF-16 storage plus escaped UTF-8 output. */
  private static long textWork(String value) {
    long result = 2L * value.length();
    for (int i = 0; i < value.length(); ) {
      int cp = value.codePointAt(i);
      i += Character.charCount(cp);
      result +=
          switch (cp) {
            case '&' -> 5;
            case '<', '>' -> 4;
            case '\"', '\'', '\n', '\r', '\t' -> 6;
            default -> cp < 128 ? 1 : cp < 2048 ? 2 : cp < 65536 ? 3 : 4;
          };
    }
    return result;
  }

  private static void reserveCopy(Budget budget, Node source) {
    budget.reserve(256 + textWork(source.getNodeName()), 1);
    if (source.getNodeValue() != null) budget.reserve(textWork(source.getNodeValue()), 0);
    var attributes = source.getAttributes();
    if (attributes != null)
      for (int i = 0; i < attributes.getLength(); i++) {
        Node attribute = attributes.item(i);
        budget.reserve(
            128 + textWork(attribute.getNodeName()) + textWork(attribute.getNodeValue()), 1);
      }
    for (Node child = source.getFirstChild(); child != null; child = child.getNextSibling())
      reserveCopy(budget, child);
  }

  public static long constructionWork(Node node) {
    Budget used = budget(node);
    return Math.max(
        used.work,
        used.nodes * ((MAX_RESPONSE_WORK + MAX_RESPONSE_NODES - 1L) / MAX_RESPONSE_NODES));
  }

  public static long copyWork(Node node) {
    Budget estimate = new Budget();
    reserveCopy(estimate, node);
    return Math.max(
        estimate.work,
        estimate.nodes * ((MAX_RESPONSE_WORK + MAX_RESPONSE_NODES - 1L) / MAX_RESPONSE_NODES));
  }

  /** Validate the complete subtree before allocating its imported DOM copy. */
  public static Element appendCopy(Node parent, Element source) {
    reserveCopy(budget(parent), source);
    Document document = parent instanceof Document d ? d : parent.getOwnerDocument();
    Element result = (Element) document.importNode(source, true);
    parent.appendChild(result);
    return result;
  }

  /** Transfer an already bounded response subtree without allocating a duplicate DOM. */
  public static Element appendAdopt(Node parent, Element source) {
    reserveCopy(budget(parent), source);
    Document document = parent instanceof Document d ? d : parent.getOwnerDocument();
    Node result = document.adoptNode(source);
    if (!(result instanceof Element element))
      throw new IllegalStateException("XML DOM adoption unavailable");
    parent.appendChild(element);
    return element;
  }

  /** A detached dead property retains its effective xml:lang, including an explicit empty value. */
  public static byte[] propertyBytes(Element property) {
    Element copy = (Element) property.cloneNode(true);
    java.util.Set<String> prefixes = new java.util.HashSet<>();
    for (Node context = property;
        context instanceof Element element;
        context = context.getParentNode()) {
      var attributes = element.getAttributes();
      for (int i = 0; i < attributes.getLength(); i++) {
        Node attribute = attributes.item(i);
        if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
            && prefixes.add(attribute.getLocalName()))
          copy.setAttributeNS(
              XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
              attribute.getNodeName(),
              attribute.getNodeValue());
      }
    }
    for (Node context = property;
        context instanceof Element element;
        context = context.getParentNode())
      if (element.hasAttributeNS(XMLConstants.XML_NS_URI, "lang")) {
        copy.setAttributeNS(
            XMLConstants.XML_NS_URI,
            "xml:lang",
            element.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
        break;
      }
    return bytes(copy);
  }

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

  /** Recognized immediate command children; an ignored extension's whole subtree stays ignored. */
  public static List<Element> children(Element parent, String namespace, String... locals) {
    var accepted = java.util.Set.of(locals);
    return children(parent).stream()
        .filter(
            e ->
                namespace.equals(name(e).getNamespaceURI())
                    && accepted.contains(name(e).getLocalPart()))
        .toList();
  }

  /** Character data of a protocol scalar, without text hidden in ignored extension subtrees. */
  public static String scalarText(Element element) {
    StringBuilder value = new StringBuilder();
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
        value.append(child.getNodeValue());
    return value.toString();
  }

  public static Element add(Node parent, QName name) {
    Document document = parent instanceof Document d ? d : parent.getOwnerDocument();
    String prefix =
        DAV.equals(name.getNamespaceURI()) ? "D" : CAL.equals(name.getNamespaceURI()) ? "C" : "P";
    budget(parent)
        .reserve(256 + textWork(name.getNamespaceURI()) * 2 + textWork(name.getLocalPart()) * 2, 1);
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
    // Invalid labels retained by an older version must not produce a malformed success response.
    if (!XmlCharacters.valid(value)) throw new DavException(500, null);
    budget(parent).reserve(textWork(value), 1);
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
