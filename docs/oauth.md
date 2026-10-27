# OAuth client access

The embedded authorization server is this application's client-facing issuer.
Upstream OIDC login, when configured and qualified, is a separate authentication
relationship. An upstream ID token or arbitrary external access token is not a DAV
credential.

## Register an application

An administrator signs in, verifies a password and passkey, then opens
`/admin/oauth-clients`. Register the application's client ID, display name, and
redirect URIs. Changes require both factors within the last ten minutes, enforced
by the service as well as the browser boundary. Registration is administrator
controlled; there is no unrestricted dynamic registration endpoint.

Native applications are public clients: authentication method `none`, no client
secret, authorization code plus S256 PKCE, and scope `dav`. The scope permits the
user's existing DAV permissions; it does not grant access to another user's
private collections or administration. The server requires consent and recent MFA
before authorizing an application, including when earlier consent is reusable.

Accepted redirects are HTTPS URLs, HTTP IP-loopback URLs with a port, and
application-owned reverse-domain schemes such as `org.example.calendar:/callback`.
Administrators must register the real application's redirects. Hosted and custom
scheme redirects use exact matching. The framework permits a different port for
an otherwise matching IP-loopback redirect, as required for native applications.
Redirect fragments and embedded credentials are rejected.

## Connect a capable client

For a root issuer `https://calendar.example`, metadata is at
`https://calendar.example/.well-known/oauth-authorization-server`. The issuer and
all endpoint URLs come from validated deployment configuration, never request
Host or forwarded headers. Issuer subpath deployments require the explicit RFC
8414 metadata proxy route described in the deployment documentation; do not assume
that appending a well-known path after an issuer subpath is equivalent.

Use an established OAuth client implementation. Open the authorization endpoint
in the system browser with `response_type=code`, the registered `client_id` and
`redirect_uri`, scope `dav`, unpredictable `state`, and a S256 PKCE challenge. The
user signs in and completes password plus passkey verification before approving
access. Exchange the returned code at `/oauth2/token` using form parameters
`grant_type=authorization_code`, `client_id`, `redirect_uri`, `code`, and
`code_verifier`. Do not send a fabricated native-client secret or the user's
account password.

Send the resulting opaque access token as `Authorization: Bearer …` to DAV
resources. Refresh using a form POST to `/oauth2/token` with
`grant_type=refresh_token`, `client_id`, and `refresh_token`. Access tokens last ten
minutes; the refresh family has an absolute thirty-day lifetime. The issuer does
not enable implicit, password, client-credentials, device, or token-exchange grants
for registered native applications.

Each refresh rotates the refresh token and replaces the current access token.
Clients must serialize refresh operations and atomically retain the latest token
pair. Replaying a spent refresh token revokes its entire family, including the
current access token. Concurrent use therefore yields at most one successful
rotation, and the losing replay invalidates its descendant. If the successful
response is lost, obtain a new browser authorization instead of blindly retrying
the consumed token.

Ordinary CalDAV clients that do not support this OAuth onboarding use a separately
issued DAV credential with HTTP Basic over HTTPS. CalDAV service discovery does
not register an OAuth client or make every client OAuth capable.

## Manage and revoke access

Users view grant metadata at `/oauth-grants`; tokens are never displayed there.
Revoking a grant requires recent MFA. It invalidates that family's access and
refresh credentials and removes associated consent. A client can also submit its
current access or refresh token to `/oauth2/revoke` with its public `client_id`.
Removing an administrator-registered client revokes its grants and removes its
stored authorization state. Disabling a local account revokes all its families.

DAV token validation checks expiry, the configured issuer and DAV audience, scope,
account state, family revocation, and the recovery epoch against PostgreSQL on
every authentication. There is no process-local validity cache. Revocation applies
to authentications after its transaction commits; a request already being handled
may finish. A revoked or disabled account never gains browser administration from
a DAV token.

## Persistence and recovery

Migrations V2 and V3 create the token-family ledger and Spring Security's JDBC
client, authorization, and consent stores. Spent refresh tokens are retained as
SHA-256 verifiers of high-entropy framework-generated tokens. Spring's JDBC
authorization store contains current bearer credential values and serialized
security state; protect database access and backups as secret material. The
selected opaque model does not need signing keys. Boot's demonstration ephemeral
JWT/JWK auto-configuration is excluded.

Code exchange, refresh consumption, token replacement, and framework grant updates
use PostgreSQL transactions and a per-authorization advisory lock. Spent-token
replay revocation commits before the adapter returns `invalid_grant`. A failure to
persist a successful rotation rolls back both grant and token changes. No blind
automatic mutation retry is used.

Do not delete spent-token history for an unexpired family: that removes replay
evidence. Expired families may be removed after the operational retention period;
their token rows cascade. The current implementation does not schedule cleanup.
The documented restore operation clears authorization and consent records,
revokes families, and changes the recovery epoch, requiring fresh browser
authorization instead of reviving credentials from an older backup.

## Evidence limits

[ADR 0002](adr/0002-native-oauth-refresh.md) records the exact framework behavior
and isolated four-test HTTP investigation. `RefreshFamilyStoreIT` exercises real
PostgreSQL transaction, replay, concurrent refresh, rollback, disable, expiry, and
recovery behavior. `OAuthHttpIT` exercises the actual HTTP code/token/DAV boundaries
using seeded completed-interactive-grant fixtures, and checks that a real
password-only browser session cannot authorize a client. Seeded grants do not
qualify a WebAuthn ceremony, browser consent flow, an independent native client, or
upstream Keycloak. Consult the executed test report and conformance matrix before
claiming those capabilities.

The focused application run on 2026-10-27 passed all seven `OAuthHttpIT` and nine
`RefreshFamilyStoreIT` tests, with zero failures, errors, or skips, on Temurin
25.0.4.1, Maven 3.9.12, Boot 4.1.2, Security 7.1.2, and PostgreSQL 18.6. See ADR 0002
for the exact command and image pin. These are component results; the unqualified
flows above remain unqualified.
