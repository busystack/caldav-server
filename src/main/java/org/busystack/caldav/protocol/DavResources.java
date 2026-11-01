package org.busystack.caldav.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.busystack.caldav.storage.CalendarStore;
import org.busystack.caldav.storage.DavStore;
import org.busystack.caldav.storage.StoreException;
import org.springframework.stereotype.Service;

@Service
final class DavResources {
  private final DavStore store;
  private final CalendarStore calendars;
  private final DavPaths paths;

  DavResources(
      DavStore store,
      CalendarStore calendars,
      org.busystack.caldav.config.DeploymentProperties deployment) {
    this.store = store;
    this.calendars = calendars;
    this.paths = new DavPaths(deployment);
  }

  DavNode resolve(UUID actor, List<String> parts) {
    if (parts.isEmpty()) return new DavNode(parts, DavNode.Kind.ROOT, actor, null, null);
    if (parts.getFirst().equals("principals")) {
      if (parts.size() == 1) return new DavNode(parts, DavNode.Kind.PRINCIPALS, actor, null, null);
      if (parts.size() != 2) throw new DavException(404, null);
      UUID principal = uuid(parts.get(1));
      try {
        store.principal(actor, principal);
      } catch (StoreException missing) {
        if (missing.kind() == StoreException.Kind.NOT_FOUND) throw new DavException(404, null);
        throw missing;
      }
      return new DavNode(parts, DavNode.Kind.PRINCIPAL, principal, null, null);
    }
    if (!parts.getFirst().equals("calendars")) throw new DavException(404, null);
    if (parts.size() == 1) return new DavNode(parts, DavNode.Kind.CALENDARS, actor, null, null);
    UUID home = uuid(parts.get(1));
    if (!home.equals(actor))
      throw new DavException(
          403, DavXml.d("need-privileges"), paths.href(parts, parts.size() == 2), DavXml.d("read"));
    if (parts.size() == 2) return new DavNode(parts, DavNode.Kind.HOME, home, null, null);
    String relative = String.join("/", parts.subList(2, parts.size()));
    try {
      var entry = store.resolve(actor, home, relative);
      return new DavNode(
          parts,
          entry.binding().calendar() ? DavNode.Kind.CALENDAR : DavNode.Kind.FOLDER,
          home,
          entry,
          null);
    } catch (StoreException ex) {
      if (ex.kind() != StoreException.Kind.NOT_FOUND) throw ex;
    }
    if (parts.size() < 4) throw new DavException(404, null);
    String parent = String.join("/", parts.subList(2, parts.size() - 1));
    var entry = store.resolve(actor, home, parent);
    if (!entry.binding().calendar()) throw new DavException(404, null);
    var resource = calendars.metadata(actor, entry.binding().collectionId(), parts.getLast());
    if (resource == null) throw new DavException(404, null);
    return new DavNode(parts, DavNode.Kind.RESOURCE, home, entry, null, resource, false);
  }

  /** Only expected member lookup failures become per-resource report statuses. */
  DavNode resolveMember(UUID actor, List<String> parts) {
    try {
      return resolve(actor, parts);
    } catch (StoreException unavailable) {
      if (unavailable.kind() == StoreException.Kind.NOT_FOUND) throw new DavException(404, null);
      if (unavailable.kind() == StoreException.Kind.FORBIDDEN)
        throw new DavException(
            403, DavXml.d("need-privileges"), paths.href(parts, false), DavXml.d("read"));
      throw unavailable;
    }
  }

  DavNode content(UUID actor, DavNode node) {
    if (node.kind() != DavNode.Kind.RESOURCE || node.resource() != null) return node;
    var body = calendars.get(actor, node.collectionId(), node.metadata().name());
    if (body == null) throw new DavException(404, null);
    return new DavNode(node.segments(), node.kind(), node.principal(), node.entry(), body);
  }

  List<DavNode> children(UUID actor, DavNode parent) {
    List<DavNode> result = new ArrayList<>();
    switch (parent.kind()) {
      case ROOT -> {
        result.add(resolve(actor, List.of("principals")));
        result.add(resolve(actor, List.of("calendars")));
      }
      case PRINCIPALS -> {
        for (var principal : store.principals(actor))
          result.add(
              new DavNode(
                  List.of("principals", principal.id().toString()),
                  DavNode.Kind.PRINCIPAL,
                  principal.id(),
                  null,
                  null));
      }
      case CALENDARS -> result.add(resolve(actor, List.of("calendars", actor.toString())));
      case HOME, FOLDER -> {
        for (var entry : store.children(actor, parent.principal(), parent.relativePath())) {
          List<String> segments =
              new ArrayList<>(List.of("calendars", parent.principal().toString()));
          segments.addAll(List.of(entry.binding().path().split("/")));
          result.add(
              new DavNode(
                  segments,
                  entry.binding().calendar() ? DavNode.Kind.CALENDAR : DavNode.Kind.FOLDER,
                  parent.principal(),
                  entry,
                  null));
        }
      }
      case CALENDAR -> {
        var metadata = calendars.metadataBatch(actor, parent.collectionId(), "", 10001);
        if (metadata.size() > 10000)
          throw new DavException(507, DavXml.d("number-of-matches-within-limits"));
        for (var resource : metadata) {
          List<String> segments = new ArrayList<>(parent.segments());
          segments.add(resource.name());
          result.add(
              new DavNode(
                  segments,
                  DavNode.Kind.RESOURCE,
                  parent.principal(),
                  parent.entry(),
                  null,
                  resource,
                  false));
        }
      }
      default -> {}
    }
    return List.copyOf(result);
  }

