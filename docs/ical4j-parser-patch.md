# Official iCal4j parser integration

The current distribution uses **official, unmodified iCal4j 4.4.0**. The former
`Ical4jCalendarParser` implementation and parser patch payload were removed during
v0.0.1 preparation. Their original provenance and corrections remain in Git at
`e094e9bf4ce34b5a8559ab25fc29f06971163752`; they are historical evidence, not a
supported patch application path.

The selected [official source archive](https://repo.maven.apache.org/maven2/org/mnode/ical4j/ical4j/4.4.0/ical4j-4.4.0-sources.jar)
has SHA-256 `0300f9b2946cd70a879585c7ba91978899373c813929f030700dfcf78175574e`.
Its actual public API provides `CalendarParser.parse(Reader, ContentHandler)`;
`CalendarParserImpl` implements it. `ContentHandler.parameter(String, String)`
is scalar. The official parser does not consume a comma-separated parameter list.
`DefaultContentHandler(Consumer<Calendar>, TimeZoneRegistry, ContentHandlerContext)`
and parameter/property factories remain the model construction extension points.

Independently written BusyStack `ParameterInput` scans only unfolded content-line
parameter headers. It retains each raw parameter list in a per-parse queue and
passes a safe scalar placeholder to the official parser. The corresponding
callback must match its queued name and placeholder before dispatching original
values to the existing handlers. It does not parse components, property values,
recurrence or timezone rules. iCal4j still performs calendar parsing and model
construction. The existing structural-case/UTF-8 adapter, limits, lexical-value
handler, sequential RFC 6868 decoding and strict factories remain in use.
No reflection, shadowing, binary patch or copied upstream parser is involved.

The adapter rejects malformed quotes/delimiters and bounds each list at 1000
values. Existing aggregate limits still apply. Stored accepted bytes and ETags
remain authoritative; this derived reader is never written to persistence.
`CalendarParameterSemanticsTest` retains MEMBER lists, embedded commas, `CN=^^n`,
`CN=^'Babe^'`, delegation, extensions and projection regressions. The calendar
and protocol suites retain malformed-boundary, UTF-8, atomic-write and query tests.
`verify-dependency-integrity.py` checks absence of removed classes/shadow namespaces
and exact official nested JAR bytes plus all 99 Spring Boot executable-loader classes. The BSD notice remains because iCal4j is
still distributed, not because modified implementation source remains.
