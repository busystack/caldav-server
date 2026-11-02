# Release procedure

The literal `project.version` of the application `pom.xml` is the only maintained
application version. `scripts/release_version.py` reads that direct Maven element,
checks the project coordinates, and accepts only `X.Y.Z` without leading zeros.
Do not maintain an application version in another configuration file. Dependency,
probe, and private browser-harness package versions identify separate software.

Work on the version's release branch. Preserve historical evidence and published
versions. Updating the application version means changing the application POM,
writing that version's release notes and obtaining fresh qualification; no release
script, workflow or Dockerfile needs a version-number edit.

## Developer builds

Use the pinned JDK described in `docs/versions.md`, the Maven wrapper and Python 3.
Commit source changes before producing an image with an exact Git revision:

```sh
python3 scripts/build-image.py
```

This runs Maven clean package, selects exactly the Maven-named executable JAR,
validates Maven coordinates, manifest and embedded SBOM, and builds a Linux AMD64
image named `busystack-caldav:<project.version>`. It supplies the version and Git
revision to Docker and verifies the resulting OCI labels and exact JAR checksum.
`target/image-build.json` records the identities. No version argument is accepted.
CI uses the same entry point with `--jar` to package the already tested JAR without
rebuilding it. Stale extra JARs and dirty source checkouts are rejected.

For development Compose, configure the ordinary database secrets and HTTPS URLs
from the operations guide, then use the same entry point:

```sh
python3 scripts/build-image.py --compose up -d --no-build --wait
```

The wrapper supplies Compose's image, build context, version and source revision.
Raw source-directory Docker and Compose builds fail without these inputs. The
Dockerfile also validates the supplied metadata against the exact packaged JAR.
Development Compose can build only the prepared, checked context. Published
Compose has no application build and pulls the version plus registry digest.

## Qualification and source identities

Run the complete application, investigation, security, distribution and packaged
HTTPS suites from `.github/workflows/ci.yml`. Pull requests verify without
publishing. Candidate retention does not publish a release or registry tag.

Perform the established real-client editor qualification on the actual version's
executable: Thunderbird system Vancouver, explicit Vancouver and explicit Berlin,
and GNOME Calendar/EDS Vancouver. Retain raw results and failed attempts in CI artifacts or the external audit archive, with a concise index; do not commit routine logs, screenshots, credentials or workstation diagnostics. Record
only executed results at `qualification/evidence/releases/<version>/verification.json`.
The record identifies `version`, `jarSha256`, the actual client source/image,
`runtimeInputs` SHA-256 values for `Dockerfile` and `compose.yaml`, client versions,
passed profile results and limitations. Historical internal-baseline replacement
work remains historical evidence; it is not a permanent future-version gate.
The reusable four client profiles and executable/runtime binding remain required.
Do not copy old pass claims to a new version.

The successful CI candidate retains the exact JAR, embedded SBOM, saved container
and complete source snapshot. Its version is resolved at its exact source commit,
not at a later controller checkout. The snapshot is checked against every Git
blob. Candidate identity distinguishes source SHA/tree, workflow/run/attempt,
JAR SHA-256, local Docker image ID, canonical saved-image configuration digest
and saved-image checksum. Loading on another Docker engine may produce a different
local ID; the loaded runtime configuration, filesystem layers and JAR must match.

## Approved integration

Create a PR from the release branch into `main`. After owner release approval,
squash it with commit message `Release v<version>` and preserve the release branch.
For the first implementation release, verify `main` contains exactly the original
`first commit` and that release commit, with the approved complete tree. Qualify
that final squash commit in CI. Official artifacts must identify that commit;
a branch candidate's different source SHA is not an official final revision.
Do not add commits to `main` merely to retain generated release metadata.

While `main` contains only the original README, manual Actions dispatch is
unavailable because workflows are absent from the default branch. Do not add a
workflow to work around this. Development and push verification use the release
branch until the approved squash merge.

## Stage, then publish

Use authenticated `gh`. Both operations are explicit manual dispatches on `main`;
concurrency is version-specific without cancellation. Minimum workflow permissions
are contents write, Actions read and packages write for the promotion job.

```sh
SOURCE_SHA=the_exact_successfully_qualified_main_commit
VERIFY_RUN=the_successful_verification_run_id
VERSION=$(python3 scripts/release_version.py --source-sha "$SOURCE_SHA")
gh workflow run release.yml --ref main \
  -f version="$VERSION" -f source_sha="$SOURCE_SHA" \
  -f verification_run="$VERIFY_RUN" -f phase=stage \
  -f candidate_version="$VERSION-rc.1"
```

Staging must execute at the qualified commit. It verifies the repository, CI
workflow, event, SHA, run attempt and all required jobs (`release-tools`,
`application`, `dependency-audit`). It rejects version mismatches, missing or
ambiguous artifacts, conflicting image tags and invalid qualification records.
It loads the saved tested image without rebuilding, validates OCI metadata and
JAR bytes, and pushes only the selected readable candidate tag.

The registry manifest digest is distinct from the local Docker image ID. Staging
builds the deployment bundle only from its checked qualified-source snapshot:
Compose, initialization/backup/restoration scripts, documentation, proxy examples,
release notes, licenses and evidence. `deployment/INSTALL.md` and operator guides
are maintained templates; `@VERSION@` is rendered from that source's POM.
Generated image digests and checksums come from verified artifacts. The complete
bundle includes the SBOM and version-specific client record.

Staging tests the exact digest-pinned archive from an arbitrary extraction path,
including Compose recovery, HTTPS browser restoration and the independent JAR
alternative. It retains all six final assets, release notes, installation receipt
and a stage record hashing them. Changed assets require a new verified candidate.

An administrator must verify that immutable releases are enabled using GitHub's
repository immutable-releases API immediately before publication. Confirm the
repository-associated GHCR package is public; do not change unrelated packages.
Then dispatch against the exact successful staging run:

```sh
STAGE_RUN=the_successful_staging_run_id
gh workflow run release.yml --ref main \
  -f version="$VERSION" -f source_sha="$SOURCE_SHA" \
  -f verification_run="$VERIFY_RUN" -f phase=publish \
  -f stage_run="$STAGE_RUN" -f immutability_confirmed=true
```

A newer trusted publication controller may resume the qualified ancestor source.
The requested version must still match the old source POM, candidate JAR, SBOM and
OCI labels. The controller may orchestrate validation; its files cannot become
release payloads. Publication downloads and validates the exact staged assets and
notes. It does not regenerate them from the controller. Installation and stage
receipts must match the selected version, source, CI attempt and artifact hashes.
The final image tag is attached to the exact staged registry manifest bytes using
the registry API. Publication does not export the image again through a different
Docker engine, which could change the registry manifest digest.

Publication creates or matches the exact `v<version>` tag, versioned image and
draft release. It rejects conflicting content, uploads only missing exact assets,
checks all remote digests, then publishes immutably. Interrupted operations can
resume with the same source/run/stage. Never overwrite a conflicting image, move a
tag or replace an immutable release. No `latest` image tag is created.

Verify the immutable release attestation, each asset, anonymous GHCR access and
anonymous downloaded-asset installation. Retain the operation journal and actual
identities. A failed step is not a successful release. Existing published versions
remain untouched unless their separately approved retirement conditions are met.
For releases with discontinuous ancestry, compare explicit tag-to-tag source trees.
