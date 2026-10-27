# Calendar semantic fixtures

The NY/Berlin VTIMEZONE fragments are exact `TimeZoneRegistryImpl.getTimeZone(id).getVTimeZone().toString()` output from the pinned BSD-3-Clause `org.mnode.ical4j:ical4j:4.4.0` artifact, exported on 2026-10-27 with external definition refresh disabled. These are test definitions added by the test helper to original semantic fixtures; production resources are never repaired or supplemented this way. Their embedded TZURL properties are preserved data and are not fetched. Dependency attribution remains in the distributable's iCal4j BSD notice.

- `America-New_York-ical4j4.4.0.ics`: SHA-256 `9a234fcadd5f04fd9e546bd3cee287151fb7b52ac36e28beb094ab927813d274`.
- `Europe-Berlin-ical4j4.4.0.ics`: SHA-256 `c74bc8ef7d9dca519c5e133f2df7835ff0a93653e99eec0241ac44dce8221eeb`.

`clients/` contains separately qualified native Thunderbird output and its provenance. Parameter-encoding cases are original fixtures in `CalendarParameterSemanticsTest`. Native-client fixtures are not replaced with library-generated data.
