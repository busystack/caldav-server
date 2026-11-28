# BusyStack CalDAV UX corrective engineering report

The corrective pass started from the clean `release/v0.2.0` checkout at
`230c59b3302689200ad79a116b5d64ee06686bcf`. The independent review and its
companion archive agree byte for byte on the review text. The two supplied
probes were rerun against the starting source and reproduced the continuation,
draft and provider-binding failures. Their isolated Java results establish the
failure mechanisms. Real application tests exercise the corrected Spring,
PostgreSQL, HTTPS and Keycloak boundaries.

The [acceptance register](UX_REPAIR_ACCEPTANCE.json) records the original 38
acceptance criteria, the 12 corrective findings, previous evidence and the
specific checks that still need evidence. The [illustrated Getting Started
guide](GETTING_STARTED.md) follows the updated workflows.

## Findings, corrections and regression evidence

The baseline reproductions, final test logs and build receipts are indexed in
the register. Java test names identify exact cases in the local reports.

| Finding | Starting behavior and cause | Correction and regression |
| --- | --- | --- |
| R01 | A real Keycloak callback linked an account after a second signed browser session removed the original proving key. The callback trusted an intent created while MFA was valid. | `BrowserSession` and `ExternalIdentityService` bind the intent to its original session, account epoch, accepted key and verification time. The conversion transaction locks and rechecks the current account and key before mutation. `removingTheProvingKeyBeforeTheProviderCallbackPreservesTheLocalAccount` checks the callback fails while the second key, local password and independently issued DAV authorization remain valid. |
| R02 | On the baseline, a normal provider start consumed a pending linking intent from another tab. The isolated resolver and real provider regressions reproduced the mismatch. | `ProviderNavigation` gives linking its own single-use start handle, binds it to one provider state, and keeps ordinary sign-in independent. `explicitMfaLinkingKeepsOwnershipAndReplacesTheOldSecurityContext` uses the actual provider, two starts and differing callback order. Resolver tests cover cancellation, expired handles and actor mismatch. |
| R03 | Inspecting the password list implicitly took an issuance slot. Over baseline HTTPS, eight mixed issuance intentions made the ordinary list return 503 and blocked the path to revocation. | `ManagementController` and the credential templates split listing from explicit creation. `credentialInspectionAndRevocationStayAvailableAtIssuanceCapacity` fills the ledger with mixed intentions, checks a contextual 503, uses the signed-in navigation and revokes from the list. |
| R04 | Spring's default bad-password redirect lost the explicit flow. The supplied source probe returned a login retry with no handle. | `AuthenticationJourney` retries with that specific handle and bounded valid username. `simultaneousAnonymousDestinationsAndWrongPasswordKeepTheirOwnFlow` uses actual HTTPS/password/MFA and verifies the other tab's destination is unaffected. |
| R05 | Two writes copied and replaced a common session map. A second provider start overwrote the single return association. Both mechanisms reproduced in the baseline probes. | `BrowserFlowStore` uses a stable hashed JDBC session scope and PostgreSQL transaction locks. The journey and provider states have independent sections. The concurrent HTTPS test overlaps two same-cookie requests, verifies both handles in the database, and resumes them after rotation. The real provider and repository tests exercise independent callbacks and single-use intent association. |
| R06 | Member removal lacked a recoverable review, registration lost the entered fields, consent lost its pending context, and stale primary recovery returned generic denial. | `BrowserFormDrafts`, `RecentMfaAuthorizationFilter`, `OAuthConsentController` and `RecoveryController` restore the named member review, safe native app fields and verified pending consent. Recovery prompts for primary sign-in and then asks for the code again. The HTTPS tests exercise stale member intent, registration, stale consent GET and POST and recovery without a registered key. Recovered pages require their own explicit submission. |
| R07 | Form recovery could rebuild a membership confirmation against new grants and implicitly approve different access. | `ReviewFormController` carries the original grant digest and links to the relevant workflow on conflict. `aStaleMemberDraftRetainsTheOriginalGrantReviewAfterReauthentication` lets another request change a grant during reauthentication and checks the original removal is rejected. |
| R08 | The resource page's primary Connect action omitted its ID and the continuation sanitizer discarded a supplied ID. | `collection.html`, `AuthenticationJourney` and the connection/credential controllers carry the canonical resource through authorized stages. The browser follows the actual resource link and completes primary sign-in and passkey enrollment as a shared reader. HTTP tests also check paging, validation, issuance and denial after access changes. |
| R09 | Group creation reset the user's return from sharing when validation or MFA interrupted it. | `GroupManagementController` and the draft controller preserve the original collection UUID and recheck ownership before return. `groupCreationReturnsToSharingAfterValidationAndFreshAuthentication` resumes the resource from both interruptions. |
| R10 | The UI offered current members and already-granted groups in addition lists. Duplicate member submission could look successful. | `GroupService`, `SharingManagementService` and the group/sharing views filter eligible choices and surface an explicit duplicate-member error. The HTTP group test checks duplicate handling and that the existing group remains available for managing its grant. |
| R11 | An unavailable import report linked to the same unavailable URL. Generic validation obscured terminal report loss and left no usable next action. | `ImportStaging` and `PortalErrors` render a typed unavailable result and a working authorized picker, with separate in-place retry after known rollback. The HTTP and browser tests follow that link and the proxy interruption profile exercises restart after an interrupted confirmation. |
| R12 | Removing access was an inline permission change without a final named review. | `SharingReviewController`, `confirm-action.html` and `collection-sharing.html` identify the resource, recipient, old permission and effective-access consequences. The confirmed POST checks fresh authority, CSRF and the original grant state. HTTP and browser cases cover cancellation and the changed-grant recovery path. |

