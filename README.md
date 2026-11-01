# BusyStack CalDAV Server 0.0.1

Independent, self-hosted Java server for calendars and tasks, with PostgreSQL persistence and built-in accounts. Local deployment works without another identity service; an existing Keycloak installation can provide optional OIDC login.

Administrator downloads are provided through the [v0.0.1 release](https://github.com/busystack/caldav-server/releases/tag/v0.0.1). Use its [release manifest](https://github.com/busystack/caldav-server/releases/download/v0.0.1/release-manifest.json) for authoritative source, checksums, Linux AMD64 image digest and verification evidence. Read the [release notes](docs/releases/0.0.1.md) for supported configurations and limitations.

The [standards correction submission](docs/standards-corrections.md) preserves the 17 original audit finding groups, subsequent residual corrections and their counterexamples. The [standards matrix](docs/conformance.md) connects capabilities and remaining verification limits to implementation and regression coverage. These records are not certification or a claim of universal client compatibility.

## Capabilities

* Multiple event and VTODO collections, authoritative accepted iCalendar bytes, recurrence/exceptions/custom time zones, calendar-query/multiget/freebusy and required principal/ACL reports.
* Strong ETags, HTTP/date/DAV write conditions, atomic properties and namespace operations, transactionally ordered revisions and WebDAV synchronization/deletions.
* Local passwords, persistent passkeys, password-plus-passkey MFA, single-use recovery, durable sessions, throttling and controlled administrator provisioning without default credentials.
* Named revocable DAV credentials; embedded OAuth code/S256 PKCE, opaque access tokens and persistent public-native refresh rotation/replay protection. Administrators register clients without source changes.
* Stable principal identities, owner-controlled individual/group READ/WRITE sharing, and focused server-rendered account/administration pages. Administration alone does not grant calendar data access.
* Optional standard OIDC with exact issuer/subject mappings, protected linking, closed provisioning and a documented upstream reauthentication/revocation boundary.

Stored organizers, attendees, attachments and alarms do not execute invitations, URL retrieval, scheduling, email, push or reminders. The account interface is not a web calendar editor. This project does not provide CardDAV or a general file service and does not depend on BusyMax or busystack_dav.

## Install

The primary installation is the release's `caldav-server-0.0.1-deploy.tar.gz`, with Docker Engine and Compose v2 (2.24.4+), on **Linux AMD64**. Download the archive, `SHA256SUMS` and `release-manifest.json` from the [release assets](https://github.com/busystack/caldav-server/releases/tag/v0.0.1), verify checksums, extract it into any directory and follow its `INSTALL.md`. Its complete Compose deployment pulls `ghcr.io/busystack/caldav-server:0.0.1` pinned to the manifest's registry digest. No source checkout, Maven or host Java is needed.

Supply your own domain/DNS, trusted HTTPS certificate and reverse proxy using the included complete root/subpath examples. Create separate private file-backed database secrets, start the two services, bootstrap the administrator locally, then provision users, passkeys/MFA, collections and DAV credentials through the browser. The [operations guide](docs/operations.md) covers setup, updates, backup, restoration and removal. PostgreSQL stays private; application HTTP stays on loopback.

The alternative is the release's executable `caldav-server-0.0.1.jar`, requiring Java 25 (qualified Temurin 25.0.4.1+1) and PostgreSQL 18.6. Verify its checksum and follow the operations guide with the downloaded JAR path. Changing an application image or JAR does not reverse database migrations. No native package or additional architecture is provided.

## Developer source builds

Selected versions remain Temurin Java 25.0.4.1+1, Spring Boot 4.1.2, managed Spring Security 7.1.2, PostgreSQL 18.6 and **official unmodified iCal4j 4.4.0**. [Exact versions and licenses](docs/versions.md), [parser integration](docs/ical4j-parser-patch.md) and [timezone integration](docs/ical4j-timezone-patch.md) document provenance. Both former modified implementation copies and patch payloads have been removed. Runtime JAR hashes are independently locked against Maven Central. Developer `compose.yaml` retains source-build support; the administrator bundle uses a generated production configuration.

```sh
python3 -m venv .client-venv
.client-venv/bin/pip install -r qualification/clients/requirements.txt -r qualification/clients/desktop-requirements.txt
bash qualification/clients/install-thunderbird.sh .client-tools
BUSYSTACK_CLIENT_PYTHON="$PWD/.client-venv/bin/python" \
BUSYSTACK_THUNDERBIRD_BINARY="$PWD/.client-tools/thunderbird/thunderbird" ./mvnw -B -ntp clean verify
docker build --build-arg SOURCE_REVISION="$(git rev-parse HEAD)" --tag busystack-caldav:0.0.1 .
```

Java 25, Docker and declared integration prerequisites are required. CI preserves formatting/static analysis, full unit/integration and investigation suites, advisory gates, artifact integrity, six packaged HTTPS profiles and local/federated restoration. Successful verification retains the tested JAR and exact container for promotion; ordinary pushes/PRs publish nothing. See the [release procedure](docs/development/releasing.md) for explicit dispatch, staging, immutability, safe retries and anonymous installation verification.

## Connect clients and manage access

Sign in through the account interface, enroll a passkey, complete MFA, create event/task collections and issue a named DAV credential. Use the displayed server address and username with that credential as the client's password. Capable registered clients can use browser OAuth instead; the account password is not the default DAV credential.

The audited baseline qualified python-caldav 3.6.0 and Thunderbird 157.0.1's native desktop provider for calendar/task CRUD, recurrence/alarm/extension round trips, task completion and synchronization, including four-resource cross-client edits. The release record identifies their fresh results for the final artifact and distinguishes browser automation, native provider tests and manual checks that could not be performed. The default accepts narrowly constrained `Depth: 1` sync reports only with XML sync-level 1; standard missing/Depth 0 headers remain supported. This is a documented RFC 6578 deviation. Set `BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE=false` for strict mode. Physical authenticators and additional client platforms remain separately unverified.

**Timezone input compatibility:** the default [local observance UNTIL repair](docs/observance-until-compatibility.md) narrowly corrects the captured Thunderbird timezone defect, strictly validates the corrected representation and persists those bytes. Already-valid input remains unchanged; unrelated invalid data is rejected. Set `BUSYSTACK_DAV_REPAIR_LOCAL_OBSERVANCE_UNTIL=false` to restore rejection of this defect. This is a documented input exception to RFC 4791 §5.3.2.1, separate from sync Depth compatibility. No forced UTC client setup is required by the repair. Client qualification remains version-scoped; see the release evidence for actual workflows and remaining limits. Original rejection and UTC-profile results are preserved as historical evidence. Fresh real-editor qualification of the corrected JAR passed Thunderbird 159.0 with system and explicit Vancouver plus explicit Berlin, and GNOME Calendar 50.0 in Vancouver, including editing, recurrence, restart and deletion. The [client-stage record](qualification/evidence/releases/0.0.1/timezone-repair/client-verification.json) distinguishes actual GUI results, the separately retried native-provider startup failure and final build/CI receipts.

See [client qualification](qualification/clients/README.md), [account security](docs/account-security.md), [passkeys/MFA](docs/passkeys-and-mfa.md), [OAuth registration](docs/oauth.md), [optional Keycloak](docs/keycloak.md), [sharing groups](docs/groups.md), [security verification](docs/security-verification.md), and [architecture decisions](docs/adr/0001-protocol-engine.md). Security self-assessment is not external certification.

The project is licensed under [Apache License 2.0](LICENSE). [Third-party inventory and notices](docs/third-party-licenses.md) record separate dependency licenses, resolved upstream metadata provenance and actual source/JAR/container notice delivery.
