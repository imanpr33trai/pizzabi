import { defineRailway, project, service } from "railway/iac";

// Preserved Phase 9 Ktor Rollback Definition (keep until post-cutover stability window completes).
// Rollback protocol (Section 12.10):
// 1. Stop Rust worker (`pizzabi-worker`) first so no duplicate queue consumers run.
// 2. Restore `pizzabi` to the pinned Ktor release image/build with RUN_MIGRATIONS_ON_STARTUP=false
//    (and without running Flyway preDeploy if additive V4+ SQLx migrations exist under expand-and-contract).
export const partial = "pizzabi";

export default defineRailway(() => {
  const backend = service("pizzabi", {
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/Dockerfile.backend",
    },
    start: "java -cp /app/libs/* com.pizzora.backend.ApplicationKt",
    healthcheck: "/api/v1/health/ready",
    healthcheckTimeout: 300,
  });

  return project("bubbly-peace", {
    resources: [backend],
  });
});
