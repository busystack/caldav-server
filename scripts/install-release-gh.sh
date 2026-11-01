#!/usr/bin/env bash
set -euo pipefail
# Isolated official CLI with release-attestation verification support. No global upgrade.
: "${RUNNER_TEMP:?RUNNER_TEMP must name a writable task directory}"
: "${GITHUB_PATH:?GITHUB_PATH must name the workflow path file}"
release_cli=$(mktemp -d "${RUNNER_TEMP}/busystack-release-gh.XXXXXXXX")
curl --fail --silent --show-error --location --retry 3 \
  https://github.com/cli/cli/releases/download/v2.103.3/gh_2.103.3_linux_amd64.tar.gz \
  --output "$release_cli/gh.tar.gz"
printf '%s  %s\n' 4c806978ffce1cec70853302a56d70ac6da4f19bae7adca4e1a8ab00633dd91d "$release_cli/gh.tar.gz" | sha256sum --check --status
tar -xzf "$release_cli/gh.tar.gz" -C "$release_cli" gh_2.103.3_linux_amd64/bin/gh
"$release_cli/gh_2.103.3_linux_amd64/bin/gh" release verify --help > /dev/null
"$release_cli/gh_2.103.3_linux_amd64/bin/gh" release verify-asset --help > /dev/null
printf '%s\n' "$release_cli/gh_2.103.3_linux_amd64/bin" >> "$GITHUB_PATH"
