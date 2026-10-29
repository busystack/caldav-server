# Operations

These instructions describe BusyStack CalDAV Server 0.0.1. The [release verification record](../qualification/evidence/releases/0.0.1/verification.json) identifies the final source, artifacts, installation exercise and any unavailable manual checks. Standards-correction behavior and earlier root/prefix/recovery results remain in the [developer correction record](standards-corrections.md); earlier qualification links below retain their historical source scope.

This deployment runs one application and PostgreSQL. Automated evidence includes real HTTPS DAV, account-security/native OAuth, optional real Keycloak, and independent calendar/task client operations. The [conformance matrix](conformance.md) and [artifact qualification records](../qualification/evidence/README.md) record completed packaged recovery/browser checks and explicitly unexecuted environments. The project is Apache-2.0, with [separate third-party licensing and notice delivery](third-party-licenses.md).

## Build and verification

Install Java 25 and `unzip`, then use the checked-in wrapper. Keep `.mvn/jvm.config` in the checkout: it pins the build JVM to UTC so executable JAR timestamp metadata is reproducible across host timezones. It does not change application timezone semantics.

```sh
python3 -m venv .client-venv
.client-venv/bin/python -m pip install -r qualification/clients/requirements.txt -r qualification/clients/desktop-requirements.txt
bash qualification/clients/install-thunderbird.sh .client-tools
BUSYSTACK_THUNDERBIRD_BINARY="$PWD/.client-tools/thunderbird/thunderbird" \
BUSYSTACK_CLIENT_PYTHON="$PWD/.client-venv/bin/python" ./mvnw --batch-mode --no-transfer-progress verify
docker build --build-arg SOURCE_REVISION="$(git rev-parse HEAD)" --tag busystack-caldav:0.0.1 .
python3 scripts/verify-operations.py --image busystack-caldav:0.0.1
```

`verify` includes the required PostgreSQL integration suite; Docker must be available. Integration prerequisites must fail visibly when absent. The shell-only Maven wrapper requires `unzip` for its SHA-256-pinned ZIP distribution; its upstream tar fallback cannot use the ZIP checksum. The Docker build uses the identical Maven 3.9.12 from its pinned image directly. The container build packages the executable JAR; it skips tests because the full suite runs separately. An image build alone is not qualification.

The executable JAR is `target/caldav-server-0.0.1.jar`. The container name above is a local build tag, not a promise that a registry image has been published. Record the checkout revision with `git rev-parse HEAD`, and calculate the JAR checksum with `sha256sum target/caldav-server-0.0.1.jar`. Preserve the matching source, image identity and configuration when installing or backing up the server.

The Python 3 operations harness requires Compose 2.24.4 or newer for the port override. It creates two randomly named disposable Compose projects, bootstraps an account locally, checks the packaged application's probes/discovery/issuer and DAV challenge, restarts it, seeds database-recovery fixtures, then backs up and restores to the second database. It verifies data/ownership/sharing, restored security-state invalidation and controlled local/external offline recovery, and removes only its own projects and volumes. It never prints generated secrets. Its SQL-seeded fixtures are database procedure evidence, not independent CalDAV-client or passkey-authentication evidence. HTTPS/proxy/browser workflows require separate end-to-end tests.

CI checks formatting, compilation/static analysis, unit/integration tests, packaging, container construction, SBOM generation, and a required public OSV advisory check with a known-vulnerable positive control. Feed failures, incomplete pagination and detected advisories fail the gate. An additional NVD audit runs only when its credential is configured; absence is recorded as unexecuted. The attempted anonymous NVD run failed before analysis. See [advisory-check scope and evidence](../qualification/security/README.md). Neither a successful advisory lookup nor this self-assessment is an independent security review.

## Two-service Compose arrangement

Requirements: Docker Engine with Compose v2, enough disk for PostgreSQL and backups, and an HTTPS reverse proxy operated on the same host. No Redis, Keycloak, LDAP, PHP, or frontend service is part of the default arrangement.

Use the exact source revision recorded for the prepared release, then complete the explicit image build above so its OCI revision label identifies that checkout. These instructions use that local `busystack-caldav:0.0.1` image; they do not assume a published tag, release download or registry image. Start Compose without rebuilding so it retains the image and source label you just verified.

