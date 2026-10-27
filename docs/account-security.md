# Account security workflows

Account management provides **Sessions**, **Recovery**, **Password**, and passkey
settings. These are account controls; calendar and task content remains in CalDAV
clients. Local accounts work without an identity provider or email service.

## Browser sessions

Spring Session JDBC **4.1.1**, selected by the Boot **4.1.2** BOM, persists browser
sessions in PostgreSQL. Migration V6 installs its standard PostgreSQL tables;
`spring.session.jdbc.initialize-schema=never` prevents automatic schema creation.
The [module artifact](https://repo.maven.apache.org/maven2/org/springframework/session/spring-session-jdbc/4.1.1/)
contains an Apache-2.0 license; its published POM metadata differs. See the [specific artifact license review](third-party-licenses.md). The application uses the documented
[JDBC customization interface](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)
for concurrent attribute creation.

The `JSESSIONID` cookie is Secure and HttpOnly, with SameSite=Lax. Lax permits the
safe top-level redirect back from an upstream OIDC provider. Cookie-authenticated
mutations still require CSRF tokens; relaxing SameSite does not remove that
protection. Sessions expire after thirty minutes of inactivity. Recent primary and
passkey factors expire for sensitive-operation authorization after ten minutes,
even when a browser session remains active.

`/security/sessions` lists creation, last-access and expiration times, and marks
the current browser. It does not reveal session bearer values. Ending a session
requires recent MFA and only affects an owned session. Disabling an account,
changing its password, or using recovery ends every session and changes the
account's durable security generation. Password and passkey user lookups capture
that generation alongside credential state. A login already checking an older
credential cannot create a valid session after a completed reset.

The application uses Spring's new-session fixation strategy, creating a new
database session row after authentication. A delayed request holding an earlier
anonymous session must not restore the old cookie identifier. Session validity is
checked against durable account state on requests and sensitive service mutations;
there is no process-local revocation cache.

## Recovery codes

After recent primary and passkey verification, `/security/recovery` generates ten
independent high-entropy recovery codes. They are shown once on a response marked
`Cache-Control: no-store`. Store them securely offline. Generating another set
revokes every unused code in the earlier set. Each code expires after one year.
The database stores a SHA-256 verifier of the random credential, not the credential
itself; human passwords use adaptive password hashing instead.

If a passkey is lost, first complete a recent primary sign-in, then submit one
code at `/security/recovery`. Recovery is an alternative second-factor operation;
it does not replace a password or a trusted upstream sign-in. The code is consumed
atomically with removing passkeys, recording their revocation tombstones, revoking
DAV and OAuth access, and ending all browser sessions. Sign in again and enroll a
new passkey. Unused codes remain available until used, regenerated, expired, or
revoked by a password change or administrative recovery.

Concurrent use of one code permits one successful reset. A persistence failure
rolls the entire reset back, including code consumption. Expired, wrong-account,
incorrect, used, revoked, and restored-old-epoch codes are rejected. No code is
accepted without current primary authentication and the correct internal account.

An externally managed account retains its verified issuer/subject mapping. Recovery
does not create a local password or bypass upstream account restrictions. If the
primary credential is also lost, use the controlled administrator-assisted local
operation documented in [operations](operations.md); the public server does not
provide a first-run or password-reset takeover endpoint.

## Password changes and upgrades

Local users change their password at `/security/password` after recent MFA.
Passwords need at least fourteen characters and at most seventy-two UTF-8 bytes.
The configured verifier is BCrypt with cost 12. Password changes atomically rotate
the account security generation, invalidate sessions, revoke DAV/OAuth access and
recovery codes, and retain registered passkeys. An externally managed account's
password must be changed at its identity provider.

Spring's `UserDetailsPasswordService` hook upgrades weaker BCrypt work factors
after successful verification. An upgrade of the same password keeps the account
generation. The update compares the loaded hash and captured generation against
current database state, so a delayed upgrade cannot overwrite a concurrent
password change or revive an older credential.

## Passkey challenge lifecycle

Spring Security and WebAuthn4J still generate and verify the actual challenges,
origins, relying-party IDs, signatures and authenticator data. The application adds
a PostgreSQL single-use guard around that verification. When the framework saves
typed request or creation options, the application records a verifier of the exact
challenge, session, purpose, recovery epoch, and available account generation before
writing the HTTP response. A matching assertion consumes the durable guard before
framework verification. Failed attempts also consume it; request fresh options
before retrying.

Challenges expire after five minutes. A different session, purpose, account
generation, or recovery epoch cannot reuse them. Single-use enforcement does not
depend on synchronizing a Java session object, which would not coordinate separate
JDBC session wrappers or processes. Authentication bodies remain limited to 64 KiB;
encoded bodies are rejected and chunked JSON is bounded while reading.

## Login protection and audit metadata

The default policy tracks five account attempts within a five-minute observation
window and thirty attempts per connection peer within one minute. The next attempt
after an allowance is exhausted starts a thirty-second account cooldown or a
sixty-second peer cooldown. A new window begins after cooldown. Attempts during
cooldown do not extend it, successful authentication clears the account counter,
and throttling never disables the account. Counter
updates are transactional and durable across restarts. The counter store is capped
at ten thousand buckets and expires inactive buckets after one hour.

The peer is the actual servlet connection address. Arbitrary forwarded headers
cannot choose it. Behind a reverse proxy, its connections share that peer allowance; the current
application does not derive peers from forwarded headers. Account throttling still distinguishes normalized account identifiers.
These limits are an operational policy, not a scalability claim.

Security audit rows contain fixed action/outcome values, an optional internal
account ID, a timestamp, and a verifier of the connection-peer identifier. They do
not contain passwords, recovery codes, tokens, assertion bodies, or calendar
content. Hashing the peer is pseudonymization rather than guaranteed anonymity.
History is pruned to about ten thousand rows with writer-concurrency headroom and
a thirty-day age limit. Scheduled housekeeping also expires throttle buckets and
challenge guards. No telemetry or remote audit service is required.

## Restart, upgrade and restore

Normal restarts retain session, credential and revocation state. Restoring older
database state uses the explicit recovery policy in the operations procedure:
clear browser sessions and recovery codes, change the global recovery epoch and
account generations, and revoke other independently issued credentials. Old
backups must never silently revive a previously revoked session or recovery code.

V6 introduces captured account generations and revokes OAuth families created
before that boundary. Those applications must obtain fresh browser authorization;
calendar ownership, collections, resource bytes and synchronization history are
preserved. Review migration effects before upgrades. Session attribute serialization
is framework-version dependent; a future framework upgrade may require deliberately
ending sessions rather than assuming arbitrary serialization compatibility.

## Verification status

`AccountSecurityWorkflowsIT` exercises real PostgreSQL and HTTPS with a disposable
test CA. It covers sessions, ownership, CSRF, fixation races, reset generations,
recovery consumption/concurrency/rollback, password changes and hash upgrades,
challenge guards, login throttling and audit retention. Seeded factor contexts
test application workflows and are explicitly distinct from a real WebAuthn
ceremony. `PasskeyCeremonyGuardTest` verifies boundary and body handling; the
separate browser qualification must verify actual authenticator operations.

The focused run on 2026-10-27 executed all **19 workflow tests** and **17 unit
tests**, with zero failures, errors, or skips, on Temurin 25.0.4.1, Maven 3.9.12,
Boot 4.1.2, Security 7.1.2, and PostgreSQL 18.6. The command was:

```sh
./mvnw -B -ntp spotless:apply verify -Dit.test=AccountSecurityWorkflowsIT
```

Consult the executed qualification report for final combined results and remaining
checks. These controls have not received an independent security assessment or
certification.

## DAV credential verifier profile

Each issued DAV credential contains a cryptographically random 256-bit secret and its credential UUID. The current verifier is versioned as `{dav-sha256-v1}` and hashes a fixed purpose label, UUID and secret using SHA-256. Verification uses a constant-time comparison. A fast verifier is appropriate for this generated entropy; human passwords retain BCrypt cost12. Plaintext secrets are displayed only at issuance and are absent from the database.

Existing BCrypt DAV verifiers are verified with the established encoder and upgraded by a conditional database update. A concurrent revocation or replacement prevents that upgrade from restoring access. The focused PostgreSQL credential suite covers generated-secret verification, legacy upgrades and the revocation race. No process-local credential cache delays disabling or revocation; already authorized in-flight work follows the documented transaction boundary.
