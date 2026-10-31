# BusyStack CalDAV Server 0.0.1

Independent, self-hosted Java server for calendars and tasks, with PostgreSQL persistence and built-in accounts. Local deployment works without another identity service; an existing Keycloak installation can provide optional OIDC login.

See the [0.0.1 release notes](docs/releases/0.0.1.md) for the supported profile and limitations, and the [release verification record](qualification/evidence/releases/0.0.1/verification.json) for exact source/artifact identities, installation results and CI evidence. Release preparation starts from the audited correction baseline `a919d632`; it does not publish a tag, GitHub release or container image. Build from this checkout using the instructions below.

The [standards correction submission](docs/standards-corrections.md) preserves the 17 original audit finding groups, subsequent residual corrections and their counterexamples. The [standards matrix](docs/conformance.md) connects capabilities and remaining verification limits to implementation and regression coverage. These records are not certification or a claim of universal client compatibility.

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
BUSYSTACK_THUNDERBIRD_BINARY="$PWD/.client-tools/thunderbird/thunderbird" ./mvnw -B -ntp clean verify
docker build --build-arg SOURCE_REVISION="$(git rev-parse HEAD)" --tag busystack-caldav:0.0.1 .
```

Java 25, Docker and the declared integration prerequisites are required; unavailable required fixtures fail visibly. CI also checks formatting/static analysis, runtime advisories with a vulnerable positive control, SBOM generation, packaged deployment and browser/restore profiles.

The executable artifact is `target/caldav-server-0.0.1.jar`; the command above builds a local `busystack-caldav:0.0.1` image. Their licenses, notices and runtime SBOM are included by the existing packaging. Follow [operations](docs/operations.md) for the application-plus-PostgreSQL Compose deployment, HTTPS root/subpath proxy configuration, offline administrator bootstrap, first user, client connection, updates and safe recovery. Restoring older state disables accounts and removes restored client/factor/session access before verified individual recovery. [Workload records](docs/performance.md) retain earlier measurements; release preparation does not establish a capacity or high-availability guarantee.

## Connect clients and manage access

Sign in through the account interface, enroll a passkey, complete MFA, create event/task collections and issue a named DAV credential. Use the displayed server address and username with that credential as the client's password. Capable registered clients can use browser OAuth instead; the account password is not the default DAV credential.

The audited baseline qualified python-caldav 3.6.0 and Thunderbird 157.0.1's native desktop provider for calendar/task CRUD, recurrence/alarm/extension round trips, task completion and synchronization, including four-resource cross-client edits. The release record identifies their fresh results for the final artifact and distinguishes browser automation, native provider tests and manual checks that could not be performed. The default accepts narrowly constrained `Depth: 1` sync reports only with XML sync-level 1; standard missing/Depth 0 headers remain supported. This is a documented RFC 6578 deviation. Set `BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE=false` for strict mode. Physical authenticators and additional client platforms remain separately unverified.

**Timezone input compatibility:** the default [local observance UNTIL repair](docs/observance-until-compatibility.md) narrowly corrects the captured Thunderbird timezone defect, strictly validates the corrected representation and persists those bytes. Already-valid input remains unchanged; unrelated invalid data is rejected. Set `BUSYSTACK_DAV_REPAIR_LOCAL_OBSERVANCE_UNTIL=false` to restore rejection of this defect. This is a documented input exception to RFC 4791 §5.3.2.1, separate from sync Depth compatibility. No forced UTC client setup is required by the repair. Client qualification remains version-scoped; see the release evidence for actual workflows and remaining limits. Original rejection and UTC-profile results are preserved as historical evidence.

See [client qualification](qualification/clients/README.md), [account security](docs/account-security.md), [passkeys/MFA](docs/passkeys-and-mfa.md), [OAuth registration](docs/oauth.md), [optional Keycloak](docs/keycloak.md), [sharing groups](docs/groups.md), [security verification](docs/security-verification.md), and [architecture decisions](docs/adr/0001-protocol-engine.md). Security self-assessment is not external certification.

The project is licensed under [Apache License 2.0](LICENSE). [Third-party inventory and notices](docs/third-party-licenses.md) record separate dependency licenses, resolved upstream metadata provenance and actual source/JAR/container notice delivery.
