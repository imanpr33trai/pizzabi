# PIZZORA Production Operations & Disaster Recovery Runbook

## 1. Architecture & Pre-Deploy Migration Separation

PIZZORA separates schema migration from API and background-worker runtime startup:
- **Pre-deploy migration**: `/usr/local/bin/pizzora-api --migrate-only` acquires PostgreSQL session advisory lock `884210001` on a dedicated connection, verifies Flyway `V1`–`V3` checksums/invariants, records `_sqlx_migrations` baseline `1..=3` (or applies additive `V4+` migrations), and exits cleanly. *(During Ktor rollback, pre-deploy Flyway is skipped; `DatabaseFactory.connect(dataSource, verifyMigrated = true)` validates schema compatibility read-only.)*
- **API & Worker runtime**: Start with `PIZZORA_ENV=production` and `RUN_MIGRATIONS_ON_STARTUP=false`. Startup verifies schema invariants read-only and aborts on drift without executing migrations.

---

## 2. Initial Production Provisioning (Container or Systemd)

### Option A: Docker Compose Production Stack
1. Copy `infra/.env.production.example` to `infra/.env.prod` and set strong random values for `JWT_SECRET`, `DATABASE_PASSWORD`, `OUTBOX_EMAIL_KEYS`, `SMTP_PASSWORD`, and Razorpay credentials (plus `S3_SECRET_KEY` if `S3_ENABLED=true`).
2. Run pre-deploy migrations and boot the stack:
   ```bash
   docker compose -f infra/docker-compose.prod.yml up -d --build
   ```
   Compose automatically runs `migrate` (`--migrate-only`) to completion before starting `backend`, `worker`, and `caddy`.
3. Bootstrap the initial Owner account (once):
   ```bash
   docker compose -f infra/docker-compose.prod.yml exec backend \
     /usr/local/bin/pizzora-api --bootstrap-owner
   ```

### Option B: Bare-Metal / VPS Systemd Units
1. Install `/usr/local/bin/pizzora-api` and `/usr/local/bin/pizzora-workers` (extracted from the verified `RUST_BACKEND_IMAGE` container or built via `cargo build --release --bin pizzora-api --bin pizzora-workers`), copy `infra/.env.production.example` to `/etc/pizzora/pizzora.env` (`chmod 600`), create `pizzora` system user + `/opt/pizzora`, and install Caddy (`/infra/caddy/Caddyfile`) or Nginx (`/infra/nginx/pizzora.conf`).
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

For production, configure `RUST_BACKEND_IMAGE` to a tested version tag or digest (for example, `ghcr.io/imanpr33trai/pizzora-rust-backend:1.0.0`), then pull and start the stack (use `BACKEND_IMAGE` with `infra/docker-compose.ktor-rollback.yml` if rolling back to Ktor):

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
- **Deployment automation (DEFERRED)**: Phase 6 staging deployment automation is intentionally deferred by user decision. No staging deployment workflow, GitHub `staging` environment, or Railway staging resource is created or modified by this change. The original requirements are preserved in Section 16 for a future, separately approved implementation.

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

---

## 12. Phase 9 — Railway Production Cutover, Expand-and-Contract Rollback & Ktor Retirement

> **Critical Safety Rule:** Never alter production Railway services, run production migrations, or retire Ktor code/images without explicit written approval for the cutover window and exact service names (`pizzabi`, `pizzabi-worker` in Railway project `bubbly-peace`). Keep `RUN_MIGRATIONS_ON_STARTUP=false` on both Rust runtime services and Ktor rollback deployments.

### 12.1 Pre-Cutover Gates
Before executing Step 1 of the cutover sequence, confirm all six gates:
1. **Change Window Approval:** Written user approval for the exact production cutover window, Railway project (`bubbly-peace`), and services (`pizzabi`, `pizzabi-worker`).
2. **Backup & Verified Restore Proof:** Fresh production PostgreSQL 18.6 `pg_dump` created, stored off-container (encrypted S3 / backup host), and validated via `bash infra/scripts/verify-backup-restore.sh` / `infra/scripts/restore-postgres.sh`.
3. **Artifact & Environment Inventory:**
   - Record current production Ktor deploy ID and pinned rollback image digest (`BACKEND_IMAGE=ghcr.io/imanpr33trai/pizzora-backend@sha256:...`).
   - Record approved Rust release image digest (`RUST_BACKEND_IMAGE=ghcr.io/imanpr33trai/pizzora-rust-backend@sha256:...`).
   - Verify production Railway variables on both `pizzabi` and `pizzabi-worker`: `PIZZORA_ENV=production`, `RUN_MIGRATIONS_ON_STARTUP=false`, `DATABASE_MAX_POOL_SIZE` (`15` API / `5` Worker — within PG `max_connections` minus reserved migrate/admin connections), strong `JWT_SECRET`, production `OUTBOX_EMAIL_KEY_CURRENT_ID` + `OUTBOX_EMAIL_KEYS`, production Razorpay keys (`rzp_live_*` + webhook secret), and SMTP credentials.
4. **Schema Compatibility:** Production remains `V1`–`V3` parity on initial cutover; any future `V4+` migration is strictly additive and expand-and-contract reviewed.
5. **Staging Sign-Off & Backlog Check:** Zero unresolved P0/P1 defects, all staging suites green, and zero unacknowledged dead-letter rows (`outbox_events WHERE dispatch_status = 'DEAD_LETTER'`).
6. **Worker Drain Proof:** Confirmed procedure to stop Ktor workers and verify `0` active worker sessions in `pg_stat_activity` before starting `pizzora-workers`.

### 12.2 Production Cutover Sequence
1. **Freeze Schema Changes & Record Identifiers:** Announce schema freeze and record active Ktor deploy ID / rollback digest (`ghcr.io/imanpr33trai/pizzora-backend@sha256:...`).
2. **Production Backup & Off-Host Verification:**
   ```bash
   BACKUP_DIR=/var/backups/pizzora/postgres BACKUP_S3_URI="<off-host-s3-uri>" \
     bash infra/scripts/backup-postgres.sh
   ```
   Verify restoreability onto an isolated temporary PG 18.6 target before touching production services.
3. **Read-Only Production Preflight Check (Zero Mutation):**
   ```bash
   PIZZORA_ENV=production \
     /usr/local/bin/pizzora-api --preflight-only --require-zero-dead-letters
   ```
   Confirm `flyway_schema_history` shows `V1`–`V3` intact, `catalog_invariants_ok: true`, and inspect `active_worker_sessions` and `stream_high_water_seq`.
4. **Stop Ktor Workers & Prove Drain:**
   Stop any Ktor worker process (`pizzora-worker` or embedded worker instance) and assert zero worker DB sessions remain:
   ```bash
   PIZZORA_ENV=production \
     /usr/local/bin/pizzora-api --preflight-only --require-zero-workers --require-zero-dead-letters
   ```
   Do **not** run Ktor and Rust workers concurrently against the same production queue.
