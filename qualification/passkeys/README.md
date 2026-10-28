# Browser security and deployment qualification

The fixture exercises the packaged Java application, real PostgreSQL, server-rendered pages, Spring Security and Chromium WebAuthn over normally validated HTTPS. It uses a virtual user-verified resident CTAP2 authenticator. It does not qualify physical authenticators, other browsers or desktop CalDAV clients.

On a Linux Docker host with Compose 2.24.4 or newer, Python 3 and OpenSSL:

```sh
docker build --build-arg SOURCE_REVISION="$(git rev-parse HEAD)" --tag busystack-caldav:local .
docker build --tag busystack-passkey-browser:qualification qualification/passkeys
python3 qualification/passkeys/run.py --application-image busystack-caldav:local --groups
python3 qualification/passkeys/run.py --application-image busystack-caldav:local --proxy --groups
```

The default profile uses direct embedded TLS at the hostname root. `--context-path /team` tests embedded TLS under a servlet prefix. `--proxy` uses the pinned non-root Nginx fixture with TLS termination, preserved `/team` paths, host-root CalDAV discovery and RFC 8414 metadata mapping, private management routes and bounded declared/chunked bodies. The ordinary production Compose deployment still consists of the application and PostgreSQL.

The checks cover password-only mutation denial; actual passkey enrollment/assertion and MFA session rotation; a collection explicitly selecting and displaying `VTODO`; named DAV credential issuance, display-once semantics and revocation; administrator-controlled public native client registration; real browser authorization/consent with S256 PKCE and a cookie-authenticated missing-CSRF denial; ordinary recovery-code issuance/display-once, anonymous denial, recent-primary use, replay rejection, access reset and fresh factor enrollment; code exchange without a client secret; refresh rotation and grant revocation; JDBC browser-session and passkey persistence across JVM restart; and disabled-session/passkey rejection. Authentication of `/dav/missing` proves routing and credential enforcement, not calendar/task content synchronization.

For the optional upstream profile:

```sh
docker build --tag busystack-keycloak-browser:26.8.1 qualification/keycloak
python3 qualification/passkeys/run.py --application-image busystack-caldav:local --oidc
```

This profile additionally starts a disposable real Keycloak instance and uses distinct reserved browser sites, `app.busystack.test` and `idp.identity.test`, with private-CA certificates. A fresh nonadministrator account is provisioned from an exact subject allowlist, despite its upstream `realm-admin` role. Actual organization password authentication and the cross-site callback must preserve the application's Secure, HttpOnly, Lax session cookie. Organization login alone cannot mutate collections; a local passkey completes MFA. The user cannot discover the separate local administrator's collection or enter administration. A local-password attempt with the upstream password must fail. This account then exercises DAV credential/OAuth browser workflows and persistence. Explicit identity linking and provider logout/deprovisioning have separate integration tests. Restored-provider login belongs to the restore profile below.

Playwright is pinned to 1.61.1 in the package lock; the digest-pinned browser image currently contains Chromium 149.0.7827.55 and prints its actual version. NSS tooling is pinned to `libnss3-tools=2:3.98-1ubuntu0.3`. These dependencies are absent from production runtime. Exact image digests are listed in [versions](../../docs/versions.md).

Each run creates a host-private temporary directory, disposable CA/certificates and randomly named Compose project. All published fixture ports bind `127.0.0.1`. Scoped reserved-domain resolution routes only the test domains to loopback, while retaining normal TLS hostname/SNI checks. A separate browser container imports only the fixture CA into its NSS database; the application's optional upstream truststore contains only that CA. Certificate-error bypasses and host trust changes are absent. A normal client lacking the CA must reject the certificate.

Generated secrets, private keys, truststores and virtual authenticator state are disposable, never committed as fixtures or uploaded as evidence. Failure output redacts passwords, tokens and authorization parameters. Cleanup removes only the named fixture containers/networks/volumes. Missing prerequisites, TLS failures, failed assertions and explicit deadlines fail visibly. Execution status belongs in [qualification evidence](../evidence/README.md); a fixture's presence does not establish a pass. Hostile ceremonies, physical devices, independent calendar/task clients and complete production readiness have separate evidence; physical devices remain unexecuted.

