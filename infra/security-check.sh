#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "[1/6] Checking zero Firebase / GMS / Google Services dependencies..."
if grep -rnEi 'firebase|google-services|com\.google\.gms' \
  app/build.gradle.kts backend/build.gradle.kts shared/ gradle/libs.versions.toml app/src backend/src; then
  echo "ERROR: Prohibited Firebase or GMS reference detected."
  exit 1
fi

echo "[2/6] Checking AndroidManifest.xml for prohibited Play permissions..."
if grep -rnE 'READ_EXTERNAL_STORAGE|READ_MEDIA_IMAGES|READ_MEDIA_VIDEO|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|REQUEST_INSTALL_PACKAGES|QUERY_ALL_PACKAGES' \
  app/src/main/AndroidManifest.xml; then
  echo "ERROR: Prohibited storage/install permission found in AndroidManifest.xml."
  exit 1
fi

echo "[3/6] Checking release network_security_config.xml disables cleartext..."
if grep -q 'cleartextTrafficPermitted="true"' app/src/main/res/xml/network_security_config.xml; then
  echo "ERROR: Release network_security_config.xml permits cleartext traffic."
  exit 1
fi
if ! grep -q 'cleartextTrafficPermitted="false"' app/src/main/res/xml/network_security_config.xml; then
  echo "ERROR: Release network_security_config.xml must explicitly set cleartextTrafficPermitted=\"false\"."
  exit 1
fi

echo "[4/6] Checking backup rules exclude encrypted DataStore preferences..."
if ! grep -q 'pizzora_secure_prefs.preferences_pb' app/src/main/res/xml/backup_rules.xml || \
   ! grep -q 'pizzora_secure_prefs.preferences_pb' app/src/main/res/xml/data_extraction_rules.xml; then
  echo "ERROR: Backup rules must exclude pizzora_secure_prefs.preferences_pb."
  exit 1
fi

echo "[5/6] Checking Android app/ for backend secrets or live keys..."
if grep -rnE 'JWT_SECRET|RAZORPAY_KEY_SECRET|RAZORPAY_WEBHOOK_SECRET|DATABASE_PASSWORD|S3_SECRET_KEY|rzp_live_|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----' \
  app/src app/build.gradle.kts; then
  echo "ERROR: Potential backend secret or private key found in Android module."
  exit 1
fi

echo "[6/6] Checking backend main source for live keys or private keys..."
if grep -rnE 'rzp_live_|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----' \
  backend/src/main shared/; then
  echo "ERROR: Hardcoded live gateway key or private key found in backend/shared source."
  exit 1
fi

echo "PASS: All Phase 18 security and Play policy checks passed."
