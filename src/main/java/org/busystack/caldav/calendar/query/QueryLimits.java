package org.busystack.caldav.calendar.query;

public record QueryLimits(
    int maximumInstances,
    int maximumRuleIncrements,
    long maximumCandidateWork,
    int maximumRangeDays,
    int maximumFilterNodes) {
  public QueryLimits {
    if (maximumInstances < 1
        || maximumRuleIncrements < 1
        || maximumRuleIncrements > 900
        || maximumCandidateWork < 1
        || maximumRangeDays < 1
        || maximumFilterNodes < 1)
      throw new IllegalArgumentException("Invalid calendar query limits");
  }

  public static QueryLimits defaults() {
    return new QueryLimits(10000, 900, 500000, 3660, 256);
  }
}
