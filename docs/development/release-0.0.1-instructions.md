# Complete and publish BusyStack CalDAV Server v0.0.1

Owner assignment, received 2026-10-31. This is the authoritative scope and acceptance checklist for the new implementation agent in `~/busystack/caldav-server` (`busystack/caldav-server`). Preserve the original audited history.

## 1. Assignment and scope

Complete third-party-source cleanup, preserve existing functionality, qualify the corrected software, implement reliable release automation, and **publish v0.0.1 through GitHub Releases and GitHub Container Registry**.

Starting branch: `release/v0.0.1`. Last verified baseline: `e094e9bf4ce34b5a8559ab25fc29f06971163752`. Verify actual local and remote state first; do not reset subsequent work to this baseline. `gh` is installed and authenticated: use it directly for inspection, PRs, Actions, releases and supported API operations; do not require a separate agent integration or reconnection.

This assignment authorizes necessary commits, pushes, release-branch integration into `main`, tag, GitHub release and public container publication **after all verification requirements pass**. Preserve repository protections; never bypass them.

Out of scope: `busystack.org` modifications/deployment, Cloudflare, APT/DEB/RPM/Snap, additional architectures, new product features, unrelated refactoring. Commit this instruction and the separate Section 8 website-agent instruction.

## 2. Establish actual starting state

Read repository instructions; inspect branch, worktree, remotes, recent changes, release/tag/package state and GitHub permissions. Preserve unrelated changes. Read README, operations, release notes and latest timezone-repair verification; parser/timezone patch documents, calendar semantics, versions and notices; POM, Dockerfile, Compose, CI and existing distribution/qualification scripts.

Use `gh` to establish permissions and inspect failed workflow logs where needed. Never print tokens or credentials or put PATs in workflows. Existing CI performs application tests, advisory checks, artifact consistency, deployment qualification and packaged restoration: extend this machinery without weakening it. Inspect source selectively; do not repeat the standards audit, reinstall working clients or recreate the project.

## 3. Remove modified third-party implementations without regressions

Use **official, unmodified third-party software only**. No patching, forking, mechanical translation, renaming, relocation, shading or rebuilding of modified third-party implementations. Changing package names or removing attribution does not establish independent ownership.

Remove both current-source and distributed copies:

- `src/main/java/org/busystack/caldav/calendar/parser/Ical4jCalendarParser.java`
- `src/main/java/org/busystack/caldav/calendar/timezone/Ical4jZoneRulesBuilder.java`

Verify references and build output. Replace active parser use with official iCal4j supported interfaces and narrowly scoped independently written BusyStack integration. Establish signatures from the selected dependency's actual API/source. Prefer existing abstractions and upstream extension points, without replacing the calendar engine. No copied implementation in a renamed class, reflection, classpath shadowing, instrumentation or hidden patched binaries.

Preserve multi-valued/quoted parameters, RFC 6868 decoding, literal carets/quotes, UTF-8 folding, boundaries and extensions; recurrence/exceptions/custom zones, historical/future transitions and evaluation limits; malformed-input rejection, atomic writes, authorization, ETags/conditional writes, synchronization and restoration. Retain regressions for multi-valued `MEMBER`, `CN=^^n` and `CN=^'Babe^'`.

Preserve the approved Thunderbird repair unchanged in purpose/scope: enabled by default, only recognized local-observance UNTIL converted using TZOFFSETFROM, corrected data validated, transformed PUT validators omitted; preserve disable setting and separate approved sync Depth behavior. Do not reopen these decisions or add exceptions.

Check source, scripts, generated classes and dependencies for other modified third-party implementations, as a focused provenance check, not a broad security audit. Remove obsolete patch application/payload paths from the current distribution; preserve historical evidence and Git history without rewriting it. Update provenance truthfully and keep notices required by distributed dependencies. Retain selected dependency/runtime versions; a necessary change needs a specific explanation and approval. Add maintainable reintroduction and packaged-integrity regressions. Build cleanly to eliminate stale classes.

## 4. Complete installation artifacts requiring no source build

Publish `ghcr.io/busystack/caldav-server:0.0.1`, record immutable registry manifest digest, and GitHub release `v0.0.1` with executable JAR, complete Compose archive, checksums, SBOM, notices and machine-readable manifest. Support only qualified **Linux AMD64**; do not advertise ARM64, Windows containers or untested environments.

The versioned archive (`caldav-server-0.0.1-deploy.tar.gz`) must contain everything its configuration references: production Compose pulling version **and digest**, no application `build:`, PostgreSQL initialization, safe examples without secrets, and installation/bootstrap/client/update/backup/restore/removal instructions. It must work in an arbitrary extraction directory, independently of the checkout, `/home/albert`, qualification directories, Maven or host JDK. Separate developer builds from administrator installation.

Preserve non-root application, private DB, persistent storage, file-backed secrets, health checks, loopback exposure and HTTPS. Default arrangement has only application and PostgreSQL; no Keycloak. Include complete HTTPS reverse-proxy examples clearly requiring operator-supplied domain and certificate. Downloads do not supply those. Document JAR alternative with actual Java/PostgreSQL requirements; no native installers or service framework.

Manifest must identify source SHA, version, CI run, JAR checksum, registry digest, architecture, deployment checksum and qualification evidence. Distinguish registry manifest digest, local image ID and archive checksum. Generate digest-pinned deployment files as release outputs; avoid committing the final digest and thereby changing the revision claimed by its own image. Never overwrite earlier prepared artifacts; corrected candidate needs distinct verified identities.

## 5. Reliable GitHub Actions automation

Separate **verification/artifact preparation** from **public publication**. Branch/PR verification remains nonpublishing and preserves required checks/evidence. Successful verification retains distributable JAR and exact tested container for promotion; failed evidence is never publication-eligible. Validate YAML, shell, names, permissions, versions and packaging helpers before expensive qualification.