5. **Run Pre-Deploy Locked Migration / Handoff Once:**
   ```bash
   PIZZORA_ENV=production DATABASE_MAX_POOL_SIZE=4 \
     /usr/local/bin/pizzora-api --migrate-only
   ```
   Capture logs and confirm `mode: AdoptExistingFlywayV1ToV3`, `pg_advisory_lock(884210001)` acquired/released cleanly, `applied_or_verified_versions: [1, 2, 3]`, and zero `V1`–`V3` DDL re-execution.
6. **Deploy Rust API & Rust Worker:**
   Apply `.railway/railway.ts` (targeting `infra/Dockerfile.rust`, `pizzabi` preDeploy `/usr/local/bin/pizzora-api --migrate-only`, start `/usr/local/bin/pizzora-api`, healthcheck `/api/v1/health/ready`; `pizzabi-worker` start `/usr/local/bin/pizzora-workers`, healthcheck `/health/worker`) or roll the VPS/Compose production stack via `docker compose -f infra/docker-compose.prod.yml up -d`.
7. **Run Cutover Smoke Tests:**
   ```bash
   BASE_URL=https://api.pizzora.app \
   SMOKE_TEST_EMAIL="<dedicated-smoke-account>" \
   SMOKE_TEST_PASSWORD="<smoke-password>" \
   WORKER_INTERNAL_URL="http://127.0.0.1:8081" \
     bash infra/scripts/verify-production-cutover.sh
   ```
   Verify `/api/v1/health/ready`, catalog/config/OpenAPI, external `/metrics` blocked, auth/refresh/logout, cart/notifications/orders/ws-ticket, invalid webhook signature rejection, KDS WebSocket sequence progression, and worker `/health/worker` across all 4 loops.
8. **Monitor Post-Cutover SLOs:**
   Watch `infra/monitoring/grafana-dashboard.json` and `infra/monitoring/alerts.yml` for:
   - HTTP 5xx rate (`PizzoraHttp5xxSpike`)
   - SQLx DB pool saturation (`PizzoraDbPoolExhaustion`)
   - Outbox unsequenced / pending dispatch backlog & dead-letter count (`PizzoraOutboxSequencerBacklog`, `PizzoraOutboxDeadLetterDetected`)
   - Failed Razorpay refunds (`PizzoraFailedRefundsPending`)
   - Worker loop heartbeats & loop errors (`PizzoraWorkerDown`, `PizzoraWorkerLoopErrors`)
9. **Hold Ktor Rollback Assets Warm:** Keep `.railway/railway.ktor-rollback.ts`, `infra/docker-compose.ktor-rollback.yml`, `infra/Dockerfile.backend`, `backend/`, `.github/workflows/publish-backend.yml`, and the pinned Ktor image digest available throughout the agreed production stability window.

### 12.3 Rollback Triggers & Exact Rollback Procedure
Trigger immediate rollback if any of the following occur:
- Sustained elevated HTTP 5xx rate or `/api/v1/health/ready` failures.
- Auth login/refresh failures, cart ownership regression, or checkout/payment/refund state inconsistency.
- Unbounded worker outbox backlog, duplicate payment/refund dispatches, schema drift, or loss of critical metrics/logs.

**Rollback Sequence (Order Matters):**
1. **Stop Rust Worker First:** Scale down/stop `pizzabi-worker` (or `docker compose stop worker`) so no dual workers touch `outbox_events`. Verify `SELECT pid, application_name, query FROM pg_stat_activity WHERE state <> 'idle'` shows no active Rust worker transactions.
2. **Route Away / Stop Rust API:** Drain or stop `pizzabi` Rust runtime.
3. **Restore Pinned Ktor Image with Migrations Disabled:**
   - Railway: deploy `.railway/railway.ktor-rollback.ts` (pinned pre-cutover `infra/Dockerfile.backend` / release digest) with `RUN_MIGRATIONS_ON_STARTUP=false` and **no** Flyway pre-deploy run.
   - Docker Compose / VPS: `BACKEND_IMAGE=<pinned-ktor-digest> docker compose -f infra/docker-compose.ktor-rollback.yml up -d`.
4. **Verify Ktor Health & Smoke Tests:** Confirm `/api/v1/health/ready`, `/metrics` (`pizzora_hikari_*`), login/catalog/checkout, and single-worker operation.
5. **No Unnecessary DB Restore:** Do **not** run `restore-postgres.sh` unless schema/data corruption is proven; PostgreSQL `V1`–`V3` + `_sqlx_migrations` leaves Ktor schema untouched.

### 12.4 Expand-and-Contract Policy (`V4+`) & Ktor Retirement
- **Expand-and-Contract (`V4+`):** Before Ktor retirement, every new SQLx migration (`V4+`) MUST be backward-compatible with running/rollback Ktor instances (additive nullable columns, defaults, or side tables only; no destructive column drops, renames, or incompatible constraint tightening until the contract phase after Ktor is retired).
- **Ktor Retirement Criteria (Explicit User Approval Required):** Only after completing the agreed production stability window with clean SLO metrics and explicit written user sign-off may Ktor backend runtime source (`backend/`), `infra/Dockerfile.backend`, `.github/workflows/publish-backend.yml`, `.railway/railway.ktor-rollback.ts`, and `infra/docker-compose.ktor-rollback.yml` be archived/removed.
- **Permanent Retention:** **Never** delete historical Flyway migrations (`V1__core_schema.sql`–`V3__hot_path_indexes.sql`), backup artifacts, or audit/cutover evidence.

---

## 13. Railway Multi-Service Deployment & Reusable Template Architecture

### 13.1 Service Inventory & Responsibilities

| Service | Source / Runtime | Responsibility | Exposure |
| :--- | :--- | :--- | :--- |
| `pizzabi-api` *(or `pizzabi` in `bubbly-peace`)* | `infra/Dockerfile.rust` (`/usr/local/bin/pizzora-api`) | Axum HTTP API, OpenAPI, Prometheus `/metrics`, WebSockets `/ws/v1/events`, and single pre-deploy migration runner | Public HTTPS domain (`0.0.0.0:$PORT`, default `8080`) |
| `pizzabi-worker` | `infra/Dockerfile.rust` (`/usr/local/bin/pizzora-workers`) | 4 supervised loops: `EventSequencerWorker`, `OutboxDispatcherWorker`, `ExpirySweepWorker`, `RazorpayReconciliationWorker` | Private network only (`0.0.0.0:8081`; **no** public domain) |
| `postgres` | Railway managed PostgreSQL (PG 18) | System-of-record relational database (`V1`–`V3` Flyway + `V4+` SQLx) | Private network (`*.railway.internal`) |
| `redis` | Railway managed Redis | Ephemeral menu cache, rate limiting, WS ticket cache, WebSocket Pub/Sub fanout | Private network (`*.railway.internal`) |
| Object Storage *(optional)* | `S3_ENABLED=false` default; S3-compatible bucket + public proxy when enabled | Product image upload (`presign`/`confirm`/`delete`) and public image URLs | External HTTPS endpoint / CDN proxy |
| `mailpit` *(staging/dev only)* | `axllent/mailpit:v1.22` | Private SMTP sink (`:1025`) & web inbox UI (`:8025`) for staging email testing | Private SMTP; optional staging-only UI |

