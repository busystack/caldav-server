# Standards updates and errata review

Reviewed 2026-10-27 against the RFC Editor’s [machine-readable errata inventory](https://www.rfc-editor.org/errata.json), individual linked records and RFC information pages. Verified corrections inform implementation; Reported and Held records are considered separately and do not silently amend the contract. Rejected proposals are not adopted. This engineering review is not independent certification. See [conformance](conformance.md) for implementation/test traceability and the qualification record for executed results.

## Inventory checked

| RFC | Verified | Held | Reported | Rejected |
| --- | --- | --- | --- | --- |
| [RFC 9110](https://www.rfc-editor.org/errata/rfc9110) | 6 | 0 | 2 | 6 |
| [RFC 4918](https://www.rfc-editor.org/errata/rfc4918) | 3 | 3 | 3 | 0 |
| [RFC 4791](https://www.rfc-editor.org/errata/rfc4791) | 17 | 0 | 6 | 1 |
| [RFC 3744](https://www.rfc-editor.org/errata/rfc3744) | 1 | 0 | 0 | 0 |
| [RFC 5397](https://www.rfc-editor.org/errata/rfc5397) | 0 | 0 | 0 | 0 |
| [RFC 6764](https://www.rfc-editor.org/errata/rfc6764) | 0 | 0 | 0 | 0 |
| [RFC 6578](https://www.rfc-editor.org/errata/rfc6578) | 0 | 0 | 0 | 0 |
| [RFC 5545](https://www.rfc-editor.org/errata/rfc5545) | 22 | 4 | 2 | 11 |
| [RFC 6868](https://www.rfc-editor.org/errata/rfc6868) | 1 | 0 | 1 | 0 |

## Relevant verified corrections

| Records | Application decision and evidence |
| --- | --- |
| RFC4918 [1068](https://www.rfc-editor.org/errata/eid1068), [1519](https://www.rfc-editor.org/errata/eid1519), [1430](https://www.rfc-editor.org/errata/eid1430) | DAV If has its own bounded Boolean-list parser; XML hrefs use canonical configured URLs; dot segments are rejected; Destination is independently validated. MKCOL is implemented for ordinary container collections. `DavIf`, `DavPaths`, `DavHttpIT`, `DavStoreIT`. Locking remains outside the advertised profile. |
| RFC4791 [1002](https://www.rfc-editor.org/errata/eid1002), [1476](https://www.rfc-editor.org/errata/eid1476) | Collection creation checks parent authority; privilege failures use DAV’s namespace and structured resource/privilege details. Namespace-aware DOM serialization supplies explicit correct namespaces. `DavController`, `DavErrors`, `DavHttpIT`. |
| RFC4791 [4161](https://www.rfc-editor.org/errata/eid4161) and verified example corrections 4153–4160, 4162–4167 | Task alarms with a DUE-only parent use END; accepted object bytes retain embedded timezones. Report projection retains required timezone content or converts derived expanded date values to UTC. Task ranges and alarms have semantic tests; protocol responses follow requested property sets instead of copying example responses. |
| RFC5545 [1911](https://www.rfc-editor.org/errata/eid1911), [7691](https://www.rfc-editor.org/errata/eid7691), [7945](https://www.rfc-editor.org/errata/eid7945) | Established content-line parser handles quoted parameters and CRLF properties; authoritative bytes are retained. Parameter semantics and errata fixtures verify comma-bearing quoted ALTREP and multiple REQUEST-STATUS lines. |
| RFC5545 [1913](https://www.rfc-editor.org/errata/eid1913), [3779](https://www.rfc-editor.org/errata/eid3779), [4271](https://www.rfc-editor.org/errata/eid4271) | iCal4j generates RRULE instances. Numeric BYDAY restrictions remain library validated. Invalid dates are omitted; nonexistent local times use the pre-gap offset under §3.3.5 and count toward COUNT. The review removed an application override that incorrectly skipped gap instances. Regression cases distinguish gaps from invalid dates. |
| RFC5545 [2677](https://www.rfc-editor.org/errata/eid2677), [2039](https://www.rfc-editor.org/errata/eid2039), [6109](https://www.rfc-editor.org/errata/eid6109) | Multiple REQUEST-STATUS properties remain valid. Absolute alarm triggers carry VALUE=DATE-TIME. DATE event end is exclusive, including multi-day events. Calendar semantic/errata tests cover these cases. |
| RFC5545 [1916](https://www.rfc-editor.org/errata/eid1916), [3740](https://www.rfc-editor.org/errata/eid3740), [3883](https://www.rfc-editor.org/errata/eid3883), [4149](https://www.rfc-editor.org/errata/eid4149) | Corrected examples inform fixtures: URI values remain URI data, finite zoned UNTIL uses UTC, VTIMEZONE onset covers relevant dates, generated free/busy contains UID and DTSTAMP. Library validation and semantic/free-busy tests apply; examples are not used as protocol grammar. |
| RFC6868 [4383](https://www.rfc-editor.org/errata/eid4383) | Correction concerns a vCard GEO example; CardDAV/vCard are outside scope. iCalendar parameter caret encoding is tested separately in `CalendarParameterSemanticsTest`. |
| RFC9110 [7138](https://www.rfc-editor.org/errata/eid7138), [7306](https://www.rfc-editor.org/errata/eid7306), [7419](https://www.rfc-editor.org/errata/eid7419) and editorial 7105,7109,8268 | No custom Accept quality/range parser is introduced. Range support is not advertised; full representation responses are permitted. Explicit UTF-8 response types and strong stored-byte validators are tested. Servlet/Spring owns general HTTP message handling. |
| RFC3744 [235](https://www.rfc-editor.org/errata/eid235) | Editorial pointer to issues, not a replacement normative algorithm. Principal/ACL reports and effective privilege checks are traced in the conformance matrix. |

Other verified RFC5545 editorial corrections (2497,4414,2038,2516,2527,3747,5602,5794,7332) affect typography, grammar references, examples or the contents; they introduce no separate application mechanism. The review does not elevate their original uncorrected examples over the grammar.

## Reported and held records

* RFC4791 4840: response compression is disabled for DAV; GET ETags identify the served authoritative bytes. 5481 reinforces retaining VTIMEZONE for task projections. 5846’s proposed writable component set is not adopted: component capability remains protected and is chosen at collection creation. 8761–8763 concern example property-set inconsistencies; responses use the actual requested properties.
* RFC4918 1207/1635 concern locking, outside the advertised profile. 1371 concerns failed-property status prose: atomic PROPPATCH has per-property errors and 424 dependencies. 4431 requests clarification of live-property definitions; the application maintains an explicit live/dead registry. 4435/4440 do not replace normative COPY semantics; source absence, explicit destinations and overwrite behavior have protocol/database tests.
* RFC5545 3038’s UTC DTSTAMP clarification is consistent with library validation. 5505’s quoted TZID concern is handled by the established parameter parser. 6316’s proposed EXDATE type clarification does not license lossy data rewriting. 8398 points out VTIMEZONE EXDATE inconsistency; accepted data is preserved and bounded observance generation remains library driven. 2495/4626 are editorial. Rejected alternative proposals are not treated as corrections.
* RFC6868 4374 is Reported. The implementation decodes left to right, once: literal ^^n remains ^n rather than becoming a newline through repeated replacement; this has a regression test.
* RFC9110 9162/9164 are Reported clarifications to header-list prose and normalized ABNF. They do not change the parser language. Duplicate ETag and DAV If fields are combined in order; duplicate conditional-date fields are invalid and ignored; duplicate singleton DAV fields fail explicitly.

## Correction review of protocol boundaries

The correction pass rechecks [RFC 6578 §§3.3, 3.6–3.7](https://www.rfc-editor.org/rfc/rfc6578.html#section-3.3): standard synchronization uses header depth zero, and truncation is represented within the multistatus result with continuation state. The default `Depth: 1` acceptance is a deliberate header exception restricted to XML `sync-level=1`; it does not claim strict compliance with §3.3. Explicit strict configuration rejects nonzero depth. No user-agent detection or broader traversal is introduced.

[ADR 0004](adr/0004-resumable-synchronization.md) defines the fixed high-watermark traversal, immutable resource/property versions, replayable continuation boundaries and commit-safe collection revisions. Row and serialized/construction-work budgets bound each report. The returned continuation covers only emitted changes; later writes are obtained after that traversal completes. Current authorization, account status, collection generation, retention and recovery state remain mandatory on every page. Complete bounded PROPFIND responses retain explicit limit errors; synchronization supplies the resumable path.

[RFC 4918 §4.3](https://www.rfc-editor.org/rfc/rfc4918.html#section-4.3) requires property language context to survive storage. MKCALENDAR and PROPPATCH now detach properties with their effective in-scope `xml:lang`, including nearer overrides and explicit empty values, while retaining namespace bindings, mixed content and whitespace. `DavPropertyBoundsIT` exercises real HTTP persistence and COPY/MOVE. Previously discarded context cannot be reconstructed from old stored values.

Property retrieval selects only requested values; `propname` retrieves names directly. Atomic aggregate storage limits and XML construction budgets supplement parser and final serialized-output limits. One namespace invariant covers fixed principal/calendar components, encoded length, segment size and the reserved resource leaf. COPY/MOVE preflight resulting descendants before replacing destination state. Namespace values must remain representable as [XML 1.0 character data](https://www.w3.org/TR/xml/#NT-Char): the additional rejection is limited to BMP U+FFFE/U+FFFF, with U+FDD0 and supplementary U+1FFFE retained and tested. This does not classify every Unicode noncharacter as XML-invalid. Expected unavailable members of valid multiget/property-expansion reports retain their own statuses; invalid targets, malformed requests, database faults and computation limits keep their distinct request-level semantics. These corrections have named regressions in the conformance matrix; executed results are linked from the current evidence index rather than inferred from source inspection.

## Updates and selected extension boundary

RFC4791’s [information page](https://www.rfc-editor.org/info/rfc4791/) lists RFC5689,6638,6764,7809,7953,8996. RFC6764 discovery is implemented. TLS1.2/1.3 at the documented proxy arrangement follows RFC8996’s removal of TLS1.0/1.1. Extended MKCOL, automatic scheduling, timezone-by-reference and availability components are not advertised. Explicit embedded VTIMEZONE remains required for TZID-bearing accepted resources. Adding a dependency class does not enable an extension.

RFC5545’s [information page](https://www.rfc-editor.org/info/rfc5545/) lists RFC5546,6868,7529,7953,7986,9073,9074,9253. RFC6868 parameter encoding is implemented. iTIP/scheduling execution, availability and specialized new-component services remain outside this calendar-access profile; valid supported-resource extension properties are retained without promising to execute them. Non-Gregorian RSCALE processing is not advertised. RFC4918’s RFC5689 update does not require advertising extended collection creation. RFC5397,6578,6764 had no errata records in the checked inventory.

Recheck the linked primary inventories when updating this contract or its libraries. Current test results, deliberate Digest/security and optional sync-Depth compatibility deviations remain explicit in the conformance matrix; no claim of literal compliance with every historical HTTP/WebDAV provision is made.
