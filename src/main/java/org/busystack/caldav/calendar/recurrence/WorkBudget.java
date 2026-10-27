package org.busystack.caldav.calendar.recurrence;

import java.time.*;
import java.time.temporal.*;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.util.Configurator;
import org.busystack.caldav.calendar.query.QueryException;
import org.busystack.caldav.calendar.query.QueryLimits;

/** Conservative bounds before entering iCal4j's recurrence candidate generation. */
public final class WorkBudget {
  private final QueryLimits limits;
  private long work;
  private int instances;

  public WorkBudget(QueryLimits limits) {
    this.limits = limits;
  }

  public QueryLimits limits() {
    return limits;
  }

  public void instance() {
    if (++instances > limits.maximumInstances()) throw QueryException.limit();
  }

  public void charge(long amount) {
    if (amount < 0 || amount > limits.maximumCandidateWork() - work) throw QueryException.limit();
    work += amount;
  }

  public void rule(Recur<?> recur, Temporal seed, Temporal from, Temporal end) {
    ChronoUnit unit =
        switch (recur.getFrequency()) {
          case SECONDLY -> ChronoUnit.SECONDS;
          case MINUTELY -> ChronoUnit.MINUTES;
          case HOURLY -> ChronoUnit.HOURS;
          case DAILY -> ChronoUnit.DAYS;
          case WEEKLY -> ChronoUnit.WEEKS;
          case MONTHLY -> ChronoUnit.MONTHS;
          case YEARLY -> ChronoUnit.YEARS;
        };
    int interval = Math.max(1, recur.getInterval());
    Temporal beginning = recur.getCount() > 0 ? seed : from;
    long increments;
    try {
      increments = Math.max(0, unit.between(comparable(beginning), comparable(end))) / interval + 4;
      // Recur's skip-search multiplies int interval * int multiplier. Reject before overflow.
      long distance = Math.abs(unit.between(comparable(seed), comparable(end)));
      if (distance > Integer.MAX_VALUE / 4L) throw QueryException.limit();
    } catch (DateTimeException | ArithmeticException ex) {
      throw QueryException.limit();
    }
    int internalStop = Configurator.getIntProperty(Recur.KEY_MAX_INCREMENT_COUNT).orElse(1000);
    long permitted =
        internalStop > 0
            ? Math.min(limits.maximumRuleIncrements(), internalStop - 2L)
            : limits.maximumRuleIncrements();
    if (increments > permitted) throw QueryException.limit();
    long candidates =
        switch (recur.getFrequency()) {
          case YEARLY -> 366;
          case MONTHLY -> 31;
          case WEEKLY -> 7;
          default -> 1;
        };
    int[] sizes = {
      recur.getMonthList().size(),
      recur.getWeekNoList().size(),
      recur.getYearDayList().size(),
      recur.getMonthDayList().size(),
      recur.getDayList().size(),
      recur.getHourList().size(),
      recur.getMinuteList().size(),
      recur.getSecondList().size(),
      recur.getSetPosList().size()
    };
    try {
      for (int size : sizes) candidates = Math.multiplyExact(candidates, Math.max(1, size));
      charge(Math.multiplyExact(candidates, increments));
    } catch (ArithmeticException ex) {
      throw QueryException.limit();
    }
  }

  private static Temporal comparable(Temporal value) {
    return value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value;
  }
}
