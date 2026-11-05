# v0.2.0 P0 engineering report

Final qualification of the V16 group-plan correction is in progress. Completed
results below currently describe the earlier V15 artifact until the corrected
runtime's full rerun replaces them. No release-readiness claim is made.

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

Local development commits (the final report/evidence commit is identified by repository HEAD):

* `39fa4e7caa95f7f4b0da28e2f307ed7baf7a0fc0`: Implement protected browser initialization and staged account onboarding
* `9fb1eb50f180ebd906698d1945afb6277c6cc910`: Add illustrated guide and end-to-end P0 qualification
* `f25b9c520cbe6a8c5562c4ccf22df4c4e5f5cff1`: Preserve safe continuation through session renewal without replaying POSTs
* `f0ca56579dfde4d622f912e6b8a1c5fb5d5484a4`: Harden operator output and qualify initialization across deployment profiles
* `01760f7baa03275267b8aea9a360ddce2669fba9`: Reuse the secure initialization-code entropy source
* `78443582d587969b9661172b6115bb4fbea8bf05`: Capture final onboarding screens and stage verifiable local deployment assets
* `fbc86b72b0a3cb61aa0893f7fbe842c04cbaf8f9`: Describe protected setup and the forward-only V15 upgrade
* `984dd487dd33dc4f7aa57b74692a141d9b4e4a43`: Keep root-path revoked-passkey assertions on the application origin
* `fac5e63e5243ea967d8028dc0d677d9fcf30a2e2`: Include and validate supporting documentation references in deployment archives
* `42bc581bbfd5ef27ee51c24a4422fb18b52dbdf0`: Qualify the documented startup command and both administrator choices
* `161fc2aa2c3a296fd44b347f308df543890f0a05`: Expect fresh passkey enrollment after restored-account primary sign-in
* `b01333a1866e9292a17f801e17922a2ae4930566`: Explain organizational sign-in freshness in the canonical guide

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

## Qualified runtime and deployment identities

The final application runtime is built from
`01760f7baa03275267b8aea9a360ddce2669fba9`. Subsequent commits change qualification
and documentation only; packaging compares every main/runtime input with that
image revision before accepting it.

* Production JAR SHA-256: `776a5eeb571c1e105e0a74f3707064e5a7d23e8d5bdc93093416ddb5d927c301`.
* Local application image/index: `sha256:a34edc9b3883709bdeba81a0b085d2adc9bdfe3cfebcfd8747c95452643403fb`.
* Final illustrated deployment archive source: `b01333a1866e9292a17f801e17922a2ae4930566`.
* Final archive SHA-256: `44b20c932a25325f459bc931f3ca9332e010c17c68fd3b58c0872db1eb464ab1`.

The six production-format local assets contain real checksums, source inputs,
deployment-file hashes, embedded SBOM and notices. Their metadata explicitly says
**local-unpublished** and invents no CI run or registry publication. The shared
production validator accepts them; the public publisher still requires its normal
CI and client-matrix evidence. A repeat of the earlier package at `7844358`
reproduced all six assets byte for byte. The complete package additionally includes
the supporting documents' relative-link targets and validates every packaged
Markdown link, beyond the canonical guide's twenty required PNGs.

The complete archive passed fresh initialization and the 48-assertion P0 browser
journey at both root and `/team` using its actual Nginx examples, with normal test-CA
TLS validation. Each installation also passed python-caldav 3.6.0 and the real
Thunderbird 159.0 native provider using a browser-issued application password.
Both started from empty databases with no pre-created administrator. These are
local package tests; anonymous download/pull of a public 0.2.0 release is not claimed.

## Security design

V15 adds initialization state; V16 corrects a demonstrated cached group-plan timeout.
V1–V15 remain unchanged after their recorded qualification. A singleton installation
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

## Group-plan defect found during complete regression

Two complete-suite attempts reached the existing 30-second deadline while adding
one member to 10,001 granted collections. The isolated case had completed add in
2.324 seconds. A disposable PostgreSQL reproduction cached the membership INSERT
plan before bulk growth: EXPLAIN showed the same one-row estimates and nested-loop
scans afterward, and execution reached SQLSTATE 57014 at the diagnostic deadline.
A cold plan selected hash joins and completed promptly. This establishes a cached
plan defect rather than evidence for extending the deadline.

The function-local `enable_nestloop=off` correction completed the same cached-plan
reproduction in 2.657 seconds and produced all 10,001 bindings. V16 applies that
preference only to the existing first-party reconciliation function. PostgreSQL
restores the caller's setting on return/error. Existing SQL permissions, mutation
locks, atomicity and the 30-second transaction deadline remain unchanged. No
third-party binaries, published migrations or authorization semantics changed.
The existing capacity regression now warms a generic plan before bulk growth.

