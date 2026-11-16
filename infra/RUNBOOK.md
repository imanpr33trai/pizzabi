# PIZZORA Production Operations & Disaster Recovery Runbook

## 1. Architecture & Pre-Deploy Migration Separation

PIZZORA separates schema migration from API and background-worker runtime startup:
- **Pre-deploy migration**: `ApplicationKt --migrate-only` runs Flyway migrations (`V1`–`V3`+) against PostgreSQL and exits.
- **API & Worker runtime**: Start with `PIZZORA_ENV=production` and `RUN_MIGRATIONS_ON_STARTUP=false`. On startup, `DatabaseFactory.connect(dataSource, verifyMigrated = true)` verifies zero pending Flyway migrations and fails fast if the pre-deploy migration step was skipped.

---

## 2. Initial Production Provisioning (Container or Systemd)

### Option A: Docker Compose Production Stack
1. Copy `infra/.env.production.example` to `infra/.env.prod` and set strong random values for `JWT_SECRET`, `DATABASE_PASSWORD`, `S3_SECRET_KEY`, and Razorpay credentials.
2. Run pre-deploy migrations and boot the stack:
   ```bash
   docker compose -f infra/docker-compose.prod.yml up -d --build
   ```
   Compose automatically runs `migrate` (`--migrate-only`) to completion before starting `backend`, `worker`, and `caddy`.
3. Bootstrap the initial Owner account (once):
   ```bash
   docker compose -f infra/docker-compose.prod.yml exec backend \
     java -cp "/app/libs/*" com.pizzora.backend.ApplicationKt --bootstrap-owner
   ```

### Option B: Bare-Metal / VPS Systemd Units
1. Install `/opt/pizzora/libs/*` from `gradle :backend:installDist`, place environment config at `/etc/pizzora/pizzora.env` (`chmod 600`), and install Caddy (`/infra/caddy/Caddyfile`) or Nginx (`/infra/nginx/pizzora.conf`).
2. Install units from `infra/systemd/`:
   ```bash
   sudo cp infra/systemd/pizzora-*.service infra/systemd/pizzora-backup.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now pizzora-migrate.service pizzora-api.service pizzora-worker.service pizzora-backup.timer
   ```

---

## 3. Staging Verification Checklist

Run `BASE_URL=https://api.pizzora.app bash infra/scripts/verify-staging-tls-and-recovery.sh` to verify:
1. TLS 1.2/1.3 certificate validity and `Strict-Transport-Security` + `X-Content-Type-Options: nosniff`.
2. `GET /api/v1/health/ready` returns `200 HEALTHY` (`postgres`, `redis`, `minio` UP).
3. Public `GET /metrics` is blocked (`403`) at Caddy/Nginx while `127.0.0.1:8080/metrics` and `127.0.0.1:8081/metrics` expose Prometheus metrics.
4. `GET http://127.0.0.1:8081/health/worker` reports `HEALTHY` across all 4 worker loops.

---

## 4. Monitoring & Alert Response

Prometheus (`infra/monitoring/prometheus.yml`) scrapes `backend:8080/metrics` and `worker:8081/metrics`. Import `infra/monitoring/grafana-dashboard.json` into Grafana.

| Alert | Severity | Immediate Action |
| :--- | :--- | :--- |
| `PizzoraApiDown` | Critical | Check `journalctl -u pizzora-api` / `docker logs pizzora-prod-api` and `/api/v1/health/ready`. |
| `PizzoraWorkerDown` | Critical | Restart `pizzora-worker`; unsequenced outbox events remain safely buffered in PostgreSQL. |
| `PizzoraOutboxDeadLetterDetected` | Critical | Inspect `SELECT * FROM outbox_events WHERE dispatch_status = 'DEAD_LETTER';` and check downstream Razorpay/SMTP errors. |
| `PizzoraFailedRefundsPending` | Critical | Inspect `SELECT * FROM refunds WHERE status = 'FAILED';`; `RazorpayReconciliationWorker` polls gateway or Owner retries via `POST /owner/orders/{id}/refund`. |
| `PizzoraDbPoolExhaustion` | Warning | Check `pg_stat_activity` for long-running queries or increase `DATABASE_MAX_POOL_SIZE`. |

---

## 5. Server & Redis Restart Recovery Procedure (KDS & Realtime Continuity)

Because PostgreSQL is the single source of truth for `outbox_events` (`stream_seq`, `dispatch_status`, `published_at`) and `ws_tickets`:
1. Simulate outage during an active KDS session:
   ```bash
   docker compose -f infra/docker-compose.prod.yml restart redis backend worker
   ```
