#!/usr/bin/env bash
set -euo pipefail

# This script is run only by the official image on an empty PostgreSQL volume.
BUSYSTACK_DATABASE_PASSWORD=$(cat /run/secrets/spring.datasource.password)
export BUSYSTACK_DATABASE_PASSWORD
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --no-psqlrc --set ON_ERROR_STOP=1 <<'SQL'
\getenv app_password BUSYSTACK_DATABASE_PASSWORD
CREATE ROLE busystack LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER DATABASE busystack OWNER TO busystack;
ALTER SCHEMA public OWNER TO busystack;
SQL
unset BUSYSTACK_DATABASE_PASSWORD
