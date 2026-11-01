# Release procedure

Owner scope: [v0.0.1 assignment](release-0.0.1-instructions.md). Release operators
use authenticated `gh` directly. Never expose tokens, commit credentials, use a
long-lived publication PAT or bypass repository protections. Default verification
is nonpublishing, including pull requests. Publication uses only the job-scoped
repository `GITHUB_TOKEN` with contents write, Actions read and packages write.
All Actions are pinned to commits. Linux AMD64 is the sole container platform.

## Prepare and qualify

Work on `release/v0.0.1`, inspect local/remote state and preserve prior work/history.
Keep runtime/dependency versions pinned. Remove copied third-party implementations
and active patch payloads without moving/renaming them. Keep historical evidence
in Git. Run the focused parser/timezone and protocol regressions first. The
runtime artifact lock records SHA-256 of complete upstream Maven Central JARs;
never regenerate it from a possibly modified local cache. A dependency change
requires the assignment's separate approval.

Before expensive execution, run:

```sh
python3 -m unittest discover -s qualification/release -p 'test_*.py'
python3 -m unittest discover -s qualification/distribution -p 'test_*.py'
python3 -m py_compile scripts/*.py
for script in scripts/*.sh; do bash -n "$script"; done
bash scripts/validate-workflows.sh
```

Required complete verification: pinned-JDK `./mvnw -B -ntp clean verify`, protocol
and OAuth investigation suites, static analysis, official nested dependency
integrity and license delivery, OSV current advisory gate and positive control,
container OS advisories, packaged operations (same-version and V9 migration/
restoration including malformed-history rejection), passkey fixture tests, and
all six existing packaged HTTPS profiles: default, strict sync override, Nginx
subpath, real Keycloak cross-site, local restoration with independent clients,
federated restoration. Missing required prerequisites fail; optional NVD absence
is recorded separately and never replaces required OSV/container checks.

Exercise actual replacement of the retained qualified internal baseline with
`python3 scripts/verify-operations.py --image CORRECTED_IMAGE --baseline-image QUALIFIED_BASELINE_IMAGE`.
Verify the baseline's full source label and artifact identity first; local tags
alone are insufficient. This path starts the baseline, replaces it with the
corrected image and checks unchanged authoritative bytes, ETags and migration
history before restart/backup/restoration. The V9 migration fixture remains
separate. Record the corrected JAR identity and passed replacement in the client
receipt; no older public version is implied.

Run official real-client editor workflows with private disposable profiles:
Thunderbird system Vancouver, explicit Vancouver, explicit Berlin, and GNOME
Calendar/EDS Vancouver. Include actual creation/editing/sync/recurrence/occurrence,
restart/reopening, tasks where applicable and deletion; retain wire/GET/conditional
validator evidence. Preserve personal accounts/settings/trust. Read the latest
retained scripts before setup; do not reinstall working clients. Keep failed
attempts separate. Commit the concise qualification receipt at
`qualification/evidence/releases/0.0.1/official-dependencies/verification.json`.
Its exact JAR SHA-256, Dockerfile/Compose runtime-input hashes and four successful client entries are mandatory publication
gates. Full logs/screenshots and private fixture material stay in the established
external evidence locations. No different executable inherits client results.

Push coherent commits, create/update the release PR with `gh pr create/edit`, wait
for every required job and merge with `gh pr merge --merge --match-head-commit SHA`.
Never squash the audited history. Preserve any protections in effect. The release
workflow must exist on `main` before manual dispatch. Freeze the integration SHA
and run complete `verify` there; changed binaries require fresh client evidence.
Metadata-only integration may reuse matching executable/runtime qualification.

The application job retains a `verified-candidate-RUN-ATTEMPT` artifact only after
all application qualification succeeds. It contains the exact tested Docker save
archive, executable JAR, embedded SBOM and candidate checksums/source/run identity.
Separate failure evidence (`application-evidence-ATTEMPT` and `dependency-audit-ATTEMPT`) is never eligible for publication. Attempt-specific names preserve failed artifacts across retries. Rerun all jobs with `gh run rerun RUN_ID` when a verification run needs a new complete publication-eligible attempt; do not overwrite its earlier evidence. The release workflow
also checks successful `release-tools` and `dependency-audit` jobs from the latest
attempt, exact workflow/repository/main push or dispatch, SHA and every checksum.
Expired or missing artifacts require a new verification candidate, not a rebuild
inside publication. No release is triggered by push, merge or tag events.

## Stage the exact image and downloadable bundle

Before release, enable immutability using the documented admin API and verify:

```sh
gh api --method PUT repos/busystack/caldav-server/immutable-releases
gh api repos/busystack/caldav-server/immutable-releases
```

Use the exact frozen 40-character main SHA and successful run ID:

```sh
gh workflow run release.yml --repo busystack/caldav-server --ref main \
  -f version=0.0.1 -f source_sha=QUALIFIED_SHA \
  -f verification_run=VERIFY_RUN_ID -f phase=stage
gh run list --repo busystack/caldav-server --workflow release.yml
gh run watch STAGE_RUN_ID --repo busystack/caldav-server --exit-status
```

