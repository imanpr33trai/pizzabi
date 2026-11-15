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
