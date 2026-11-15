#!/usr/bin/env bash
set -euo pipefail

# PIZZORA Automated Backup & Restore Drill Verification Script
# Spins up two ephemeral PostgreSQL 16 containers (source + clean restore target),
# applies Flyway migrations, seeds sample transactional rows, runs backup-postgres.sh,
# restores onto the fresh container via restore-postgres.sh, and asserts zero data loss.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
SRC_CONTAINER="pizzora-drill-src-$$"
DST_CONTAINER="pizzora-drill-dst-$$"
SRC_PORT="${DRILL_SRC_PORT:-55432}"
DST_PORT="${DRILL_DST_PORT:-55433}"
PG_PASS="drill_ephemeral_pass_123"

cleanup() {
  docker rm -f "$SRC_CONTAINER" "$DST_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

for cmd in docker pg_dump pg_restore psql; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: Required command '$cmd' not found in PATH." >&2
    exit 1
  fi
done

echo "[drill] Starting ephemeral source and target Postgres 16 containers..."
docker run -d --name "$SRC_CONTAINER" \
  -e POSTGRES_DB=pizzora -e POSTGRES_USER=pizzora -e POSTGRES_PASSWORD="$PG_PASS" \
  -p "127.0.0.1:${SRC_PORT}:5432" postgres:16-alpine >/dev/null

docker run -d --name "$DST_CONTAINER" \
  -e POSTGRES_DB=pizzora -e POSTGRES_USER=pizzora -e POSTGRES_PASSWORD="$PG_PASS" \
  -p "127.0.0.1:${DST_PORT}:5432" postgres:16-alpine >/dev/null

wait_pg() {
  local port="$1"
  for _ in $(seq 1 30); do
    if PGPASSWORD="$PG_PASS" psql -h 127.0.0.1 -p "$port" -U pizzora -d pizzora -c "SELECT 1" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "ERROR: Postgres on port $port failed to become ready." >&2
  exit 1
}

wait_pg "$SRC_PORT"
wait_pg "$DST_PORT"

echo "[drill] Applying Flyway V1-V3 migrations on source instance..."
for sql in "$ROOT_DIR"/backend/src/main/resources/db/migration/V*.sql; do
  PGPASSWORD="$PG_PASS" psql -h 127.0.0.1 -p "$SRC_PORT" -U pizzora -d pizzora -v ON_ERROR_STOP=1 -f "$sql" >/dev/null
done

echo "[drill] Seeding synthetic production-like state on source instance..."
PGPASSWORD="$PG_PASS" psql -h 127.0.0.1 -p "$SRC_PORT" -U pizzora -d pizzora -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
CREATE TABLE IF NOT EXISTS flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50), description VARCHAR(200), success BOOLEAN NOT NULL);
INSERT INTO flyway_schema_history (installed_rank, version, description, success) VALUES (1, '1', 'core schema', TRUE) ON CONFLICT DO NOTHING;
INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active)
VALUES ('11111111-1111-7111-8111-111111111111', 'drill@pizzora.app', NOW(), 'argon2_stub', 'Drill Customer', '9999999999', TRUE);
INSERT INTO orders (id, order_number, customer_id, idempotency_key, request_hash, fulfillment_type, schedule_type, status, version, subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot)
VALUES ('22222222-2222-7222-8222-222222222222', 'PZ-DRILL-001', '11111111-1111-7111-8111-111111111111', 'idem-drill-1', 'hash-1', 'PICKUP', 'ASAP', 'COMPLETED', 4, 40000, 0, 0, 2000, 42000, 500);
INSERT INTO payments (id, order_id, method, status, amount_paise, captured_paise, refunded_paise)
VALUES ('33333333-3333-7333-8333-333333333333', '22222222-2222-7222-8222-222222222222', 'COD', 'COD_COLLECTED', 42000, 42000, 0);
UPDATE event_stream_cursor SET last_seq = 42 WHERE id = 1;
SQL

echo "[drill] Running backup-postgres.sh against source..."
ARCHIVE_PATH="$(
  BACKUP_DIR="$TMP_DIR" \
  PGHOST=127.0.0.1 \
  PGPORT="$SRC_PORT" \
  PGUSER=pizzora \
  PGDATABASE=pizzora \
  PGPASSWORD="$PG_PASS" \
  bash "$ROOT_DIR/infra/scripts/backup-postgres.sh" | tail -n 1
)"

echo "[drill] Running restore-postgres.sh against clean target..."
PGHOST=127.0.0.1 \
PGPORT="$DST_PORT" \
PGUSER=pizzora \
PGDATABASE=pizzora \
PGPASSWORD="$PG_PASS" \
bash "$ROOT_DIR/infra/scripts/restore-postgres.sh" "$ARCHIVE_PATH"

echo "[drill] Asserting exact row and cursor parity between source and restored target..."
SRC_SIG="$(PGPASSWORD="$PG_PASS" psql -h 127.0.0.1 -p "$SRC_PORT" -U pizzora -d pizzora -tAc "SELECT (SELECT COUNT(*) FROM users) || ':' || (SELECT COUNT(*) FROM orders) || ':' || (SELECT total_paise FROM orders WHERE order_number='PZ-DRILL-001') || ':' || (SELECT captured_paise FROM payments) || ':' || (SELECT last_seq FROM event_stream_cursor WHERE id=1);")"
DST_SIG="$(PGPASSWORD="$PG_PASS" psql -h 127.0.0.1 -p "$DST_PORT" -U pizzora -d pizzora -tAc "SELECT (SELECT COUNT(*) FROM users) || ':' || (SELECT COUNT(*) FROM orders) || ':' || (SELECT total_paise FROM orders WHERE order_number='PZ-DRILL-001') || ':' || (SELECT captured_paise FROM payments) || ':' || (SELECT last_seq FROM event_stream_cursor WHERE id=1);")"

if [[ "$SRC_SIG" != "$DST_SIG" ]]; then
  echo "ERROR: Restored signature mismatch! src=$SRC_SIG dst=$DST_SIG" >&2
  exit 1
fi

echo "PASS: Backup & restore drill succeeded with zero data loss (signature=$DST_SIG)."
