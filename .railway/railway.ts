import { defineRailway, project, service } from "railway/iac";

// This repository owns ONLY the PIZZORA API (`pizzabi`) and standalone worker
// (`pizzabi-worker`) services in the live Railway production project (`bubbly-peace`).
// `export const partial = "pizzabi"` guarantees existing PostgreSQL 18.6, Redis,
// volumes, custom domains, and Railway dashboard Secrets remain completely untouched.
//
// Required secrets (set in Railway Dashboard Variables, never committed here):
// - DATABASE_URL (reference to managed PostgreSQL)
// - REDIS_URL (reference to managed Redis)
// - OUTBOX_EMAIL_KEY_CURRENT_ID & OUTBOX_EMAIL_KEYS (256-bit AES-GCM keyring)
// - JWT_SECRET (>=32 chars), RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET
// - SMTP_HOST, SMTP_PORT, SMTP_FROM, SMTP_USERNAME, SMTP_PASSWORD, SMTP_STARTTLS
// - Optional S3_*: keep S3_ENABLED=false unless bucket + S3_PUBLIC_URL proxy are configured
//
// For brand-new isolated staging or reusable template provisioning, use
// `.railway/railway.template.ts` instead (never apply it against `bubbly-peace`).
// Ktor rollback definition remains preserved in `.railway/railway.ktor-rollback.ts`.
export const partial = "pizzabi";

export default defineRailway(() => {
  const backend = service("pizzabi", {
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/Dockerfile.rust",
    },
    preDeploy: [
      "/usr/local/bin/pizzora-api",
      "--migrate-only",
    ],
    start: "/usr/local/bin/pizzora-api",
    healthcheck: "/api/v1/health/ready",
    healthcheckTimeout: 300,
    env: {
      PIZZORA_ENV: "production",
      RUN_MIGRATIONS_ON_STARTUP: "false",
      DATABASE_MAX_POOL_SIZE: "15",
      DATABASE_MIN_CONNECTIONS: "2",
      SHUTDOWN_TIMEOUT_SECS: "15",
      REDIS_REQUIRED: "false",
      S3_ENABLED: "false",
    },
  });

  const worker = service("pizzabi-worker", {
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/Dockerfile.rust",
    },
    // Railway's internal healthcheck probes $PORT, while pizzora-workers binds $WORKER_HEALTH_PORT.
    // Both MUST be 8081 here; do NOT attach a public domain to pizzabi-worker.
    start: "/usr/local/bin/pizzora-workers",
    healthcheck: "/health/worker",
    healthcheckTimeout: 300,
    env: {
      PIZZORA_ENV: "production",
      RUN_MIGRATIONS_ON_STARTUP: "false",
      PORT: "8081",
      WORKER_HEALTH_PORT: "8081",
      DATABASE_MAX_POOL_SIZE: "5",
      DATABASE_MIN_CONNECTIONS: "1",
      SHUTDOWN_TIMEOUT_SECS: "15",
      REDIS_REQUIRED: "false",
      S3_ENABLED: "false",
    },
  });

  return project("bubbly-peace", {
    resources: [backend, worker],
  });
});
