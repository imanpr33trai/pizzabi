#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "[1/8] Checking zero Firebase / GMS / Google Services dependencies or imports..."
if grep -rnE 'com\.google\.firebase|com\.google\.gms|com\.google\.android\.gms|google-services|firebase-bom|firebase-auth|firebase-firestore|firebase-messaging|google-services\.json' \
  app/build.gradle.kts backend/build.gradle.kts shared/ gradle/libs.versions.toml app/src backend/src; then
  echo "ERROR: Prohibited Firebase or GMS dependency/import detected."
  exit 1
fi
if grep -rnEi 'firebase' app/build.gradle.kts backend/build.gradle.kts gradle/libs.versions.toml shared/; then
  echo "ERROR: Prohibited Firebase declaration in build/catalog files."
  exit 1
fi

echo "[2/8] Checking AndroidManifest.xml for prohibited Play permissions and exported components..."
if grep -rnE 'READ_EXTERNAL_STORAGE|READ_MEDIA_IMAGES|READ_MEDIA_VIDEO|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|REQUEST_INSTALL_PACKAGES|QUERY_ALL_PACKAGES' \
  app/src/main/AndroidManifest.xml; then
  echo "ERROR: Prohibited storage/install permission found in AndroidManifest.xml."
  exit 1
fi
if grep -q 'android:usesCleartextTraffic="true"' app/src/main/AndroidManifest.xml; then
  echo "ERROR: AndroidManifest.xml must not set usesCleartextTraffic=\"true\"."
  exit 1
fi

echo "[3/8] Checking release and debug network_security_config.xml..."
if grep -q 'cleartextTrafficPermitted="true"' app/src/main/res/xml/network_security_config.xml; then
  echo "ERROR: Release network_security_config.xml permits cleartext traffic."
  exit 1
fi
if ! grep -q 'cleartextTrafficPermitted="false"' app/src/main/res/xml/network_security_config.xml; then
  echo "ERROR: Release network_security_config.xml must explicitly set cleartextTrafficPermitted=\"false\"."
  exit 1
fi

echo "[4/8] Checking backup rules exclude encrypted DataStore preferences and Room database..."
for f in app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml; do
  if ! grep -q 'pizzora_secure_prefs.preferences_pb' "$f" || ! grep -q 'pizzora.db' "$f"; then
    echo "ERROR: $f must exclude pizzora_secure_prefs.preferences_pb and pizzora.db."
    exit 1
  fi
done

echo "[5/8] Checking Android app/ for backend secrets, BuildConfig leaks, or live keys..."
if grep -rnE 'JWT_SECRET|RAZORPAY_KEY_SECRET|RAZORPAY_WEBHOOK_SECRET|DATABASE_PASSWORD|S3_SECRET_KEY' \
  app/src/main app/build.gradle.kts; then
  echo "ERROR: Potential backend secret reference found in Android module."
  exit 1
fi
if grep -rnE 'rzp_live_|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----' \
  app/src app/build.gradle.kts; then
  echo "ERROR: Live key or private key found in Android module."
  exit 1
fi

echo "[6/8] Checking backend/shared source for live keys or private keys..."
if grep -rnE 'rzp_live_|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----' \
  backend/src/main shared/; then
  echo "ERROR: Hardcoded live gateway key or private key found in backend/shared source."
  exit 1
fi

echo "[7/8] Checking repository for hardcoded API tokens or committed local.properties..."
if find . -maxdepth 3 -name 'local.properties' | grep -q .; then
  echo "ERROR: local.properties must never exist or be committed in the workspace."
  exit 1
fi
if grep -rnE --exclude-dir=.gradle --exclude-dir=build --exclude-dir=.git \
  'AIza[0-9A-Za-z_-]{35}|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}' \
  app backend shared infra; then
  echo "ERROR: Potential hardcoded API key/token detected."
  exit 1
fi

echo "[8/8] Checking Android BuildConfig release endpoint defaults to HTTPS..."
if ! grep -q 'https://api.pizzora.app' app/build.gradle.kts; then
  echo "ERROR: Release buildType must default API_BASE_URL to an HTTPS endpoint."
  exit 1
fi

echo "PASS: All Phase 18 security and Play policy checks passed."
