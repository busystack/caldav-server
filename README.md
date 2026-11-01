# BusyStack CalDAV Server

Independent, self-hosted Java server for calendars and tasks, with PostgreSQL persistence and built-in accounts. Local deployment works without another identity service; an existing Keycloak installation can provide optional OIDC login.

The last published version is v0.0.1; [v0.1.0 preparation](docs/releases/0.1.0.md) is in progress. Administrator downloads are provided through the [v0.0.1 release](https://github.com/busystack/caldav-server/releases/tag/v0.0.1). Use its [release manifest](https://github.com/busystack/caldav-server/releases/download/v0.0.1/release-manifest.json) for authoritative source, checksums, Linux AMD64 image digest and verification evidence. Read the [release notes](docs/releases/0.0.1.md) for supported configurations and limitations.

## Capabilities

* Multiple event and VTODO collections, authoritative accepted iCalendar bytes, recurrence/exceptions/custom time zones, calendar-query/multiget/freebusy and required principal/ACL reports.
* Strong ETags, HTTP/date/DAV write conditions, atomic properties and namespace operations, transactionally ordered revisions and WebDAV synchronization/deletions.
* Local passwords, persistent passkeys, password-plus-passkey MFA, single-use recovery, durable sessions, throttling and controlled administrator provisioning without default credentials.
* Named revocable DAV credentials; embedded OAuth code/S256 PKCE, opaque access tokens and persistent public-native refresh rotation/replay protection. Administrators register clients without source changes.
* Stable principal identities, owner-controlled individual/group READ/WRITE sharing, and focused server-rendered account/administration pages. Administration alone does not grant calendar data access.
* Optional standard OIDC with exact issuer/subject mappings, protected linking, closed provisioning and a documented upstream reauthentication/revocation boundary.

Stored organizers, attendees, attachments and alarms do not execute invitations, URL retrieval, scheduling, email, push or reminders. The account interface is not a web calendar editor. This project does not provide CardDAV or a general file service.

## Install

The primary installation is the release's `caldav-server-0.0.1-deploy.tar.gz`, with Docker Engine and Compose v2 (2.24.4+), on **Linux AMD64**. Download the archive, `SHA256SUMS` and `release-manifest.json` from the [release assets](https://github.com/busystack/caldav-server/releases/tag/v0.0.1), verify checksums, extract it into any directory and follow its `INSTALL.md`. Its complete Compose deployment pulls `ghcr.io/busystack/caldav-server:0.0.1` pinned to the manifest's registry digest. No source checkout, Maven or host Java is needed.

Supply your own domain/DNS, trusted HTTPS certificate and reverse proxy using the included complete root/subpath examples. Create separate private file-backed database secrets, start the two services, bootstrap the administrator locally, then provision users, passkeys/MFA, collections and DAV credentials through the browser. The [operations guide](docs/operations.md) covers setup, updates, backup, restoration and removal. PostgreSQL stays private; application HTTP stays on loopback.

The alternative is the release's executable `caldav-server-0.0.1.jar`, requiring Java 25 (qualified Temurin 25.0.4.1+1) and PostgreSQL 18.6. Verify its checksum and follow the operations guide with the downloaded JAR path. Changing an application image or JAR does not reverse database migrations. No native package or additional architecture is provided.

## Developer source builds

Build dependencies include Temurin Java 25.0.4.1+1, Spring Boot 4.1.2, Spring Security 7.1.2, PostgreSQL 18.6 and official iCal4j 4.4.0. See [dependency versions and licenses](docs/versions.md). The repository's `compose.yaml` supports source builds through the checked build entry point. Maven's `project.version` supplies the application version automatically.

```sh
python3 -m venv .client-venv
.client-venv/bin/pip install -r qualification/clients/requirements.txt -r qualification/clients/desktop-requirements.txt
bash qualification/clients/install-thunderbird.sh .client-tools
BUSYSTACK_CLIENT_PYTHON="$PWD/.client-venv/bin/python" \
BUSYSTACK_THUNDERBIRD_BINARY="$PWD/.client-tools/thunderbird/thunderbird" ./mvnw -B -ntp clean verify
python3 scripts/build-image.py
# With the database secrets and HTTPS URLs configured:
python3 scripts/build-image.py --compose up -d --no-build --wait
```

Java 25, Docker and the declared integration prerequisites are required. `verify` runs formatting, static analysis, unit tests and integration tests. See the [release procedure](docs/development/releasing.md) for distribution and deployment checks.

## Connect clients and manage access

Sign in through the account interface, enroll a passkey, complete MFA, create event/task collections and issue a named DAV credential. Use the displayed server address and username with that credential as the client's password. Capable registered clients can use browser OAuth instead; the account password is not the default DAV credential.

Tested client configurations include Thunderbird 159.0 with system and explicit America/Vancouver and explicit Europe/Berlin, and GNOME Calendar 50.0/EDS 3.56.2 with America/Vancouver. See the [release notes](docs/releases/0.0.1.md) for supported configurations and limitations.

The default accepts `Depth: 1` sync reports only with XML sync-level 1; standard missing/Depth 0 headers remain supported. This is a documented RFC 6578 compatibility deviation. Set `BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE=false` for strict mode.

**Timezone input compatibility:** the default [local observance UNTIL repair](docs/observance-until-compatibility.md) narrowly corrects the Thunderbird timezone defect using each observance's `TZOFFSETFROM`, validates and stores the corrected representation, and omits validators on transformed PUT responses. Already-valid input remains unchanged; unrelated invalid data is rejected. Set `BUSYSTACK_DAV_REPAIR_LOCAL_OBSERVANCE_UNTIL=false` to restore rejection of this defect. This is an input exception to RFC 4791 §5.3.2.1, separate from sync Depth compatibility. It requires no forced UTC client setup.

See [account security](docs/account-security.md), [passkeys/MFA](docs/passkeys-and-mfa.md), [OAuth registration](docs/oauth.md), [optional Keycloak](docs/keycloak.md), [sharing groups](docs/groups.md), and [calendar semantics](docs/calendar-semantics.md).

The project is licensed under [Apache License 2.0](LICENSE). [Third-party inventory and notices](docs/third-party-licenses.md) record separate dependency licenses, resolved upstream metadata provenance and actual source/JAR/container notice delivery.
