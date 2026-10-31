# Captured Thunderbird input (immutable test fixtures)

These two files are byte-for-byte copies of Thunderbird 159.0 editor uploads retained in
`~/busystack/caldav-server-timezone-verification-20261030/thunderbird159/cases/`:

* `system-vancouver/evidence/creation-put-1.ics` → `thunderbird159-vancouver.ics`
* `explicit-berlin/evidence/creation-put-1.ics` → `thunderbird159-berlin.ics`

They intentionally contain invalid local observance UNTIL values. Strict parsing must still
reject them. Admission with the narrow default repair must produce valid corrected bytes;
tests verify all six Vancouver and seven Berlin boundaries against local-onset recurrences
using each observance's TZOFFSETFROM. The original external evidence is unchanged.

The fixtures are client-generated synthetic events, not host IANA timezone replacements.
