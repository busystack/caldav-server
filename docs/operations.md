# Operations

This deployment runs one application and PostgreSQL. Automated evidence includes real HTTPS DAV, account-security/native OAuth, optional real Keycloak, and independent calendar/task client operations. The [conformance matrix](conformance.md) and [artifact qualification records](../qualification/evidence/README.md) record completed packaged recovery/browser checks and explicitly unexecuted environments. The owner-selected project license is still a public-distribution requirement.

## Build and verification

Install Java 25 and `unzip`, then use the checked-in wrapper. Keep `.mvn/jvm.config` in the checkout: it pins the build JVM to UTC so executable JAR timestamp metadata is reproducible across host timezones. It does not change application timezone semantics.

```sh
python3 -m venv .client-venv
.client-venv/bin/python -m pip install -r qualification/clients/requirements.txt -r qualification/clients/desktop-requirements.txt
bash qualification/clients/install-thunderbird.sh .client-tools
BUSYSTACK_THUNDERBIRD_BINARY="$PWD/.client-tools/thunderbird/thunderbird" \
BUSYSTACK_CLIENT_PYTHON="$PWD/.client-venv/bin/python" ./mvnw --batch-mode --no-transfer-progress verify
docker build --tag busystack-caldav:local .
python3 scripts/verify-operations.py --image busystack-caldav:local
```

`verify` includes the required PostgreSQL integration suite; Docker must be available. Integration prerequisites must fail visibly when absent. The shell-only Maven wrapper requires `unzip` for its SHA-256-pinned ZIP distribution; its upstream tar fallback cannot use the ZIP checksum. The Docker build uses the identical Maven 3.9.12 from its pinned image directly. The container build packages the executable JAR; it skips tests because the full suite runs separately. An image build alone is not qualification.

The Python 3 operations harness requires Compose 2.24.4 or newer for the port override. It creates two randomly named disposable Compose projects, bootstraps an account locally, checks the packaged application's probes/discovery/issuer and DAV challenge, restarts it, seeds database-recovery fixtures, then backs up and restores to the second database. It verifies data/ownership/sharing, restored security-state invalidation and controlled local/external offline recovery, and removes only its own projects and volumes. It never prints generated secrets. Its SQL-seeded fixtures are database procedure evidence, not independent CalDAV-client or passkey-authentication evidence. HTTPS/proxy/browser workflows require separate end-to-end tests.

CI checks formatting, compilation/static analysis, unit/integration tests, packaging, container construction, SBOM generation, and a required public OSV advisory check with a known-vulnerable positive control. Feed failures, incomplete pagination and detected advisories fail the gate. An additional NVD audit runs only when its credential is configured; absence is recorded as unexecuted. The attempted anonymous NVD run failed before analysis. See [advisory-check scope and evidence](../qualification/security/README.md). Neither a successful advisory lookup nor this self-assessment is an independent security review.

## Two-service Compose arrangement

