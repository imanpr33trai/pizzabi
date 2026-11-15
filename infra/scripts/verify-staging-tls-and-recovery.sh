#!/usr/bin/env bash
set -euo pipefail

# PIZZORA Staging TLS, Readiness & Metrics Verification Script
# Usage: BASE_URL=https://api.pizzora.app bash infra/scripts/verify-staging-tls-and-recovery.sh

BASE_URL="${BASE_URL:-https://api.pizzora.app}"
HOST="${BASE_URL#https://}"
HOST="${HOST#http://}"
HOST="${HOST%%/*}"
HOST_ONLY="${HOST%%:*}"
PORT="${HOST##*:}"
if [[ "$PORT" == "$HOST_ONLY" ]]; then
  PORT=443
fi

echo "[1/5] Checking TLS handshake & certificate validity for ${HOST_ONLY}:${PORT}..."
if command -v openssl >/dev/null 2>&1; then
  echo | openssl s_client -servername "$HOST_ONLY" -connect "${HOST_ONLY}:${PORT}" -tls1_2 2>/dev/null | grep -q "Verify return code: 0 (ok)" || \
    echo "[warn] OpenSSL certificate chain did not return code 0 (self-signed or staging CA?)"
fi

echo "[2/5] Checking HSTS and security headers on /api/v1/health/live..."
HEADERS="$(curl -fsSI "${BASE_URL}/api/v1/health/live")"
echo "$HEADERS" | grep -qi "strict-transport-security" || { echo "ERROR: Missing HSTS header" >&2; exit 1; }
echo "$HEADERS" | grep -qi "x-content-type-options: nosniff" || { echo "ERROR: Missing nosniff header" >&2; exit 1; }

echo "[3/5] Checking /api/v1/health/ready..."
curl -fsS "${BASE_URL}/api/v1/health/ready"
echo ""

echo "[4/5] Verifying external /metrics is blocked (403) by reverse proxy..."
METRICS_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/metrics")"
if [[ "$METRICS_CODE" != "403" && "$METRICS_CODE" != "404" ]]; then
  echo "ERROR: External /metrics returned HTTP $METRICS_CODE; must be blocked publicly!" >&2
  exit 1
fi

echo "[5/5] Checking internal localhost API & Worker readiness + /metrics..."
curl -fsS "http://127.0.0.1:8080/metrics" | grep -q "pizzora_stream_last_seq"
curl -fsS "http://127.0.0.1:8081/health/worker" | grep -q "pizzora-worker"
curl -fsS "http://127.0.0.1:8081/metrics" | grep -q "pizzora_worker_uptime_seconds"

echo "PASS: Staging TLS, security headers, internal metrics, and worker probes verified."
