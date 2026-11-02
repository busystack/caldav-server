# BusyStack CalDAV Server

A self-hosted CalDAV server for calendars and tasks, with PostgreSQL persistence
and built-in account administration. It supports local accounts and optional
OpenID Connect login through an existing identity provider.

## Capabilities

- Event and task collections, recurrence and exceptions, custom timezones,
  calendar queries, free/busy reports and incremental synchronization.
- Conditional writes with strong ETags and owner-controlled individual or group
  read/write sharing.
- Named, revocable DAV credentials and OAuth authorization with PKCE.
- Passkeys, password-plus-passkey MFA, recovery codes and browser-based account
  and administrator tools.

## Install

Use the **Docker Compose deployment bundle** from the
[latest stable release](https://github.com/busystack/caldav-server/releases/latest).
Download its deployment archive, `SHA256SUMS` and `release-manifest.json`, verify
the checksums, extract the archive and follow its `INSTALL.md`.

The bundle supports **Linux AMD64**, Docker Engine and Compose v2 (2.24.4 or newer).
It pulls the verified application image by version and immutable registry digest
and includes a private PostgreSQL service. No source checkout, Maven or host Java
is required. Supply your own domain, HTTPS termination and private secret files.
Keep the database private and the application HTTP listener on loopback.

The executable JAR is an alternative for administrators managing Java 25,
PostgreSQL 18.6 and their own process supervision. See the
[installation guide](deployment/INSTALL.md) and [operations guide](docs/operations.md)
for configuration, administrator bootstrap, updates, backups and restoration.
Use the generated instructions included with the downloaded release.

## Connect a client

Sign in to the account interface, enroll a passkey, complete MFA, create event or
task collections and issue a named DAV credential. Configure your CalDAV client
with the displayed server address and username, using that credential as its
password. The account password is not the default DAV credential. Registered
OAuth clients can use the browser authorization flow.

Qualified graphical clients are Thunderbird 159.0 and GNOME Calendar 50.0 /
Evolution Data Server 3.56.2 in the configurations listed in the
[compatibility documentation](docs/observance-until-compatibility.md).

## Documentation and limitations

Start with [account security](docs/account-security.md),
[passkeys and MFA](docs/passkeys-and-mfa.md), [OAuth](docs/oauth.md),
[optional OIDC](docs/keycloak.md), [sharing groups](docs/groups.md) and
[calendar semantics](docs/calendar-semantics.md).

The account interface is not a web calendar editor. CardDAV, scheduling and
invitation delivery, alarm execution and automatic failover are outside the
supported profile. Stored attendees, organizers, attachments and alarms do not
cause outbound actions. The documented
[synchronization Depth and timezone-input compatibility behavior](docs/conformance.md)
remains configurable; see its scope and strict-mode settings before changing it.

## Development

Use the Maven wrapper with the [declared toolchain](docs/versions.md) and required
integration-test prerequisites in [client verification](qualification/clients/README.md).
`./mvnw -B -ntp clean verify` runs formatting, static analysis and application tests.
`python3 scripts/build-image.py` builds and verifies an image using Maven's version
automatically. See the [release procedure](docs/development/releasing.md) for the
supported development Compose command and distribution checks.

Contributions can be proposed through repository issues and pull requests. Keep
changes focused and run the relevant verification before submitting them.
The project is licensed under [Apache License 2.0](LICENSE); dependencies retain
their [own licenses and notices](docs/third-party-licenses.md).
