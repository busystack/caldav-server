# Local observance UNTIL input compatibility

BusyStack defaults to one narrowly defined calendar admission repair. In an RRULE directly in
`VCALENDAR → VTIMEZONE → STANDARD` or `DAYLIGHT`, a local DATE-TIME UNTIL is converted to UTC
by subtracting that observance's own TZOFFSETFROM. For example, `20260308T020000` with
`TZOFFSETFROM:-0800` becomes `20260308T100000Z`. No host timezone, current offset, User-Agent,
PRODID, account type or hardcoded zone name selects this behavior.

This accommodates the client output described in [Mozilla bug 2004421](https://bugzilla.mozilla.org/show_bug.cgi?id=2004421).
It is an **input compatibility exception** to [RFC 4791 §5.3.2.1](https://www.rfc-editor.org/rfc/rfc4791.html#section-5.3.2.1),
which requires valid submitted calendar data. The original input is invalid under
[RFC 5545 §§3.3.10, 3.6.5](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.6.5).
Validating the corrected stored representation does not make this an unconditional standards
conformance claim. The independent standards audit and historical rejection evidence are retained.

Disable only this repair to restore the preceding rejection policy:

```sh
export BUSYSTACK_DAV_REPAIR_LOCAL_OBSERVANCE_UNTIL=false
docker compose up -d application
```

The equivalent JAR property is `busystack.dav.repair-local-observance-until=false`.
The executable and Compose both default to true. The separate approved sync Depth compatibility
setting and the HTTPS DAV credential/Bearer authentication profile are unchanged.

The application-owned adapter retains component boundaries and original octets. It unfolds only
for inspection and refolds changed RRULE lines as necessary; all unrelated bytes remain intact.
Offsets with seconds and UTC date rollover are accounted for. A valid UTC UNTIL is untouched.
Mandatory DTSTART/TZOFFSETFROM/TZOFFSETTO must be present and unambiguous; unsafe dates, offsets,
duplicate rule parts and COUNT/UNTIL conflicts reject. Leap-second candidates or UTC conversions
outside years 0001–9999 are explicitly unsupported rather than guessed. Existing byte,
component, property, timezone and recurrence work bounds still apply. No recurrence is expanded
by the adapter. The complete corrected calendar must pass the unchanged strict parser and
validator, so another invalid component/property still rejects the whole request before mutation.
Event/task rules, text and opaque extension values are never repaired. Collection timezone
properties and report timezone inputs retain their existing strict validation.

Repaired bytes are the single authoritative stored representation used by GET, ETags, metadata,
history, queries, expansion, free/busy, multiget and synchronization. Valid requests retain their
original bytes. There is no read-time repair, bulk rewrite, schema change or third-party patch.
The existing transaction locks, permissions, UID conflicts and write preconditions still apply.
The minimal structured `calendar_input_repaired` diagnostic records the named repair and rule
count, without calendar contents, identities or credentials.

A successful transformed PUT omits **both ETag and Last-Modified**, including a repeated request
whose corrected bytes already match storage. Transformation is relative to that request, not to
the previous object. GET and DAV:getetag subsequently expose the strong ETag for the stored
corrected bytes. An unchanged valid PUT retains the existing ETag response behavior. This follows
[RFC 9110 §9.3.4](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.4);
clients must retrieve the stored representation/validator before subsequent conditional edits.

Permanent regressions use unchanged captured Thunderbird 159.0 Vancouver/Berlin uploads and
compare every finite cutoff's final included and first excluded recurrence using the submitted
offset, independently of host timezone data. Focused HTTPS tests additionally check validator
omission, corrected retrieval/reports/history, rejected-write atomicity, UID conflicts, stale
preconditions, concurrent replacements and the explicit disabled configuration. Client and final
artifact qualification results are recorded separately; a unit test is not a graphical client pass.