The collection settings form now uses the creation color control and preserves
empty values, six-digit colors and eight-digit values with alpha. Optional
labels, empty read-only guidance, the app-specific page heading and group
navigation wording match their current UI. Operations and restore
documentation now describes V19.

## Security, recovery and privacy

V19 stores short-lived browser navigation separately from content and durable
authorization. Per-journey limits remain eight handles, a 15-minute lifetime
and a 32 KiB destination and draft budget. Draft fields are allowlisted by
route. Passwords, application secrets, recovery codes, uploaded contents and
consent POSTs are excluded. Keycloak's temporary protocol state is scoped to
the original browser and callback. Application-level mutations reload current
authority and require fresh CSRF and explicit resubmission. Existing idle and
overall browser deadlines, the strict ten-minute rule for each factor, and
CalDAV and OAuth authorization rules continue to apply.

Backups restore with prior browser authority invalidated. Successful recovery
clears pending V18 issuance intentions and V19 navigation state. Current and
old data-schema fixtures preserve calendar and journal bytes, ETags, immutable
history and share bindings. Operators still require the protected
initialization code from the local command for a new installation.

The previous Keycloak restore fixture made a disposable account's display
name equal its login name. The protected restore parser checks the exact
username as a visible HTML text node and a working CSRF-bearing form before the
backup. The repaired Overview renders both values as separately readable text.
The fixture again uses the login `federated-browser` and display name
`Federated Browser`. The published trust model still uses verified issuer and
subject, with no role elevation from Keycloak or local-password fallback for a
managed account.

The protected SHA-256 values, checked at the start and end, are:

| File | SHA-256 |
| --- | --- |
| `qualification/passkeys/README.md` | `e8de08d838411e5a25fa19709ed9ab6f96dad8b04ae1a7be500ff215954e5c1b` |
| `qualification/passkeys/recovery.py` | `45d9ca63c2519c3a5ced5e182e2a352cb306511d5f01135c94f16a163f93099a` |
| `qualification/passkeys/test_recovery_refresh.py` | `7407ba56c278bae926e066ce127618dfb78ecaebe4ae34d327e674097c77dc90` |

## Container advisories and official fixes

Trivy 0.76.1 inventoried 115 Ubuntu 26.04 runtime packages. The exact image
produced 253 package/CVE findings across 65 CVE IDs, with 248 Medium and 5 Low.
There were zero High, Critical or Unknown findings under the established gate.
The raw scan and all severity rows are retained. The scan covers the OS package
inventory. The separately executed OSV query covered all 112 resolved Maven
coordinates, returned no advisories and detected the existing Log4j positive
control. The optional NVD audit requires an unavailable feed credential.

