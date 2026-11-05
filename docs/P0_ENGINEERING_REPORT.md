# v0.2.0 P0 engineering report

Qualification is in progress. This report does not yet assert release readiness.
The final results below will identify executed artifacts and remaining acceptance.

## Repository and preservation

Work uses the existing `release/v0.2.0` checkout. Starting HEAD was
`3f74590cd8d22341ae1a496af41377a8521733fa`, matching the requested qualified runtime.
No development branch, worktree or additional application checkout was created.
No push, merge, tag, release publication or public deployment was performed.

The three pre-existing modifications remain separate from development commits:

| Preserved file | Original and retained SHA-256 |
| --- | --- |
| `qualification/passkeys/README.md` | `185237c93242b01afbff712aea76e84226df2752d068f5cba86c7690be9cc8bc` |
| `qualification/passkeys/recovery.py` | `d9d10f88fcd7ab02f0ca53287c412b4525908f42aece7910d14e5cca2d8f4403` |
| `qualification/passkeys/test_recovery_refresh.py` | `168b179887f90c6506c1f538209abe186a7ad69a5409bab3d36520020361297c` |

Development commits so far:

* `39fa4e7caa95f7f4b0da28e2f307ed7baf7a0fc0`: protected browser initialization and staged onboarding.
* `9fb1eb50f180ebd906698d1945afb6277c6cc910`: illustrated guide, real screenshots and qualification tooling.
* `f25b9c520cbe6a8c5562c4ccf22df4c4e5f5cff1`: safe continuation through primary-session renewal and GET navigation for browser history restoration.

## Implemented behavior

The production release archive retains digest-pinned Compose deployment,
PostgreSQL, file-backed secrets, HTTPS proxy examples and backup/restore tooling.
It now includes the canonical [Getting Started guide](GETTING_STARTED.md), its
20 screenshots and the necessary portal/administration/portability references.
Packaging rejects missing guide files, missing/invalid PNGs and broken local
references. Normal deployment requires neither a checkout nor Java/Maven on the
host. Public certificate issuance remains the operator's responsibility.

A new database opens **Set up BusyStack**. The local command
`bash scripts/initialize.sh` issues a code from an operator-only one-off process.
The code is not printed by normal startup or returned by HTTP. Its Compose profile
disables container logging while retaining attached operator output. The browser
collects code, username, password and confirmation. Successful setup shows
**Administrator account created** and requires ordinary sign-in; it does not create
an authenticated browser session.

Authentication uses actual account/passkey/factor state. Primary sign-in leads to
**Secure your account** when no passkey exists, or **Verify your passkey** when one
does. First enrollment has no management table or required credential label.
Registration displays **Passkey created**, followed by a separate assertion.
Management remains guarded by the existing recent-MFA policy. A dedicated
verification route prevents the former registration/management redirect loop.

The first browser-created administrator receives **Welcome to BusyStack**, with
**Set up my calendar**, **Manage users**, and optional recovery-code access.
Only this initial administrator welcome dismissal is retained; ordinary-user
progress is derived from actual credentials, recovery information and owned/shared
collections. Shared recipients can connect applications without creating another
collection. Ordinary users receive no server-administration navigation.

Connections selects Thunderbird, GNOME Calendar or generic CalDAV. Settings come
from validated deployment configuration and retain root/subpath behavior. A
selected client survives application-password issuance and continuation. Labels,
username, one-time password and expiry are displayed with no-store/no-referrer.
Clipboard feedback is local to the action and distinguishes password, username
and server address; failures offer manual selection without moving focus.
Passwords are not put in browser storage, URLs or new database fields. The UI
never claims a device is connected merely because a password was issued.

Authentication failures distinguish capability, incomplete interaction, unavailable
authenticator, challenge expiry, primary expiry, revoked session, server rejection
and uncertain network response where the browser/server supply enough information.
A `NotAllowedError` is not equated solely with cancellation. A lost first-registration
response can reconcile against authenticated server enrollment state. Historical
POST pages navigate with GET when restored; authentication does not replay POSTs.

## Security design

V15 is the only new migration; V1–V14 remain unchanged. A singleton installation
row holds a permanent initialized latch, code verifier/expiry, bounded failed-attempt
counter, initial-administrator UUID and welcome-dismissal timestamp.

Code generation uses `SecureRandom` for 32 bytes (256 unpredictable bits), encoded
as 43 URL-safe Base64 characters. Only a purpose-separated SHA-256 verifier is
stored. Validity is fifteen minutes; replacements invalidate predecessors. Five
invalid attempts invalidate the outstanding code. Invalid, expired and consumed
codes receive the same public error. No universal setup secret or default password
is introduced.

Issuance and claims lock the same database row with `FOR UPDATE`. Account creation
uses existing `AccountService` inside that transaction. An account-insertion
trigger closes the latch and clears the verifier, also covering offline bootstrap
and closed federation provisioning. Failed account writes roll back consumption
and closure. Concurrent claims have at most one winner. Existing accounts,
including disabled accounts, close initialization on migration. Account deletion
does not reopen it. Restoring V15 clears pending codes and preserves closure;
older initialized backups close it when forward migrations run.

