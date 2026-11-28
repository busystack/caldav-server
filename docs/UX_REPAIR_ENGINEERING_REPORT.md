# BusyStack CalDAV UX repair

The protected setup, browser sign-in, account portal and administrative workflows were revised on `release/v0.2.0`. The illustrated guide follows the actual application, including its one-time credential and recovery screens. Trusted-HTTPS walkthroughs ran against extracted bundles at the root and under `/team`. Python CalDAV and native Thunderbird used a password issued during a real virtual-authenticator sign-in and completed content and synchronization operations. A separate uninitialized installation is available for manual testing with the user's passkey provider.

The [acceptance register](UX_REPAIR_ACCEPTANCE.json) records each of the 21 findings and 38 acceptance rows. Nineteen rows passed and eighteen await additional evidence. One row failed because direct and group share revocations still use inline review where the brief requires a named final confirmation. Some form and step-up routes need broader coverage, particularly simultaneous browser-tab continuation, ambiguous issuance outcomes, and accessibility with the user's browser and provider. The bundle serves local acceptance. A public release remains a separate qualification decision.

## Functional changes

| Area | Root cause and repair |
| --- | --- |
| Setup and first sign-in | The introduction, protected form and success interstitial split one task into several routes. Setup now combines the operator code and administrator fields, checks password matching before submission, links to local help and redirects to sign-in. Passkey registration reveals one explicit verification action. A successful assertion goes to Overview or the validated destination. |
| Authentication and navigation | A factor freshness check previously stood in for a completed browser session. Accepted WebAuthn assertions now establish a browser session bound to the account, security epoch, authenticating credential and overall deadline. Normal authorized navigation uses that proof. Sensitive actions independently require recent primary and passkey factors. Application and authentication shells reflect the actual state. The application uses a section rail or a mobile disclosure. |
| Continuations and validation | A global destination let unrelated actions compete for the same continuation. Per-flow handles now carry validated same-deployment destinations or allowlisted drafts. Provider callbacks preserve their flow identity. Review GETs use the current authority and version before the user explicitly resubmits with fresh CSRF. Editable errors recover authorized identity and safe values where the form has a recovery handler. |
| Resources, sharing and portability | Scoped lists distinguish All, Owned and Shared. Creation starts with calendar, task list or journal. Editing keeps the resource identity and component type. Sharing consolidates direct/group/effective access, and member review detects a changed grant set even when its count matches. Import stages a preview and redirects to GET reports. Empty export uses resource-specific guidance. Oversized multipart CSRF and header uploads return the same styled 413. |
| Connections and security | Connection setup keeps the selected application and authorized resource through list navigation, issuance, validation and return. Application password results display client steps, server settings, username, password and expiry together. The one-time result clears secrets on departure. Security screens distinguish enrolled protection from factor freshness. Named reviews explain several destructive operations. |
| Administration | Account status and sign-in method are distinct filters. Handoff describes the sign-in method and identifies how to share the initial password. Concurrent browser administrator disables preserve at least one enabled administrator. Group pages prioritize membership. Audit translates stored codes and uses current names where possible. Diagnostics explain their origin and bounded measurements. |
| Documentation | The guide and deployment/operations documentation follow protected setup, the assertion, direct Overview, three supported resource types, sharing, recovery, app passwords, staged import and restore. The capture manifests and packaging validator check the images, local relative links and heading anchors. |

## Security behavior and evidence

The deployment keeps a 30-minute idle limit and an eight-hour overall browser-session limit. The explicit session-extension control preserves the original proof deadline and both factor timestamps. Cookie rotation, account epochs and checking the original live authenticating credential on protected requests keep a primary-only or revoked session out of the application shell. Browser validation and access-denied rendering use current authority.

V18 adds a transactional issuance ledger for application passwords and recovery sets. Its limits are 15 minutes, eight pending intents per account and 8,192 retained records. The credential or recovery set and its receipt commit together. The receipt retains a safe identifier. A repeated request cannot obtain the original plaintext or silently regenerate it. Distinct recovery intents compare the expected recovery-set generation. Same-version restoration of a backup containing a real pending intent removed it, along with restored credentials and grants according to existing restore policy. The three protected recovery qualification files stayed byte-identical.