2. Verify during Redis downtime:
   - `GET /api/v1/health/ready` reports `200 DEGRADED` (`postgres=UP, redis=DOWN`).
   - Orders, KDS transitions, and `POST /auth/ws-ticket` (PostgreSQL `ws_tickets` fallback) continue operating cleanly.
3. Verify on reconnect:
   - Android KDS/Customer clients reconnect `/ws/v1/events?since_seq=<last_high_water_seq>` (or call `GET /api/v1/events/replay?since_seq=...`).
   - `EventSequencerWorker` resumes gap-free `stream_seq` assignment and `OutboxDispatcherWorker` replays any `PENDING` sequenced events without drops or duplicates.

---

## 6. Automated Backups & Disaster Recovery Drill

### Nightly Automated Backups
- Triggered daily at `21:00 UTC` (`02:30 IST`) via `pizzora-backup.timer` running `infra/scripts/backup-postgres.sh`.
- Produces compressed custom-format dump (`pizzora_<db>_<utc>.dump`) + `.sha256` checksum, uploads to `BACKUP_S3_URI`, and retains 14 days locally.

### Weekly Automated Restore Drill
Execute against ephemeral containers to verify zero data loss:
```bash
bash infra/scripts/verify-backup-restore.sh
```

### Emergency Production Restore
1. Stop API and Worker so no writes occur during restore:
   ```bash
   sudo systemctl stop pizzora-api pizzora-worker
   ```
2. Restore latest verified dump onto target PostgreSQL instance:
   ```bash
   PGHOST=127.0.0.1 PGUSER=pizzora_app PGDATABASE=pizzora_prod \
     bash infra/scripts/restore-postgres.sh /var/backups/pizzora/postgres/pizzora_pizzora_prod_<timestamp>.dump
   ```
3. Run pre-deploy migration check (`--migrate-only`) and restart services:
   ```bash
   sudo systemctl start pizzora-migrate pizzora-api pizzora-worker
   ```

## 7. Build-free Docker deployment (GitHub Container Registry)

The `.github/workflows/publish-backend.yml` workflow runs backend tests on pull requests and publishes the backend runtime image to GHCR on pushes to `main` and version tags (`vX.Y.Z`). The API, worker, and migration job all use the same immutable application image; their Compose commands select the appropriate entry point.

After the first successful publish, open the package at `https://github.com/imanpr33trai/pizzabi/pkgs/container/pizzora-backend` and set its visibility to **Public** if you want machines to pull it without authenticating. If it remains private, run `docker login ghcr.io` with a GitHub token that has `read:packages`.

For local development, pull and start the prebuilt image:

```bash
docker compose -f infra/docker-compose.yml pull
docker compose -f infra/docker-compose.yml up -d
```

For production, configure `BACKEND_IMAGE` to a tested version tag (for example, `ghcr.io/imanpr33trai/pizzora-backend:1.0.0`) in the deployment environment, then pull and start the stack:

```bash
docker compose -f infra/docker-compose.prod.yml pull
docker compose -f infra/docker-compose.prod.yml up -d
```

The Dockerfile remains the source of the image build, but Gradle and Docker builds run in GitHub Actions rather than on the deployment machine. PostgreSQL, Redis, storage, and proxy images remain separate infrastructure dependencies.

---

## 8. Rust Backend & Standalone Worker Runtime (`pizzora-api` :8080 & `pizzora-workers` :8081)

### 8.1 Container Image & Non-Root Execution (`infra/Dockerfile.rust`)
- Built with multi-stage `cargo-chef` (`rust:1.88.0-bookworm`) producing stripped release binaries `/usr/local/bin/pizzora-api` and `/usr/local/bin/pizzora-workers` inside `debian:bookworm-slim`.
- Executes strictly under non-root system user `pizzora` (`UID/GID 10001`).
- Published by `.github/workflows/publish-rust-backend.yml` to `ghcr.io/imanpr33trai/pizzora-rust-backend` (`validate` on PRs, `preview-main` on `main`, semver release tags on `vX.Y.Z`).

### 8.2 Pre-Deploy Migration Separation & Advisory Lock
- **Pre-deploy migration**: `/usr/local/bin/pizzora-api --migrate-only` (or `pizzora-workers --migrate-only`) acquires `pg_advisory_lock(884210001)` on one dedicated `PgConnection`, verifies Flyway `V1`–`V3` CRC32 checksums and catalog invariants, records `_sqlx_migrations` baseline `1..=3`, applies any `V4+` migrations, and releases `pg_advisory_unlock(884210001)` cleanly.
- **API & Worker runtime**: Boot with `RUN_MIGRATIONS_ON_STARTUP=false`; startup verifies catalog invariants read-only and aborts on schema drift without attempting auto-repair.
- **One-time Owner Bootstrap**: `/usr/local/bin/pizzora-api --bootstrap-owner` (never scheduled automatically in steady-state deployment configs).

