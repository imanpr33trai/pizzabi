import {
  defineRailway,
  github,
  image,
  postgres,
  project,
  redis,
  service,
} from "railway/iac";

// Reusable full-stack PIZZORA Railway IaC definition for new isolated staging
// environments or Railway Template creation.
//
// SAFETY GUARDRAIL:
// - NEVER run `railway config apply` with this file against the existing live
//   production project (`bubbly-peace`). Use `.railway/railway.ts` (partial = "pizzabi")
//   for `bubbly-peace` so existing PostgreSQL, Redis, domains, and secrets are preserved.
//
// Architecture:
// - `postgres`: Railway managed PostgreSQL service (target PG 18.x).
// - `redis`: Railway managed Redis service (cache, WS Pub/Sub, optional fallback).
// - `pizzabi-api`: Axum API + WebSocket server (`/usr/local/bin/pizzora-api`), owns
//   preDeploy migration (`--migrate-only`) under pg_advisory_lock(884210001).
// - `pizzabi-worker`: Standalone worker loops (`/usr/local/bin/pizzora-workers`) with
//   `PORT=8081` & `WORKER_HEALTH_PORT=8081`, NO preDeploy, NO public domain.
// - `mailpit`: Deployed ONLY when `ctx.environment !== "production"` for SMTP testing.
// - Object storage: `S3_ENABLED=false` by default. Railway Storage Buckets (`bucket()`)
//   are private-by-default and virtual-hosted style; enabling S3 requires configuring
//   `S3_ENABLED=true`, `S3_ENDPOINT`, `S3_BUCKET`, `S3_REGION`, `S3_ACCESS_KEY`,
//   `S3_SECRET_KEY`, and a publicly readable `S3_PUBLIC_URL` (e.g., R2/AWS S3/CDN proxy).
//
// Required secrets per project/environment (configure via Railway Variables/Template prompts):
// - JWT_SECRET (>=32 random chars)
// - OUTBOX_EMAIL_KEY_CURRENT_ID (e.g., k_2026_01)
// - OUTBOX_EMAIL_KEYS (<kid>:<base64_32byte_key>)
// - RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET (rzp_test_* in staging)
// - Production SMTP relay secrets (`SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, `SMTP_USERNAME`,
//   `SMTP_PASSWORD`, `SMTP_STARTTLS=true`) when `ctx.environment === "production"`.
export default defineRailway((ctx) => {
  const isProduction = ctx.environment === "production";

  const db = postgres("postgres");
  const cache = redis("redis");

  const mailpit = !isProduction
    ? service("mailpit", {
        source: image("axllent/mailpit:v1.22"),
        healthcheck: "/api/v1/info",
        healthcheckTimeout: 120,
        env: {
          PORT: "8025",
          MP_SMTP_AUTH_ACCEPT_ANY: "1",
          MP_SMTP_AUTH_ALLOW_INSECURE: "1",
        },
      })
    : null;

  const stagingSmtpEnv = mailpit
    ? {
        SMTP_HOST: "${{mailpit.RAILWAY_PRIVATE_DOMAIN}}",
        SMTP_PORT: "1025",
        SMTP_STARTTLS: "false",
        SMTP_FROM: "staging-no-reply@pizzora.local",
      }
    : {
        SMTP_PORT: "587",
        SMTP_STARTTLS: "true",
      };

  const sharedRuntimeEnv = {
    PIZZORA_ENV: "production",
    RUN_MIGRATIONS_ON_STARTUP: "false",
    SHUTDOWN_TIMEOUT_SECS: "15",
    RUST_LOG: "info,sqlx::query=warn,tower_http=info",
    DATABASE_URL: db.env.DATABASE_URL,
    DATABASE_MIN_CONNECTIONS: "1",
    DATABASE_CONNECT_TIMEOUT_MS: "5000",
    REDIS_URL: cache.env.REDIS_URL,
    REDIS_TIMEOUT_MS: "500",
    REDIS_REQUIRED: "false",
    S3_ENABLED: "false",
    ...stagingSmtpEnv,
  };

  const api = service("pizzabi-api", {
    source: github("imanpr33trai/pizzabi", { branch: "main" }),
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
      ...sharedRuntimeEnv,
      PORT: "8080",
      DATABASE_MAX_POOL_SIZE: "15",
      DATABASE_MIN_CONNECTIONS: "2",
    },
  });

  const worker = service("pizzabi-worker", {
    source: github("imanpr33trai/pizzabi", { branch: "main" }),
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/Dockerfile.rust",
    },
    start: "/usr/local/bin/pizzora-workers",
    healthcheck: "/health/worker",
    healthcheckTimeout: 300,
    env: {
      ...sharedRuntimeEnv,
      PORT: "8081",
      WORKER_HEALTH_PORT: "8081",
      DATABASE_MAX_POOL_SIZE: "5",
      DATABASE_MIN_CONNECTIONS: "1",
    },
  });

  return project("pizzora-stack", {
    resources: mailpit ? [db, cache, mailpit, api, worker] : [db, cache, api, worker],
  });
});
