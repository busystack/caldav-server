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
If the snapshot transaction cannot commit, transaction completion discards the
completed file before any download is issued. Failed deletion remains charged to
the shared capacity budget until cleanup succeeds.
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

Export and import both support at most 10,000 resources / UID series and 64 MiB.
Every successful export can be uploaded directly into an authorized destination of
the same component type. No manual splitting, recurrence reconstruction or
calendar-context merging is required. Arbitrary larger migrations are not supported.

## Preview, confirm and results

Select an authorized collection and upload actual iCalendar content. File names
are ignored and never select server paths. The server's existing parser, text and
complexity limits locate UID series; every resulting resource goes through full
CalendarAdmission and the existing VEVENT/VTODO validation. All exceptions for a
UID remain with the master. Unsupported components and wrong-destination types
are reported explicitly. Malformed framing rejects the file before mutation.

Preview and actual results show 50 entries per page with whole-file counts. An
identical existing UID/resource is **Skipped**. Different content with an existing
UID is **Conflicting** and is preserved. Multiple masters or repeated valid UIDs in
separate input objects are rejected rather than guessed or discarded. Unsupported
and invalid items are reported. No replacement or implicit merge is offered.

A preview uses an opaque account/security-generation-bound token. One preview per
account is retained for ten minutes, with at most 32 previews/results per instance.
Successful result pagination remains available for ten minutes after commit.
A replacement is installed only after validation and staging succeed; a bad upload
preserves the existing preview. **Review your latest import preview or results**
returns to it from the portability page or a failed replacement upload. The link
is shown only after current MFA and destination access checks. Retained report metadata has a separate 32 MiB
aggregate bound. Expiration or cancellation removes the private data.

Confirmation requires the checkbox, current account state, recent MFA and current
destination write permission again. The service takes the existing DAV
namespace/collection locks, claims the preview exclusively and reclassifies
conflicts. New valid series use create-only preconditions in one PostgreSQL
transaction. Any database failure rolls back every write. A known rollback restores
the preview for retry while it remains valid; follow the error page's review link.
A successful commit consumes it before filesystem cleanup, so cleanup failure
cannot permit replay. An indeterminate transaction outcome invalidates the preview;
review the collection before uploading again. Successful results explicitly show
imported, skipped, conflicting and rejected items. No invitation, outbound
attachment fetch or alarm execution occurs.

## Private temporary storage

All portability and servlet multipart files live beneath an application-controlled
private directory, defaulting to `${java.io.tmpdir}/busystack-portability`. Override
`busystack.portability.directory` only with a dedicated directory owned by the
application user with mode 0700; staged data files use 0600. This location must not
be served by a web server. Paths and random filenames never derive from uploaded
names or calendar data. Symbolic-link directories are refused. A process lock
prevents two instances sharing the directory.

Completion, cancellation, expiration and graceful shutdown attempt deletion.
Failed deletion retains its storage reservation and is retried every thirty
seconds. Pending previews are intentionally invalid after JVM restart. Startup
removes only recognized application data/multipart orphan files under the locked
private directory; it preserves unrelated files and refuses unexpected unsafe
entries. A stopped standalone JAR cannot delete files: the next startup cleans
crash remnants. Compose's `/tmp` tmpfs also disappears when the container stops.
The deployment remains a single application instance.
The supported container and standalone launch commands use
`-XX:+ExitOnOutOfMemoryError`: actual allocation failure terminates the JVM rather
than leaving a live process with partially established in-memory work state.
Compose restarts it; a standalone operator restarts the JAR. Pending previews
then become invalid and startup cleanup applies. Ordinary database rollback
continues to preserve a valid preview for retry without restarting.

## Processing and deployment limits

| Budget | Bound |
| --- | --- |
| Individual input / export | 64 MiB |
| UID series / exported resources | 10,000 |
| Individual admitted DAV resource | 1 MiB (unchanged) |
| Aggregate reconstructed import resources | 64 MiB |
| On-disk parser index | 32 MiB |
| Portability private files, including pending cleanup | 72 |
| Concurrent portability requests | 2 |
| Aggregate temporary reservations | 272 MiB |
| Upload reservation before multipart parsing | 161 MiB (65 multipart + 64 input + 32 index) |
| Other operation reservation | 64 MiB (including export or confirmation index) |
| Parsing / transaction processing deadline | 600 seconds |

The importer reads logical lines and top-level components incrementally and uses a
binary disk index of original byte ranges. It reconstructs and admits one UID
resource at a time, preserving recurrence order and each VCALENDAR's own metadata
and timezone context. It never retains the whole upload plus all reconstructed
resources in memory. Nesting is bounded to eight, logical lines to 1 MiB, and each
reconstructed resource retains the existing parser limits (2,000 components,
20,000 properties, 40,000 parameters) and recurrence/timezone validation bounds.
The item, file, index and deadline bounds also limit total file-processing work.

Multipart buffer reservations remain charged through deletion, including early
security rejection. Cleanup waits until no synchronous upload can still read a
part; failed deletion retains capacity and is retried with other cleanup.
Capacity is reserved before multipart parsing or processing, transferred to
retained files and released only after deletion. Two maximum uploads cannot run
together. Retained previews may prevent an otherwise allowed operation: 503 with
Retry-After means retry or cancel an unused preview. Exports complete under their
reservation before sending success headers, never as truncated successful files.
The 384 MiB Compose `/tmp` tmpfs leaves 112 MiB outside the portability ledger for
operational headroom. The 1 GiB container uses a 40% JVM heap ceiling so tmpfs and
native memory have room; this is a bounded configuration, not a capacity promise.
A standalone JAR must provision at least 384 MiB free on its private staging
filesystem, separately from PostgreSQL, backups and other processes.

The proxy allows 65 MiB multipart requests **only** at `/portability/preview`
(including an application prefix). Portability proxy request/response disk
buffering is disabled and its response deadline is 660 seconds. Multipart bodies
are rejected at other application routes before buffering. Ordinary DAV 1 MiB and
authentication 64 KiB request limits remain unchanged. Supplied root/subpath and
qualification proxy fixtures implement this policy. Protect staging, snapshots
and backups as described in [operations](operations.md).

Focused service/HTTPS tests and native fixtures exercise byte-preserving
export → import → retrieve comparisons, UID/resource counts, recurrence, tasks,
timezones, duplicate/conflict classification, rollback, unauthorized operations,
CSRF and safe download names. Packaged browser evidence is independent of those
service tests; consult the current qualification receipt for executed results.
