#!/usr/bin/env bash
set -euo pipefail
# Run from the extracted deployment directory. Only this local one-off process
# prints the secret, after the verifier transaction has committed.
docker compose run --rm --no-deps -T application \
  --spring.main.web-application-type=none \
  --spring.main.banner-mode=off --logging.level.root=OFF \
  --busystack.issue-initialization-code=true
