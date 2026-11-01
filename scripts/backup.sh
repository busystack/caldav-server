#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# != 1 ]]; then
  printf 'Usage: %s NEW_ARCHIVE_PATH\n' "$0" >&2
  exit 2
fi
archive=$1
if [[ -e "$archive" || -e "$archive.sha256" ]]; then
  printf 'Refusing to replace an existing backup or checksum.\n' >&2
  exit 2
fi
temporary=$(mktemp "${archive}.partial.XXXXXXXX")
trap 'rm -f -- "$temporary"' EXIT
docker compose exec -T database pg_dump --username postgres --dbname busystack --format custom --no-owner --no-acl > "$temporary"
test -s "$temporary"
ln -- "$temporary" "$archive"
rm -- "$temporary"
archive_directory=$(dirname -- "$archive")
archive_filename=$(basename -- "$archive")
(cd "$archive_directory" && sha256sum -- "$archive_filename") > "$archive.sha256"
printf 'Created database archive and checksum. Back up deployment configuration and secret files separately.\n'