Requirements: Docker Engine with Compose v2, enough disk for PostgreSQL and backups, and an HTTPS reverse proxy operated on the same host. No Redis, Keycloak, LDAP, PHP, or frontend service is part of the default arrangement.

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
docker compose up --build -d
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
    gzip off;
    # Insert the matching root or /calendar locations below.
}
```

Obtain and renew that certificate through your operator's CA/ACME procedure, protect its private key and test renewal before deployment. The application image contains no production certificate or trust bypass. Run the proxy's configuration validation before reloading it.

Configured public and issuer URLs must use canonical ASCII deployment prefixes such as `/calendar`; literal or percent-encoded Unicode prefixes, semicolons and invalid ports are rejected at startup. Unicode names in calendar/resource segments remain supported. The configured path becomes the servlet context and must be preserved by the proxy.

Disable compression at the application and reverse proxy so strong GET validators identify the representation served. For an Nginx hostname-root arrangement, the relevant locations are:

```nginx
gzip off;
location ^~ /actuator/ { return 404; }
location / {
    client_max_body_size 1m;
    proxy_connect_timeout 5s;
    proxy_read_timeout 30s;
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
java -jar target/caldav-server-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --busystack.bootstrap=true \
  --busystack.bootstrap-username=administrator \
  --busystack.bootstrap-password-file=/private/path/administrator-password
```

Use at least the password length accepted by account validation. Remove the one-time password file after securely recording the credential. After bootstrap, use the account interface to enroll a passkey and complete password plus WebAuthn MFA before issuing DAV credentials or changing collections, sharing, recovery codes and security settings. The interface also provides session and OAuth grant management; see [account security](account-security.md) and the executed qualification evidence.

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

## Client access and sync compatibility

After browser primary authentication and passkey verification, issue a named DAV credential for each application/device. Configure the client with the public HTTPS CalDAV endpoint, `<public-base-url>/dav/`, the account username and that credential. The account password does not authenticate DAV requests. Revocation, expiry, local disabling and collection permissions are enforced for each client access; OAuth onboarding is a separate registered-client workflow described in [OAuth configuration](oauth.md).

The independently tested python-caldav 3.6.0 and Thunderbird 157.0.1 providers send HTTP `Depth: 1` for synchronization, while RFC 6578 requires `Depth: 0`. Strict mode is the default. To use those tested incremental-sync profiles, explicitly enable the documented compatibility deviation and restart the application:

```sh
export BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE=true
docker compose up -d application
```

This accepts `Depth: 1` only with XML sync-level 1. It preserves collection scope, permissions, snapshot/token semantics and limits; invalid, duplicate and infinite Depth remain rejected. It is not literal compliance with that RFC requirement. The exact client/provider operations and native desktop limitations are in [independent-client qualification](../qualification/clients/README.md). Standard discovery and OAuth registration remain distinct.

## Health, diagnostics and limits

`/actuator/health/liveness` and `/actuator/health/readiness` return probe status without details. The Compose application health check calls readiness locally. Readiness includes the database check. Only health endpoints are exposed by Actuator; there is no public diagnostic dump.

The database connection pool is bounded to 10 connections; connection acquisition times out after 10 seconds. Servlet form bodies are limited to 64 KiB, accepted calendar objects to 1 MiB in the persistence schema, and the container temporary filesystem to 128 MiB. DAV request bodies are streamed with a 1 MiB cap; compressed Content-Encoding is rejected with 415 rather than decompressed. The principal protocol/query limits are below; exceeding a budget returns an explicit failure rather than incomplete matching data. These are work bounds, not measured capacity guarantees.

| Boundary | Current limit |
| --- | --- |
| DAV XML depth / elements / attributes per element | 32 / 20,000 / 100 |
| Accepted iCalendar component depth / components | 8 / 2,000 |
| iCalendar properties / parameter values | 20,000 / 40,000 |
| Query filter nodes / range days | 256 / 3,660 |
| Recurrence instances / rule increments / candidate work | 10,000 / 900 / 500,000 |
| Serialized XML response and cumulative report-copy/expansion reservations | 16 MiB (conservative traversal reservations may fail earlier) |

The fixed query budgets are maintained in `QueryLimits.defaults()`, with additional timezone and aggregate report guards. Raising a limit requires renewed hostile-input and memory qualification.

Application logs must remain free of authorization headers, credential secrets, recovery data, and calendar content. Use container exit status, migration validation failures, health status and database diagnostics when investigating startup. No external telemetry service is required to serve the application.

## Synchronization history and database growth

Resource change and deletion history is retained indefinitely by default. There is no automatic time-based pruning job or operator pruning API. Token generations still change for collection identity/permission changes and restoration. `CalendarStore.pruneHistory` has transactional tests for the collection lock and minimum retained revision, but its existence does not enable scheduled expiry.

Monitor database/volume growth, `pg_total_relation_size('calendar_resources')`, `pg_total_relation_size('collection_changes')`, row counts, dead tuples and ordinary PostgreSQL vacuum statistics. Repeated edits/deletions add history even when current resource count is stable. Recurrence rules are stored as submitted resources rather than materialized into an unbounded occurrence table. Retention needs storage planning and verified backups.

Keep history intact under the supported default. Any future operator pruning interface must perform deletion and the minimum-token update atomically under the same per-collection lock; removing history rows alone cannot provide complete deltas to old clients. No unsupported manual pruning automation is prescribed here.

## Backup and isolated restoration

The database contains account/principal mappings, ownership, memberships, calendar representations, revisions, history, DAV credential verifiers, and the implemented OAuth persistence tables. Back up it and the deployment configuration/secret files. Store encrypted backups outside the server, control access, and periodically test restoration. The selected OAuth access-token model uses opaque reference tokens, so it does not require a JWT signing-key file. Any future cryptographic material must be added to the recovery contract before functionality relying on it is released.

From the checkout with the same Compose configuration environment:

```sh
install -d -m 700 /private/backups
./scripts/backup.sh /private/backups/caldav-2026-10-27.dump
```

The script creates a consistent PostgreSQL custom-format archive and a SHA-256 checksum with private file permissions. It refuses to overwrite an existing archive. Retain the exact application version, Compose configuration, and secret material separately. Never publish backups as CI artifacts.

Restore only to a newly initialized, isolated database using the same application schema version. Stop the application first. The script refuses a running application or any nonempty target database; it never removes an existing volume or database:

```sh
docker compose stop application
./scripts/restore.sh /private/backups/caldav-2026-10-27.dump
```

After loading an archive at schema versions 1 through 9, restoration disables all accounts, changes the durable `system_state.recovery_epoch`, deletes all restored DAV credentials, passkeys and OAuth authorizations/consents, and revokes refresh-token families. It also deletes JDBC browser sessions, single-use recovery codes, WebAuthn ceremony records and login-throttle state, rotates every account security epoch and records the restore in the security audit. It preserves external issuer/subject mappings but expires their upstream-authentication timestamps. Sync tokens include the changed epoch; pre-restore tokens must be rejected. Disabling restored accounts and removing restored credentials/grants prevents passwords or credentials changed/revoked after the backup from silently becoming valid again. DAV bindings, folders, dead properties, sharing groups, group memberships/grants and the retired-principal identity registry remain authoritative data and are retained. Registered OAuth clients remain registered, but users must authorize fresh grants. If loading or invalidation fails, leave the application stopped and investigate. This script refuses later migrations until the recovery policy is updated for new persisted security state.

Keep **all** application instances stopped during recovery: no running instance may authenticate against state while it is being restored and invalidated. After independently verifying a user's identity, use the controlled local recovery operation with an owner-only file containing a fresh password:

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

Take and verify a backup, retain the previous application artifact/configuration, stop the application, update pinned images and application code, and run migration validation and the qualification suite against an isolated restored copy before deployment. Flyway applies versioned migrations and validates their checksums at startup; production schema auto-mutation is not enabled. Migrations are forward-only unless a particular migration has a separately tested reversal. Rolling back a JAR does not safely roll back a database schema.

The native Thunderbird and Python client results are scoped to their recorded versions and interoperability profile; see [client evidence](../qualification/clients/README.md). This Compose arrangement provides no high availability, automatic failover or measured user-capacity guarantee. The [executed workload record](performance.md) reports the actual host/image/dataset, end-to-end latency, sampled memory/CPU and PostgreSQL behavior; raw evidence is retained separately. It is one sequential client, not a user-capacity or concurrency guarantee.
