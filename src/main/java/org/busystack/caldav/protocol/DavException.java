package org.busystack.caldav.protocol;

import javax.xml.namespace.QName;

/** An intentional protocol failure; messages never contain submitted calendar data. */
public final class DavException extends RuntimeException {
  private final int status;
  private final QName condition;

  public DavException(int status, QName condition) {
    super("DAV request rejected (" + status + ")");
    this.status = status;
    this.condition = condition;
  }

  public int status() {
    return status;
  }

  public QName condition() {
    return condition;
  }
}
