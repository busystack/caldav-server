# Independent clients

`IndependentClientIT` starts the actual application with PostgreSQL 18.6 and TLS signed by a disposable CA. Its account and named DAV credential are fixture setup through application services; the separate Python process performs discovery, calendar/task creation, reads, writes, queries and synchronization through `python-caldav:3.6.0` and `icalendar:7.3.0`. It shares neither HTTP/XML request builders nor calendar parsing with the server. Missing Python/dependencies fail this required test.

The independent-client fixture explicitly enables `busystack.dav.allow-sync-depth-one=true`. Both python-caldav 3.6.0 and Thunderbird 157 send HTTP `Depth: 1` for sync reports, whereas [RFC 6578 section 3.2](https://www.rfc-editor.org/rfc/rfc6578.html#section-3.2) requires a 400 response for nonzero Depth. The strict default remains false and is tested separately. The opt-in mode accepts only Depth1 with XML sync-level1, applies the same collection scope/authorization/snapshot/limits as Depth0, and rejects infinity/invalid/duplicate headers. Actual tokens/deletions are required in this fixture; client emulation/fallback is disabled. This is a documented compatibility deviation, not strict-default incremental-sync qualification for those client versions.

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

Results and native-client serialized fixtures are written under `target/qualification`. A result is evidence only after its process exits successfully; staging or compilation is not a pass. See the maintained tested-client matrix for the executed snapshot and exact limitations.