CSRF remains enabled. Setup responses use no-store/no-referrer and never redisplay
codes or passwords. The existing username policy and fourteen-character/seventy-two
UTF-8-byte password policy are reused. Organizational login is held at setup until
local operator initialization is complete. Optional OIDC still uses verified
issuer/subject mapping, closed allowlisting, local authorization and local passkeys.
Realm roles do not grant administration; managed users gain no password fallback.

`RecentAuthentication` retains its ten-minute policy. Registration grants no new
WebAuthn authentication authority. Assertions retain framework verification,
account binding, single-use challenges, security generations and session rotation.
Validated application GET destinations survive Spring's new-session strategy as
security routing state; ordinary session attributes are still discarded.
Administrator status does not grant private collection access. Existing session,
DAV, OAuth, recovery and sharing services remain authoritative.

## Investigation of passkey/credential state

The automated browser journey reproduced an issued application password followed
by no enrolled passkeys after a legitimate last-passkey deletion. The previously
completed WebAuthn factor remains recent under the established ten-minute policy;
removal alone is not an account-wide security reset. A new password-only session
with no passkeys was denied issuance. Registration alone was also denied issuance;
completed MFA permitted it. No MFA bypass was demonstrated, and this does not
establish the exact history of the earlier manual observation.

Recovery and password changes retain their stronger session/security-generation
and independent-access invalidation behavior. Once verification expires, an account
without a passkey must enroll and assert a new one after appropriate primary
sign-in, or use controlled recovery. UI state after enrollment/deletion is rebuilt
from server records instead of retaining a contradictory empty table.

## Verification evidence

Detailed logs are retained locally under `.qualification-runs/p0/`; they are not
public release attestations. Disposable fixtures use synthetic accounts and private
secret files. Log output is sanitized when a test tool would otherwise print
headers. Real-browser screenshots are captured with production CSS/JavaScript;
secret blocks are masked during capture. No third-party code or browser binaries
were modified and no dependencies were added.

Executed preliminary results:

| Check | Result and scope |
| --- | --- |
| Complete unit suite before continuation correction | 423 passed; no failures/errors/skips |
| Unit suite including continuation/session-rotation regression | 424 passed; no failures/errors/skips |
| Initial real PostgreSQL/HTTPS initialization suite | Six passed before adding rollback and V14-upgrade coverage |
| Signed WebAuthn HTTP suite | Eight passed, including real signatures, replay/expiry/account binding and protected issuance |
| Preliminary Chromium P0 journey | 42 assertions passed; 20 real screenshots; normal isolated-CA TLS validation |
| Release-tool tests | 32 passed, including missing-guide/image and broken-link rejection; publisher transports in these unit tests are mocked, with no external publication |
| Existing qualification fixture unit tests | Fourteen passed |

The complete Maven command is:

```sh
BUSYSTACK_THUNDERBIRD_BINARY="$PWD/.client-tools/thunderbird/thunderbird" \
BUSYSTACK_CLIENT_PYTHON="$PWD/.client-venv/bin/python" \
JAVA_HOME="$PWD/.qualification-runs/corrective-jdk" \
PATH="$PWD/.qualification-runs/corrective-jdk/bin:$PATH" \
./mvnw -B -ntp clean verify
```

The selected build runtime is unchanged official Temurin 25.0.5+8; Maven 3.9.12 is
provided by the pinned wrapper. PostgreSQL 18.6 and real Keycloak 26.8.1 use the
existing pinned distributions. Browser evidence uses Playwright 1.61.1 /
Chromium 149.0.7827.55 with a CTAP2 virtual authenticator. That does not qualify
Firefox/Bitwarden, synced-provider behavior generally or physical authenticators.

Two earlier long runs were interrupted by host restarts. They are not counted as
successful complete verification. The current full run exposed a V14-upgrade
fixture search-path error, OIDC fixtures that omitted the newly required initial
local administrator, and an existing 10,001-collection group deadline failure.
Fixture corrections preserve the production initialization gate and published
migrations; the group deadline remains unchanged. Final rerun results are pending.

## Packaged and manual acceptance

The local qualification tools are described in
[qualification/p0/README.md](../qualification/p0/README.md). They assemble an
explicitly unpublished archive through the production file-selection/archive
functions and record real source, JAR and image identities. They do not fabricate
CI runs, published assets or a completed historical graphical-client release
matrix. The existing publisher must continue to reject a pending client receipt.

Final root/proxy/subpath, upgrade, restore, real-client, screenshot and manual
fixture identities/results will be added after execution. Anonymous public
release download/pull acceptance requires published assets and is separate from
local pre-publication qualification; publication is explicitly outside authorization.

Firefox/Bitwarden acceptance remains pending until the user performs it in an
isolated profile and reports the behavior. The prepared procedure covers initial
setup, administrator creation, primary sign-in, Bitwarden registration, separate
verification, a protected operation, application-password issuance, sign-out and
returning-user verification. No existing Firefox profile or Bitwarden vault is
modified and no password-manager credential is registered automatically.

No complete WCAG conformance, independent security assessment, physical-authenticator
qualification or public release readiness is claimed.