The second full attempt was deliberately stopped after this already-fatal error,
before its long portal suite: exit 143, 424 unit tests passed, 229 integration tests
completed with this single error. It is not a successful complete run. The earlier
full attempt completed 261 integration tests with the six documented failures.
Final corrected-artifact results must supersede these attempts below.

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

Executed results (all rows marked passed exited 0):

| Check | Result and scope |
| --- | --- |
| Final complete Maven verification | Running; counts/exit status will be recorded after completion |
| Corrected focused integration gate | 36 integration tests passed: initialization 8, large-group maintenance 1, signed WebAuthn HTTP 8, real Keycloak 5, OIDC boundaries 4, security boundaries 6, portal 4. That invocation subsequently failed SpotBugs on one-use `SecureRandom`; corrected below. |
| Initialization/static-analysis correction | 2 unit and 8 integration tests passed; SpotBugs 0 findings; complete targeted `verify` exited 0 |
| Release-tool tests | 33 passed, including guide/image/relative-supporting-link failure cases; publication transports mocked |
| Existing qualification fixture unit tests | 14 passed |
| Fresh P0 journey, embedded HTTPS | 48 assertions; 20 screenshots; Python CalDAV and Thunderbird 159.0 passed |
| Archive `fac5e63`, root Nginx HTTPS | 48 assertions, 20 screenshots, Python CalDAV and Thunderbird 159.0 passed |
| Same archive, `/team` Nginx HTTPS | 48 assertions, 20 screenshots, both independent clients passed |
| Final archive `b01333a`, documented startup and personal administrator choice | 49 assertions; 20 screenshots; normal production health checks and local initialization command |
| Existing root browser regression | Passed enrollment/assertion, recovery, OAuth, sharing, process-kill import rollback, restart and disabled-account denial |
| Existing proxy + restore + clients | Passed actual backup/restore, old-session/DAV/Bearer/refresh/sync rejection, fresh MFA, byte-exact audit fixtures, shared READ/WRITE boundaries, revocation and both independent clients |
| Real cross-site Keycloak + restore | Passed provider password login, local passkeys, nonadministrator provisioning despite upstream role, local-password denial, recovery, restoration, sharing and disabled-account denial |
| GNOME Calendar 50.0-1 / EDS 3.56.2-8 | Passed actual connection wizard, normal private-CA TLS, event synchronization and EDS create/modify/delete independently observed over HTTPS |
| Packaged operations | Passed corrective V14 image replacement and V9 restore/forward migration, V15 closure, backup/checksum, non-root/read-only runtime, controlled local/external recovery and all malformed-history safeguards |
| Distribution integrity | Passed image/JAR identity, dependency integrity and notices/license inspection; 112 runtime components |

The two administrator activities were exercised on separate fresh deployments.
Ordinary-user coverage includes an existing shared collection without forced
creation, administrator-route denial, returning authentication with the existing
passkey, last-key deletion and renewed password-only issuance denial.

Browser presentation checks cover production local assets, desktop/mobile reflow,
associated labels, keyboard access, focus indicators and error association. The P0
journey checks each of its twenty screens at 390px and checks clipboard success
and failure, including focus retention. These are targeted accessibility checks,
not a complete WCAG 2.2 AA audit.

An early test-harness exception exposed a disposable session cookie in diagnostics.
That fixture was destroyed, retained diagnostics were sanitized, and subsequent
failure handling avoids printing credential headers. No usable test secret was
committed. Normal application-log checks passed for the operator code, account
password and issued application password.

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
migrations; the group deadline remains unchanged and the isolated large-group case
passed. The final complete rerun is recorded separately. Two browser fixture
corrections removed a root-path network-reference URL and an obsolete dashboard
expectation after restored primary sign-in; both affected profiles subsequently
passed without a production authentication change.

## Packaged and manual acceptance

The local qualification tools are described in
[qualification/p0/README.md](../qualification/p0/README.md). They assemble an
explicitly unpublished archive through the production file-selection/archive
functions and record real source, JAR and image identities. They do not fabricate
CI runs, published assets or a completed historical graphical-client release
matrix. The existing publisher must continue to reject a pending client receipt.

All browser, packaged, upgrade, restore and native-client profiles above passed.
The final archive was installed from an arbitrary extracted directory with an empty
database, operator-supplied HTTPS and no pre-created administrator. Tests use a
disposable trusted CA and loopback hostname; they do not qualify public DNS or a
production certificate deployment. The application runs from the immutable local
image reference in the generated release package, without source compilation in
the installation. The local cache replaces the unavailable publication step; no
authentication exception or test-only runtime mode is used.

Anonymous public
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
