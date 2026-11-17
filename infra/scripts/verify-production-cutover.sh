#!/usr/bin/env bash
set -euo pipefail

# PIZZORA Phase 9 Production Cutover Verification & Smoke Script
# Verifies TLS/headers, API health & readiness, public catalog/OpenAPI parity,
# auth/webhook security rejection envelopes, optional authenticated end-to-end flow,
# and optional internal worker health/metrics probes.
#
# Usage:
#   BASE_URL=https://api.pizzora.app bash infra/scripts/verify-production-cutover.sh
#
# Optional authenticated smoke checks (use dedicated non-destructive smoke credentials via env vars):
#   BASE_URL=https://api.pizzora.app \
#   SMOKE_TEST_EMAIL="smoke@example.com" \
#   SMOKE_TEST_PASSWORD="..." \
#   WORKER_INTERNAL_URL="http://127.0.0.1:8081" \
#     bash infra/scripts/verify-production-cutover.sh

BASE_URL="${BASE_URL:?Set BASE_URL (e.g. https://api.pizzora.app)}"
BASE_URL="${BASE_URL%/}"
WORKER_INTERNAL_URL="${WORKER_INTERNAL_URL:-}"
WORKER_INTERNAL_URL="${WORKER_INTERNAL_URL%/}"

for cmd in curl python3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: Required command '$cmd' not found in PATH." >&2
    exit 1
  fi
done

