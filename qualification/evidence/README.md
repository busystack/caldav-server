# Final qualification evidence — 2026-10-27

The final runtime source, commit `1ebff28026645a4c5570e4cb6d4b1d8ff306665e`, passed 149 unit and 134 integration tests with no failures, errors or skips, and SpotBugs zero findings. Its independently built host/container JAR bytes match. The actual PostgreSQL, independent native/Python clients, packaged browser/proxy/organization/recovery and measured workload results below passed. Historical and failed attempts follow separately; they are not relabeled as final passes.

* Application image: `sha256:9d458a08d84255cd174e3d64a597b794c8a3eaa621b6c050a1fbcee4c798a287`.
* Executable JAR SHA256: `b6cfa0332fce0c2b0b60339b643c308a0137b828d82253a418eeb8ce911d01ac`.
* Runtime SBOM SHA256: `3bb533f23bc9358d953325f3af1d715b2f546639b1755fea902442ad5decbb32`, 112 Maven components.
* Root/proxy/organization browser fixture: `sha256:e17ee217a20278b58472fe40021a9de2d267a1927cf25f5f6a14a10737ca5213`.
* Local/federated restore browser fixture, including the strict saved-request URL assertion fix: `sha256:5cf02b1154bb2c50bbd1d6d75623c6c8032451bad8e189c9d3d5d67a36baac1b`.

## Final results

| Record | Executed scope and result |
| --- | --- |
| [Application build and artifact](application-final-date.json) | 149 unit/134 PostgreSQL and real HTTPS integration tests, zero skips, SpotBugs 0; exact commands/toolchain/JAR/image/SBOM; python-caldav 3.6.0 and actual Thunderbird 157.0.1 native provider, including four-resource same-object cross-client edits; real Keycloak 26.8.1. |
| [Packaged SQL operations](operations-final-date.txt) | Actual non-root/read-only app+PostgreSQL startup/restart, probes, discovery/metadata/DAV challenge, migrations 1–9 dump/restore, account/owner/resource/share/group/binding/property/retired-principal preservation and security-state invalidation. Controlled local/external recovery includes rejected subject mismatch. |
| [Direct root browser/groups](browser-root-final-date.txt) | Trusted-CA embedded HTTPS, actual virtual CTAP2 password+passkey MFA/session rotation, VTODO collection, group lifecycle/owner grants, recovery-code issue/use/replay/reset, negative consent-CSRF, DAV/OAuth issuance/revocation, durable session/passkey restart and disabled-account rejection. |
| [Nginx prefix browser/groups](browser-proxy-final-date.txt) | The complete account journey through pinned Nginx HTTPS with preserved `/team`; host-root discovery, RFC 8414 metadata mapping, hostile-header/canonical redirects, private management, exact DAV methods, declared/chunked limits and disabled compression. |
| [Cross-site organization browser/groups](browser-oidc-final-date.txt) | Real upstream password/code login across `app.busystack.test` and `idp.identity.test` with normal CA/SNI validation and protected Lax cookies. Exact-subject provisioning stays nonadministrator despite realm-admin; local password fallback is denied; organization primary plus local passkey, recovery, DAV/OAuth, restart and disable pass. |
| [Local HTTPS restoration](restore-local-final-date.txt) | A dump with demonstrably valid DAV/OAuth credentials is followed by actual browser revocation, restoration into an empty owned project, disabled accounts and cleared old access, then verified owner recovery/new password/passkey/DAV/OAuth. Old Basic/Bearer/refresh fail before expiry, old sync state is invalid, authoritative task/owner and fresh sync survive. A separately recovered READ recipient completes real browser MFA/DAV enrollment, GET 200/PUT 403, then owner UI revocation and subsequent denial; a WRITE grant remains stored with its account disabled. Final restart/disable also passes. |
| [Federated HTTPS restoration](restore-oidc-final-date.txt) | The same checks with the original real provider kept running. Exact issuer/subject recovery is followed by fresh real organization login/passkey enrollment, with no local-password fallback. Fresh owner/recipient access, old credential/sync rejection, share removal and durable restart/disable pass. |
| [Measured independent-client workload](workload-final-date.json) | 270 synthetic resources through python-caldav and trusted-CA HTTPS; 54.63 seconds total, 19 memory/CPU samples and before/after PostgreSQL counters, no observer errors. Resource queries explicitly use `expand=False`; server report expansion has separate protocol/semantic evidence. See [measurement interpretation](../../docs/performance.md). |

Browser fixtures use Playwright 1.61.1/Chromium 149.0.7827.55 and virtual user-verified resident CTAP2 authenticators. Generated passwords, tokens, private keys and truststores remain in private disposable fixture directories, not evidence artifacts. Each runner removes only its own generated projects/containers/volumes; the provider attachment during restore is ownership checked and removed afterward. No production certificate validation is weakened.

