import { defineRailway, project, service } from "railway/iac";

// This repository owns the PIZZORA API (`pizzabi`) and standalone worker
// (`pizzabi-worker`) services in the shared Railway production environment.
// Keep PostgreSQL 18.6 and Redis managed by their existing resources.
//
// Required production runtime environment variables (set in Railway Secrets):
// - PIZZORA_ENV=production
// - RUN_MIGRATIONS_ON_STARTUP=false
// - DATABASE_MAX_POOL_SIZE=15 (pizzabi) / DATABASE_MAX_POOL_SIZE=5 (pizzabi-worker)
// - OUTBOX_EMAIL_KEY_CURRENT_ID & OUTBOX_EMAIL_KEYS (256-bit AEAD keyring)
// - JWT_SECRET, RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET
//
// Ktor rollback definition is preserved in `.railway/railway.ktor-rollback.ts`
// until the post-cutover stability window and Ktor retirement are approved.
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
  });

  const worker = service("pizzabi-worker", {
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/Dockerfile.rust",
    },
    start: "/usr/local/bin/pizzora-workers",
    healthcheck: "/health/worker",
    healthcheckTimeout: 300,
  });

  return project("bubbly-peace", {
    resources: [backend, worker],
  });
});
