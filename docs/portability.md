# iCalendar import and export

The account portal exposes data portability through the same CalendarStore,
DavStore and CalendarAdmission used by DAV. It does not add an event/task editor,
a weaker calendar profile or scheduling actions.

## Export

An owner or current read-authorized recipient can export a collection after
primary sign-in and passkey verification within ten minutes. Application
administration provides no additional content access. A repeatable-read snapshot
retrieves bodies in the existing batches of at most 16 resources / 4 MiB. A private
temporary file is completed and validated before download success headers are
sent. Errors never return a silently incomplete successful export. The download
uses `text/calendar; charset=utf-8`, a fixed safe attachment filename and
`Cache-Control: no-store`; the file is removed after delivery.
An empty export snapshot returns to the collection with a notice; no unusable
empty interchange file is downloaded.

For multiple resources with byte-identical calendar headers and embedded timezone
definitions, export produces one VCALENDAR containing all their components. Headers,
VTIMEZONEs and every event/task component retain their original bytes; only repeated
identical wrappers and timezone definitions are removed. A single resource exports
unchanged. No serializer rewrites recurrence, properties or parameter values.

When headers or timezone definitions differ, combining them could lose calendar
properties or conflate different definitions of the same TZID. Export instead emits
a lossless [RFC 5545 §3.4 iCalendar stream](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.4)
of complete stored VCALENDAR objects, with a `-stream.ics` filename. A missing final
line ending receives a separator. BusyStack imports both representations.

**Thunderbird 159.0's native file parser does not consume multiple VCALENDAR roots.**
Its CalICSService wraps ICAL.parse's multi-root array as a single component. Use a
single-VCALENDAR export for its file importer; a lossless stream requires an importer
that accepts RFC 5545 streams, such as BusyStack. Native DAV synchronization remains
independent of file import. Do not assume another application's file-import support
from its protocol support.

Export limits are 10,000 resources and 64 MiB. Files larger than the import limit
must be divided at complete UID series (single VCALENDAR) or VCALENDAR boundaries
(stream) before importing. These are explicit operating limits, not a claim of
arbitrary-size migration.

## Preview, confirm and results

Select an authorized collection and upload actual iCalendar content. File names
are ignored and never select server paths. The server's existing parser, text and
complexity limits locate UID series; every resulting resource goes through full
CalendarAdmission and the existing VEVENT/VTODO validation. All exceptions for a
UID remain with the master. Unsupported components and wrong-destination types
are reported explicitly. Malformed framing rejects the file before mutation.

Preview lists each item and its disposition. An identical existing UID/resource is
**Skipped**. Different content with an existing UID is **Conflicting** and is not
overwritten. Multiple masters or repeated UIDs in separate input objects are
rejected rather than guessed or discarded. Validation rejections are counted.
No replacement or implicit merge is offered.

A preview is an opaque account/security-generation-bound token backed by a private
server temporary file. One preview per account is retained for at most ten minutes;
a new preview replaces the earlier one. Total staged files are capped at 32 MiB /
32 previews per instance. Confirmation or cancellation deletes the staged file.
Graceful shutdown cleans pending files; after a crash, operators must ensure
ordinary temporary-file cleanup. Previews cannot survive a JVM restart or transfer
to another instance; the default deployment runs one application instance.

Confirmation requires the checkbox, current account state and recent MFA again.
The service takes existing DAV namespace/collection locks, rechecks write permission
and reclassifies conflicts. Valid new series are written with create-only
preconditions in one transaction. A database failure rolls them all back. Otherwise
the results explicitly report imported, skipped, conflicting and rejected items;
only the valid new subset is imported. Replaying a consumed or expired token fails.
No invitation, outbound attachment fetch or alarm execution occurs.

## Processing and deployment limits

Upload files are limited to 8 MiB, each resulting UID resource to 1 MiB, and items
to 1,000. The existing depth/component/property/parameter bounds apply cumulatively
across the input; retained resulting resource bytes are separately bounded to
16 MiB to prevent timezone duplication exhausting memory. Two simultaneous
portability requests are admitted; further requests receive 503 and Retry-After.
Temporary disk use also remains subject to the deployment's private temp limit.

The proxy must allow 9 MiB multipart requests **only** at `/portability/preview`
(with its configured application prefix). Other DAV/form limits remain unchanged.
The deployment examples and qualification proxy include this location. Do not
raise DAV resource or authentication-body limits to enable multi-item imports.
Temporary files, database snapshots and backups can contain private calendar data;
protect host and database access as described in [operations](operations.md).

Focused service/HTTPS tests and native fixtures exercise byte-preserving
export → import → retrieve comparisons, UID/resource counts, recurrence, tasks,
timezones, duplicate/conflict classification, rollback, unauthorized operations,
CSRF and safe download names. Packaged browser evidence is independent of those
service tests; consult the current qualification receipt for executed results.
