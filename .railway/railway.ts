import { defineRailway, project, service } from "railway/iac";

// This repository owns only the PIZZORA backend service in the shared Railway
// environment. Keep Postgres and Redis managed by their existing resources.
export const partial = "pizzabi";

export default defineRailway(() => {
  const backend = service("pizzabi", {
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/Dockerfile.backend",
    },
    preDeploy: [
      "java",
      "-cp",
      "/app/libs/*",
      "com.pizzora.backend.ApplicationKt",
      "--migrate-only",
    ],
    healthcheck: "/api/v1/health/ready",
    healthcheckTimeout: 300,
  });

  return project("bubbly-peace", {
    resources: [backend],
  });
});