### 13.2 Exact Build, Start, Pre-Deploy, and Healthcheck Settings

All services built from this repo share `infra/Dockerfile.rust` (multi-stage `cargo-chef` with `rust:1.88.0-bookworm`, stripped non-root `debian:bookworm-slim` runtime `pizzora:pizzora`, `STOPSIGNAL SIGTERM`) and root `.dockerignore` (which preserves both Rust and `infra/Dockerfile.backend` Ktor rollback inputs while excluding `app/` and `rust/target/`).

| Setting | `pizzabi-api` / `pizzabi` | `pizzabi-worker` | `mailpit` *(staging only)* |
| :--- | :--- | :--- | :--- |
| **Dockerfile** | `infra/Dockerfile.rust` | `infra/Dockerfile.rust` | Image `axllent/mailpit:v1.22` |
| **Pre-Deploy** | `["/usr/local/bin/pizzora-api", "--migrate-only"]` | *(None — never duplicate migrations)* | *(None)* |
| **Start Command** | `/usr/local/bin/pizzora-api` | `/usr/local/bin/pizzora-workers` | *(Image default)* |
| **Bind & Port** | `0.0.0.0:$PORT` (default `8080`) | `0.0.0.0:$WORKER_HEALTH_PORT` (set `PORT=8081` & `WORKER_HEALTH_PORT=8081`) | SMTP `:1025`, UI `PORT=8025` |
| **Healthcheck** | `GET /api/v1/health/ready` (`300s` timeout) | `GET /health/worker` (`300s` timeout) | `GET /api/v1/info` (`120s` timeout) |
| **Public Domain** | Enabled | **Disabled** (private only) | Optional in staging; **never** in prod |

> **Critical Worker `$PORT` Rule:** Railway healthchecks always probe `$PORT`, whereas `pizzora-workers` binds `$WORKER_HEALTH_PORT` (`8081`), and `infra/Dockerfile.rust` sets `ENV PORT=8080`. Always keep **both** `PORT=8081` and `WORKER_HEALTH_PORT=8081` configured on `pizzabi-worker` in Railway.

### 13.3 Service-by-Service Environment Variables (Non-Secrets vs Secrets)

Reference inventory: the variable lists below. Startup fails fast in `PIZZORA_ENV=production` if any mandatory secret is absent or uses a dev default (printing only the variable name, never its value).

