# Browser security and deployment qualification

The fixture exercises the packaged Java application, real PostgreSQL, server-rendered pages, Spring Security and Chromium WebAuthn over normally validated HTTPS. It uses a virtual user-verified resident CTAP2 authenticator. It does not qualify physical authenticators, other browsers or desktop CalDAV clients.

On a Linux Docker host with Compose 2.24.4 or newer, Python 3 and OpenSSL:

```sh
docker build --tag busystack-caldav:local .
docker build --tag busystack-passkey-browser:qualification qualification/passkeys
python3 qualification/passkeys/run.py --application-image busystack-caldav:local
python3 qualification/passkeys/run.py --application-image busystack-caldav:local --proxy
```

The default profile uses direct embedded TLS at the hostname root. `--context-path /team` tests embedded TLS under a servlet prefix. `--proxy` uses the pinned non-root Nginx fixture with TLS termination, preserved `/team` paths, host-root CalDAV discovery and RFC 8414 metadata mapping, private management routes and bounded declared/chunked bodies. The ordinary production Compose deployment still consists of the application and PostgreSQL.

The checks cover password-only mutation denial; actual passkey enrollment/assertion and MFA session rotation; a collection explicitly selecting and displaying `VTODO`; named DAV credential issuance, display-once semantics and revocation; administrator-controlled public native client registration; real browser authorization/consent with S256 PKCE; code exchange without a client secret; refresh rotation and grant revocation; JDBC browser-session and passkey persistence across JVM restart; and disabled-session/passkey rejection. Authentication of `/dav/missing` proves routing and credential enforcement, not calendar/task content synchronization.

For the optional upstream profile:

```sh
docker build --tag busystack-keycloak-browser:26.8.1 qualification/keycloak
python3 qualification/passkeys/run.py --application-image busystack-caldav:local --oidc
```

This profile additionally starts a disposable real Keycloak instance and uses distinct reserved browser sites, `app.busystack.test` and `idp.identity.test`, with private-CA certificates. A fresh nonadministrator account is provisioned from an exact subject allowlist, despite its upstream `realm-admin` role. Actual organization password authentication and the cross-site callback must preserve the application's Secure, HttpOnly, Lax session cookie. Organization login alone cannot mutate collections; a local passkey completes MFA. The user cannot discover the separate local administrator's collection or enter administration. A local-password attempt with the upstream password must fail. This account then exercises DAV credential/OAuth browser workflows and persistence. Explicit identity linking, provider logout/deprovisioning and restored-provider login require their separate tests.

Playwright is pinned to 1.61.1 in the package lock; the digest-pinned browser image currently contains Chromium 149.0.7827.55 and prints its actual version. NSS tooling is pinned to `libnss3-tools=2:3.98-1ubuntu0.3`. These dependencies are absent from production runtime. Exact image digests are listed in [versions](../../docs/versions.md).

Each run creates a host-private temporary directory, disposable CA/certificates and randomly named Compose project. All published fixture ports bind `127.0.0.1`. Scoped reserved-domain resolution routes only the test domains to loopback, while retaining normal TLS hostname/SNI checks. A separate browser container imports only the fixture CA into its NSS database; the application's optional upstream truststore contains only that CA. Certificate-error bypasses and host trust changes are absent. A normal client lacking the CA must reject the certificate.

Generated secrets, private keys, truststores and virtual authenticator state are disposable, never committed as fixtures or uploaded as evidence. Failure output redacts passwords, tokens and authorization parameters. Cleanup removes only the named fixture containers/networks/volumes. Missing prerequisites, TLS failures, failed assertions and explicit deadlines fail visibly. Execution status belongs in [qualification evidence](../evidence/README.md); a fixture's presence does not establish a pass. Recovery, hostile ceremonies, physical devices, independent calendar/task clients and complete production readiness remain separate acceptance requirements.

The browser integration uses [Playwright CDP sessions](https://playwright.dev/docs/api/class-cdpsession) and the [Chromium WebAuthn testing protocol](https://chromedevtools.github.io/devtools-protocol/tot/WebAuthn/).
