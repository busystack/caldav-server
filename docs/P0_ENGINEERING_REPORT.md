# v0.2.0 P0 engineering report

Implementation and complete local automated qualification passed. The final runtime
has 685 passing Maven tests, zero SpotBugs findings, and successful packaged,
browser, real-Keycloak and native-client profiles. Firefox/Bitwarden acceptance
and public release qualification remain separate conditions below.

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
* `358a1ed2903d8d51622ecad00de6c973c9a94375`: Bound cached group reconciliation plans without extending mutation deadlines
* `7c8f36b8676b10186822432ecaa20a098e8c8144`: Record green V16 verification and final production-image screenshots
* `40176d20800c7d0a75f692f15a0d782cfcf6d5c5`: Record final packaged, browser and native-client qualification evidence

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
`358a1ed2903d8d51622ecad00de6c973c9a94375`. Subsequent commits change qualification
and documentation only; packaging compares every main/runtime input with that
image revision before accepting it.

* Production JAR SHA-256: `ce7b72f10ed342129d8b60b319b78ea091d9a03f20f029120e29eb6cf304ea62`.
* Local application image/index: `sha256:7d3a07b372ec3ffa161b3ef2aa33c1564d524652c88ae011d1b4956629ee5699`.
* Delivery archive source: `40176d20800c7d0a75f692f15a0d782cfcf6d5c5`.
* Delivery archive SHA-256: `af932106d114655593dbff909b9315faaa66185444e3b235cef8d217e2e612d9`.

The six production-format local assets contain real checksums, source inputs,
deployment-file hashes, embedded SBOM and notices. Their metadata explicitly says
**local-unpublished** and invents no CI run or registry publication. The shared
production validator accepts them; the public publisher still requires its normal
CI and client-matrix evidence. Both the earlier `7844358` package and the final `40176d2` delivery package
reproduced all six assets byte for byte. The complete package additionally includes
the supporting documents' relative-link targets and validates every packaged
Markdown link, beyond the canonical guide's twenty required PNGs.

The full eight-profile packaged matrix used archive source
`7c8f36b8676b10186822432ecaa20a098e8c8144`, SHA-256
`99100b1f9e30ef9910f74b34e9afb0487ce0dcf650cb8898f220368c143d7473`.
Root installation passed 49 assertions; `/team` passed 48. Each passed real
python-caldav 3.6.0 and Thunderbird 159.0 native-provider correctness checks using
a browser-issued application password. The source-image capture separately
passed 49 assertions and produced the twenty final guide images.

The delivery archive updates only `SOURCE.json` and the qualification receipt.
Every other deployment file, including Compose, scripts, guide and images, is
byte-identical to the archive that passed the full matrix. All six delivery assets reproduced byte for byte. Its separate clean HTTPS
installation exited 0 with 49 browser assertions and both independent clients
passing. Source and extracted-package validation passed for the guide, all twenty
PNGs, and every relative Markdown link in the archive. No source compilation is
used inside an installation. These are unpublished local-package tests, not
anonymous download/pull of a public release.

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
The corrected full run at `358a1ed` passed all 685 tests. Its complete group suite
measured add/remove/re-add/delete at 2120/980/2346/1070 ms and verified that the
caller's planning preference was restored. The diagnostic plans and measurements
are retained in [group-plan evidence](../qualification/evidence/releases/0.2.0/group-plan/result.json).

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
| Complete Maven `clean verify`, source `358a1ed` | Exit 0; 424 unit + 261 integration tests, no failures/errors/skips; SpotBugs 0; 41:50 elapsed |
| Focused V16 correction gate | Exit 0; 2 unit + 24 integration tests, including the entire 16-test group suite and 8-test initialization suite; SpotBugs 0 |
| Release-tool tests | 33 passed; missing guide/image/supporting-link cases included; publisher transports mocked |
| Qualification fixture unit tests | 14 passed; provided pre-existing fixture edits preserved |
| Final production-image P0 capture | 49 assertions, 20 real screenshots; Python CalDAV 3.6.0 and native Thunderbird 159.0 passed |
| Packaged root, personal administrator activity | 49 assertions, 20 screenshots, both independent clients passed |
| Packaged `/team`, manage-users activity | 48 assertions, 20 screenshots, both independent clients passed |
| Root browser regression | Passed real enrollment/assertion, recovery, OAuth, sharing, process-kill import rollback, restart and disabled-account denial |
| Proxy + restore + clients | Passed actual backup/restore, old-session/DAV/Bearer/refresh/sync rejection, fresh MFA, byte-exact audit fixtures, shared READ/WRITE boundaries, revocation and both clients |
| Real cross-site Keycloak + restore | Passed provider password login, local passkeys, nonadministrator provisioning despite upstream role, local-password denial, recovery, restoration, sharing and disabled-account denial |
| GNOME Calendar 50.0-1 / EDS 3.56.2-8 | Passed actual connection wizard, normal private-CA TLS, event synchronization and native EDS create/modify/delete independently observed over HTTPS |
| Packaged operations | Passed corrective V14 image replacement and V9 restore/forward migration to V16, initialization closure, backup/checksum, non-root/read-only runtime, controlled local/external recovery and every malformed-history safeguard |
| Distribution integrity | Passed exact image/JAR, dependency integrity and license/notices inspection; 112 runtime components |