For a trial installation or restore exercise, choose a new Compose project and an unused loopback port before running any Compose command, for example `export COMPOSE_PROJECT_NAME=busystack-caldav-trial` and `export BUSYSTACK_HTTP_PORT=18080`. Use the same environment for startup, bootstrap, backup and restore. The proxy upstream must use the chosen port instead of 8080. Never reuse an existing deployment's project or database volume for an installation check. The ordinary installation uses the checked-in project name and loopback port 8080.

Create two independent secrets outside the checkout. For example:

```sh
install -d -m 700 "$HOME/.config/busystack-caldav"
(umask 077; openssl rand -base64 48 > "$HOME/.config/busystack-caldav/database-password")
(umask 077; openssl rand -base64 48 > "$HOME/.config/busystack-caldav/postgres-admin-password")
chmod 444 "$HOME/.config/busystack-caldav/database-password" "$HOME/.config/busystack-caldav/postgres-admin-password"
export BUSYSTACK_DATABASE_PASSWORD_FILE="$HOME/.config/busystack-caldav/database-password"
export BUSYSTACK_POSTGRES_ADMIN_PASSWORD_FILE="$HOME/.config/busystack-caldav/postgres-admin-password"
export BUSYSTACK_PUBLIC_BASE_URL=https://calendar.example.org
export BUSYSTACK_ISSUER=https://calendar.example.org
docker compose up -d --no-build
docker compose ps
```

The base URL and issuer must be equal, canonical absolute URLs with no trailing slash, query, credentials, fragment, or percent-encoded path. Configuration is validated at startup. These are external addresses, not `http://application:8080` or `http://database:5432`. Loopback HTTP is accepted only with the explicit development switch `BUSYSTACK_ALLOW_INSECURE_LOCAL=true`; that setting does not make HTTP suitable for network credential use.

The application runs as UID/GID 10001, with a read-only root filesystem, a bounded temporary directory, dropped Linux capabilities, and a 1 GiB container memory limit. That memory limit is a conservative engineering default, not a measured production capacity claim. PostgreSQL data is persisted at `/var/lib/postgresql`, the PostgreSQL 18 image's volume root. No database port is published. The application listener is published only on `127.0.0.1`, port 8080 by default (`BUSYSTACK_HTTP_PORT` can change the host port).

PostgreSQL administration uses a separate secret. Initialization creates a non-superuser `busystack` database owner for application migrations and runtime. Initialization scripts run only on an empty database volume. Replacing a password file does not rotate an already initialized PostgreSQL role; rotate the database role and secret together during controlled downtime.

Compose secret mounts supply `spring.datasource.password` through Spring's config-tree support. Local Compose implements file-backed secrets as bind mounts and preserves host file ownership/mode. Therefore the secret files need read permissions for the container users, while the enclosing host directory must remain mode 0700 to deny other host users access. Do not make that directory public. The separate one-time administrator bootstrap password file must remain owner-only. Do not commit a `.env` containing secrets, copy secrets into an image, or place credentials in an application's command line.

## HTTPS and proxy paths

Terminate TLS at a configured reverse proxy and allow its local connection to `127.0.0.1:8080`. Deny public access to `/actuator/`; health probes are for local/container monitoring. Set request-size and timeout limits at the proxy as well as the application. Use valid certificates and clients' normal certificate validation.

Put either location arrangement below inside your proxy's HTTPS virtual host, using its real certificate chain and private key. For example, the surrounding Nginx configuration is:

```nginx
server {
    listen 443 ssl;
    server_name calendar.example.org;
    ssl_certificate /etc/ssl/caldav/fullchain.pem;
    ssl_certificate_key /etc/ssl/caldav/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;
    gzip off;
    # Insert the matching root or /calendar locations below.
}
```

Obtain and renew that certificate through your operator's CA/ACME procedure, protect its private key and test renewal before deployment. The application image contains no production certificate or trust bypass. Run the proxy's configuration validation before reloading it.

