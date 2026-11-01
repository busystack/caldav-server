# Official iCal4j timezone integration

The current distribution uses **official, unmodified iCal4j 4.4.0**. The former
`Ical4jZoneRulesBuilder` copy and timezone patch payload were removed during
v0.0.1 preparation. It had no active production references; the clean build and
packaged integrity gate prevent stale classes from being delivered. Original
provenance and defect evidence remain in Git at `e094e9b`, without history rewrite.

The active application-owned `BoundedTimeZoneRegistry` implements the official
`TimeZoneRegistry` interface. `ObservanceZoneRules` integrates official iCal4j
recurrence results into chronological rules, retaining explicit RDATE transitions,
finite COUNT/UNTIL, bounded unproven cycles and coverage checks. No implementation
was moved or copied during removal. See [calendar semantics](calendar-semantics.md),
`CalendarTimezoneRdateTest` and `CalendarTimezoneCoverageTest`.

This is separate from the approved [local observance UNTIL input repair](observance-until-compatibility.md).
That repair, disable setting, strict corrected-data validation and transformed PUT
validator omission remain unchanged. Official dependency bytes and notices are
checked as described in [parser integration](ical4j-parser-patch.md).