- **API Non-Secrets (`pizzabi-api` / `pizzabi`):** `PIZZORA_ENV=production`, `PORT=8080`, `RUN_MIGRATIONS_ON_STARTUP=false`, `SHUTDOWN_TIMEOUT_SECS=15`, `DATABASE_MAX_POOL_SIZE=15`, `DATABASE_MIN_CONNECTIONS=2`, `DATABASE_CONNECT_TIMEOUT_MS=5000`, `REDIS_TIMEOUT_MS=500`, `REDIS_REQUIRED=false`, `S3_ENABLED=false`, `RATE_LIMIT_ENABLED=true`, `RUST_LOG=info,sqlx::query=warn,tower_http=info`.
- **Worker Non-Secrets (`pizzabi-worker`):** Same non-secret flags except `PORT=8081`, `WORKER_HEALTH_PORT=8081`, `DATABASE_MAX_POOL_SIZE=5`, `DATABASE_MIN_CONNECTIONS=1`, plus `WORKER_SEQUENCER_POLL_MS=150`, `WORKER_DISPATCHER_POLL_MS=150`, `WORKER_EXPIRY_SWEEP_MS=15000`, `WORKER_RECONCILER_POLL_MS=30000`, `WORKER_MAX_ATTEMPTS=5`, `WORKER_BATCH_SIZE=10`, `WORKER_LEASE_SECONDS=30`, `WORKER_OUTBOX_RETENTION_DAYS=7`.
- **Shared Secrets (Railway Variables / Secrets only, per environment):** `DATABASE_URL` (`${{postgres.DATABASE_URL}}`), `REDIS_URL` (`${{redis.REDIS_URL}}`), `JWT_SECRET` (`>=32` random chars), `OUTBOX_EMAIL_KEY_CURRENT_ID` + `OUTBOX_EMAIL_KEYS` (`<kid>:<base64_32byte_key>`), `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, SMTP (`SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_STARTTLS`), and if `S3_ENABLED=true`, `S3_ACCESS_KEY` + `S3_SECRET_KEY`.

### 13.4 Single Image Without Duplicate Migrations or Duplicate Worker Loops

- Both `pizzabi-api` and `pizzabi-worker` build from `infra/Dockerfile.rust`, which packages both `/usr/local/bin/pizzora-api` and `/usr/local/bin/pizzora-workers` inside one immutable runtime image.
- **Single Migration Mechanism:** Only `pizzabi-api` runs `preDeploy: ["/usr/local/bin/pizzora-api", "--migrate-only"]` (acquiring `pg_advisory_lock(884210001)` on a single `PgConnection`, verifying Flyway `V1`–`V3` checksums, baselining `_sqlx_migrations`, and applying `V4+`). Both `pizzabi-api` and `pizzabi-worker` run with `RUN_MIGRATIONS_ON_STARTUP=false`, so runtime startup only calls `verify_catalog_invariants` read-only. On a brand-new project deploy where `pizzabi-worker` boots before `preDeploy` finishes, `verify_catalog_invariants` exits non-zero cleanly and Railway restarts `pizzabi-worker` until the schema is ready.
- **Single Worker Owner:** `pizzora-api` never spawns outbox/reconciliation background loops; only `pizzabi-worker` executes those loops using `FOR UPDATE SKIP LOCKED` leases.

### 13.5 PostgreSQL Connection Budget & Redis Degraded Mode

- **Pool Sizing:** Starting defaults are `DATABASE_MAX_POOL_SIZE=15` (`pizzabi-api`) + `5` (`pizzabi-worker`) + `4` transient during `preDeploy` (`24` peak during rollout, `20` steady-state), leaving safe headroom for superuser/monitoring connections against Railway PostgreSQL (`max_connections=100` typical default; verify `SHOW max_connections;` on the target plan before scaling replicas).
- **Redis (`REDIS_REQUIRED=false`):** Redis speeds up menu caching, rate limiting, and multi-instance WebSocket Pub/Sub fanout. If Redis is temporarily unreachable when `REDIS_REQUIRED=false`, `/api/v1/health/ready` returns HTTP `200 DEGRADED`, checkout/KDS/WS tickets fall back cleanly to PostgreSQL, and outbox `stream_seq` + `/api/v1/events/replay` prevent dropped realtime events.

### 13.6 Object Storage (`S3_ENABLED=false` vs `S3_ENABLED=true` & MinIO/Bucket Notes)

- When `S3_ENABLED=false`, API startup skips S3 validation, `/api/v1/health/ready` reports `status: "HEALTHY"` with S3 disabled, and owner image upload routes return HTTP `503 OBJECT_STORAGE_UNAVAILABLE`.
- Upstream `minio/minio` open-source development is archived/unmaintained, so Railway templates should **not** deploy unmanaged MinIO containers in production.
- **Railway Storage Buckets Compatibility Note:** Railway native Storage Buckets (`t3.storageapi.dev`) are **private by default** and use **virtual-hosted-style URLs**, whereas `pizzora-api`'s `S3ObjectStorageClient` signs **path-style** canonical URIs (`/{bucket}/{key}`) and serves unsigned `S3_PUBLIC_URL/{key}` links to Android clients. Therefore, keep `S3_ENABLED=false` by default in templates unless pairing a path-style-compatible S3 endpoint (e.g., Cloudflare R2, AWS S3 regional endpoint, or Garage) with a publicly readable `S3_PUBLIC_URL` CDN/proxy.

### 13.7 Staging vs Production Isolation & Reusable Template Deployment

- **Staging (`mailpit` + Sandbox Keys):** Uses separate Railway `postgres` + `redis`, `mailpit` (`axllent/mailpit:v1.22`) wired via `SMTP_HOST=${{mailpit.RAILWAY_PRIVATE_DOMAIN}}`, `SMTP_PORT=1025`, `SMTP_STARTTLS=false`, Razorpay test keys (`rzp_test_*`), and dedicated staging `JWT_SECRET` + `OUTBOX_EMAIL_KEYS`.
- **Live Production Guardrail (`bubbly-peace`):** `.railway/railway.ts` keeps `export const partial = "pizzabi"` targeting `project("bubbly-peace")` with ONLY `pizzabi` and `pizzabi-worker` so production DB, Redis, volumes, domains, and secrets remain untouched.
- **Reusable New Stack / Template Provisioning:** Use `.railway/railway.template.ts` (targeting a **new** Railway project, never `bubbly-peace`) or create a Railway Dashboard Template mirroring `.railway/railway.template.ts` and the variable inventory in Section 13.3.

### 13.8 Common Railway Failure Troubleshooting

| Symptom | Root Cause | Resolution |
| :--- | :--- | :--- |
| **`pizzabi-worker` healthcheck times out (`Service Unavailable`)** | Worker listens on `WORKER_HEALTH_PORT` (`8081`) while Railway probes `$PORT` (`8080` from Dockerfile `ENV`). | Set **both** `PORT=8081` and `WORKER_HEALTH_PORT=8081` on `pizzabi-worker`. |
| **Crash loop immediately after deploy naming `JWT_SECRET` / `OUTBOX_EMAIL_KEYS` / `RAZORPAY_*`** | `PIZZORA_ENV=production` fail-fast validation rejected a missing secret, short `<32`-char `JWT_SECRET`, or dev placeholder. | Populate distinct strong secrets in Railway Variables for that service/environment. |
| **API `preDeploy` fails with `FLYWAY_CHECKSUM_MISMATCH` or missing table** | Schema drift against `V1`–`V3` Flyway checksums or dirty migration state. | Inspect `flyway_schema_history` and `_sqlx_migrations` manually; do not auto-repair blindly. Run `--preflight-only` to inspect invariants. |
| **Worker logs `verify_catalog_invariants` error on first boot of empty DB** | `pizzabi-worker` booted concurrently while `pizzabi-api` `preDeploy` (`--migrate-only`) was still applying `V1`–`V3`. | Wait for `pizzabi-api` `preDeploy` to complete; Railway auto-restart brings `pizzabi-worker` healthy (`200 HEALTHY`). |
| **`too many clients` / pool timeout** | Combined `DATABASE_MAX_POOL_SIZE` across replicas + `preDeploy` exceeded Postgres `max_connections`. | Check `SHOW max_connections;`, lower pool sizes or enable Railway PgBouncer (`DATABASE_URL` pooled + `DATABASE_UNPOOLED_URL` for `preDeploy`). |
| **API `DEGRADED` (`redis: DOWN`)** | `REDIS_URL` wrong/unreachable with `REDIS_REQUIRED=false`. | Verify `${{redis.REDIS_URL}}` private network binding; API stays operational on PostgreSQL fallbacks. |

---

## 14. Android In-App Updates & GitHub Release Distribution

Signed Android production APKs (`com.aistudio.pizzora.xkqzmw`) are distributed via GitHub Releases (`imanpr33trai/pizzabi`) alongside `update.json` and `SHA256SUMS.txt` generated by `.github/scripts/generate-release-metadata.py` during tag builds (`vX.Y.Z`) in `.github/workflows/security-and-ci.yml`.

See [docs/IN_APP_UPDATES.md](../docs/IN_APP_UPDATES.md) for release tagging, `release-policy.json` (`minimumSupportedVersionCode` and `mandatory` updates), APK signature verification & PackageInstaller flows, troubleshooting, and Android forward-only hotfix procedures when rolling back a faulty release.

### 14.1 Release Tag Protection & Production Signing Environment

Production tags (`refs/tags/v*`) are protected by the repository ruleset
`release-tag-protection`, whose desired-state payload is
[`.github/rulesets/release-tag-protection.json`](../.github/rulesets/release-tag-protection.json).
The ruleset blocks tag creation, update, deletion, and non-fast-forward moves
for every actor except the repository admin (`imanpr33trai`, user id
`123877434`), who has an explicit bypass so a single-maintainer release remains
possible. Non-admin collaborators and automation cannot create or move release
tags.

`build-release-apk` in `.github/workflows/security-and-ci.yml` additionally:

1. Runs **“Verify release tag points to a commit on main”** after checkout and
   fails unless the tagged commit is an ancestor of `origin/main`.
2. Uses the protected **`production`** environment, which requires reviewer
   approval and only permits deployments from `v*` tags.

The same protected environment also covers every other production publishing
job: the Kotlin and Rust release Docker images (via the conditional
`inputs.mode == 'release'` environment in the reusable workflows) and
`publish-github-release`. A release therefore creates several `production`
deployments, each of which can require approval; `prevent_self_review` is
`false`, so the single maintainer can approve their own deployments. Approve
each pending deployment in **Actions → the run → Review deployments**.

**Verified live state (2026-11-20, read-only API inspection):**

| Control | Observed state |
|---|---|
| `release-tag-protection` ruleset | active, id `27707812`, target `tag`, include `refs/tags/v*`, rules `creation`, `update`, `deletion`, `non_fast_forward`, bypass only user `123877434` |
| `production` environment | required reviewer `imanpr33trai` (`prevent_self_review=false`), custom deployment policy `v*` (type `tag`) |
| `production` environment secrets | **empty** — `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD` are still repository-level |
| `build-release-apk` | `environment: production`, semver validation, monotonic `versionCode`, tag-on-`main` ancestry check, signature/ABI/package-ID verification, SHA-256 checksums |
| `publish-github-release` | `environment: production`, requires `build-release-apk` success, verifies published assets and checksums |

Desired-state application (admin only; run once — re-posting the
ruleset creates a duplicate, so check `rulesets?targets=tag` first):

```bash
REPO=imanpr33trai/pizzabi
gh api -X POST "repos/$REPO/rulesets" \
  --input .github/rulesets/release-tag-protection.json
