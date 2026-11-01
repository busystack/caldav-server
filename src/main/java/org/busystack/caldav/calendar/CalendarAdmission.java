package org.busystack.caldav.calendar;

import org.busystack.caldav.config.DavCompatibilityProperties;
import org.springframework.stereotype.Component;

/** The sole input transformation policy; read/query parsing always remains strict. */
@Component
public final class CalendarAdmission {
  public record Result(byte[] content, CalendarContent.Parsed parsed, int repairedRules) {
    public Result {
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }

    public boolean transformed() {
      return repairedRules != 0;
    }
  }

  private final DavCompatibilityProperties compatibility;

  public CalendarAdmission(DavCompatibilityProperties compatibility) {
    this.compatibility = compatibility;
  }

  public Result admit(byte[] content, String component) {
    var adapted =
        compatibility.repairLocalObservanceUntil()
            ? ObservanceUntilRepair.apply(content)
            : new ObservanceUntilRepair.Result(content, 0);
    return new Result(
        adapted.content(), CalendarContent.parse(adapted.content(), component), adapted.rules());
  }
}
