#!/usr/bin/env bash
set -euo pipefail
# Official unmodified release binaries, hashes from GitHub release asset metadata.
workflow_tools=$(mktemp -d /tmp/busystack-workflow-tools.XXXXXXXX)
trap 'rm -rf -- "$workflow_tools"' EXIT
curl --fail --silent --show-error --location --retry 3 \
  https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz \
  --output "$workflow_tools/actionlint.tar.gz"
curl --fail --silent --show-error --location --retry 3 \
  https://github.com/koalaman/shellcheck/releases/download/v0.11.0/shellcheck-v0.11.0.linux.x86_64.tar.gz \
  --output "$workflow_tools/shellcheck.tar.gz"
printf '%s  %s\n' 8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8 "$workflow_tools/actionlint.tar.gz" \
  b7af85e41cc99489dcc21d66c6d5f3685138f06d34651e6d34b42ec6d54fe6f6 "$workflow_tools/shellcheck.tar.gz" | sha256sum --check --status
tar -xzf "$workflow_tools/actionlint.tar.gz" -C "$workflow_tools" actionlint
tar -xzf "$workflow_tools/shellcheck.tar.gz" -C "$workflow_tools" shellcheck-v0.11.0/shellcheck
export PATH="$workflow_tools:$workflow_tools/shellcheck-v0.11.0:$PATH"
actionlint
shellcheck scripts/*.sh