gh api -X PUT "repos/$REPO/environments/production" --input - <<'JSON'
{
  "wait_timer": 0,
  "prevent_self_review": false,
  "reviewers": [{ "type": "User", "id": 123877434 }],
  "deployment_branch_policy": {
    "protected_branches": false,
    "custom_branch_policies": true
  }
}
JSON
gh api -X POST "repos/$REPO/environments/production/deployment-branch-policies" \
  --input - <<'JSON'
{ "name": "v*", "type": "tag" }
JSON
```

The three Android signing secrets (`ANDROID_KEYSTORE_BASE64`,
`ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD`) are still repository-level
secrets at the time of writing; moving them into the `production` environment
must be done manually because secret values cannot be read back through the
API:

```bash
# Read each value from the secure password manager, then set it on the
# environment. Repeat for all three names.
gh secret set ANDROID_KEYSTORE_BASE64 --env production --repo imanpr33trai/pizzabi
gh secret set ANDROID_STORE_PASSWORD --env production --repo imanpr33trai/pizzabi
gh secret set ANDROID_KEY_PASSWORD --env production --repo imanpr33trai/pizzabi
```

Only after a controlled `v*` release succeeds with the environment secrets,
delete the repository-level copies in **Settings → Secrets and variables →
Actions**. Do not delete them first.

**Preserve the signing identity.** Copy the existing production upload keystore
and its existing passwords; do **not** generate a new keystore or rotate the
upload key during this migration. A different certificate would make new
releases unupgradable over installed builds and would break Play App Signing
upload continuity. Before and after the migration, verify that the certificate
fingerprint is unchanged (run this locally against the secure backup, never in
CI logs):

```bash
keytool -list -v -keystore upload-keystore.jks -alias upload \
  -storepass '<from secure backup>' | grep -E 'Alias name|SHA256:'
```

Record the fingerprint in the secure password manager. `build-release-apk`
already rejects a debug certificate and verifies the APK package ID
(`com.aistudio.pizzora.xkqzmw`), `versionCode`, `versionName`, ABIs, checksums,
and signature readability; the fingerprint comparison is the human check that
the same upload identity was migrated.

Verification (read-only):

```bash
REPO=imanpr33trai/pizzabi
gh api "repos/$REPO/rulesets?targets=tag" \
  --jq '.[] | select(.name=="release-tag-protection") | {id,name,enforcement}'
gh api "repos/$REPO/environments/production" \
  --jq '{name, protection_rules, deployment_branch_policy}'
gh api "repos/$REPO/environments/production/deployment-branch-policies" \
  --jq '.branch_policies[] | {name, type}'
```

Expected results:

| Check | Expected |
|---|---|
| Tag ruleset | `release-tag-protection`, `active`, target `tag`, include `refs/tags/v*` |
| Tag rules | `creation`, `update`, `deletion`, `non_fast_forward` |
| Environment | `production` with a required reviewer and custom tag policy |
| Deployment policy | `v*`, type `tag` |
| Release job | waits for environment approval; fails before signing if the tag is not on `main` |

Failure recovery:

| Symptom | Meaning | Recovery |
|---|---|---|
| Release job waits indefinitely | `production` environment approval pending | Approve the deployment in **Actions → the run → Review deployments** |
| `release tag must point to a commit on main` | Tag was created on a feature/nightly commit | Do not bypass; delete/recreate the tag from a `main` commit after promotion |
| `Missing required secret ANDROID_*` | Environment secret migration incomplete | Set the missing environment secret from the secure backup; do not re-add it at repository scope |
| Non-admin cannot create/move a tag | Ruleset enforcement | An admin must create the tag after the release commit is on `main` |
| Tag ruleset blocks an emergency rollback tag | Immutability control | Admin bypass is intentional; fix forward rather than moving a published tag |

Rollback: delete the `release-tag-protection` ruleset, remove
`environment: production` from `build-release-apk` and
`publish-github-release`, remove the conditional `production` environment from
the two reusable publishing workflows, and (only if environment secrets were
already added) restore the repository-level secrets from the secure backup
before deleting the environment copies.

---

## 15. Phase 4 — Manual `nightly` → `main` Promotion (Exact SHA)

**Status: implemented in the repository, not yet exercised against the live
API.** The nightly validation tier, the terminal `Nightly Validation —
Complete` gate, the `main` promotion gate and the promotion workflow are all
present in source. No promotion PR was created while preparing this runbook;
the workflow performs writes only when a maintainer dispatches it.

Promotion of `nightly` into `main` is **manual and exact-SHA**. The workflow
`.github/workflows/promote-nightly-to-main.yml` ("Promote Nightly to Main")
verifies that one specific nightly commit passed the complete post-merge
nightly validation, then creates or updates the promotion PR. It **never**
merges, pushes, approves, tags, deploys, publishes releases, or touches Railway.

### 15.1 Preconditions

1. The workflow file exists on `refs/heads/main` (land it through a normal PR
   first; a `workflow_dispatch` run always uses the workflow definition from the
   selected ref, and this workflow refuses to run from anything but `main`).
2. GitHub exposes `workflow_dispatch` workflows that exist on the repository's
   **default branch**. At the time of writing the default branch is
   `ui-ux-changes`, not `main`. Merge the workflow file to the default branch as
   well (or change the default branch to `main`), otherwise it may not appear
   in the **Run workflow** list. The dispatch itself must still select **main**.
3. The `nightly` ruleset is active and `Nightly Validation — Complete` is the
   terminal gate owned by `.github/workflows/security-and-ci.yml`.
4. `refs/heads/main` is an ancestor of `refs/heads/nightly`.
5. Repository setting for bot-created PRs: **Settings → Actions → General →
   Workflow permissions → “Allow GitHub Actions to create and approve pull
   requests”** must be enabled for the workflow’s `GITHUB_TOKEN`
   (`pull-requests: write`) to open the PR. The repository has reported
   `default_workflow_permissions: "read"` and
   `can_approve_pull_request_reviews: false`; when disabled, the workflow fails
   closed with an explicit error. Do **not** add a PAT or GitHub App token as a
   workaround without explicit approval and a documented rotation plan.

### 15.2 What the workflow verifies (all must pass)

| # | Check | Rejection reason |
|---|---|---|
| 1 | Trigger is `workflow_dispatch` and `GITHUB_REF == refs/heads/main` | verification code would not come from protected main |
| 2 | `expected_sha` is 40 lowercase hex characters | malformed input |
| 3 | `expected_sha` equals the current `refs/heads/nightly` tip | stale, previous, or unknown SHA |
| 4 | `compare main...nightly` → `behind_by == 0`, `status == ahead` | main has commits not in nightly, or nothing to promote |
| 5 | The **latest** `push` run of `security-and-ci.yml` on branch `nightly` has `head_branch=nightly`, `head_sha=expected_sha`, `status=completed`, `conclusion=success` | failed, cancelled, in-progress, or superseded run; a `pull_request` run never qualifies |
| 6 | Run detail (latest attempt) is `completed`/`success` (records `run_attempt`) | incomplete or re-run-failed attempt |
| 7 | The run’s check suite contains `Nightly Validation — Complete` with `status=completed`, `conclusion=success`, `head_sha=expected_sha` | missing or failing terminal gate (CI Summary is never a substitute) |
| 8 | The nightly tip is re-read immediately before the PR write and still equals `expected_sha` | nightly moved during promotion → abort, no PR write |

Only after all checks pass does it create or update the PR (title
`release: promote validated nightly to main`, head `nightly`, base `main`).
An existing open nightly→main PR is updated in place (body + evidence comment),
never duplicated. If the PR head SHA is not the validated SHA, the comment says
so explicitly and requires validation to be checked again.

### 15.3 Procedure (GitHub UI)

1. Read the current tip and the latest nightly run:

   ```bash
   REPO=imanpr33trai/pizzabi
   TIP="$(gh api "repos/$REPO/git/ref/heads/nightly" --jq '.object.sha')"
   echo "$TIP"
   gh api "repos/$REPO/actions/workflows/security-and-ci.yml/runs?branch=nightly&event=push&per_page=1" \
     --jq '.workflow_runs[0] | {id, head_sha, status, conclusion, html_url}'
   ```

   The run’s `head_sha` must equal `$TIP`, `status` must be `completed`, and
   `conclusion` must be `success`.

2. In GitHub: **Actions → Promote Nightly to Main → Run workflow**.
   - Branch: **main**
   - `expected_sha`: paste `$TIP` exactly.

3. Wait for the workflow run to finish. Expected result: **success**, with the
   summary table showing the validated SHA, nightly run URL/attempt, terminal
   gate `success`, and the promotion PR URL.

4. Open the promotion PR. Confirm:
   - Title: `release: promote validated nightly to main`
   - Head `nightly`, base `main`
   - Body lists the validated SHA, the nightly run URL, the terminal gate
     result, and the `main..nightly` commit summary.

### 15.4 Procedure (CLI)

```bash
REPO=imanpr33trai/pizzabi
TIP="$(gh api "repos/$REPO/git/ref/heads/nightly" --jq '.object.sha')"
gh workflow run promote-nightly-to-main.yml --repo "$REPO" --ref main \
  -f expected_sha="$TIP"