### 8.3 Worker Loop Topology & At-Least-Once Email Semantics
- `pizzora-workers` runs four independent supervised loops with bounded concurrency and `FOR UPDATE SKIP LOCKED` leases:
  1. `sequencer_loop`: `EventSequencerWorker` under transaction advisory lock `748291041`, assigning monotonic gap-free `stream_seq`.
  2. `dispatcher_loop`: `OutboxDispatcherWorker` handling bounded-concurrent payment gateway orders, refunds, encrypted email dispatch (`Semaphore(4)`), and ordered Redis Pub/Sub broadcasts + notification inbox inserts.
  3. `expiry_sweep`: `ExpirySweepWorker` expiring unpaid orders (releasing inventory, coupon redemptions, guarded slots, and payment outbox rows), scrubbing expired `sealed_envelope` fields, and purging historical email outbox rows beyond `WORKER_OUTBOX_RETENTION_DAYS`.
  4. `reconciler_loop`: `RazorpayReconciliationWorker` polling ambiguous payment attempts and pending/failed refunds.
- **Email Delivery Semantics**: SMTP dispatch is strictly **at-least-once** (NOT exactly-once). Every email uses stable `outbox_events.id` and deterministic `Message-ID: <outbox-{outbox_event_id}@{from_domain}>`; if a worker crashes after SMTP `250 OK` and before committing `DISPATCHED`, lease expiry (`WORKER_LEASE_SECONDS`) replays the row safely while auth token consumption remains atomic and single-use (`used_at IS NULL`).
- **Health & Metrics**: `GET /health/worker` on `:8081` returns `200 HEALTHY` only when PostgreSQL and all 4 loops are healthy (`503 UNHEALTHY` otherwise); `GET /metrics` exposes Prometheus metrics including `pizzora_worker_uptime_seconds`, `pizzora_stream_last_seq`, `pizzora_outbox_*`, `pizzora_refunds_*`, and `pizzora_db_pool_connections`.

---

## 9. Isolated Railway Staging Topology

- **Production Guardrail**: Existing production service `pizzabi` (Ktor), production PostgreSQL 18.6, and production Redis remain completely untouched during Phase 8 staging.
- **Separate Staging Services** (never share DB credentials, Redis instances, `JWT_SECRET`, `OUTBOX_EMAIL_KEYS`, SMTP credentials, or live Razorpay keys with production):
  - `pizzora-rust-staging-pg18`: Dedicated Railway PostgreSQL 18.6 instance (or local `infra/docker-compose.rust-staging.yml` `postgres:18-alpine`).
  - `pizzora-rust-staging-redis`: Dedicated Railway Redis 7 instance (or local Compose `redis:7-alpine`).
  - `pizzabi-staging-api`: Runs `/usr/local/bin/pizzora-api` bound to `0.0.0.0:$PORT` (`PORT=8080`), healthcheck `/api/v1/health/ready`, `RUN_MIGRATIONS_ON_STARTUP=false`, explicit `DATABASE_MAX_POOL_SIZE=15`.
  - `pizzabi-staging-worker`: Runs `/usr/local/bin/pizzora-workers` bound to `0.0.0.0:$WORKER_HEALTH_PORT` (`8081`), healthcheck `/health/worker`, `RUN_MIGRATIONS_ON_STARTUP=false`, explicit `DATABASE_MAX_POOL_SIZE=5`. Do **not** run duplicate Ktor workers against the staging queue.
- **Connection Budget**: Verify target Railway PostgreSQL `max_connections` minus reserved superuser/admin/migrate slots (`API_MAX_POOL + WORKER_MAX_POOL + MIGRATE_POOL + 10 <= PG_MAX_CONNECTIONS`). Starting budget: API `15`, Worker `5`, Migrate job `4` against PG `max_connections=100`.

---

## 10. Complete Environment Variable Inventory (Section 13.2)

Never commit `.env` files, paste production values into docs/issues, or leave development secrets active when `PIZZORA_ENV=production`. Startup fails fast with the missing/invalid variable name (never its value).

