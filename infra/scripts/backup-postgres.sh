#!/usr/bin/env bash
set -euo pipefail

# PIZZORA Automated PostgreSQL Backup Script
# Creates a consistent custom-format pg_dump archive + SHA-256 checksum,
# optionally uploads to off-site S3 storage, and prunes expired local backups.

BACKUP_DIR="${BACKUP_DIR:-/var/backups/pizzora/postgres}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-${DATABASE_USER:-pizzora}}"
PGDATABASE="${PGDATABASE:-${POSTGRES_DB:-pizzora}}"
PG_DUMP_BIN="${PG_DUMP_BIN:-pg_dump}"

if [[ -z "${PGPASSWORD:-}" && -n "${DATABASE_PASSWORD:-}" ]]; then
  export PGPASSWORD="$DATABASE_PASSWORD"
fi

mkdir -p "$BACKUP_DIR"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARCHIVE_PATH="$BACKUP_DIR/pizzora_${PGDATABASE}_${TIMESTAMP}.dump"
SHA_PATH="${ARCHIVE_PATH}.sha256"

echo "[backup] Starting pg_dump database=$PGDATABASE host=$PGHOST port=$PGPORT"
"$PG_DUMP_BIN" \
  --host="$PGHOST" \
  --port="$PGPORT" \
  --username="$PGUSER" \
  --dbname="$PGDATABASE" \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-privileges \
  --file="$ARCHIVE_PATH"

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$BACKUP_DIR" && sha256sum "$(basename "$ARCHIVE_PATH")" > "$(basename "$SHA_PATH")")
else
  (cd "$BACKUP_DIR" && shasum -a 256 "$(basename "$ARCHIVE_PATH")" > "$(basename "$SHA_PATH")")
fi

echo "[backup] Created archive=$ARCHIVE_PATH checksum=$SHA_PATH size=$(wc -c < "$ARCHIVE_PATH") bytes"

if [[ -n "${BACKUP_S3_URI:-}" ]]; then
  if command -v aws >/dev/null 2>&1; then
    echo "[backup] Uploading archive and checksum to ${BACKUP_S3_URI}"
    aws s3 cp "$ARCHIVE_PATH" "${BACKUP_S3_URI%/}/$(basename "$ARCHIVE_PATH")" --only-show-errors
    aws s3 cp "$SHA_PATH" "${BACKUP_S3_URI%/}/$(basename "$SHA_PATH")" --only-show-errors
  else
    echo "[backup] WARN: BACKUP_S3_URI is set but aws CLI is not installed; skipping remote upload" >&2
  fi
fi

if [[ "$BACKUP_RETENTION_DAYS" =~ ^[0-9]+$ ]] && [[ "$BACKUP_RETENTION_DAYS" -gt 0 ]]; then
  find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'pizzora_*.dump' -o -name 'pizzora_*.dump.sha256' \) -mtime +"$BACKUP_RETENTION_DAYS" -delete
  echo "[backup] Pruned local backups older than ${BACKUP_RETENTION_DAYS} days"
fi

echo "$ARCHIVE_PATH"
