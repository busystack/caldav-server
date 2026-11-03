# Independent clients

`IndependentClientIT` starts the actual application with PostgreSQL 18.6 and TLS signed by a disposable CA. Its account and named DAV credential are fixture setup through application services; the separate Python process performs discovery, calendar/task creation, reads, writes, queries and synchronization through `python-caldav:3.6.0` and `icalendar:7.3.0`. It shares neither HTTP/XML request builders nor calendar parsing with the server. Missing Python/dependencies fail this required test.

The independent-client fixture leaves the synchronization-depth setting **absent**. Both python-caldav 3.6.0 and Thunderbird 157 send HTTP `Depth: 1`, whereas [RFC 6578 section 3.2](https://www.rfc-editor.org/rfc/rfc6578.html#section-3.2) requires rejection of nonzero Depth. The server default deliberately accommodates Depth 1 only with XML sync-level 1. Missing Depth and Depth 0 work normally; unsupported/malformed/duplicate/infinite values remain rejected. Explicit `BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE=false` selects strict mode and has a separate real-HTTPS qualification profile. Scope, authorization, continuation and computation limits are unchanged. This is a documented exception to that RFC requirement, not strict compliance with it. Each independent runner performs actual authenticated missing/0/1 REPORTs and wrong-level rejection, records observed effective behavior, and separately records the calling harness's configuration input. Client synchronization fallback remains disabled.

```sh
python3 -m venv .client-venv
.client-venv/bin/pip install -r qualification/clients/requirements.txt
BUSYSTACK_CLIENT_PYTHON="$PWD/.client-venv/bin/python" ./mvnw verify
```

For the desktop-client provider profile, install the additional pinned Mozilla driver dependencies and a published Thunderbird 157.0.1 Linux binary. The installer checks the exact [official SHA512SUMS](https://archive.mozilla.org/pub/thunderbird/releases/157.0.1/SHA512SUMS) entry before extraction. It requires an empty tooling destination and changes no installed Thunderbird/profile.

```sh
.client-venv/bin/pip install -r qualification/clients/desktop-requirements.txt
bash qualification/clients/install-thunderbird.sh .client-tools
BUSYSTACK_CLIENT_PYTHON="$PWD/.client-venv/bin/python" \
BUSYSTACK_THUNDERBIRD_BINARY="$PWD/.client-tools/thunderbird/thunderbird" \
  ./mvnw -Dit.test=IndependentClientIT verify
```

This launches Thunderbird's native CalDAV provider in its real desktop executable under headless Marionette control, in a new private temporary profile. The fixture adds its test CA to that profile's normal certificate database; it never disables certificate validation. It exercises recurring-event and undated-VTODO CRUD, task completion, Unicode, alarms, unknown properties, refresh and deletion. It does not qualify the graphical account wizard, visual rendering, or a person using the interface. Without the binary setting, desktop qualification is explicitly recorded as unexecuted.

The same-object round-trip profile also requires Thunderbird to create a recurring event, a completed undated task, an all-day event and a floating-time event. The separate python-caldav/icalendar client reads and modifies those exact resources; Thunderbird refreshes and verifies the modifications, edits them again, and Python verifies the final data and unchanged resource URLs. Assertions retain recurrence count, alarm trigger, extension properties, task completion timestamp, exclusive all-day end, and floating values without invented UTC/TZID qualifiers. A passing JSON result includes `crossClient.sameResources: 4`. The recorded IndependentClientIT run passed this complete profile; subsequent application artifacts must run the process again before extending that claim. This profile uses isolated test-CA trust and the unmodified server defaults, with observed depth behavior recorded alongside configuration provenance.

IndependentClientIT also exports the live resources through PortabilityService after
Python's edits. Thunderbird's native file parser must consume all four UIDs across
the event/task downloads and retain the detached recurrence exception. The result
records `nativePortalExportItems: 4`. Compatible calendar contexts export one
VCALENDAR; distinct contexts retain a lossless stream. Thunderbird 159.0's file
parser cannot consume multiple VCALENDAR roots; this limitation does not affect
its native DAV provider. See [portability](../../docs/portability.md).

Results and native-client serialized fixtures are written under
`target/qualification` and retained as CI artifacts. The
[qualification index](../evidence/README.md) identifies concise version-specific
records and archived historical results. A result qualifies only its executed
source, artifact and configuration after the process exits successfully.

The [local observance UNTIL repair](../../docs/observance-until-compatibility.md)
has separate real-editor qualification for Thunderbird 159.0 (system/explicit
Vancouver and explicit Berlin) and GNOME Calendar 50.0 / EDS 3.56.2 Vancouver.
The provider harness retains bounded, credential-redacted startup diagnostics under
`target/qualification`; these generated logs do not belong in the source tree.
The packaged restore corpus admits the captured uploads, verifies corrected hashes
and transformed-PUT validator omission, and checks bytes/history/UTC expansion after
restoration. Missing prerequisites, startup failures and assertion failures remain
failures; earlier attempts are not relabeled as successful runs.

## Optional installed GNOME connection wizard

On a Linux graphical workstation with GNOME Calendar `50.0-1`, Evolution Data
Server `3.56.2-8`, Python GI/AT-SPI, GNOME Keyring, bubblewrap, xauth and Docker, `qualification/passkeys/run.py --gnome` adds a real native
connection wizard to the existing packaged HTTPS browser profile. The existing
browser fixture supplies an authenticated private Xvfb display; no host desktop
or existing graphical session is accessed. Use
`--application-image` and `--browser-image` as documented in the
[passkey qualification](../passkeys/README.md). Missing prerequisites fail;
this optional profile is never silently substituted for the normal browser run.

The browser creates the collection, edits metadata, imports contents and issues a
DAV credential. The host fixture drives only its own GNOME accessibility tree
through Manage Calendars, Add Calendar, HTTPS address, credentials and discovery.
It verifies the durable native CalDAV source. It uses a disposable home, private
session/accessibility buses, keyring and namespace-only fixture CA trust. The
private buses use their documented AppArmor setting because policy queries cannot
operate against the namespace's read-only security filesystem; host policy is
unchanged. The pinned GNOME 50 Add Calendar row has no activation action and
reports local coordinates as screen coordinates; the fixture uses the visually
checked native dialog layout and standard X11 input within that private display. It does
not modify installed software, the host trust store or the user's existing
calendar/profile. Secrets remain in temporary private exchange files; retained
results contain redacted diagnostics and accessible control names. This profile
qualifies account setup, not every event-editing or assistive-technology workflow.
`--gnome-only` is an explicit targeted profile for native setup; the full browser,
security and restore profiles must still run separately. `BUSYSTACK_GNOME_DEBUG=1`
optionally runs only the isolated native client under the installed GDB, stopping
on GLib criticals and retaining function-only backtraces without arguments or
locals. This diagnoses upstream crashes without changing installed binaries.
