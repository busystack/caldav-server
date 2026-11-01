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

Results and native-client serialized fixtures are written under `target/qualification`. Source `47b3543` passed both independent clients against the final packaged application with omitted compatibility configuration: [python-caldav result](../evidence/correction-final47-python-caldav.json), [native Thunderbird result](../evidence/correction-final47-thunderbird.json), and [exact CI artifact/configuration record](../evidence/correction-ci-final.json). The extended raw harness additionally completed 104 content and 104 metadata sync pages over 10,037 resources/46,158,025 bytes, including retry, restart, concurrent mutations, deletions and the subsequent delta without enumeration fallback. A result is evidence only after its process exits successfully; staging or compilation is not a pass. The maintained qualification index distinguishes CI and local artifacts and retains their limitations.

The [local observance UNTIL admission repair](../../docs/observance-until-compatibility.md) has separate fresh real-editor qualification for Thunderbird 159.0 (system/explicit Vancouver and explicit Berlin) and GNOME Calendar 50.0 Vancouver. The original strict rejection evidence is unchanged. The native-provider harness now retains a bounded credential-redacted process log at `target/qualification/thunderbird-process.log` even when startup fails; it keeps the existing deadlines and required prerequisites. The packaged restore corpus additionally admits the two unchanged captured uploads, asserts fixed corrected hashes and absent transformed-PUT validators, and checks corrected bytes/history/UTC expansion after restoration. See the client-stage record for its initial local full-run failure and successful isolated provider retry; final CI receipts must be assessed separately.