| Category | Variable(s) | Default (Dev) | Classification & Handling |
| :--- | :--- | :--- | :--- |
| **Runtime** | `PIZZORA_ENV`, `PORT`, `RUST_LOG`, `SHUTDOWN_TIMEOUT_SECS` | `development`, `8080`, `info,sqlx::query=warn,tower_http=info`, `15` | Non-secret. Set `PIZZORA_ENV=production` in staging/prod to enforce secret validation and JSON logs. API binds `0.0.0.0:$PORT`; graceful SIGTERM drain uses `SHUTDOWN_TIMEOUT_SECS`. |
| **PostgreSQL** | `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `DATABASE_MAX_POOL_SIZE`, `DATABASE_MIN_CONNECTIONS`, `DATABASE_CONNECT_TIMEOUT_MS`, `RUN_MIGRATIONS_ON_STARTUP` | `jdbc:postgresql://localhost:5432/pizzora`, `pizzora`, *(dev default)*, `20`, `2`, `5000`, `true` (dev) / `false` (prod) | `DATABASE_URL` (if containing password) and `DATABASE_PASSWORD` are **Secrets**. Set `RUN_MIGRATIONS_ON_STARTUP=false` on API/Worker runtimes and run `--migrate-only` beforehand. |
| **Redis** | `REDIS_URL`, `REDIS_TIMEOUT_MS`, `REDIS_REQUIRED` | `redis://localhost:6379`, `500`, `false` | **Secret** if authenticated (`rediss://...`). When `REDIS_REQUIRED=false`, API & Worker degrade cleanly to PostgreSQL WS-ticket / replay fallbacks and log `DEGRADED` on `/health/ready`. |
| **Auth** | `JWT_SECRET`, `JWT_ISSUER`, `JWT_ACCESS_TTL_SECONDS`, `REFRESH_TOKEN_TTL_DAYS`, `WS_TICKET_TTL_SECONDS` | *(dev-only)*, `pizzora-api`, `900`, `30`, `30` | `JWT_SECRET` is a **Critical Secret** (`>= 32` chars; dev placeholders rejected in production). |
| **Payments** | `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `RAZORPAY_BASE_URL` | `""`, `""`, `""`, `https://api.razorpay.com/v1` | `RAZORPAY_KEY_SECRET` & `RAZORPAY_WEBHOOK_SECRET` are **Critical Secrets** (mandatory in production). Use Razorpay **Test/Sandbox** (`rzp_test_*`) in staging only. |
| **Email (SMTP)** | `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_STARTTLS` | `localhost`, `1025`, `no-reply@pizzora.local`, none, none, `false` | `SMTP_PASSWORD` (and `SMTP_USERNAME` if paired) are **Secrets**. Enable `SMTP_STARTTLS=true` for staging/prod relays. |
| **Outbox Encryption** | `OUTBOX_EMAIL_KEY_CURRENT_ID`, `OUTBOX_EMAIL_KEYS` | *(dev-only `k_dev_local_01`)* | **High-Sensitivity Secrets** (`<kid>:<base64_32byte_key>` comma-separated list). Production rejects dev default keys and keys `< 32` decoded bytes. |
| **Object Storage** | `S3_ENABLED`, `S3_ENDPOINT`, `S3_BUCKET`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_PUBLIC_URL` | `false` (prod) / MinIO (dev) | `S3_ACCESS_KEY` & `S3_SECRET_KEY` are **Secrets**. When `S3_ENABLED=false`, API skips object-storage readiness requirements and image upload routes return HTTP `503`. |
| **Worker Tuning** | `WORKER_HEALTH_PORT`, `WORKER_SEQUENCER_POLL_MS`, `WORKER_DISPATCHER_POLL_MS`, `WORKER_EXPIRY_SWEEP_MS`, `WORKER_RECONCILER_POLL_MS`, `WORKER_MAX_ATTEMPTS`, `WORKER_BATCH_SIZE`, `WORKER_LEASE_SECONDS`, `WORKER_OUTBOX_RETENTION_DAYS` | `8081`, `150`, `150`, `15000`, `30000`, `5`, `10`, `30`, `7` | Non-secret operational tuning. Keep only the standalone `pizzora-workers` process running these loops. |
| **Security & Limits** | `MAX_JSON_BODY_BYTES`, `MAX_WEBHOOK_BODY_BYTES`, `MAX_WS_FRAME_BYTES`, `RATE_LIMIT_ENABLED` | `262144`, `524288`, `65536`, `true` | Non-secret HTTP/WS guardrails enforced at startup. |
| **Bootstrap (One-shot)** | `BOOTSTRAP_OWNER_EMAIL`, `BOOTSTRAP_OWNER_PASSWORD`, `BOOTSTRAP_OWNER_NAME`, `BOOTSTRAP_OWNER_PHONE` | unset | **Secret/Sensitive**. Supply only during initial `--bootstrap-owner` command; never leave populated in persistent runtime environment variables. |

### 10.1 PostgreSQL URL Normalization & Precedence Rules (`pizzora-config`)
1. Accepts `jdbc:postgresql://`, `postgresql://`, and `postgres://` (stripping `jdbc:` prefix while preserving SSL/query parameters and bracketed IPv6 hosts like `[::1]`).
2. Percent-decodes URL userinfo and re-encodes normalized URL inside `Zeroizing<String>`.
3. **Credential Precedence**:
   - Non-empty explicit `DATABASE_USER` overrides username embedded in `DATABASE_URL` (fallback default `"pizzora"`).
   - Non-empty explicit `DATABASE_PASSWORD` overrides password embedded in `DATABASE_URL` (fallback dev default rejected in production).