Exact matrix command arrays, exit codes, timestamps, log digests, client versions,
artifact identities and screenshot hashes are in the committed
[P0 result record](../qualification/evidence/releases/0.2.0/p0-results.json).
All eight final matrix commands exited 0. The metadata-only delivery successor
also passed its own fresh installation. Local raw logs use the `v16-` prefix
under `.qualification-runs/p0/`; Maven uses `full-maven-v16.log/json`.

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

Two earlier runs were interrupted by host restarts and are not counted as successful
verification. The first completed full run (`f25b9c5`) exited 1 with 424 passing
unit tests and 261 integration tests containing one failure and five errors. It
exposed V14-fixture search-path handling, OIDC fixtures without the now-required
initial administrator, and the group-plan timeout. Fixture corrections preserved
the initialization gate and published migrations. A subsequent targeted run passed
36 integration tests but failed SpotBugs for one-use `SecureRandom`; retaining the
entropy source fixed that finding. The next full attempt (`b01333a`) was stopped
with exit 143 after the reproduced group timeout (229 completed integration tests,
one error). V16 fixes that demonstrated production defect; the final full run is
green. Two browser fixture corrections removed a root-path network-reference URL
and an obsolete post-recovery dashboard expectation; affected final profiles passed.

The `NotAllowedError` cancellation presentation check uses an injected browser API
error; it does not claim a person cancelled a native password-manager dialog.
Actual enrollment/assertion use Chromium's virtual CTAP2 authenticator. The lost
registration-response check interrupts a real committed HTTP response, then verifies
authoritative reconciliation and the continuing requirement for an assertion.

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

Anonymous public release download/pull acceptance requires published assets and is separate from
local pre-publication qualification; publication is explicitly outside authorization.

## Documentation deliverables and acceptance

* Canonical guide: [docs/GETTING_STARTED.md](GETTING_STARTED.md), nineteen chapters
  covering the complete operator, administrator and ordinary-user journey.
* Screenshots: `docs/images/getting-started/`, twenty PNGs captured from runtime
  `358a1ed` with production CSS/JavaScript at a 1280×900 browser viewport. The
  one-time secret blocks in images 15 and 18 are deterministically masked.
* Release payload: `INSTALL.md`, the complete guide, all twenty referenced PNGs,
  and supporting operator documents and relative-link targets.
* Validation: source and extracted-guide references passed; every relative
  Markdown link in the package resolves. Missing guides/images/targets fail the
  automated release-tool tests. All six final assets reproduced byte for byte.
* Installation acceptance: isolated empty Compose databases, normal trusted test-CA
  HTTPS, browser-only initial administrator creation, first/returning authentication,
  both administrator choices, ordinary shared-user onboarding, application-password
  issuance and working real CalDAV clients. Root passed 49 assertions, `/team` 48;
  the delivery archive's independent clean root installation passed 49 again.