The browser integration uses [Playwright CDP sessions](https://playwright.dev/docs/api/class-cdpsession) and the [Chromium WebAuthn testing protocol](https://chromedevtools.github.io/devtools-protocol/tot/WebAuthn/).

## Group sharing, restoration and measured workload profiles

`--groups` requires actual CSRF-protected account provisioning, group creation/rename/membership updates/deletion and owner READ/WRITE/revoke forms. The actor retains one authoritative collection; administrator membership management is distinct from owner-controlled data grants.

```sh
python3 qualification/passkeys/run.py --application-image busystack-caldav:local --proxy --groups --restore --workload --client-python .client-venv/bin/python
python3 qualification/passkeys/run.py --application-image busystack-caldav:local --oidc --groups --restore
```

`--restore` creates an actual VTODO through HTTPS using a browser-issued DAV credential, authorizes an actual native OAuth grant, records a sync token, and saves a database dump while those credentials are demonstrably valid. It then revokes access through the browser and restores the older dump into a separate empty owned project. Every restored account is initially disabled; epochs change and factors/sessions/credentials/grants are cleared. The operator reviews known fixture permissions and recovers only the selected owner and a synthetic READ recipient. The owner completes fresh local or real cross-site upstream authentication and passkey enrollment, then issues fresh DAV and OAuth access. The second principal completes actual local password/passkey enrollment and DAV issuance. HTTPS assertions cover byte-exact task/ownership, old Basic/Bearer/refresh rejection, invalid old sync state, fresh initial sync, restored READ GET permission, PUT denial, and owner removal of that share followed by recipient denial. A separate WRITE grant remains preserved in PostgreSQL while its account stays disabled. The same provider remains running during the federated restore; exact issuer/subject recovery does not create a local password fallback.

`--workload` requires the local restore profile and the locked independent-client Python environment. It leaves the sync-depth setting absent, verifies the effective default through authenticated REPORTs, runs the independent-client dataset, and captures latency distributions, environment/image identifiers, Docker memory/CPU samples and PostgreSQL statistics in `target/qualification/workload.json`. It verifies credentials remain valid immediately before the backup, so elapsed token expiry cannot masquerade as restoration revocation. Workload failure or expiry fails the qualification. Results describe one client on a shared host, not capacity or high availability. All ordinary browser profiles leave the setting absent. The required `--strict-sync-depth` profile sets explicit false and verifies actual missing/Depth 0 success with Depth 1 rejection. No client user-agent mode is used.

The required `--workload` profile additionally seeds only owned disposable fixtures: 10,037 independently generated valid calendar resources exceeding 32 MiB, and 12,500 group grants across 25 calendars and 500 groups. Actual independent HTTPS requests traverse content and metadata using 97-row RFC 6578 pages, retry continuation, write/delete/recreate/insert across boundaries, restart the JVM midway and resume, then verify deltas and a selective query. Repeated valid property updates fill 12 resource targets; selected values, names, all properties, three concurrent readers, an explicit oversized-response failure and subsequent healthy GET are measured. The real browser checks first/last dashboard sharing pages, owner revocation, group membership update and deletion. Source fixture population is SQL rather than a bulk-create throughput measurement; ordinary client CRUD is separately qualified. Image, memory samples, duration and database counters remain recorded. The controlled restart creates an explicitly recorded measurement gap, not an unreported observer failure.

`--workload` also requires the exact native Thunderbird prerequisite (`--thunderbird-binary` or `BUSYSTACK_THUNDERBIRD_BINARY`, default `.client-tools/thunderbird/thunderbird`). It re-runs both independent Python and native headless Thunderbird same-resource event/task round trips against the **final packaged server**, with no compatibility-enabling override. Missing prerequisites fail visibly. The earlier Maven integration run is separate evidence rather than a substitute for this artifact-level execution.

The expanded fixture's selective query has a request-specific 150-second client deadline, while all other capacity requests keep 60 seconds and the process keeps its 900-second bound. The pinned proxy uses a 120-second read deadline and unchanged 5-second connect deadline. This adjustment follows a fixed 10,037-resource real-HTTP selective query taking 41.564 seconds at a 256 MiB heap, beyond the former 30-second proxy deadline; see [measurement scope](../../docs/performance.md). It does not extend authentication, MFA or token policy and does not permit unlimited query work.
