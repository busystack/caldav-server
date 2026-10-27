# Standards review record

Checked 2026-10-27. This is a source-review record, not a conformance finding. The implementation/test mapping is in [conformance.md](conformance.md); protocol requirements without a production adapter remain incomplete.

The [RFC Editor explains errata status](https://www.rfc-editor.org/series/rfc-errata/): a reported erratum is not automatically a verified correction. Review the current errata status when implementing an affected requirement, and attach the relevant regression test. Do not silently treat rejected or held proposals as mandatory changes.

| Reference | Review finding and implementation consequence |
| --- | --- |
| [RFC 4791 information page](https://www.rfc-editor.org/info/rfc4791/) and [errata](https://www.rfc-editor.org/errata/rfc4791) | Updates include RFC 5689, 6638, 6764, 7809, 7953 and 8996. Scheduling, extended MKCOL, time zones by reference and availability require separate capability decisions and evidence. Their presence in the update list does not authorize advertising them. RFC 6764 discovery belongs to the requested profile. |
| [RFC 4918 errata](https://www.rfc-editor.org/errata_search.php?rfc=4918) | Includes verified and held records. The protocol adapter still needs explicit treatment of applicable `If`, namespace, property and multistatus requirements; a library handler's existence is insufficient. |
| [RFC 5545 errata](https://www.rfc-editor.org/errata/rfc5545) and [inline verified corrections](https://www.rfc-editor.org/rfc/inline-errata/rfc5545.html) | Erratum 5505, concerning quoted TZID values, is held for document update. Calendar preservation and parameter tests must not equate a held proposal with a new normative requirement. RFC 6868 parameter encoding is independently required by the project. Complete recurrence, task-time-range and errata regression qualification is outstanding. |
| [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) | Strong ETags must describe served bytes; a successful transformed PUT has validator restrictions. The storage core hashes authoritative bytes, but protocol response conformance remains unimplemented. |
| [RFC 6578](https://www.rfc-editor.org/rfc/rfc6578.html) | The storage core serializes collection revisions and takes a consistent response snapshot. XML report behavior, limited responses, membership changes and independent-client synchronization still need qualification. |
| [Spring Security Digest guidance](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/digest.html) | The chosen security profile deliberately excludes Digest and its incompatible password-storage requirements. HTTPS Basic with device credentials and local opaque Bearer tokens are the intended profile. The historical RFC 4918 Digest requirement must remain a documented deviation, not a blanket compliance claim. |

No scheduling, reminder execution, locking or vendor sharing capability is advertised by the main application. The Bedework investigation demonstrates why its default headers cannot be adopted unchanged. Organizer, attendee, alarm and attachment storage does not establish execution of those features.