The tested delivery assets are retained locally in
`.qualification-runs/p0/package-v16-delivery/assets/`; the extracted deployment is
its sibling `caldav-server-0.2.0-deploy/`. This is an unpublished qualification
artifact. The official publication and immutable-image distribution controls remain
in force; no external release was created.

## Manual Firefox/Bitwarden acceptance — prepared, pending

The isolated delivery-package deployment is ready at **https://localhost:49681**.
Preparation exited 0; SQL inspection confirmed zero accounts, `initialized=false`,
and no code verifier. No existing Firefox profile, Bitwarden vault or host trust
store was modified. No administrator or password-manager credential was prepared.

Use a **new Firefox profile** (for example through `about:profiles`). Import only
this disposable CA into that profile's certificate authorities and trust it for
websites; do not bypass certificate validation:

```text
/home/albert/Projects/busystack/caldav-server/.qualification-runs/p0-manual-v16/ca.pem
```

CA SHA-256 fingerprint:
`38:01:75:12:D9:02:97:E1:83:02:38:A4:4C:C3:2E:9B:54:F8:7E:24:ED:26:69:3E:C7:92:4D:AF:FD:72:8F:78`.
The server certificate expires **2026-11-07 12:57:11 UTC**. Install/configure
Bitwarden in that isolated profile yourself. The agent has not accessed your vault.

Perform and record these steps, keeping passwords, codes and tokens private:

1. Open the HTTPS URL and confirm **Set up BusyStack**.
2. From the local installation directory below, issue a code and create a synthetic
   administrator in the browser with a fresh password you choose.
3. Sign in normally with that account.
4. Choose **Create passkey** and save it through Bitwarden's normal interface.
5. Choose **Verify your passkey**, complete the assertion, then **Continue**.
6. Choose **Set up my calendar** and create a disposable collection as a protected operation.
7. Create an application password and check its one-time display/copy/expiry information.
8. Sign out.
9. Sign in again with the same administrator.
10. Verify the existing Bitwarden passkey again; another enrollment should not be required.

The exact initialization command is:

```sh
cd /home/albert/Projects/busystack/caldav-server/.qualification-runs/p0-manual-v16/install
bash scripts/initialize.sh
```

Record actual Firefox/Bitwarden versions, completed steps and any prompt/cancellation
behavior. Do not send authentication secrets. Until the user performs the test,
its result remains **pending**; Chromium virtual-authenticator results are separate.
Bitwarden behavior does not establish hardware-backed user presence.

The fixture remains running for acceptance. When finished, remove only this owned
project and its disposable database, from the same installation directory:

```sh
docker compose down --volumes --remove-orphans
```

This cleanup is destructive to **this test project's data**. It does not remove
an existing Firefox profile or Bitwarden item. If the certificate expires, prepare
a new fixture with `qualification/p0/manual.py` and a new output directory; follow
its new CA/address rather than bypassing TLS validation.

## Remaining acceptance and scope

No known functional P0 defect remains in the qualified paths. The user's real
Firefox/Bitwarden procedure is pending. Anonymous public release download/pull
was not executed because this work is unpublished and publication is not authorized.
The existing four graphical release-matrix profiles remain pending for the new
executable; native Thunderbird and GNOME wizard/backend checks do not substitute
for every graphical editor/timezone profile. The publisher's gate remains strict.
These conditions prevent a public release-readiness claim.


No complete WCAG conformance, independent security assessment, physical-authenticator
qualification or public release readiness is claimed.

## Final repository state

All implementation, guide, screenshot and evidence changes are committed locally
on `release/v0.2.0`; the final report/evidence commit is repository HEAD, also
identified in the completion message. Runtime qualification is bound to `358a1ed`,
the full-matrix package to `7c8f36b`, and the delivery package to `40176d2`.
The final signoff changes report/evidence only. The only remaining working-tree
modifications are the three original qualification files listed at the start;
their hashes and their absence from development commits were checked again.
No additional application branch, worktree or checkout, dependency, third-party
software modification, push, merge, Git tag, release publication or public
production deployment was introduced.
