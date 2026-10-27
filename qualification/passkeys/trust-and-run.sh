#!/usr/bin/env bash
set -euo pipefail
test -r "${BUSYSTACK_BROWSER_CA_FILE:?Mount the disposable test CA}"
install -d -m 700 /root/.pki/nssdb
certutil -N --empty-password -d sql:/root/.pki/nssdb
certutil -A -d sql:/root/.pki/nssdb -n 'BusyStack disposable qualification CA' -t 'C,,' -i "$BUSYSTACK_BROWSER_CA_FILE"
exec node /qualification/passkeys.mjs