The desktop result is the actual native headless provider, not a manual graphical account wizard. Physical authenticators, additional desktop/mobile platforms, container OS advisory coverage and independent security certification remain unexecuted. OSV runtime results are dated feed observations; the additional anonymous NVD scan failed before analysis. The owner has not chosen a project license, which remains a public-distribution blocker. Refer to [the standards matrix](../../docs/conformance.md), [ASVS self-assessment](../../docs/security-verification.md), [versions](../../docs/versions.md) and [advisory scope](../security/README.md).

## Failed attempts retained

* An initial combined run passed 123 unit and 120 integration cases but hit the 300-second real Keycloak startup deadline before that class's authentication assertions. The bounded deadline was changed to 480 seconds based on observed startup; complete reruns subsequently passed, including the final 149/134 run. A timeout is not recorded as an authentication pass.
* The first V9 SQL smoke reached the restored application's Compose health gate and failed after its SQL invalidation/data/permission and offline-recovery assertions. No application diagnostics were retained before cleanup, so the cause is unclassified. Bounded sanitized failure diagnostics were added; serialized baseline and final-image reruns passed without a production change. That failed attempt is not converted into a pass or a startup performance guarantee.
* [Local HTTPS restore attempt1](restore-local-final-date-attempt1.txt) stopped after restored password login because its fixture expected exactly `/team/`; Spring's legitimate saved request landed on `/team/?continue`. The corrected predicate accepts only the configured origin/root path and optional single empty continue parameter, rejects other origin/path/query/fragment values and requires the account heading. Both final local/federated restore profiles then passed without a production change. [The first benchmark](workload-final-date-attempt1.json) separately passed in 55.37 seconds; it does not make that surrounding restore attempt a pass.
* An earlier remote CI run stopped before application tests because setup-java rejected Java's four-part 25.0.4.1+1 input as SemVer. The replacement installer extracts and validates the exact JDK from the pinned Temurin image. Current remote CI execution status is reported separately in the handoff; local runtime/profile records do not imply every historical CI run passed.

## Historical checkpoints

These records are superseded for final-artifact claims and retain their original limited scope.

### V1–V4 initial package

Image `sha256:f0893fa6700b52023be3321a71fb482d7291e0fef8affdf97f1bd53b80f75e94` used Temurin 25.0.4.1+1, Boot 4.1.2/Security 7.1.2, PostgreSQL 18.6, migrations 1–4 and 107 Maven components. [Operations](operations-interim.txt) passed packaged probes/restart and SQL-seeded data/ownership/READ-share recovery with revoked access invalidation. [Browser](browser-interim.txt) passed virtual CTAP2/MFA/restart/disable, but the collection named “tasks” actually used default VEVENT. It did not qualify VTODO content, independent clients, production DAV routes, Keycloak or a reverse proxy. Later fixtures explicitly select and assert VTODO.

### V7 account boundaries

[SQL operations](operations-v7.txt) passed on image `sha256:fcc996eb65cb34449f35cda17a59698e59031befe1ad1d32b41b160a01aaf613`, including authenticated-TCP PostgreSQL readiness, browser/recovery/ceremony invalidation, account generations and rejected/matching external-subject recovery. Interactive restored-provider authentication and HTTP synchronization were outside that smoke test.

[Proxy/browser](browser-proxy-v7.txt) and [cross-site organization/browser](browser-cross-site-oidc-v7.txt) passed on account image `sha256:3787cc51962da7adf8f041ae756deaf1ea162d882afdb1dbc4bffc9f7a2cabde` and browser `sha256:c3190f73ddf8b60836dd0e27a6fd441ee3a86beb0b1ce3e43c49aaa4adcb2797`. These included the verified canonical response-redirect fix and narrowly allowlisted WebAuthn JDBC mapper, normal proxy/test-CA trust, actual MFA/VTODO selection, DAV and S256 OAuth lifecycle, durable sessions/passkeys and nonadministrator organization login. They preceded the production DAV adapter and do not qualify calendar/task content or V8/V9.

An earlier V7 consent attempt failed because the framework's default JDBC module list omitted WebAuthn; the supported mapper correction and regression later passed. An earlier proxy readiness assertion rejected expected backend restart 502; a bounded readiness poll subsequently passed. An earlier cross-site startup attempt failed before browser launch; the complete later run passed. These failures remain failures with the stated scope.

### V9 pre-date baseline

[Baseline build](application-baseline-v9.json), source 8da74b8, passed 123 unit/125 integration cases and SpotBugs 0, before the final HTTP date-condition increment. Its exact image was `sha256:461ca83649a45d8ca37ba1570584f567c0bbc95279b14a6662edfc9ce6987e89`. [Serialized operations](operations-v9-baseline.txt) additionally preserved V8 folders/dead properties and V9 groups/grants/retired principals. [Proxy/groups browser](browser-proxy-v9-baseline.txt) additionally passed group-management forms, negative consent-CSRF and the real ordinary recovery-code reset/replay workflow. These are historical baseline results, not substitutes for the final image records above.