4. Redacted diagnostics (`normalized_redacted_url()`) mask credentials as `***` and never emit raw secrets in errors or logs.

### 10.2 Outbox AEAD Keyring Rotation & Grace-Window Runbook
1. Generate a fresh 256-bit key (`openssl rand -base64 32`) with a new monotonic key ID (e.g. `k_2026_02`).
2. **Add before switching**: Update `OUTBOX_EMAIL_KEYS` in both API and Worker secrets to include both old and new entries (`k_2026_01:<old_b64>,k_2026_02:<new_b64>`) while leaving `OUTBOX_EMAIL_KEY_CURRENT_ID=k_2026_01`. Deploy/restart so all running workers can decrypt `k_2026_02`.
3. **Switch active writer**: Set `OUTBOX_EMAIL_KEY_CURRENT_ID=k_2026_02` and roll API + Worker. New outbox envelopes encrypt under `k_2026_02` with AAD `"{outbox_event_id}:{user_id}:{event_type}"`, while in-flight `k_2026_01` rows decrypt seamlessly.
4. **Wait > 24 hours (maximum email verification TTL) and verify zero pending old-key rows**:
   ```sql
   SELECT COUNT(*)
   FROM outbox_events
   WHERE event_type = 'EMAIL_DELIVERY_REQUESTED'
     AND dispatch_status IN ('PENDING', 'PROCESSING')
     AND payload_json->'sealed_envelope'->>'kid' = 'k_2026_01';
   ```
5. Only once count is `0` and > 24 h have elapsed, remove `k_2026_01` from `OUTBOX_EMAIL_KEYS`.

---

## 11. Phase 8 Staging Verification & End-to-End Checklist

1. **Workspace + PG 18.6 + Redis Integration Tests (requires live PG 18.6 & Redis 7)**:
   ```bash
   cd rust
   cargo fmt --all -- --check
   cargo clippy --workspace --all-targets --all-features -- -D warnings
   cargo test --workspace --all-features
   DATABASE_URL="postgres://pizzora:pizzora_dev_password@127.0.0.1:5432/pizzora" \
   TEST_POSTGRES_URL="postgres://pizzora:pizzora_dev_password@127.0.0.1:5432/pizzora" \
   REDIS_URL="redis://127.0.0.1:6379" \
     cargo test --workspace --all-features -- --ignored --test-threads=1
   ```
2. **Isolated Staging Compose Stack & Health Probes**:
   ```bash
   docker compose -f infra/docker-compose.rust-staging.yml up -d --build
   docker compose -f infra/docker-compose.rust-staging.yml ps
   curl -fsS http://127.0.0.1:8080/api/v1/health/ready
   curl -fsS http://127.0.0.1:8081/health/worker
   curl -fsS http://127.0.0.1:8081/metrics | grep pizzora_worker_uptime_seconds
   ```
3. **Railway Staging TLS, Proxy & Crash/Redis Recovery Drill**:
   ```bash
   BASE_URL="https://<your-railway-staging-api-domain>" bash infra/scripts/verify-staging-tls-and-recovery.sh
   docker compose -f infra/docker-compose.rust-staging.yml restart redis rust-worker
   curl -fsS http://127.0.0.1:8080/api/v1/health/ready
   curl -fsS http://127.0.0.1:8081/health/worker
   ```
4. **Android Staging End-to-End Verification**:
   - Build a debug/staging APK pointing strictly at the isolated Railway staging URL (`PIZZORA_API_BASE_URL=https://<your-railway-staging-api-domain>` `./gradlew :app:assembleDebug -PpizzoraRequireRust=true`) and verify customer registration/email verification, menu browsing, cart sync, checkout + Razorpay sandbox payment + webhook delivery, KDS live WebSocket order progression, and owner catalog/notification updates.