  List<DavNode> descendants(UUID actor, DavNode node, int depth) {
    List<DavNode> result = new ArrayList<>();
    walk(actor, node, depth, result);
    return List.copyOf(result);
  }

  private void walk(UUID actor, DavNode node, int depth, List<DavNode> result) {
    if (result.size() >= 10000)
      throw new DavException(507, DavXml.d("number-of-matches-within-limits"));
    result.add(node);
    if (depth > 0 && node.collection())
      for (var child : children(actor, node)) walk(actor, child, depth - 1, result);
  }

  /** Queries scan bounded metadata batches; PROPFIND retains its complete-response row limit. */
  Iterable<DavNode> candidates(UUID actor, DavNode root, int depth) {
    return candidates(actor, root, depth, false, false);
  }

  Iterable<DavNode> candidates(
      UUID actor, DavNode root, int depth, boolean content, boolean eventsOnly) {
    return () ->
        new java.util.Iterator<>() {
          record Frame(java.util.Iterator<DavNode> nodes, int depth) {}

          final java.util.Deque<Frame> frames =
              new java.util.ArrayDeque<>(List.of(new Frame(List.of(root).iterator(), depth)));
          DavNode ready;
          int examined;

          public boolean hasNext() {
            while (ready == null && !frames.isEmpty()) {
              Frame frame = frames.peek();
              if (!frame.nodes().hasNext()) {
                frames.pop();
                continue;
              }
              DavNode node = frame.nodes().next();
              if (++examined > 100000)
                throw new DavException(507, DavXml.d("number-of-matches-within-limits"));
              if (node.kind() == DavNode.Kind.RESOURCE) {
                ready = node;
              } else if (node.collection() && frame.depth() > 0) {
                if (eventsOnly
                    && node.kind() == DavNode.Kind.CALENDAR
                    && !node.entry().collection().component().equals("VEVENT")) continue;
                var iterator =
                    node.kind() == DavNode.Kind.CALENDAR
                        ? calendarChildren(actor, node, content)
                        : children(actor, node).iterator();
                frames.push(new Frame(iterator, frame.depth() - 1));
              }
            }
            return ready != null;
          }

          public DavNode next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            DavNode result = ready;
            ready = null;
            return result;
          }
        };
  }

  private java.util.Iterator<DavNode> calendarChildren(
      UUID actor, DavNode calendar, boolean content) {
    return new java.util.Iterator<>() {
      List<DavNode> batch = List.of();
      int position;
      String after = "";
      boolean complete;

      public boolean hasNext() {
        if (position == batch.size() && !complete) {
          batch =
              content
                  ? calendars.resourceBatch(actor, calendar.collectionId(), after).stream()
                      .map(item -> candidate(calendar, item.metadata(), item))
                      .toList()
                  : calendars.metadataBatch(actor, calendar.collectionId(), after, 128).stream()
                      .map(item -> candidate(calendar, item, null))
                      .toList();
          position = 0;
          complete = batch.isEmpty();
        }
        return position < batch.size();
      }

      public DavNode next() {
        if (!hasNext()) throw new java.util.NoSuchElementException();
        var item = batch.get(position++);
        after = item.metadata().name();
        return item;
      }
    };
  }

  private static DavNode candidate(
      DavNode collection, CalendarStore.Metadata metadata, CalendarStore.Resource resource) {
    var parts = new ArrayList<>(collection.segments());
    parts.add(metadata.name());
    return new DavNode(
        parts,
        DavNode.Kind.RESOURCE,
        collection.principal(),
        collection.entry(),
        resource,
        metadata,
        false);
  }

  static UUID uuid(String text) {
    try {
      var id = UUID.fromString(text);
      if (!id.toString().equals(text)) throw new DavException(404, null);
      return id;
    } catch (IllegalArgumentException ex) {
      throw new DavException(404, null);
    }
  }
}
