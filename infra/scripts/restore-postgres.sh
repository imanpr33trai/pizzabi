#!/usr/bin/env bash
set -euo pipefail

# PIZZORA PostgreSQL Restore Script
# Verifies SHA-256 checksum, restores a custom-format pg_dump archive onto a target database,
# and verifies Flyway history and critical table integrity.

ARCHIVE_PATH="${1:-}"
if [[ -z "$ARCHIVE_PATH" || ! -f "$ARCHIVE_PATH" ]]; then
  echo "Usage: $0 /path/to/pizzora_backup.dump" >&2
  exit 1
fi

PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-${DATABASE_USER:-pizzora}}"
PGDATABASE="${PGDATABASE:-${POSTGRES_DB:-pizzora}}"
PG_RESTORE_BIN="${PG_RESTORE_BIN:-pg_restore}"
PSQL_BIN="${PSQL_BIN:-psql}"

if [[ -z "${PGPASSWORD:-}" && -n "${DATABASE_PASSWORD:-}" ]]; then
  export PGPASSWORD="$DATABASE_PASSWORD"
fi

SHA_PATH="${ARCHIVE_PATH}.sha256"
if [[ -f "$SHA_PATH" ]]; then
  echo "[restore] Verifying SHA-256 checksum ($SHA_PATH)"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$(dirname "$ARCHIVE_PATH")" && sha256sum -c "$(basename "$SHA_PATH")")
  else
    (cd "$(dirname "$ARCHIVE_PATH")" && shasum -a 256 -c "$(basename "$SHA_PATH")")
  fi
else
  echo "[restore] WARN: Checksum file $SHA_PATH not found; proceeding without checksum verification" >&2
fi

echo "[restore] Restoring $ARCHIVE_PATH into database=$PGDATABASE host=$PGHOST port=$PGPORT"
if "$PG_RESTORE_BIN" --version >/dev/null 2>&1 && "$PSQL_BIN" --version >/dev/null 2>&1; then
  SERVER_VERSION_NUM="$("$PSQL_BIN" \
    --host="$PGHOST" \
    --port="$PGPORT" \
    --username="$PGUSER" \
    --dbname="$PGDATABASE" \
    --tuples-only \
    --no-align \
    --command="SHOW server_version_num;" | tr -d '[:space:]')"

  "$PG_RESTORE_BIN" \
    --clean \
    --if-exists \
    --no-owner \
    --no-privileges \
    --file=- \
    "$ARCHIVE_PATH" | \
    awk -v server_ver="${SERVER_VERSION_NUM:-0}" '
      BEGIN { in_copy = 0 }
      in_copy == 1 {
        print
        if ($0 == "\\.") { in_copy = 0 }
        next
      }
      /^COPY .* FROM stdin;$/ {
        in_copy = 1
        print
        next
      }
      (server_ver + 0) < 170000 && /^SET transaction_timeout = / { next }
      /^DROP SCHEMA (IF EXISTS )?(public|"public");$/ { next }
      /^CREATE SCHEMA (public|"public");$/ { print "CREATE SCHEMA IF NOT EXISTS public;"; next }
      { print }
    ' | \
    "$PSQL_BIN" \
      --host="$PGHOST" \
      --port="$PGPORT" \
      --username="$PGUSER" \
      --dbname="$PGDATABASE" \
      --single-transaction \
      --set=ON_ERROR_STOP=1
else
  "$PG_RESTORE_BIN" \
    --host="$PGHOST" \
    --port="$PGPORT" \
    --username="$PGUSER" \
    --dbname="$PGDATABASE" \
    --clean \
    --if-exists \
    --no-owner \
    --no-privileges \
    --single-transaction \
    "$ARCHIVE_PATH"
fi

echo "[restore] Verifying Flyway schema history and critical tables..."
"$PSQL_BIN" \
  --host="$PGHOST" \
  --port="$PGPORT" \
  --username="$PGUSER" \
  --dbname="$PGDATABASE" \
  --tuples-only \
  --no-align \
  --command="SELECT 'flyway_versions=' || COUNT(*), 'roles=' || (SELECT COUNT(*) FROM roles), 'orders=' || (SELECT COUNT(*) FROM orders), 'payments=' || (SELECT COUNT(*) FROM payments), 'audit_logs=' || (SELECT COUNT(*) FROM audit_logs), 'stream_cursor=' || (SELECT last_seq FROM event_stream_cursor WHERE id = 1) FROM flyway_schema_history WHERE success = TRUE;"

echo "[restore] PASS: Database restore and integrity verification completed."
