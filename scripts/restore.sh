#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# != 1 ]]; then
  printf 'Usage: %s ARCHIVE_PATH\nRestore requires a stopped application and an empty database.\n' "$0" >&2
  exit 2
fi
archive=$1
test -r "$archive"
test -r "$archive.sha256"
archive_directory=$(dirname -- "$archive")
archive_filename=$(basename -- "$archive")
(cd "$archive_directory" && sha256sum --check --status "$archive_filename.sha256")
running=$(docker compose ps --status running --services)
while IFS= read -r service; do
  if [[ "$service" == application ]]; then
    printf 'Stop the application before restoring; database must remain running.\n' >&2
    exit 2
  fi
done <<< "$running"
table_count=$(docker compose exec -T database psql --username postgres --dbname busystack --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 --command "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema');")
if [[ "$table_count" != 0 ]]; then
  printf 'Restore target is not empty. Use an isolated, newly initialized database; this script does not delete existing data.\n' >&2
  exit 2
fi
docker compose exec -T database pg_restore --username postgres --role busystack --dbname busystack --exit-on-error --single-transaction --no-owner --no-acl < "$archive"
docker compose exec -T database psql --username postgres --dbname busystack --no-psqlrc --set ON_ERROR_STOP=1 --single-transaction <<'SQL'
DO $recovery$
DECLARE
  affected integer;
BEGIN
  IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE success AND version IS NOT NULL AND version NOT IN ('1', '2', '3', '4', '5', '6', '7', '8', '9')) THEN
    RAISE EXCEPTION 'This recovery script supports schema versions 1 through 9 only; leave application stopped and update recovery procedure';
  END IF;
  UPDATE system_state SET recovery_epoch = gen_random_uuid();
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 1 THEN
    RAISE EXCEPTION 'Expected exactly one system_state row';
  END IF;
  DELETE FROM dav_credentials;
  UPDATE accounts SET enabled = false;
  IF to_regclass('public.oauth_refresh_families') IS NOT NULL THEN
    UPDATE oauth_refresh_families SET revoked_at = COALESCE(revoked_at, now());
  END IF;
  IF to_regclass('public.oauth2_authorization') IS NOT NULL THEN
    DELETE FROM oauth2_authorization;
    DELETE FROM oauth2_authorization_consent;
  END IF;
  IF to_regclass('public.user_credentials') IS NOT NULL THEN
    DELETE FROM user_credentials;
    DELETE FROM user_entities;
  END IF;
  IF to_regclass('public.external_identities') IS NOT NULL THEN
    UPDATE external_identities SET last_verified_at = timestamptz '1970-01-01 00:00:00+00';
  END IF;
  IF to_regclass('public.spring_session') IS NOT NULL THEN
    DELETE FROM spring_session;
    DELETE FROM account_recovery_codes;
    DELETE FROM webauthn_ceremonies;
    DELETE FROM login_throttle;
    UPDATE accounts SET security_epoch = gen_random_uuid();
    INSERT INTO security_audit(action,outcome) VALUES ('DATABASE_RESTORE','SUCCESS');
  END IF;
END;
$recovery$;
SQL
printf 'Restored database, disabled all accounts, changed recovery epoch, removed DAV credentials, passkeys and OAuth grants, and revoked refresh families.\n'
printf 'Keep every application instance stopped. Verify each user identity, then use the documented local or external offline account-recovery command to enable only that verified account. Later schemas require a reviewed recovery procedure.\n'