sleep 5
gh run list --repo "$REPO" --workflow promote-nightly-to-main.yml --limit 1
gh run watch <run-id> --repo "$REPO" --exit-status
gh pr list --repo "$REPO" --base main --head nightly --state open
```

### 15.5 After the promotion PR exists

1. The PR triggers the normal `pull_request` tier, including the required
   `Main PR Validation — Complete` terminal gate and `strict_required_status_checks_policy`.
2. Immediately before merging, re-verify the PR head is still the nightly tip:

   ```bash
   REPO=imanpr33trai/pizzabi
   PR=<number>
   TIP="$(gh api "repos/$REPO/git/ref/heads/nightly" --jq '.object.sha')"
   HEAD="$(gh api "repos/$REPO/pulls/$PR" --jq '.head.sha')"
   test "$TIP" = "$HEAD" && echo "OK: promotion candidate is the current nightly tip"
   ```

3. Merge with a **merge commit only** (the `main-branch-protection` ruleset
   rejects squash and rebase). Approval and merge are human actions; the
   workflow never performs them.
4. Production release remains a separate, manual `v*` tag on `main` (see
   Section 14). Promotion to `main` only runs the existing post-merge/preview
   tier; it never publishes production artifacts.

### 15.6 Failure recovery

| Workflow error | Meaning | Recovery |
|---|---|---|
| `dispatch this workflow from refs/heads/main` | Wrong ref selected in the UI/CLI | Re-dispatch with Branch: `main` |
| `expected_sha must be a 40-character lowercase hex SHA` | Bad input | Copy the full 40-char SHA |
| `expected_sha does not equal the current nightly tip` | Nightly moved or a previous SHA was supplied | Wait for the new tip’s nightly push run to succeed, re-dispatch with the new SHA |
| `main has N commit(s) not in nightly` | main diverged (main is not an ancestor) | Merge main into nightly through a PR, wait for a green `Nightly Validation — Complete`, re-dispatch |
| `nightly is not strictly ahead of main` | Nothing to promote | No action; wait for new nightly commits |
| `no push run ... found on branch nightly` | Nightly push run missing (or run history pruned) | Push/merge to nightly through a PR and wait for the full nightly run |
| `latest nightly push run validated <other SHA>` | Successful run is stale/superseded | A newer nightly commit exists; validate it first, then promote that SHA |
| `latest nightly push run is 'in_progress'` | Validation still running | Wait for completion, re-dispatch |
| `concluded 'failure'/'cancelled'` | Nightly tip is not validated | Fix the failure via a PR to nightly; wait for a fresh green run |
| `terminal gate ... is MISSING` | Run predates the terminal gate or the gate was skipped | Treat the SHA as unvalidated; re-run nightly validation on the current tip |
| `terminal gate concluded ... not success` | Terminal gate failed | Inspect the run’s failed stage; fix and revalidate |
| `nightly moved during promotion` | Race: nightly advanced between verification and PR write | Automatic abort; no PR was touched. Re-dispatch with the new tip after its validation |
| `PR creation failed` + GitHub Actions PR setting hint | Repo setting blocks bot-created PRs | Enable Settings → Actions → General → Workflow permissions → “Allow GitHub Actions to create and approve pull requests” (or obtain approval for a scoped PAT/App; do not add one silently) |
| Promotion PR exists but head SHA ≠ validated SHA | PR head no longer matches the evidence | Do not merge; re-run validation and promotion, then re-check the body/comment |

If a promotion PR must be withdrawn, close it manually. The workflow does not
reopen closed PRs; a later successful dispatch creates a new PR.

### 15.7 Permissions and concurrency

- Workflow permissions: `contents: read`, `actions: read`, `checks: read`,
  `pull-requests: write` — nothing else. It uses `secrets.GITHUB_TOKEN` and
  never prints it.
- Concurrency group `pizzora-promote-nightly-to-main` with
  `cancel-in-progress: false`: simultaneous promotion requests queue instead of
  racing.
- No `actions/checkout` and no execution of branch code: all state is read via
  the Git Data, Actions, and Checks APIs, so nightly (or PR) content can never
  influence the privileged verification logic.

### 15.8 Validation status

- **Statically verified:** YAML structure, trigger/inputs, minimum permissions,
  SHA regex, ancestry and run-selection logic, terminal-gate name match against
  `security-and-ci.yml`, absence of any merge/push/approve/tag/deploy command,
  and concurrency settings.
- **Not executed:** a real promotion dispatch (it would create a pull request).
  The read-only GitHub API endpoints it uses (`git/ref`, `compare`,
  `actions/runs`, `check-suites/.../check-runs`, `pulls`) are the same endpoints
  verified during the audit.

---

## 16. Phase 6 — Isolated Railway Staging Environment (DEFERRED)

**Status: DEFERRED BY USER DECISION — intentionally postponed, not failed and
not incomplete due to an implementation defect.** Phase 6 is outside the
completion criteria for the current Phase 4/5 change. No staging deployment
workflow, staging configuration example, GitHub `staging` environment, Railway
staging project, database, Redis instance, bucket, or credential is created or
modified by this change, and no staging deployment has been executed.
Production Railway project `bubbly-peace`, service `pizzabi`, `pizzabi-worker`,
production variables, and production data were not modified.

The requirements below are preserved verbatim in intent for a future,
separately approved Phase 6 implementation. They are design input, not
delivered functionality.

### 16.1 Required resources (manual provisioning only)

| Resource | Requirement |
|---|---|
| Railway project | A **new** project whose name contains `staging` (for example `pizzora-staging`). Never `bubbly-peace`. |
| PostgreSQL | Dedicated PostgreSQL 18 instance/database. Never the production database. |
| Redis | Dedicated Redis 7 instance. Never the production Redis. |
| Object storage | Optional S3-compatible bucket, dedicated to staging. Keep `S3_ENABLED=false` until a path-style-compatible endpoint and public URL exist. |
| API service | `pizzabi-staging-api` (name must contain `staging`), built from `infra/Dockerfile.rust`, public HTTPS domain. |
| Worker service | `pizzabi-staging-worker` (name must contain `staging`), same image, **no public domain**. |
| Mailpit (optional) | `axllent/mailpit:v1.22` staging-only SMTP sink. Never in production. |

Service settings (verified against `infra/Dockerfile.rust`, the Rust startup
code, and `.railway/railway.template.ts`):

| Setting | API | Worker |
|---|---|---|
| Dockerfile | `infra/Dockerfile.rust` | `infra/Dockerfile.rust` |
| Pre-deploy | `["/usr/local/bin/pizzora-api", "--migrate-only"]` | **none** (never duplicate migrations) |
| Start | `/usr/local/bin/pizzora-api` | `/usr/local/bin/pizzora-workers` |
| Bind / port | `0.0.0.0:$PORT` (default `8080`) | `0.0.0.0:$WORKER_HEALTH_PORT`; set **both** `PORT=8081` and `WORKER_HEALTH_PORT=8081` |
| Healthcheck | `/api/v1/health/ready` | `/health/worker` |
| Migrations | `RUN_MIGRATIONS_ON_STARTUP=false` | `RUN_MIGRATIONS_ON_STARTUP=false` |
| Public domain | enabled | **disabled** |

### 16.2 Provisioning (requires explicit approval)

Use the reusable IaC template against a **new** project, never against
`bubbly-peace`.

**Tooling constraint (verified 2026-11-20):** the installed Railway CLI is
`4.43.0`, which has no `railway config` subcommand. The authoring dependency
pinned in `.railway/package.json` (`railway@3.17.0`, bin `railway-iac-ts`)
refuses to run with it:

```text
This version of railway/iac requires Railway CLI 5.42.1 or newer.
The IaC engine now ships in the CLI, not the TypeScript SDK.
```

Therefore:

- **Recommended:** create the topology manually in the Railway dashboard
  (project containing `staging`, PostgreSQL 18, Redis 7, API/worker services,
  optional Mailpit) using the settings in Section 16.1. This needs no CLI
  upgrade.
- **Optional, requires explicit approval:** upgrade the CLI to `>= 5.42.1`,
  confirm `railway config --help` exists, then review the plan before applying:

  ```bash
  railway link --project <new-staging-project-id> --environment staging
  railway config plan --file .railway/railway.template.ts
  railway config apply --file .railway/railway.template.ts
  ```

  Do not install or upgrade CLI tooling, and do not apply the template, without
  approval to provision staging.

The future deployment workflow should use only commands present in `4.43.0`
(`link`, `status`, `up`, `deployment list`) and pin that version explicitly.
Do not add such a workflow while Phase 6 is deferred.

For a local, no-Railway staging stack use
`infra/docker-compose.rust-staging.yml`. Create an untracked
`infra/.env.rust-staging` with staging-only values (the file is gitignored);
never commit filled secrets.

### 16.3 Staging application variables (Railway)

Set these on the **staging** Railway services only, after Phase 6 is approved.
Never copy production values. The inventory is listed here; do not commit a
filled environment file.

- Non-secret: `PIZZORA_ENV=production`, `RUN_MIGRATIONS_ON_STARTUP=false`,
  `PORT`/`WORKER_HEALTH_PORT` (`8080` API, `8081` worker), pool sizes
  (`DATABASE_MAX_POOL_SIZE=15` API / `5` worker), `REDIS_REQUIRED=false`,
  `S3_ENABLED=false`, `SHUTDOWN_TIMEOUT_SECS=15`.
- Secret: `DATABASE_URL` (staging PostgreSQL reference), `REDIS_URL` (staging
  Redis reference), `JWT_SECRET` (≥ 32 random chars, staging-only),
  `OUTBOX_EMAIL_KEY_CURRENT_ID` + `OUTBOX_EMAIL_KEYS` (staging-only keyring),
  `RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET`/`RAZORPAY_WEBHOOK_SECRET`
  (**`rzp_test_*` sandbox keys only**), SMTP credentials (Mailpit or a
  staging relay), and `S3_*` only if object storage is enabled.
- The planned workflow must reject values containing `bubbly-peace`,
  `api.pizzora.app`, or `rzp_live` and require the project/environment/service
  names to contain `staging`.

### 16.4 Planned GitHub `staging` Environment (manual, one-time; only after approval)

When Phase 6 is resumed and approved, create the environment with a required
reviewer (self-review allowed for the single maintainer), restrict deployments
to `main` and `nightly`, then add the staging-only project token and variables:

```bash
REPO=imanpr33trai/pizzabi
gh api -X PUT "repos/$REPO/environments/staging" --input - <<'JSON'
{
  "wait_timer": 0,
  "prevent_self_review": false,
  "reviewers": [{ "type": "User", "id": 123877434 }],
  "deployment_branch_policy": {
    "protected_branches": false,
    "custom_branch_policies": true
  }
}
JSON
gh api -X POST "repos/$REPO/environments/staging/deployment-branch-policies" \
  --input - <<'JSON'
{ "name": "main", "type": "branch" }
JSON
gh api -X POST "repos/$REPO/environments/staging/deployment-branch-policies" \
  --input - <<'JSON'
{ "name": "nightly", "type": "branch" }
JSON

