package org.busystack.caldav.protocol;

import java.util.List;
import java.util.UUID;
import org.busystack.caldav.storage.CalendarStore;
import org.busystack.caldav.storage.DavStore;

record DavNode(
    List<String> segments,
    Kind kind,
    UUID principal,
    DavStore.Entry entry,
    CalendarStore.Resource resource) {
  enum Kind {
    ROOT,
    PRINCIPALS,
    PRINCIPAL,
    CALENDARS,
    HOME,
    FOLDER,
    CALENDAR,
    RESOURCE
  }

  DavNode {
    segments = List.copyOf(segments);
  }

  boolean collection() {
    return kind != Kind.RESOURCE && kind != Kind.PRINCIPAL;
  }

  boolean trailingSlash() {
    return kind != Kind.RESOURCE;
  }

  UUID collectionId() {
    return entry == null ? null : entry.binding().collectionId();
  }

  String relativePath() {
    return segments.size() > 2 ? String.join("/", segments.subList(2, segments.size())) : "";
  }
}