Staging loads/promotes the verified archive without rebuilding the server, checks
its image config ID, JAR and platform, and pushes only a candidate tag with run
and attempt identity. The package is associated with this repository through its
OCI source label and workflow publication. It records the **registry manifest
digest**, distinct from Docker's local config image ID and archive checksum.
`release-bundle.py` generates final version-and-digest-pinned Compose as an output,
not a committed source file. No circular digest/source commit is created.

Staging extracts the exact generated archive into a random directory, pulls its
image by digest, checks complete configuration and dependencies, exercises
non-root deployment/bootstrap/restart/backup/restore, V9 upgrade, HTTPS browser
MFA/user/calendar provisioning and recovery, and runs the exact release JAR with
host Java/PostgreSQL separately. Application/database containers have no checkout
mounts. Calendar corpus files are controller-owned test inputs resolved from the
qualification code, independently of the extracted deployment root. They are not
installed into the deployment or mounted into the application. The release-helper
regression checks this distinction with a deployment directory containing no
source tree. The successful `release-stage-RUN-ATTEMPT` artifact retains exact assets,
manifest, their checksums and installation result. No version tag/release is
created during staging. Failed installation evidence is retained but ineligible.

## Make package public, then publish

GitHub's documented package visibility control is the package settings UI, not
an invented REST endpoint. A new GHCR package defaults private regardless of
repository visibility. An organization package admin must open
https://github.com/orgs/busystack/packages/container/package/caldav-server,
choose **Package settings → Danger Zone → Change visibility → Public**, and confirm the package name.
If this UI is unavailable to the agent, request that exact operator action after
staging; complete all unaffected preparation. Public visibility cannot be assumed
from inherited access permissions. Publication explicitly verifies anonymous
registry access before creating the version tag/release.

Recheck immutable releases immediately before publication. The ephemeral workflow token cannot request repository Administration permission, so this admin API check is the dispatcher’s explicit responsibility; `immutability_confirmed=true` records that fresh check. The workflow refuses publication without it and verifies `immutable=true` after publication. Dispatch the same
frozen SHA, verification run and exact successful staging run:

```sh
gh api repos/busystack/caldav-server/immutable-releases
gh workflow run release.yml --repo busystack/caldav-server --ref main \
  -f version=0.0.1 -f source_sha=QUALIFIED_SHA \
  -f verification_run=VERIFY_RUN_ID -f phase=publish -f stage_run=STAGE_RUN_ID \
  -f immutability_confirmed=true
gh run watch PUBLICATION_RUN_ID --repo busystack/caldav-server --exit-status
```

The version concurrency group never cancels an active publishing job. After
validating candidate and staged evidence, publication rejects mismatched existing
version/tag/release/asset state, creates the exact tag, pushes the tested image as
`0.0.1`, creates a draft release using `gh release create --verify-tag --draft`,
uploads all assets without clobber, verifies server-reported SHA-256 for every
asset, then publishes the draft and verifies `immutable=true`. Relative documentation
links in the release notes are resolved to the exact qualified source commit before
the draft is created. Operations remain
in one explicitly dispatched workflow; `GITHUB_TOKEN` events are not expected to
trigger a second workflow. No `latest` tag is published.

Required assets: `caldav-server-0.0.1.jar`, `caldav-server-0.0.1-deploy.tar.gz`,
`sbom.cdx.json`, `notices.tar.gz`, `release-manifest.json`, `SHA256SUMS`.
The manifest links exact source/verification/evidence, architecture, JAR/archive
checksums, registry digest and tested local image ID with distinct field names.
The deployment archive includes notices, initialization/backup/restore scripts,
configuration examples, full HTTPS root/subpath proxy examples and operations.

## Safe retries and public verification

There is no transaction across GHCR, Git refs and Releases. Each irreversible
step appends an operation to the retained `release-operations-RUN-ATTEMPT` journal.
Inspect logs and actual state with `gh run view --log-failed`, `gh api`,
`gh release view` and registry manifest inspection. Retry the same inputs; never
move an existing version/tag to different content or replace an asset. Matching
completed operations are accepted, missing operations resume. Conflicting content
fails before overwrite. Existing immutable releases can only be verified/resumed;
a defect after publication requires a new version, never deletion/recreation.

The final workflow anonymously downloads the public assets using unauthenticated
HTTPS, verifies all SHA-256 values, uses an empty Docker configuration without
GitHub/registry credentials to pull the digest and repeats installation acceptance.
The release job installs an isolated checksum-pinned official GitHub CLI 2.103.3, verifies the cryptographic release attestation with `gh release verify`, and verifies every asset with `gh release verify-asset`. It retains the attestation result and operation journal. The system CLI/authentication configuration is unchanged.

After successful publication, retain source-bound concise results in the release
evidence index, including actual run URLs, digest, assets/checksums, client evidence
locations and limitations. The stable authoritative generated identity reference is
https://github.com/busystack/caldav-server/releases/download/v0.0.1/release-manifest.json.
Use it in the website handoff; do not paste identities from earlier candidates.

References: [GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry),
[manual dispatch](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow),
[token events](https://docs.github.com/en/actions/how-tos/writing-workflows/choosing-when-your-workflow-runs/triggering-a-workflow),
[immutability API](https://docs.github.com/en/rest/repos/repos#enable-immutable-releases),
[package visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility),
[immutable release lifecycle](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases).