Canonical's Ubuntu 26.04 records were checked for all 65 CVEs and matched to
the source package behind each binary. Canonical marks 123 package/CVE rows
released, 89 deferred and 41 needed. The Ubuntu archive snapshot at
`20261127T180000Z`, verified with the Ubuntu archive keyring, resolved all
released upgrades across 15 binary packages in an APT simulation. These builds are available upstream.
The local delivery still uses the current pinned dependency and OS layer
inventory. [Canonical snapshot service](https://snapshot.ubuntu.com/)

| Source package | Tested → signed Ubuntu candidate | Canonical security notice |
| --- | --- | --- |
| binutils | `2.46-3ubuntu2` → `2.46-3ubuntu2.1` | [USN-9214-1](https://ubuntu.com/security/notices/USN-9214-1) |
| glibc | `2.43-2ubuntu2.4` → `2.43-2ubuntu2.7` | [USN-9065-1](https://ubuntu.com/security/notices/USN-9065-1) |
| Expat | `2.7.4-1ubuntu0.2` → `2.7.4-1ubuntu0.3` | [USN-9196-1](https://ubuntu.com/security/notices/USN-9196-1) |
| PCRE2 | `10.46-1build1` → `10.46-1ubuntu0.1` | [USN-8974-1](https://ubuntu.com/security/notices/USN-8974-1) |
| Perl | `5.40.1-7ubuntu0.3` → `5.40.1-7ubuntu0.4` | [USN-9006-1](https://ubuntu.com/security/notices/USN-9006-1) |

## Final verification

| Gate | Result |
| --- | --- |
| Maven clean verify, Eclipse Temurin 25.0.5+8 | Passed with 653 unit and 288 integration tests. There were no failures, errors or skips. SpotBugs reported zero findings. The full Maven run includes 47 portal cases. |
| JDBC continuation follow-up | Both added HTTPS cases passed, covering concurrent claims, wrong-password retry, eviction and expiry. |
| Python tooling | 76 passed across release (34), distribution (11), P0 (6), passkeys (14) and security (11). |
| Protocol and OAuth investigations | Six protocol cases and four OAuth cases passed, unchanged from their established qualification definitions. |
| Extracted root bundle | 119 browser assertions and 30 captures passed, including independent Python CalDAV 3.6.0 and Thunderbird 157.0.1 round trips. |
| Extracted `/team` bundle | 119 browser assertions and 30 captures passed behind Nginx, including resource-specific sign-in, issuance response loss and import report recovery. |
| Maintained root and strict-sync browser profiles | Passed protected routes, group management, recovery and restart. Strict mode rejected the Depth: 1 accommodation. |
| Maintained proxy and transaction interruption | Passed real TLS and forwarding boundaries. Killing the disposable application after an uncommitted database write rolled the transaction back. A restarted application rejected the old preview, and a new preview imported successfully. |
| Local browser restoration and clients | Passed with Python CalDAV and native Thunderbird against a browser-issued password. Stale browser and DAV/OAuth credentials, pre-restore sync state and revoked readers were rejected after restore. |
| Real Keycloak browser restoration | Passed using the distinct names above. The protected checkpoint verified a previously live session before backup, rejected old credentials and sync state after restore, and verified fresh MFA, private and shared content, revoked-reader denial and journal synchronization. The provider-restore profile exercised ordinary Keycloak sign-in and local-fallback denial. The separate standalone attempts ended in a Chromium network-interface change and a navigation wait timeout. The complete restore profile supplied the normal sign-in and recovery evidence. |
| Portability | A 10,000-resource, more-than-60-MiB round trip overlapped with real exports. The private 8-MiB export mount also reached actual ENOSPC. BusyStack returned 503 before exposing a partial download, cleaned its staging file and exported the same bytes after space returned. The temporary-storage and fatal-allocation probes passed. |
| Empty export and stale link | All three resource types and a real last-object removal race passed over packaged HTTPS. |
| Secret-page history | Both one-time results stayed clear during Back and normal refresh. Chromium supplied persisted page events for 0 of the two results and reported `HTTPMethodNotGET` for both. [Chromium cache eligibility](https://chromium.googlesource.com/chromium/src/%2B/18aacd316ac7976d3336362b4d2dd8ad51022fe9/content/browser/back_forward_cache/back_forward_cache_impl.cc) |
| Old and current database schemas | V18 upgraded to V19. V9 restore and V19 same-version restore passed. Unsupported history left the application stopped. Pending V18 issuance intentions and V19 navigation rows were cleared. |
| Exact distribution and advisory inventory | The packaged JAR content matches the image. Maven advisory and container blocking gates passed, with individual OS findings retained. |
| Native GNOME | The graphical profile reached the application handoff. The established fixture requires Calendar `50.0-1` and Evolution Data Server `3.56.2-8`. The host provides `46.1-0ubuntu2` and `3.52.3-0ubuntu1.2`, respectively, so native GNOME qualification remains open. |

The build used the project Maven wrapper. The automated port, cross-site
Keycloak, database and native profile jobs each used owned disposable projects.
An earlier full test run shared the portal test login quota across unrelated
cases, producing 429 responses. It also exposed outdated credential-link and
OAuth redirect expectations in two fixtures. The suite now gives each portal
case a fresh throttle table, follows the explicit creation link from setup and
checks separate canonical OAuth handles together with the exact saved request.
A later stress schedule showed one atomic claimant could reach Overview without an interruption notice. The final build returns the same recovery notice for both ways a claim can lose. The case still requires exactly one claimant to resume the task. Production limits and assertions about application authority remain in force. The first repeat on the corrected image also recorded database storage errors while the scanner cached its feed. The scanner cache was removed after its successful verification, and the full Java suite ran again without that concurrent scan.

### Commands and evidence

- `./mvnw -B -ntp -DforkCount=2 clean verify` used the pinned JDK, independent-client environment and existing PostgreSQL/Testcontainers configuration. The raw Maven logs include exit codes, suite totals and class-level reports.
- `python3 -m unittest discover -s qualification/<suite> -p 'test_*.py'` ran the five tooling suites. The independent protocol and OAuth POMs ran with the same Java toolchain.
- `qualification/p0/package.py` staged and validated both local asset sets at the current package source. The extracted workflows used `qualification/p0/run.py --proxy` with the exact application and browser image IDs. The maintained browser profiles used `qualification/passkeys/run.py` with locally retained evidence.
- The startup, V18 and V9 fixture logs were generated by `scripts/verify-operations.py`. The packaged disk-full probe used an additional private 8-MiB tmpfs, filled only inside its disposable container, and recorded the rejection and successful retry.
- The retained [acceptance register](UX_REPAIR_ACCEPTANCE.json) indexes raw logs and records individual R and A states. Its evidence archive includes the actual package receipts, the baseline source probes and HTTPS reproductions, the protected hashes, scanner outputs and the official upstream status mapping.

## Artifact identity and local handoff

| Artifact | Verified identity |
| --- | --- |
| Runtime source commit | `7f6568fa7990e7e35220430e8952cfb9186626b8` |
| Package source commit | `7f6568fa7990e7e35220430e8952cfb9186626b8` |
| JAR SHA-256 | `8de40c38d57c6b55bc1cc5197ccb8f2967206c8cf8dc9907ef97f46c5293ff61` |
| Local application image ID | `sha256:f0ed8a6efee98572cf227875a01d61fb3d9c1617d909c03582dfaf789a245f96` |
| Local browser image ID | `sha256:e81f60d1e3848e7a5702a209b21cf4c2dd87df7cc261ec5521c29bf087a67f99` |
| Runtime source archive SHA-256 | `b44acce8e307fcf6ab5c8d3baa1b56f132674d363c1ea6ede5a47920b0e28052` |
| Package source snapshot SHA-256 | `d552c1bcc492360dd535bef41a35df082266b5aaba2605a5777e415a09d3f3d7` |
| Deployment archive SHA-256 | `48d6ea7ad56db0ef40137303d6a07e107ac72e7a438ca18c9256fa8ea6f20912` |

Two independent package assemblies produced identical assets. All image
runtime inputs match the package source snapshot. The manual installation was extracted from the final archive.
The fixture has its own Compose project,
private PostgreSQL volume and localhost-only proxy. At handoff no accounts
or initialization code existed, and protected setup rendered over verified TLS.

Visit [https://localhost:56145](https://localhost:56145) after trusting the fixture's public CA
in a disposable browser profile. From the fixture's `manual/install` directory,
run `bash scripts/initialize.sh` to obtain the operator code, then complete the
protected setup page. The [Getting Started guide](GETTING_STARTED.md) covers
passkey setup and ordinary administration. The leaf certificate is valid through November 30, 2026 at 05:02 UTC. The fixture's public CA and verified certificate dates are recorded in its handoff evidence.

The register records **12 passed** of 12 reviewed corrections and
**25 passed, 13 blocked** across the original 38 acceptance rows. Manual browser
and authenticator acceptance remains pending, including a user-controlled
software provider, any physical key ceremonies and assistive technology.
The register identifies each remaining exhaustive-case check. This is a local
unpublished qualification bundle.