# Railway PROJECT token scoped to the staging project/environment only.
gh secret set RAILWAY_STAGING_PROJECT_TOKEN --env staging --repo "$REPO"
gh variable set STAGING_PROJECT_ID --env staging --repo "$REPO" --body "<staging-project-id>"
gh variable set STAGING_ENVIRONMENT --env staging --repo "$REPO" --body "staging"
gh variable set STAGING_API_SERVICE --env staging --repo "$REPO" --body "pizzabi-staging-api"
gh variable set STAGING_WORKER_SERVICE --env staging --repo "$REPO" --body "pizzabi-staging-worker"
gh variable set STAGING_API_URL --env staging --repo "$REPO" --body "https://<staging-api-domain>"
```

Do **not** reuse an account/workspace Railway token or any production secret.
The planned workflow would read only these `staging`-environment values; there
must be no repository-level or production fallback.

### 16.5 Planned manual deployment procedure (not active)

This procedure applies only after a future Phase 6 workflow is implemented and
approved. No `deploy-staging.yml` exists in the current change.

1. Confirm the commit is the current tip of `main` or `nightly` and that its
   latest `push` validation run succeeded (for `nightly`, also confirm
   `Nightly Validation — Complete` is green for that exact SHA).
2. **Actions → Deploy Rust Backend to Railway Staging → Run workflow** (future):
   - Branch: **main**
   - `deploy_staging`: **true**
   - `commit_sha`: the full 40-character SHA
   - `source_branch`: `main` or `nightly`
3. Approve the pending `staging` deployment in **Actions → the run → Review
   deployments**.
4. Verify the run summary: both services deployed `SUCCESS`, API readiness
   reached `/api/v1/health/ready`, worker health passed the Railway
   `/health/worker` healthcheck.

```bash
# Future procedure; the workflow does not exist while Phase 6 is deferred.
REPO=imanpr33trai/pizzabi
SHA="$(gh api "repos/$REPO/git/ref/heads/main" --jq '.object.sha')"
gh workflow run deploy-staging.yml --repo "$REPO" --ref main \
  -f deploy_staging=true -f commit_sha="$SHA" -f source_branch=main