Security regression evidence includes real signed HTTP WebAuthn, actual Keycloak login and signed issuer/subject handling, OAuth request/consent/token/refresh and revocation, protected initialization, DAV security boundaries and concurrent administrator disables. Browser qualification uses synthetic principals, an isolated browser profile, a disposable trusted CA and a virtual CTAP2 authenticator. [Spring Security MFA](https://docs.spring.io/spring-security/reference/7.1/servlet/authentication/mfa.html) · [OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) · [OWASP Logging](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)

The captured OSV feed returned no advisories across 112 Maven package versions. The deliberately vulnerable positive-control dependency returned advisories, including `GHSA-jfh8-c2jp-5v3q`. Trivy 0.76.1 scanned 115 Ubuntu 26.04 OS packages in the exact final container and returned zero High, Critical or Unknown findings under the existing gate. The report retains 248 Medium findings, 123 with a published fixed version, and five Low findings with no fixed version returned. Examples with fixes include binutils from `2.46-3ubuntu2` to `2.46-3ubuntu2.1`, libc-bin from `2.43-2ubuntu2.4` to `2.43-2ubuntu2.7`, and libexpat1 from `2.7.4-1ubuntu0.2` to `2.7.4-1ubuntu0.3`. The feeds record package findings and severities as of the checks.

## Interoperability

On Chromium 149.0.7827.55 and Playwright 1.61.1, the expanded browser qualification completed 94 assertions and captured 27 screens. Python CalDAV 3.6.0 with icalendar 7.3.0 exercised principal/home discovery, event and task CRUD, recurrence expansion and exceptions, and initial/incremental sync. Thunderbird 157.0.1 used its native CalDAV provider with an isolated profile. It exercised event and task creation, edit, deletion and sync, and exchanged four resources with Python while preserving recurrence, exceptions, alarms, all-day and floating times, unknown properties and task completion. These runs came from a portal-issued application password. GNOME desktop behavior and other clients require their own evidence.

## Accessibility and manual acceptance

The browser exercised the 320px, 390px and desktop layouts, keyboard menu opening and Escape/focus return, dark mode, forced colors and reduced-motion preference. I visually inspected all 27 illustrations and reviewed the one-time password, recovery and validation screens at full size. Captures masked generated credentials and recovery codes before writing images. The UI also carries associated field errors and safe summary focus. The remaining evaluation covers complete keyboard journeys, screen-reader announcements, text enlargement/zoom, the actual browser and passkey provider, and the two session deadlines. [WCAG 2.2 Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow) · [WCAG 2.2 Timing Adjustable](https://www.w3.org/WAI/WCAG22/Understanding/timing-adjustable.html) · [FIDO Passkey Design Principles](https://www.passkeycentral.org/design-user-journeys/principles)

The manual instance is at `https://localhost:34603`. Its certificate expires on November 30, 2026 at 00:06:03 UTC. The receipt in `.qualification-runs/ux-1246f565b115/delivery-manual/acceptance.json` identifies the installation directory and disposable CA. Trust that CA in a disposable browser profile and open setup over HTTPS. Run `bash scripts/initialize.sh` from the receipt's installation directory to obtain the operator code privately. [Getting Started guide](GETTING_STARTED.md#6--create-the-first-administrator)

1. Deliberately enter mismatched passwords, correct the form and create the administrator.
2. Sign in, then create and verify a passkey with your chosen provider.
3. Confirm direct arrival at Overview and open Server administration.
4. Create a calendar, task list and journal and explore their empty states.
5. Issue an application password and configure a supported client while keeping the result open.
6. Create, read and update a synthetic item in that client and check synchronization.
7. Share a resource and deliberately enter an invalid sharing recipient.
8. Continue browsing after ten minutes, then try a sensitive action and check where verification returns you.
9. Sign out and back in with your existing passkey.
10. Review keyboard operation, error announcements and assistive technology as available.

After testing, destructive cleanup applies to this fixture only. Run `docker compose down --volumes --remove-orphans` in its installation directory.

## Packaging, installation and restore

The bundle was produced by the local packaging workflow with source, notices and a CycloneDX SBOM. It references an immutable local image ID with `published: false` and a null registry digest. The source-free extraction walkthroughs used normal TLS validation, installation scripts and real proxy prefixes. Operations checks covered packed startup, private storage and database networking, probes, configured DAV discovery, restart, same-version database backup and restore, and V9/V17 upgrades through V18. Upgrade and restore preserved journal bytes, ETags, ownership, bindings and migration history. Restore kept initialization closed and removed stale issuance intents. Unsupported future, failed, gapped, duplicate and missing-first migration histories were rejected.

Docker's default subnet pools exhausted during parallel checks. Subsequent P0 and operations runs used explicit unused private fixture subnet pools. Those settings were confined to the task's disposable Compose projects. The existing port 18080 deployment, original volumes and normal browser profiles were left in place.

## Automated verification

The earlier runtime's clean Maven run passed 652 unit tests and ran 274 integration tests with two failures. One expected an old select control's markup after the form changed to a radio group. The other exposed multipart parameter parsing during the error dispatch for a large form-body CSRF upload. The portal layout now reads the connection origin only on credential routes. The revised assertion and targeted coverage for both multipart admission paths, grant review and client resource context then passed four real HTTPS integration tests. The earlier logs remain under `.qualification-runs/ux-6948c298ca07/`.

| Gate | Executed result |
| --- | --- |
| Final Maven clean verify | 652 unit tests and 276 integration tests passed, with zero failures, errors or skips. |
| Final Java portal integration | 36 passed with zero failures, errors or skips. This includes bounded import paths, transaction rollback, genuine signed WebAuthn, contextual validation and the two follow-up regressions. |
| Release, distribution, P0, passkeys and security Python tooling | 34, 11, 6, 14 and 11 tests passed, respectively. |
| Protocol and OAuth investigations | 6 and 4 tests passed, respectively. Their source stayed byte-identical during the repair. |
| Final archive root and `/team` | Both passed 94 browser assertions and 27 captures. The root run also passed native Python CalDAV and Thunderbird content checks. |
| Final archive native application review | The expanded `/team` run passed 100 assertions. It exercised invalid redirects, editable recovery, cancellation and named removal of a registered application. |
| Maintained root and strict synchronization browser profiles | Passed Groups, recovery-code reset, one-time application password, OAuth consent, restart and disabled-account checks. Strict mode rejected the Depth: 1 accommodation. |
| Maintained proxy and import interruption profile | Passed trusted TLS, prefixed discovery, body limits and the full browser profile. A real interruption after the first import write rolled back the transaction. Startup rejected the old preview, and a fresh preview imported successfully. |
| Maintained Keycloak browser profile | Passed on rerun with the same application image. It exercised a cross-site provider login, local-fallback denial, passkey enrollment and recovery, DAV and OAuth revocation, restart and disabled account denial. The first attempt timed out during an upstream transition after code recovery. |
| Native-client and local browser restore | Passed on rerun. The fixture accepted the new bounded continuation URL, recovered fresh passkeys and checked private and shared DAV content, sync, revocation, restart and replay rejection. Python CalDAV and Thunderbird passed before the restore. |
| Keycloak browser restore | Passed through real provider login, a previously live session at backup, replay rejection, fresh authentication after restore, preserved journal and task bytes, restored share permissions and revoked-reader denial. The initial attempt with a distinct display name reached the protected probe, which expects the username as an exact visible text node. The rerun labels the disposable restore principal with its login name. The protected probe, authentication and provider checks kept their original assertions. |
| Distribution and advisory policies | Exact JAR and image contents matched. Maven advisory and container blocking policies passed with the retained results described above. The optional additional NVD audit requires a feed credential. |

## Build identity and retained evidence

Evidence is retained locally under `.qualification-runs/ux-1246f565b115/` and indexed by the [acceptance register](UX_REPAIR_ACCEPTANCE.json). The Java build used Eclipse Temurin 25.0.5+8. Packaging and qualification used Docker 29.9.2, Compose 5.6.1 and Linux amd64. The third-party runtime dependency inventory was unchanged.

| Artifact | Identity |
| --- | --- |
| Runtime commit | `1246f565b11570ddf761089b38479061773028cd` |
| Delivery source commit | `997283b976650f97b10a57bb13361d55fba52c07` |
| JAR SHA-256 | `ec28ec6d5dd46e5e73ac438aa800d8a2b466d508c619ceed4d47104677bbc2d9` |
| Local image ID | `sha256:07e9a4306b4c6c0161442a50dffcbf5a3747fe792a3b2ddb78337f365e5f2c93` |
| Browser fixture image ID | `sha256:8daae839696b52dd625ce58c4f149ca6d8b02385d81ee7d5a6a1acb82c3ca276` |
| Runtime source archive SHA-256 | `965474b36834986b3145c42196c302913ac8c2c74dc8a9babb2dcebdcad0e442` |
| Delivery source snapshot SHA-256 | `f36d1156b2243a8e1e34aaac24994320e56acefc11ab352b54020b3976a55a6f` |
| Delivery archive SHA-256 | `145d77371dcff608ab044b559638e93d6fc591cd23ae188195c592e281a8415d` |

The image receipt hashes every runtime input. The distribution verifier matched the embedded JAR and its SBOM to that runtime. The delivery source commit changes seven image assets relative to the runtime commit. Packaging verified equality of runtime source inputs before assembling the bundle. Two independent packaging runs produced byte-identical assets.

| Starting checkout and protected files | Observed value |
| --- | --- |
| Starting HEAD, clean working tree | `75685be70c48a776322f957a17d937b5352a279b` |
| `qualification/passkeys/README.md` SHA-256 | `e8de08d838411e5a25fa19709ed9ab6f96dad8b04ae1a7be500ff215954e5c1b` |
| `qualification/passkeys/recovery.py` SHA-256 | `45d9ca63c2519c3a5ced5e182e2a352cb306511d5f01135c94f16a163f93099a` |
| `qualification/passkeys/test_recovery_refresh.py` SHA-256 | `7407ba56c278bae926e066ce127618dfb78ecaebe4ae34d327e674097c77dc90` |

The final documentation commit records acceptance evidence and can be located with `git log -1 -- docs/UX_REPAIR_ENGINEERING_REPORT.md`. Publication and production deployment were outside this local run.