Explicit publication workflow, preferably `workflow_dispatch`, takes version and exact successful verification run/source. No publication on every push or merge. Validate repository, expected workflow, success of required jobs, exact source match, checksums/version agreement and absence of conflicting release/tag/image version. **Promote tested artifacts; do not silently rebuild**. Rebuild means a new candidate requiring verification. Minimum job-scoped permissions, commit-pinned Actions, no publication credentials in untrusted PRs. Publish GHCR with workflow `GITHUB_TOKEN`, not a long-lived PAT.

A dispatched workflow must first exist on default branch. Do not depend on a `GITHUB_TOKEN` tag/release event triggering another workflow; keep dependencies together or explicitly dispatch/reuse. Serialize same-version publication without cancellation by new verification. Safe retries recognize matching completed operations and reject conflicts. Nondestructively test wrong SHA, failed/incomplete verification, missing artifacts, checksum mismatch and conflicting version.

Associate GHCR with repository and make it public; new package defaults private, public image must allow anonymous pull. Never move version tag to different content; deployments use digest, no `latest`. Enable GitHub release immutability before publication, assemble draft, upload/verify assets, then publish and lock assets/tag. Use documented APIs/CLI and installed help; no invented endpoints. If required visibility/immutability needs unavailable admin UI, identify exact action and finish all safe preparation without silently skipping it.

Authoritative references:

- https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry
- https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow
- https://docs.github.com/en/actions/how-tos/writing-workflows/choosing-when-your-workflow-runs/triggering-a-workflow
- https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases

## 6. Qualify corrected implementation and installation

Focused development regressions, then once stable complete existing verification, investigation tests, distribution checks, advisories, all six packaged HTTPS profiles and both restoration workflows. Do not weaken/suppress tests or count missing prerequisites as passes. Keep failed attempts separately. Reuse fixtures/client automation and read latest execution records before desktop setup.

Real official unmodified clients with disposable profiles, preserving personal accounts/credentials/settings/system trust:

- Thunderbird: system and explicit America/Vancouver, explicit Europe/Berlin; real creation, synchronization, corrected retrieval, conditional edit, recurrence/occurrence edit, tasks, restart persistence, deletion.
- GNOME Calendar/EDS: established Vancouver workflow; real-editor creation/editing, synchronization, recurrence, reopening after restart, deletion.

Handcrafted HTTP supplements but never replaces real-client workflows.

Exercise actual downloadable bundle in clean disposable environment without source mounts: extraction, configuration, secrets, pull, startup, HTTPS, bootstrap, first user/calendar, client access, persistence, backup and restore. Qualify JAR separately. Test update/replacement with existing supported baseline/migration fixtures; do not invent older public versions. Changing image does not undo DB migrations.

Before publishing, test staged registry image by digest and exact staged archive. Afterwards anonymously download public assets and pull without GitHub/registry credentials, repeat focused install smoke. Clean runner/disposable Docker is acceptable; no arbitrary VM purchase. Reuse evidence only with matching identities: identical JAR/runtime inputs may retain client qualification across metadata-only merge; different executable cannot inherit it.

## 7. Integrate and publish

Remain on release branch until cleanup, packaging, documentation and workflow ready. Coherent commits, no rewriting audited history. Use `gh` PR into `main`; merge only after required checks, preserve history/protections, no squash. Freeze final main commit; verify exact source/artifact/CI relation and integration differences. Earlier run does not qualify changed binaries.

Create `v0.0.1` at exact qualified SHA; never implicitly select current default head. Use CLI `--verify-tag` (https://cli.github.com/manual/gh_release_create). Run publication workflow and watch actual result via `gh`. Complete public GHCR and immutable release; verify identities, available attestations and anonymous checks. No cross-service atomic transaction exists: record each operation for safe resume. After immutable publication do not delete/recreate release or move tag; defect requires new version and explicit report. **Do not stop at ready to publish when requirements and permissions are satisfied.**

## 8. Repository documentation and website handoff

README administrator path starts with published bundle/image; source builds separate. Update operations, release notes, provenance, compatibility, conformance references. Keep approved exceptions and version-scoped verified client claims, without universal compatibility/certification.

Document preparation, exact qualification, `gh`, permissions, ordering, retries, immutable versions and anonymous verification. Commit self-contained `docs/releases/0.0.1-website-handoff.md` instructing next agent to add product/install information to existing `busystack.org`:

- Verified self-hosted calendar/task/admin scope, optional external identity, exclusions.
- Actual repo/release/package links and authoritative release manifest.
- Primary Compose installation, JAR alternative.
- Prerequisites, HTTPS, bootstrap, client setup, updates, backup/restore, license, supported configurations, limitations.
- Inspect website repo and follow existing architecture/styling/localization/deployment.
- Acceptance for links/download names/commands/published-release consistency.

Use stable manifest references for generated checksums/digests. No invented website route or completion claim and no website repository changes. Durable docs in Git; detailed evidence in established locations; no secrets or unnecessary raw logs committed.

## 9. Discipline and final handoff

Resolve ordinary implementation, fixture, packaging and workflow problems autonomously. Avoid setup-only reports, duplicate full suites after minor edits, broad investigations without findings. Precisely report unavailable permission/setting or unresolved defect; finish unaffected work, preserve resumable checkpoint, never call blocked work passed.

Final response must state removal/preserved behavior; final SHA, merged PR, tag/release/public image/digest; actual verification including clients and anonymous install; asset/checksum identities/evidence; assignment/procedure/website instruction paths; remaining blockers/limits.

Completion: administrators install published server without compiling, distribution contains no unauthorized modified third-party implementations, established workflows pass, next website task fully specified but not executed.
