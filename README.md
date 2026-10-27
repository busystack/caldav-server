# BusyStack CalDAV Server

Independent, self-hosted Java server for calendars and tasks, with PostgreSQL persistence and built-in accounts. Keycloak login is optional; the local installation does not require an identity server.

The DAV content adapter is connected. Raw HTTPS/PostgreSQL tests cover calendars and VTODOs, reports, sharing, conditional writes, namespace operations and synchronization. Python-caldav 3.6.0 and Thunderbird 157.0.1’s native desktop provider have exercised event/task CRUD and synchronization. Their tested profile explicitly enables the documented sync Depth compatibility setting; graphical account-wizard qualification is not claimed.

**Product acceptance is still in progress at this checkpoint:** sharing groups, remaining semantic edge regressions, the final combined suite, and HTTPS authentication/synchronization after restoring the final packaged image are being completed. The [conformance matrix](docs/conformance.md) and [qualification evidence](qualification/evidence/README.md) identify executed evidence and remaining work.

## Application

* Stable UUID principals; multiple event/task collections; authoritative accepted iCalendar bytes; strong representation ETags; transactional revisions, deletion history and snapshot sync.
* Local passwords, persistent passkeys, password-plus-passkey MFA, single-use recovery, durable sessions, throttling and administrator provisioning without default credentials.
* Individually revocable DAV credentials; embedded OAuth authorization-code/S256 PKCE, opaque access tokens, persistent native refresh rotation/replay revocation and administrator-controlled client registration.
* Optional upstream Keycloak through standard OIDC, exact issuer/subject mappings and a documented reauthentication/revocation boundary.
* Server-rendered account/administration pages, HTTPS deployment configuration, versioned database migrations, backup/restoration tooling and a non-root container definition.

This is calendar access and free/busy storage/querying. Stored attendees, organizers, attachments and alarms do not execute invitations, external URL retrieval, email, scheduling, notifications or reminders. There is no general file service, CardDAV, proprietary calendar/task API or web calendar editor. BusyMax and busystack_dav are independent projects.

## Build and deploy

Selected runtime: Temurin Java 25.0.4.1+1, Spring Boot 4.1.2 with its managed Spring Security 7.1.2, PostgreSQL 18.6, and iCal4j 4.4.0. Exact pins, compatibility evidence and upstream links are in [versions](docs/versions.md). A narrowly scoped, licensed iCal4j parser correction is [documented separately](docs/ical4j-parser-patch.md).

```sh
python3 -m venv .client-venv
.client-venv/bin/pip install -r qualification/clients/requirements.txt
BUSYSTACK_CLIENT_PYTHON="$PWD/.client-venv/bin/python" ./mvnw verify
```

Java 25 and Docker are required. Missing integration prerequisites fail the build. See [independent-client qualification](qualification/clients/README.md) for the pinned desktop-provider profile and its exact scope. CI also checks formatting, static analysis, runtime dependency advisories and the distributable SBOM.

Follow [operations](docs/operations.md) for validated configuration, HTTPS proxy paths, the two-service Compose deployment, controlled administrator bootstrap, upgrades and recovery. Keep database and application secrets outside the repository/image. See [account security](docs/account-security.md), [passkeys/MFA](docs/passkeys-and-mfa.md), [OAuth registration](docs/oauth.md), [optional Keycloak](docs/keycloak.md), [security verification](docs/security-verification.md), and [architecture decisions](docs/adr/0001-protocol-engine.md).

The repository owner has not selected a project license. That is an unresolved public-distribution requirement; no license has been invented on the owner’s behalf. Third-party notices and the exact runtime inventory are described in [the license inventory](docs/third-party-licenses.md).
