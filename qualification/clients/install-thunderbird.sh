#!/usr/bin/env bash
set -euo pipefail

# Test tooling only. Published Mozilla binary and its pinned official SHA512SUMS entry.
destination=${1:?Usage: install-thunderbird.sh EMPTY_TOOL_DIRECTORY}
mkdir -p "$destination"
tool_root=$(cd "$destination" && pwd)
test ! -e "$tool_root/thunderbird" || { echo 'Tool directory already contains Thunderbird; choose an empty directory.' >&2; exit 1; }
archive=$(mktemp "$tool_root/thunderbird-download.XXXXXX")
trap 'rm -f "$archive"' EXIT
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 --max-time 300 \
  https://archive.mozilla.org/pub/thunderbird/releases/157.0.1/linux-x86_64/en-US/thunderbird-157.0.1.tar.xz \
  --output "$archive"
printf '%s  %s\n' '8918c554924886f21ecb394cd877cf69cd3f343fb3dfab829a9793fbf558b1e90a32249a2f76afa781fdbb09e4f498b12f146f6078cf4be9339c09897c3fee27' "$archive" | sha512sum --check --status
tar --extract --xz --file "$archive" --directory "$tool_root"
printf '%s\n' "$tool_root/thunderbird/thunderbird"