The TLS 1.2 list permits ephemeral elliptic-curve key exchange with AEAD suites. It excludes static RSA and finite-field DH suites deprecated by RFC 10015; TLS 1.3 uses the runtime's modern TLS 1.3 suites. The embedded listener has the corresponding protocol/cipher allow-list when explicitly enabled with `server.ssl.enabled=true` and configured key material. Its default remains plain loopback HTTP behind the configured TLS terminator. The disposable qualification negotiates TLS 1.2/1.3, rejects obsolete protocol/key-exchange offers, and checks trusted certificate identity, wrong hostname and untrusted CA. These local tests do not qualify an operator's public certificate, DNS or network boundaries.

Configured public and issuer URLs must use canonical ASCII deployment prefixes such as `/calendar`; literal or percent-encoded Unicode prefixes, semicolons and invalid ports are rejected at startup. Unicode names in calendar/resource segments remain supported. The configured path becomes the servlet context and must be preserved by the proxy.

Disable compression at the application and reverse proxy so strong GET validators identify the representation served. For an Nginx hostname-root arrangement, the relevant locations are:

```nginx
gzip off;
location ^~ /actuator/ { return 404; }
location / {
    client_max_body_size 1m;
    proxy_connect_timeout 5s;
    proxy_read_timeout 120s;
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host calendar.example.org;
    proxy_set_header Forwarded "";
    proxy_set_header X-Forwarded-Host "";
    proxy_set_header X-Forwarded-Proto "";
}
```

This fragment belongs inside a TLS-enabled server configuration; it is not a complete TLS configuration. The application ignores forwarding headers and constructs discovery URLs from validated configuration. Prevent direct remote access to the internal HTTP listener. Do not enable blanket forwarded-header trust to change redirects or issuer values.

A configured public subpath automatically sets the matching servlet context path. For `BUSYSTACK_PUBLIC_BASE_URL=https://calendar.example.org/calendar` and the same issuer, the proxy must **preserve** `/calendar` when forwarding; rendered browser links, cookie paths, callbacks and DAV endpoints then share that prefix. An explicitly configured `server.servlet.context-path` must match the public URL path or startup fails. Do not strip the prefix.

Host-root discovery routes need explicit mappings. For the example above:

```nginx
location = /.well-known/caldav {
    return 308 https://calendar.example.org/calendar/dav/;
}
location = /.well-known/oauth-authorization-server/calendar {
    proxy_pass http://127.0.0.1:8080/calendar/.well-known/oauth-authorization-server;
    proxy_set_header Host calendar.example.org;
    proxy_set_header Forwarded "";
    proxy_set_header X-Forwarded-Host "";
    proxy_set_header X-Forwarded-Proto "";
}
location ^~ /calendar/actuator/ { return 404; }
location /calendar/ {
    client_max_body_size 1m;
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host calendar.example.org;
    proxy_set_header Forwarded "";
    proxy_set_header X-Forwarded-Host "";
    proxy_set_header X-Forwarded-Proto "";
}
```

