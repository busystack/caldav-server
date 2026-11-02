package org.busystack.caldav.management;

import java.util.List;

public record PortalPage<T>(List<T> rows, int page, boolean hasNext) {
  public PortalPage {
    rows = List.copyOf(rows);
  }

  public static int offset(int page) {
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
    return page * 50;
  }

  public static <T> PortalPage<T> of(List<T> rows, int page) {
    return new PortalPage<>(rows.subList(0, Math.min(50, rows.size())), page, rows.size() > 50);
  }
}