gh run list --repo "$REPO" --workflow deploy-staging.yml --limit 1
```

With `deploy_staging=false` (the required default), the planned workflow would
validate the request and perform **no Railway action**: the deploy job would be
skipped and no token used.

### 16.6 What the planned workflow must enforce

- `workflow_dispatch` only; dispatch must come from `refs/heads/main`.
- `deploy_staging` must be boolean; `false` means validation-only.
- `commit_sha` must be 40 lowercase hex characters and equal the current tip of
  the selected `source_branch`.
- The latest `push` run of `security-and-ci.yml` on that branch must be
  completed/success for that exact SHA; `nightly` additionally requires the
  terminal gate. Stale, superseded, failed, cancelled, in-progress or PR runs
  are rejected.
- Staging isolation guards reject production project/service names, the
  production API domain, and `rzp_live` keys before any Railway command runs.
- The linked Railway project must match `STAGING_PROJECT_ID`, must not be
  `bubbly-peace`, and must contain `staging` in its project/environment name.
- Deployment uses the exact checked-out SHA (`actions/checkout` at
  `commit_sha`, `persist-credentials: false`) and the pinned Railway CLI
  `@railway/cli@4.43.0`.
- Both services must reach deployment status `SUCCESS` (Railway only reports
  `SUCCESS` after the service healthcheck passes), and the API readiness
  endpoint must report `postgres=UP`.

### 16.7 Health checks and graceful shutdown

- **API readiness:** `GET /api/v1/health/ready` on `$PORT` (default `8080`).
  Returns `200 HEALTHY` when PostgreSQL/Redis/S3 requirements are met, or
  `200 DEGRADED` when Redis is down and `REDIS_REQUIRED=false`. The planned
  verification would require `postgres=UP`.
- **Worker health:** `GET /health/worker` on `WORKER_HEALTH_PORT` (`8081`).
  Railway probes `$PORT`, so set **both** `PORT=8081` and
  `WORKER_HEALTH_PORT=8081` on the worker; the deployment only becomes
  `SUCCESS` when this healthcheck passes.
- **Graceful shutdown:** the image uses `STOPSIGNAL SIGTERM` and the runtime
  drains for up to `SHUTDOWN_TIMEOUT_SECS=15` on redeploy/stop. The worker
  loops use `FOR UPDATE SKIP LOCKED` leases, so an interrupted redeploy does
  not lose work.
- **Connectivity:** startup fails fast (naming the variable, never its value)
  when required staging secrets are missing or invalid; `RUN_MIGRATIONS_ON_STARTUP=false`
  keeps runtime startup read-only.

### 16.8 Database migration rules

- Flyway `V1`–`V3` are historical and must never be rewritten; SQLx owns `V4`
  and later.
- Only the API service runs `preDeploy: ["/usr/local/bin/pizzora-api",
  "--migrate-only"]`; it holds `pg_advisory_lock(884210001)` on one dedicated
  connection, verifies Flyway checksums, baselines `_sqlx_migrations`, applies
  `V4+`, and releases the lock.
- The worker has **no** pre-deploy migration command and runs with
  `RUN_MIGRATIONS_ON_STARTUP=false`, so migrations never run concurrently from
  both services.
- Never point a staging `DATABASE_URL` at production and never execute staging
  migrations against production. Verify the target host is the staging
  PostgreSQL private domain before deploying.

### 16.9 Troubleshooting (future implementation)

This table applies only after the deferred Phase 6 workflow is implemented and
approved.

| Symptom | Meaning | Recovery |
|---|---|---|
| `required staging setting ... is empty` | `staging` environment variable/secret missing | Set it on the `staging` Environment; do not use repository-level production values |
| `staging configuration references a production identifier` | Denylist matched `bubbly-peace`, `api.pizzora.app`, or `rzp_live` | Replace with staging-only values |
| `commit_sha is not the current ... tip` | SHA is stale or from another branch | Re-dispatch with the current tip |
| `latest ... push run validated <other SHA>` | No completed validation for that SHA | Wait for/re-run the branch push validation |
| `linked Railway project ... does not contain 'staging'` | Wrong project linked | Correct `STAGING_PROJECT_ID`; never link `bubbly-peace` |
| `railway link`/auth failure | Token is missing, expired, or not scoped to the staging project | Rotate the Railway **project** token and update the `staging` secret; do not substitute an account token silently |
| Deployment not `SUCCESS` | Build failure, crash, or failed healthcheck | Inspect Railway build/deploy logs; verify `PORT`/`WORKER_HEALTH_PORT`, `DATABASE_URL`, `REDIS_URL`, and secrets |
| API readiness fails or `postgres=DOWN` | Staging database unreachable or migrations not applied | Verify the staging PostgreSQL reference and the API pre-deploy logs |
| Worker restarts during first deploy | Worker booted before the API pre-deploy migration finished | Expected; Railway restarts it until the schema is ready |

### 16.10 Staging vs production (explicit)

| Aspect | Staging | Production |
|---|---|---|
| Railway project | new project containing `staging` | `bubbly-peace` (never touched by this phase) |
| Services | `pizzabi-staging-api`, `pizzabi-staging-worker` | `pizzabi`, `pizzabi-worker` |
| Database / Redis / storage | dedicated staging resources | production resources only |
| Credentials | staging-only project token + staging secrets | production secrets in the `production` GitHub Environment |
| Payments | Razorpay `rzp_test_*` sandbox | live Razorpay keys |
| Deployment trigger | planned manual `deploy-staging.yml` (not present while deferred) | protected `v*` tag release |
| GitHub environment | `staging` (not created while deferred) | `production` |

### 16.11 Validation status

- **Deferred:** no Phase 6 workflow, staging environment, Railway resource, or
  staging configuration file exists in this change, so there is nothing to
  execute or validate. The requirements above were checked against the Rust
  source, `infra/Dockerfile.rust`, the migration lock in `pizzora-db`, the local
  staging Compose file, and the installed Railway CLI (`4.43.0`) during
  planning; those findings are preserved for the future implementation.
- **Not executed:** any staging deployment, Railway provisioning, GitHub
  `staging` environment creation, or secret configuration. These require a
  separately approved Phase 6 and the manual resources in Sections 16.1–16.4.