The metadata URL follows [RFC 8414 §3](https://www.rfc-editor.org/rfc/rfc8414.html#section-3): the well-known segment is inserted before the issuer's path. The Spring application's local metadata endpoint remains inside its servlet context. Use literal configured authority/prefix values in these mappings. The embedded TLS security suite exercises a servlet subpath. The final packaged image passed actual pinned Nginx `/team`, root TLS, cross-site Keycloak and local/federated HTTPS restoration profiles with normal test-CA validation; see [exact image/evidence scope](../qualification/evidence/README.md). Later protocol/schema changes require corresponding artifact reruns.

## Local administrator bootstrap

There is no default administrator password or unauthenticated setup endpoint. The local bootstrap command requires an empty account database, a private owner-only password file, and `--spring.main.web-application-type=none`. Use the executable JAR with database configuration supplied securely through environment/configuration:

```sh
java -jar target/caldav-server-0.0.1.jar \
  --spring.main.web-application-type=none \
  --busystack.bootstrap=true \
  --busystack.bootstrap-username=administrator \
  --busystack.bootstrap-password-file=/private/path/administrator-password
```

Passwords need at least fourteen characters and at most seventy-two UTF-8 bytes. Create the one-time file in a private directory, without putting the password in a process argument or shell command. For example, the following generates a 48-character ASCII password with owner-only permissions; record its contents securely in your password manager before use:

```sh
install -d -m 700 "$HOME/.config/busystack-caldav"
(umask 077; openssl rand -hex 24 > "$HOME/.config/busystack-caldav/administrator-password")
```

Use that file's absolute path for `/private/path/administrator-password` in these examples. Remove the one-time file after successful bootstrap and securely recording the credential. Do not apply the database secrets' mode 0444 to it: bootstrap requires an owner-only file. After bootstrap, use the account interface to enroll a passkey and complete password plus WebAuthn MFA before issuing DAV credentials or changing collections, sharing, recovery codes and security settings. The interface also provides session and OAuth grant management; see [account security](account-security.md).

For the Compose deployment, run the same local operation inside its private network. Mount an existing owner-only password file and run the one-time process with the host operator's UID so its permission check remains meaningful:

```sh
docker compose stop application
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" \
  --volume /private/path/administrator-password:/bootstrap-password:ro \
  application --spring.main.web-application-type=none \
  --busystack.bootstrap=true \
  --busystack.bootstrap-username=administrator \
  --busystack.bootstrap-password-file=/bootstrap-password
docker compose up -d application
```

The image must already be built and the database healthy. The command refuses to replace any existing account. Do not set bootstrap flags on the ordinary long-running application service.

## First browser sign-in and user creation

1. Open the configured public HTTPS address in a browser that trusts its certificate. Use the final hostname: passkeys are bound to its relying-party ID and origin. At **Sign in to BusyStack**, enter the bootstrap username/password and choose **Verify password**.
2. Follow **enroll your first passkey** or open **Passkeys and authentication**. Give it a name, choose **Register passkey**, and complete the authenticator's user-verification prompt. Choose **Verify passkey to complete MFA** for the same account. Registration alone is not the completed two-factor sign-in.
3. Return to **Account management**, then open **Administration**. Under **Provision an account**, enter the new user's username, display name and initial password and choose **Create account**. Usernames start with an ASCII letter or digit and contain only ASCII letters, digits, `.`, `_` or `-`, up to 64 characters. Passwords use the limits above. The new account is an ordinary user; administrator status does not grant access to that user's private calendars.
4. Give the initial credential to the intended user through a secure channel. Sign out of the administrator session. The new user signs in and enrolls/verifies their own passkey using the same steps. Account changes require both factors verified within ten minutes. When they become stale, return to `/login` and use **Verify password** and **Verify passkey** for the same account. For an already enrolled account, the passkey settings page requires that recent MFA and redirects to login until it is complete.
5. On the user's account page, use **Create a collection** to create an **Events** collection and a separate **Tasks** collection. Their displayed component types are `VEVENT` and `VTODO`. The account interface manages collections and access; create and edit the actual events/tasks in a CalDAV client.
6. Under **DAV credentials**, enter a device/application label and a lifetime from 1 to 365 days, then choose **Issue credential**. The **New DAV credential** page shows the complete secret once. Copy it directly into that client's password store; do not use the account password. Losing the secret requires issuing a replacement, not retrieving the old one. Return to the account page to revoke a device's credential when needed.

Keep a second enrolled authenticator or securely stored recovery codes where possible. A virtual-authenticator test does not establish that a particular physical device or synchronized passkey provider works; check that device before relying on it. The documented offline recovery procedure remains available to the server operator.

## Client access and sync compatibility

After browser primary authentication and passkey verification, issue a named DAV credential for each application/device. Configure the client with the public HTTPS CalDAV endpoint, `<public-base-url>/dav/`, the account username and that credential. The account password does not authenticate DAV requests. Revocation, expiry, local disabling and collection permissions are enforced for each client access; OAuth onboarding is a separate registered-client workflow described in [OAuth configuration](oauth.md).

The account page's **Connect a CalDAV client** section supplies the full server address and username, including any configured deployment prefix. Copy those values rather than inventing a collection UUID or removing the prefix. In a client that supports CalDAV discovery, select a network CalDAV account/calendar, supply those values and the issued DAV secret, then select the discovered event and task collections. Clients may expose `VTODO` collections in a separate task view. Trust the server's normal certificate chain; do not disable certificate or hostname checking to connect.

The release exercise inspected Thunderbird 157.0.1's graphical setup screens. For that version:

1. Open the **Calendar** view and create a new calendar, choosing **On the Network**.
2. Copy **Username** from the BusyStack account page and paste its exact displayed DAV server address into **Location**. Leave **This location doesn't require credentials** unchecked, then choose **Find Calendars**.
3. In the password prompt, enter the named DAV credential, not the BusyStack account password. Optionally choose **Use Password Manager** to retain it for later synchronization.
4. Choose **CalDAV**, select the discovered event and task collections, and choose **Subscribe**. Use the calendar view for events and the task view for the `VTODO` collection.

These labels and discovery steps are version-scoped; see the release record for completed content, synchronization and restart checks. Other client versions may present different controls.

**Observed Thunderbird 157.0.1 event limitation:** in the release exercise, the default **Use system timezone** selection resolved to `America/Vancouver` using 2026b timezone data. A new event's embedded `VTIMEZONE` contained six `STANDARD`/`DAYLIGHT` recurrence rules with local `UNTIL` values. Those observance boundaries must use UTC under [RFC 5545 §3.3.10](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.3.10). The server rejected the exact original event with `403 CALDAV:valid-calendar-data`, and subsequent GET remained 404. The event was not saved on the server; do not treat the editor's local state as successful synchronization. An undated task was accepted in the same client exercise.

**Bounded verified UTC profile:** before creating events in Thunderbird 157.0.1, open **Settings → Calendar**, choose **Set timezone manually**, and select **Etc/UTC** in **Timezone** instead of **Use system timezone**. The release exercise used those actual settings controls, then the real event/task editors, password prompt and calendar wizard. Event/task creation and editing, task completion, **Reload Remote Calendars**, deletion and confirmation dialogs passed, with independent server reads verifying the resulting content. Restarting both PostgreSQL and the application preserved authoritative bytes and ETags; restarting the same private Thunderbird profile reused its saved credential and retained those resources before their verified deletion. The original accepted undated task from the failed default-timezone exercise remained separate from these UTC test resources.

These were automated interactions with an actual headed client, not a person manually operating it. The release record and screenshots retain both the failure and positive profile. Prior programmatic provider tests using controlled UTC, date-only and floating values do not establish ordinary default-timezone graphical interoperability. The failure applies to the captured `America/Vancouver` representation, not every timezone or Thunderbird version; other zones remain unverified. BusyStack does not silently repair client timezone data or weaken validation. Confirm the selected profile and a server round trip before relying on the client for new events.

Verify first use with a small event and task: create, synchronize, edit from another connected client or profile, synchronize again, complete a task, and delete test data. Confirm persistent data remains after `docker compose restart application`. This restart retains the database volume; `docker compose down --volumes` destroys that project's data and is not an update or restart command.

The independently tested python-caldav 3.6.0 and Thunderbird 157.0.1 providers send HTTP `Depth: 1` for synchronization, while RFC 6578 requires `Depth: 0`. The narrowly constrained accommodation is enabled by default for both the executable JAR and Compose. Ordinary deployments need no override. To select strict RFC depth validation, explicitly disable it and restart:

```sh
export BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE=false
docker compose up -d application
```

The default accepts `Depth: 1` only with XML sync-level 1. Missing Depth and `Depth: 0` retain standard behavior; explicit false rejects nonzero depth. It preserves collection scope, permissions, snapshot/token semantics and limits; invalid, duplicate and infinite Depth remain rejected. It is not literal compliance with that RFC requirement. The exact client/provider operations and native desktop limitations are in [independent-client qualification](../qualification/clients/README.md). Standard discovery and OAuth registration remain distinct.

## Health, diagnostics and limits

`/actuator/health/liveness` and `/actuator/health/readiness` return probe status without details. The Compose application health check calls readiness locally. Readiness includes the database check. Only health endpoints are exposed by Actuator; there is no public diagnostic dump.

The database connection pool is bounded to 10 connections; connection acquisition times out after 10 seconds. Servlet form bodies are limited to 64 KiB, accepted calendar objects to 1 MiB in the persistence schema, and the container temporary filesystem to 128 MiB. DAV request bodies are streamed with a 1 MiB cap; compressed Content-Encoding is rejected with 415 rather than decompressed. The principal protocol/query limits are below; exceeding a budget returns an explicit failure rather than incomplete matching data. These are work bounds, not measured capacity guarantees.

| Boundary | Current limit |
| --- | --- |
| DAV XML depth / elements / attributes per element | 32 / 20,000 / 100 |
| Accepted iCalendar component depth / components | 8 / 2,000 |
| iCalendar properties / parameter values | 20,000 / 40,000 |
| Query filter nodes / range days | 256 / 3,660 |
| Generated recurrence instances / rule increments / candidate work | 10,000 / 900 / 500,000 |
| Matched calendar-query resources | 10,000 |
| Complete DAV collection grant listing / ACL replacement | 1,000 direct/group entries; browser sharing uses separate paged management |
| Serialized XML response and cumulative report-copy/expansion reservations | 16 MiB (conservative traversal reservations may fail earlier) |
| Response DOM construction / nodes / concurrent DAV work | 16 MiB conservative work / 50,000 nodes / 4 requests |
| Dead properties per target / individual XML / aggregate XML | 1,000 / 64 KiB / 1 MiB |
| DAV namespace after `/dav/` | 20 segments including `calendars`, principal UUID and resource leaf; 4,096 encoded bytes, excluding the separately validated deployment prefix |
| Complete PROPFIND traversal / query candidate traversal | 10,000 nodes / 100,000 nodes; explicit limit errors, without fabricated PROPFIND pagination |
| Synchronization page | At most 10,000 returned members; larger valid client ceilings are accepted and capped, further bounded by actual selected XML and DOM work; 4 MiB retained bodies and 4 MiB metadata work |

The fixed query budgets are maintained in `QueryLimits.defaults()`, with additional timezone and aggregate report guards. Raising a limit requires renewed hostile-input and memory qualification.

Existing non-recurring components, consumed one resource at a time, spend cumulative candidate work rather than generated-instance capacity. Calendar-query counts actual matching resources separately. Recurrence generation and retained free/busy periods retain their existing instance guards; calendar-data projection separately bounds returned components. Unrelated originals therefore cannot exhaust a narrow query's result capacity merely by exceeding 10,000 stored resources. Exceeding computation or actual response limits still fails explicitly.

Property writes enforce the aggregate limit under storage locks and V11 database guards. Existing oversized property sets remain stored: explicit small-value and name-only requests remain usable, while oversized all-property requests fail before retrieving all XML. Removal and nonincreasing repairs remain possible. Property names and selected values use separate SQL projections, including historical synchronization properties. Calendar bodies are fetched only when needed. Queries and free/busy scan at most 16 bodies / 4 MiB per batch; collection timezones are read directly and reused in a bounded cache.

XML construction reserves node, attribute, UTF-16 and escaped UTF-8 work before allocating output subtrees. Responses are completed before HTTP success is committed. When four DAV operations are already active, further DAV work receives `503` with `Retry-After: 1` before dispatch or mutation. The default ten-connection pool also leaves room for four report snapshots plus their short independent cursor writes. The integration suite runs with a 256 MiB heap; this is verification evidence, not a production capacity guarantee.

Each namespace segment is limited to 255 UTF-16 units. Semicolons and Unicode line/paragraph separators are rejected consistently with the existing HTTP firewall, including in Destination headers; controls, slash/backslash and dot segments remain rejected. U+FFFE and U+FFFF are also rejected because resource names appear in XML 1.0 property values. This narrow check preserves valid character data such as U+FDD0 and supplementary U+1FFFE; it does not reject Unicode noncharacters as a category. Calendar admission reserves one segment and the worst permitted encoded resource leaf, so a successfully created calendar can accept ordinary child resources. Storage, HTTP paths and database guards share these bounds. COPY/MOVE preflight all resulting descendant URLs and copied property budgets under transaction locks before replacing a destination. Collection depth is measured separately from the configured reverse-proxy prefix.

Account, configured OIDC account, collection and group display labels must also be representable as XML 1.0 character data. Existing naming and control-character policies still apply. A legacy stored label containing an invalid XML character causes an explicit server error before response text is attached; it is not silently rewritten or included in a malformed successful XML response. Metadata requests that do not select the invalid label remain usable.

Application logs must remain free of authorization headers, credential secrets, recovery data, and calendar content. Use container exit status, migration validation failures, health status and database diagnostics when investigating startup. No external telemetry service is required to serve the application.

## Synchronization history and database growth

Resource change and deletion history is retained indefinitely by default. There is no automatic time-based pruning job or operator pruning API. Collection permission changes rotate the collection generation; restoration rotates the global recovery epoch and invalidates all tokens while retaining collection identity. `CalendarStore.pruneHistory` has transactional tests for the collection lock and minimum retained revision, but its existence does not enable scheduled expiry.

V10 retains immutable calendar representations and indexed historical property values. Initial and incremental synchronization freeze a revision watermark and page through names with a durable scoped cursor. Truncated reports return `207`, a collection-level `507` entry and a continuation token; the final token advances only to the original watermark. Concurrent updates, deletions and renamed/recreated names are returned in the following traversal. [ADR 0004](adr/0004-resumable-synchronization.md) specifies the algorithm, selected-response accounting and retry invariants. No aggregate calendar-body quota prevents an accepted collection from being synchronized.

Continuation state expires after 24 hours and is checked against current access, collection generation, actor, recovery epoch and retention state on every request. A separate read-committed cleanup removes at most 1,024 expired states per minute; expiry does not depend on cleanup. A change to a collection's default timezone invalidates existing tokens. Revoked access cannot use old continuation state. Interrupted requests may leave an unused cursor, which confers no access and expires normally.

Monitor database/volume growth, `pg_total_relation_size` for `calendar_resources`, `collection_changes`, `calendar_resource_versions`, `calendar_resource_version_properties` and `sync_continuations`, row counts, dead tuples and ordinary PostgreSQL vacuum statistics. Repeated edits/deletions add historical bodies and properties even when current resource count is stable. Recurrence rules are stored as submitted resources rather than materialized into an unbounded occurrence table. Retention needs storage planning and verified backups.

Keep history intact under the supported default. Any future operator pruning interface must perform deletion and the minimum-token update atomically under the same per-collection lock; removing history rows alone cannot provide complete deltas to old clients. No unsupported manual pruning automation is prescribed here.

## Backup and isolated restoration

The database contains account/principal mappings, ownership, memberships, calendar representations, revisions, history, DAV credential verifiers, and the implemented OAuth persistence tables. Back up it and the deployment configuration/secret files. Store encrypted backups outside the server, control access, and periodically test restoration. The selected OAuth access-token model uses opaque reference tokens, so it does not require a JWT signing-key file. Any future cryptographic material must be added to the recovery contract before functionality relying on it is released.

From the checkout with the same Compose configuration environment:

```sh
install -d -m 700 /private/backups
./scripts/backup.sh /private/backups/caldav-2026-10-27.dump
```

The script creates a consistent PostgreSQL custom-format archive and a SHA-256 checksum with private file permissions. It refuses to overwrite an existing archive. Retain the exact application version, Compose configuration, and secret material separately. Never publish backups as CI artifacts.

Restore only to a newly initialized, isolated database. Stop every application instance first. The script accepts a complete, unique, successful migration history from V1 through at most V13; failed, missing, duplicate, gapped and future histories fail closed. It refuses a running application or any nonempty target database and never removes an existing volume or database:

```sh
docker compose stop application
./scripts/restore.sh /private/backups/caldav-2026-10-27.dump
```

After loading an archive at schema versions 1 through 13, restoration disables all accounts, changes the durable `system_state.recovery_epoch`, deletes all restored DAV credentials, passkeys and OAuth authorizations/consents, and revokes refresh-token families. It also deletes JDBC browser sessions, single-use recovery codes, WebAuthn ceremony records and login-throttle state, rotates every account security epoch and records the restore in the security audit. It preserves external issuer/subject mappings but expires their upstream-authentication timestamps. Sync tokens include the changed epoch; pre-restore tokens must be rejected. Disabling restored accounts and removing restored credentials/grants prevents passwords or credentials changed/revoked after the backup from silently becoming valid again. DAV bindings, folders, dead properties, sharing groups, group memberships/grants and the retired-principal identity registry remain authoritative data and are retained. Registered OAuth clients remain registered, but users must authorize fresh grants. If loading or invalidation fails, leave the application stopped and investigate. This script refuses later migrations until the recovery policy is updated for new persisted security state.

Keep **all** application instances stopped while loading and invalidating restored state. For a supported older archive, including the reviewed V9 schema, the current image can then start with every account disabled to apply forward migrations; stop it again before controlled account recovery. This is an offline upgrade arrangement, not a rolling upgrade. Do not start another older application instance against the upgraded database. The required operations harness exercises both same-version restoration and V9 restoration followed by migration to V13.

After independently verifying a user's identity, use the controlled local recovery operation with an owner-only file containing a fresh password:

```sh
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" \
  --volume /private/path/new-password:/recovery-password:ro \
  application --spring.main.web-application-type=none \
  --busystack.recover-account=true \
  --busystack.recovery-username=administrator \
  --busystack.recovery-password-file=/recovery-password
```

The operation enables the selected **local** account with its new password and removes its prior client credentials, OAuth grants and passkeys. It rejects externally managed identities; do not convert a federated account to local-password login during recovery. For a restored federated account, verify the trusted issuer and immutable subject independently, then run the matching operation without a password:

```sh
docker compose run --rm --no-deps application \
  --spring.main.web-application-type=none \
  --busystack.recover-external-account=true \
  --busystack.recovery-username=organization-user \
  --busystack.recovery-issuer=https://identity.example.org/realms/organization \
  --busystack.recovery-subject=verified-existing-subject
```

This operation requires the existing issuer/subject pair to match exactly, expires prior upstream verification, clears client access/factors and enables only that account. The next interactive authentication must use the trusted upstream provider and fresh passkey enrollment. It does not create a local password or invent an identity mapping. The operations smoke test checks the offline decision and rejected subject mismatch. A separate final HTTPS profile passed fresh organization login/passkey enrollment against the same still-running real provider after restoration; old credentials/sync state and unauthorized shared writes remained rejected.

Recover each account explicitly after identity verification; do not run a blanket SQL enable operation. Review restored administrative roles, sharing permissions and OAuth client registrations before allowing access: an older dump cannot know about later permission or registration changes. Users must enroll fresh passkeys and reauthorize clients. Securely remove the temporary password files. Recovery is an operator-controlled local process, without an email-service dependency or a public bypass endpoint. The executed authentication/restore evidence does not constitute independent security review. Any future persisted security state must be included in this policy before its feature is released.

A full restore qualification must verify users, ownership, resource bytes, sharing, authentication, credential revocation, and invalidated synchronization tokens through the running application. A `pg_restore` exit code alone does not pass that acceptance requirement. See the maintained test evidence for what has actually executed.

## Upgrades

Take and verify a backup, retain the previous application artifact/configuration, stop the application, select the intended source revision, and build the new image before starting it against the existing database volume. Do not change dependency or base-image pins as an incidental part of an application update. Run migration validation and the qualification suite against an isolated restored copy before deployment. For the source-based Compose arrangement, after that rehearsal the application replacement is:

```sh
docker compose stop application
docker compose build --build-arg SOURCE_REVISION="$(git rev-parse HEAD)" application
docker compose up -d --no-deps application
docker compose ps
```

Keep the same Compose project, secret paths and public URL. Inspect startup/migration status and verify discovery, an existing resource and synchronization before reopening access. Flyway applies versioned migrations and validates their checksums at startup; production schema auto-mutation is not enabled. Migrations are forward-only unless a particular migration has a separately tested reversal. Rolling back a JAR does not safely roll back a database schema. Version 0.0.1 uses migrations V1–V13 and introduces no migration during final release preparation.

The native Thunderbird and Python client results are scoped to their recorded versions and interoperability profile; see [client evidence](../qualification/clients/README.md). This Compose arrangement provides no high availability, automatic failover or measured user-capacity guarantee. The [executed workload record](performance.md) reports the actual host/image/dataset, end-to-end latency, sampled memory/CPU and PostgreSQL behavior; raw evidence is retained separately. It is one sequential client, not a user-capacity or concurrency guarantee.
