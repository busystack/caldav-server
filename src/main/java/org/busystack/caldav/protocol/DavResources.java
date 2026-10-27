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

  DavResources(DavStore store, CalendarStore calendars) {
    this.store = store;
    this.calendars = calendars;
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
    if (!home.equals(actor)) throw new DavException(403, null);
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
    var resource = calendars.get(actor, entry.binding().collectionId(), parts.getLast());
    if (resource == null) throw new DavException(404, null);
    return new DavNode(parts, DavNode.Kind.RESOURCE, home, entry, resource);
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
        var snapshot = store.snapshot(actor, parent.principal(), parent.relativePath());
        for (var resource : snapshot.resources()) {
          List<String> segments = new ArrayList<>(parent.segments());
          segments.add(resource.name());
          result.add(
              new DavNode(
                  segments, DavNode.Kind.RESOURCE, parent.principal(), parent.entry(), resource));
        }
      }
      default -> {}
    }
    return List.copyOf(result);
  }

  List<DavNode> descendants(UUID actor, DavNode node, int depth) {
    List<DavNode> result = new ArrayList<>();
    walk(actor, node, depth, result, new long[1]);
    return List.copyOf(result);
  }

  private void walk(
      UUID actor, DavNode node, int depth, List<DavNode> result, long[] contentBytes) {
    if (result.size() >= 10000)
      throw new DavException(507, DavXml.d("number-of-matches-within-limits"));
    result.add(node);
    if (node.resource() != null) {
      contentBytes[0] += node.resource().content().length;
      if (contentBytes[0] > 32L * 1024 * 1024)
        throw new DavException(507, DavXml.d("number-of-matches-within-limits"));
    }
    if (depth > 0 && node.collection())
      for (var child : children(actor, node)) walk(actor, child, depth - 1, result, contentBytes);
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
