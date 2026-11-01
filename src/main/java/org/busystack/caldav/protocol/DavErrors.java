package org.busystack.caldav.protocol;

import javax.xml.namespace.QName;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** RFC 3744 privilege failures name the affected resource and at least one missing privilege. */
final class DavErrors {
  private DavErrors() {}

  static Element append(Node parent, QName condition, String href, QName privilege) {
    Element error = DavXml.add(parent, DavXml.d("error"));
    if (condition == null) return error;
    if (condition.equals(DavXml.d("need-privileges"))) {
      if (href == null || privilege == null) return error;
      Element resource = DavXml.add(DavXml.add(error, condition), DavXml.d("resource"));
      DavXml.text(resource, DavXml.d("href"), href);
      DavXml.add(DavXml.add(resource, DavXml.d("privilege")), privilege);
    } else DavXml.add(error, condition);
    return error;
  }
}
