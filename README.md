# BusyStack CalDAV Server

Independent, self-hosted Java server for calendars and tasks, with PostgreSQL persistence and built-in accounts. Local deployment works without another identity service; an existing Keycloak installation can provide optional OIDC login.

The final runtime passed 149 unit and 134 PostgreSQL/HTTPS integration tests, with no skips and zero SpotBugs findings. Packaged qualification passed startup/restart, trusted-CA root and reverse-proxy deployments, real browser passkey/MFA/recovery/OAuth workflows, real cross-site Keycloak login, and local/federated backup restoration with credential/token invalidation and shared task access. [Exact artifact, commands and results](qualification/evidence/README.md) are retained alongside the [standards matrix](docs/conformance.md).

## Capabilities

* Multiple event and VTODO collections, authoritative accepted iCalendar bytes, recurrence/exceptions/custom time zones, calendar-query/multiget/freebusy and required principal/ACL reports.
* Strong ETags, HTTP/date/DAV write conditions, atomic properties and namespace operations, transactionally ordered revisions and WebDAV synchronization/deletions.
* Local passwords, persistent passkeys, password-plus-passkey MFA, single-use recovery, durable sessions, throttling and controlled administrator provisioning without default credentials.
* Named revocable DAV credentials; embedded OAuth code/S256 PKCE, opaque access tokens and persistent public-native refresh rotation/replay protection. Administrators register clients without source changes.
* Stable principal identities, owner-controlled individual/group READ/WRITE sharing, and focused server-rendered account/administration pages. Administration alone does not grant calendar data access.
* Optional standard OIDC with exact issuer/subject mappings, protected linking, closed provisioning and a documented upstream reauthentication/revocation boundary.

Stored organizers, attendees, attachments and alarms do not execute invitations, URL retrieval, scheduling, email, push or reminders. The account interface is not a web calendar editor. This project does not provide CardDAV or a general file service and does not depend on BusyMax or busystack_dav.

## Build and deployment

Selected baseline: Temurin Java 25.0.4.1+1, Spring Boot 4.1.2 with managed Spring Security 7.1.2, PostgreSQL 18.6 and iCal4j 4.4.0. [Exact versions and licenses](docs/versions.md) include the documented BSD parser/timezone corrections. Maven Wrapper, plugins and container images are pinned; host/container executable JAR bytes were verified identical.

```sh
python3 -m venv .client-venv
.client-venv/bin/pip install -r qualification/clients/requirements.txt -r qualification/clients/desktop-requirements.txt
bash qualification/clients/install-thunderbird.sh .client-tools
BUSYSTACK_CLIENT_PYTHON="$PWD/.client-venv/bin/python" \
BUSYSTACK_THUNDERBIRD_BINARY="$PWD/.client-tools/thunderbird/thunderbird" ./mvnw verify
docker build --tag busystack-caldav:local .
```

Java 25, Docker and the declared integration prerequisites are required; unavailable required fixtures fail visibly. CI also checks formatting/static analysis, runtime advisories with a vulnerable positive control, SBOM generation, packaged deployment and browser/restore profiles.

Follow [operations](docs/operations.md) for the application-plus-PostgreSQL Compose deployment, HTTPS root/subpath proxy configuration, offline administrator bootstrap, secret files, upgrades and safe recovery. Restoring older state disables accounts and removes restored client/factor/session access before verified individual recovery. [Measured workload results](docs/performance.md) describe 270 synthetic resources on a shared host, including query latency, sampled memory/CPU and database behavior; no capacity or high-availability guarantee is inferred.

## Connect clients and manage access

Sign in through the account interface, enroll a passkey, complete MFA, create event/task collections and issue a named DAV credential. Use the displayed server address and username with that credential as the client's password. Capable registered clients can use browser OAuth instead; the account password is not the default DAV credential.

Python-caldav 3.6.0 and Thunderbird 157.0.1's actual native desktop provider passed calendar/task CRUD, recurrence/alarm/extension round trips, task completion and synchronization, including four-resource cross-client edits. These versions require the explicit `BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE=true` compatibility setting before connecting. The default remains strict RFC 6578 Depth 0; the tested Depth 1 behavior is a documented deviation. Desktop qualification used an isolated headless native provider, without a manual graphical account-wizard claim. Physical authenticators and additional client platforms remain unexecuted.

See [client qualification](qualification/clients/README.md), [account security](docs/account-security.md), [passkeys/MFA](docs/passkeys-and-mfa.md), [OAuth registration](docs/oauth.md), [optional Keycloak](docs/keycloak.md), [sharing groups](docs/groups.md), [security verification](docs/security-verification.md), and [architecture decisions](docs/adr/0001-protocol-engine.md). Security self-assessment is not external certification.

The owner has not selected the repository's project license. That remains a public-distribution blocker; no license is inferred from dependencies. [Third-party inventory and notices](docs/third-party-licenses.md) record their separate licensing evidence and remaining distribution review.
