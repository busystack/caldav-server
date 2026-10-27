package org.busystack.caldav.protocol;

import javax.xml.namespace.QName;

/** An intentional protocol failure; messages never contain submitted calendar data. */
public final class DavException extends RuntimeException {
  private final int status;
  private final QName condition;
  private final String failingHref;
  private final QName requiredPrivilege;

  public DavException(int status, QName condition) {
    this(status, condition, null, null);
  }

  public DavException(int status, QName condition, String failingHref, QName requiredPrivilege) {
    super("DAV request rejected (" + status + ")");
    if ((failingHref == null) != (requiredPrivilege == null))
      throw new IllegalArgumentException("Privilege failures require a resource and privilege");
    this.status = status;
    this.condition = condition;
    this.failingHref = failingHref;
    this.requiredPrivilege = requiredPrivilege;
  }

  public int status() {
    return status;
  }

  public QName condition() {
    return condition;
  }

  public String failingHref() {
    return failingHref;
  }

  public QName requiredPrivilege() {
    return requiredPrivilege;
  }
}