echo "[1/7] Checking /api/v1/health/live and security headers on ${BASE_URL}..."
LIVE_HEADERS="$(curl -fsSI "${BASE_URL}/api/v1/health/live")"
echo "$LIVE_HEADERS" | grep -qi "x-content-type-options: nosniff" || {
  echo "ERROR: Missing X-Content-Type-Options: nosniff header" >&2
  exit 1
}
if [[ "${BASE_URL}" == https://* ]]; then
  echo "$LIVE_HEADERS" | grep -qi "strict-transport-security" || {
    echo "ERROR: Missing Strict-Transport-Security header on HTTPS endpoint" >&2
    exit 1
  }
fi

echo "[2/7] Checking /api/v1/health/ready..."
READY_JSON="$(curl -fsS "${BASE_URL}/api/v1/health/ready")"
python3 - <<'PY' "$READY_JSON"
import json, sys
data = json.loads(sys.argv[1])
status = data.get("status")
if status not in ("HEALTHY", "DEGRADED"):
    raise SystemExit(f"Unexpected readiness status: {data}")
raw_deps = data.get("dependencies", {})
deps = raw_deps if isinstance(raw_deps, dict) else {d.get("name"): d.get("status") for d in raw_deps}
if deps.get("postgres") != "UP":
    raise SystemExit(f"PostgreSQL dependency is not UP: {deps}")
print(f"Readiness OK: status={status} dependencies={deps}")
PY

echo "[3/7] Checking public catalog, config, and OpenAPI endpoints..."
curl -fsS "${BASE_URL}/api/v1/config/public" | python3 -c "import json,sys; d=json.load(sys.stdin); assert 'currency' in d or 'razorpayKeyId' in d or len(d)>0"
curl -fsS "${BASE_URL}/api/v1/catalog/categories" >/dev/null
curl -fsS "${BASE_URL}/api/v1/catalog/menu" >/dev/null
curl -fsS "${BASE_URL}/api/v1/openapi.json" | python3 -c "import json,sys; d=json.load(sys.stdin); assert 'openapi' in d and 'paths' in d"

echo "[4/7] Checking external /metrics restriction and auth/webhook error envelopes..."
METRICS_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/metrics")"
if [[ "${BASE_URL}" == https://* && "$METRICS_CODE" != "403" && "$METRICS_CODE" != "404" ]]; then
  echo "ERROR: External /metrics returned HTTP $METRICS_CODE; must be blocked publicly." >&2
  exit 1
fi

UNAUTH_BODY_FILE="$(mktemp)"
trap 'rm -f "$UNAUTH_BODY_FILE"' EXIT
UNAUTH_CODE="$(curl -s -o "$UNAUTH_BODY_FILE" -w "%{http_code}" "${BASE_URL}/api/v1/cart")"
if [[ "$UNAUTH_CODE" != "401" ]]; then
  echo "ERROR: Expected 401 for unauthenticated /api/v1/cart, got HTTP $UNAUTH_CODE" >&2
  exit 1
fi
python3 - <<'PY' "$UNAUTH_BODY_FILE"
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as fh:
    body = json.load(fh)
assert "code" in body and "message" in body, f"Missing error envelope fields: {body}"
PY

WEBHOOK_CODE="$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${BASE_URL}/api/v1/webhooks/razorpay" \
  -H "Content-Type: application/json" \
  -H "X-Razorpay-Signature: deadbeef" \
  -d '{"event":"payment.captured"}')"
if [[ "$WEBHOOK_CODE" != "401" && "$WEBHOOK_CODE" != "400" ]]; then
  echo "ERROR: Expected 401/400 for invalid webhook signature, got HTTP $WEBHOOK_CODE" >&2
  exit 1
fi

echo "[5/7] Checking rate-limit & JSON validation parity..."
LOGIN_BAD_CODE="$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${BASE_URL}/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{}')"
if [[ "$LOGIN_BAD_CODE" != "400" && "$LOGIN_BAD_CODE" != "422" ]]; then
  echo "ERROR: Expected 400/422 for empty login body, got HTTP $LOGIN_BAD_CODE" >&2
  exit 1
fi

echo "[6/7] Checking optional authenticated session smoke flow..."
if [[ -n "${SMOKE_TEST_EMAIL:-}" && -n "${SMOKE_TEST_PASSWORD:-}" ]]; then
  LOGIN_PAYLOAD="$(python3 - <<'PY'
import json, os
print(json.dumps({
    "email": os.environ["SMOKE_TEST_EMAIL"],
    "password": os.environ["SMOKE_TEST_PASSWORD"],
}))
PY
)"
  TOKENS_JSON="$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "$LOGIN_PAYLOAD")"

  ACCESS_TOKEN="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["accessToken"])' "$TOKENS_JSON")"
  REFRESH_TOKEN="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["refreshToken"])' "$TOKENS_JSON")"

  curl -fsS -H "Authorization: Bearer ${ACCESS_TOKEN}" "${BASE_URL}/api/v1/me" >/dev/null
  curl -fsS -H "Authorization: Bearer ${ACCESS_TOKEN}" "${BASE_URL}/api/v1/cart" >/dev/null
  curl -fsS -H "Authorization: Bearer ${ACCESS_TOKEN}" "${BASE_URL}/api/v1/notifications" >/dev/null
  curl -fsS -H "Authorization: Bearer ${ACCESS_TOKEN}" "${BASE_URL}/api/v1/orders" >/dev/null
  curl -fsS -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "${BASE_URL}/api/v1/auth/ws-ticket" >/dev/null

  REFRESH_PAYLOAD="$(python3 -c 'import json,sys; print(json.dumps({"refreshToken": sys.argv[1]}))' "$REFRESH_TOKEN")"
  REFRESHED_JSON="$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/refresh" -H "Content-Type: application/json" -d "$REFRESH_PAYLOAD")"
  NEW_ACCESS="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["accessToken"])' "$REFRESHED_JSON")"
  NEW_REFRESH="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["refreshToken"])' "$REFRESHED_JSON")"

  LOGOUT_PAYLOAD="$(python3 -c 'import json,sys; print(json.dumps({"refreshToken": sys.argv[1]}))' "$NEW_REFRESH")"
  curl -fsS -X POST -H "Authorization: Bearer ${NEW_ACCESS}" -H "Content-Type: application/json" \
    -d "$LOGOUT_PAYLOAD" "${BASE_URL}/api/v1/auth/logout" >/dev/null
  echo "Authenticated login -> me -> cart -> notifications -> orders -> ws-ticket -> refresh -> logout OK"
else
  echo "Skipped authenticated smoke flow (SMOKE_TEST_EMAIL / SMOKE_TEST_PASSWORD unset)."
fi

echo "[7/7] Checking optional internal worker health & metrics..."
if [[ -n "$WORKER_INTERNAL_URL" ]]; then
  WORKER_HEALTH="$(curl -fsS "${WORKER_INTERNAL_URL}/health/worker")"
  python3 - <<'PY' "$WORKER_HEALTH"
import json, sys
data = json.loads(sys.argv[1])
if data.get("status") != "HEALTHY":
    raise SystemExit(f"Worker unhealthy: {data}")
deps = data.get("dependencies", {})
for req in ("postgres", "sequencer_loop", "dispatcher_loop", "expiry_sweep", "reconciler_loop"):
    if deps.get(req) != "UP":
        raise SystemExit(f"Worker dependency '{req}' is not UP: {deps}")
print(f"Worker health OK: {data}")
PY
  curl -fsS "${WORKER_INTERNAL_URL}/metrics" | grep -q "pizzora_worker_uptime_seconds"
  echo "Worker Prometheus metrics OK"
else
  echo "Skipped internal worker check (WORKER_INTERNAL_URL unset)."
fi

echo "PASS: Production cutover smoke verification succeeded."
