# PIZZORA Android Self-Update & GitHub Release Guide

PIZZORA distributes signed production APKs directly via public GitHub Releases (`imanpr33trai/pizzabi`) and checks for updates inside the app without Google Play In-App Updates.

---

## 1. Architecture Overview

| Layer | Responsibility | Key Files |
| :--- | :--- | :--- |
| **CI Release Pipeline** | Builds signed release APK (`arm64-v8a`, `x86_64`), validates monotonic `versionCode`, generates `update.json` & `SHA256SUMS.txt`, and publishes all 3 assets to GitHub Releases. | `.github/workflows/security-and-ci.yml`, `.github/scripts/generate-release-metadata.py`, `release-policy.json` |
| **Discovery & Cache** | Queries `GET /repos/imanpr33trai/pizzabi/releases/latest` using `If-None-Match` (`ETag`), rejects drafts/prereleases, and persists check metadata in DataStore (`pizzora_update_prefs`). | `app/src/main/java/com/example/update/UpdateRepository.kt`, `UpdatePreferencesStore.kt` |
| **Validation & Policy** | Enforces HTTPS + GitHub host/redirect allowlists, schema v1 + `com.aistudio.pizzora.xkqzmw` identity, non-downgrade, `minSdk`/ABI compatibility, and `mandatory` / `minimumSupportedVersionCode` rules. | `UpdateUrlPolicy.kt`, `UpdateChecker.kt` |
| **Download & Verification** | Single-flight streaming into app-private `filesDir/updates/`, storage headroom check, cancellation/retries, constant-time SHA-256 verification, APK archive inspection, and signing certificate continuity check. | `ApkDownloadManager.kt`, `ApkIntegrityVerifier.kt` |
| **Android Installation** | Checks `canRequestPackageInstalls()`, guides users to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`, and installs via `PackageInstaller` with `USER_ACTION_REQUIRED`. | `ApkInstaller.kt`, `UpdateViewModel.kt`, `UpdateScreens.kt` |

---

## 2. Creating a Production Release

Releases are triggered solely by pushing a semantic version Git tag (`vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-prerelease`) to `imanpr33trai/pizzabi` after all CI quality, parity, unit, and instrumentation gates pass:

1. Ensure `release-policy.json` reflects your target `minimumSupportedVersionCode` and `mandatory` flag before tagging.
2. Commit any release-notes updates or policy changes on `main`.
3. Create and push an annotated semantic version tag:
   ```bash
   git checkout main
   git pull --ff-only
   git tag -a v0.7.0 -m "PIZZORA v0.7.0 — release highlights..."
   git push origin v0.7.0
   ```
4. **Version code formula & monotonicity:**
   - CI derives `versionCode = MAJOR * 1000000 + MINOR * 1000 + PATCH` (where `MINOR < 1000` and `PATCH < 1000`).
   - Example: `v0.7.0` -> `7000`; `v1.2.3` -> `1002003`.
   - `build-release-apk` verifies the new `versionCode` is strictly greater than all prior stable `v*` Git tags and the latest published GitHub Release.
5. **Prereleases vs stable releases:**
   - Tags with a pre-release suffix (for example `v0.8.0-rc.1`) publish with `--prerelease` and `prerelease: true` in `update.json`.
   - `/releases/latest` and the Android `UpdateRepository` exclude prereleases and draft releases from stable update discovery.

---

## 3. Metadata & Checksum Generation (`update.json` and `SHA256SUMS.txt`)

`.github/scripts/generate-release-metadata.py` runs after the APK is built, signed with the `upload` keystore, verified with `apksigner` and `aapt2`, and staged as `pizzora-vX.Y.Z.apk`.

Published assets on every GitHub Release:
1. `pizzora-vX.Y.Z.apk`
2. `update.json`
3. `SHA256SUMS.txt`

`update.json` fields:
- `schemaVersion` (`1`)
- `applicationId` (`com.aistudio.pizzora.xkqzmw`)
- `versionName` (`X.Y.Z`)
- `versionCode` (integer)
- `minimumSupportedVersionCode` (integer)
- `mandatory` (boolean)
- `releaseNotes` (sourced from `releaseNotesOverride`, tag message, or git log since previous stable tag)
- `apkAssetName` (`pizzora-vX.Y.Z.apk`)
- `apkSha256` (lowercase hex SHA-256 of the final signed APK)
- `apkSizeBytes` (exact byte size of the final signed APK)
- `minSdk` (`24`)
- `supportedAbis` (`["arm64-v8a", "x86_64"]`)
- `releaseUrl` (`https://github.com/imanpr33trai/pizzabi/releases/tag/vX.Y.Z`)
- `publishedAt` (UTC ISO-8601 timestamp stamped during `publish-github-release`)
- `prerelease` (`false` for stable releases)

---

## 4. Configuring `minimumSupportedVersionCode` and Mandatory Updates

Edit `/release-policy.json` in the repository before creating the release tag:

```json
{
  "schemaVersion": 1,
  "minimumSupportedVersionCode": 7000,
  "mandatory": false,
  "releaseNotesOverride": ""
}
```

- **Mark a release mandatory for all older versions:** set `"mandatory": true`.
- **Force upgrade only for versions below a threshold (e.g., breaking API change):** set `"minimumSupportedVersionCode"` to the lowest still-compatible `versionCode` (must be `>= 1` and `<=` the release's `versionCode`) and keep `"mandatory": false`. Clients with `installedVersionCode < minimumSupportedVersionCode` automatically treat the update as mandatory (`BELOW_MINIMUM_SUPPORTED_VERSION`).
- **Release notes priority:** if `"releaseNotesOverride"` is non-empty, it is used verbatim; otherwise annotated tag body, otherwise git log since the previous stable tag.

`Stage 0 — Preflight` validates `release-policy.json` on every push/PR so invalid schemas cannot reach tagging.

---

## 5. How Users Install Updates

1. **Automatic & manual discovery:**
   - PIZZORA checks GitHub Releases once on initial launch at most once every 24 hours (and re-checks on launch/resume whenever a mandatory update was previously seen so policy changes are never cached indefinitely).
   - Users can check manually at any time:
     - **Customer:** **Profile -> App Updates -> Check for Updates**
     - **Owner:** **More -> App Updates -> Check for Updates**
     - **Kitchen Display (KDS):** **Top bar Update icon -> App Updates -> Check for Updates**
2. **Download & Integrity Verification:**
   - Tapping **Download & Install** streams `pizzora-vX.Y.Z.apk` into app-private storage (`filesDir/updates/`) with real-time progress and cancellation support.
   - Before installing, `ApkIntegrityVerifier` verifies constant-time SHA-256 against `update.json`, parses the APK archive (`PackageManager.getPackageArchiveInfo`), checks `packageName == "com.aistudio.pizzora.xkqzmw"`, checks `versionCode > installedVersionCode && == metadata.versionCode`, and verifies signing certificate SHA-256 continuity against the currently installed app.
3. **Unknown App Sources Permission & PackageInstaller Confirmation:**
   - If Android has not granted unknown-source install permission for PIZZORA (`PackageManager.canRequestPackageInstalls() == false`), the UI guides the user to **Open Settings** (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`). Upon returning to the app, PIZZORA automatically resumes verification and installer launch.
   - Installation uses `android.content.pm.PackageInstaller` with `USER_ACTION_REQUIRED` so Android always presents an explicit system confirmation prompt.

---

## 6. Local Development & Testing

- **Security & workflow checks:**
  ```bash
  bash infra/security-check.sh
  python3 .github/scripts/generate-release-metadata.py --validate-policy-only --policy-file release-policy.json
  ```
- **Unit, Robolectric & Compose UI tests:**
  ```bash
  ./gradlew :app:testDebugUnitTest --no-daemon
  ```
  Targeted update tests:
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.example.update.*" --tests "com.example.Phase18SecurityRobolectricTest" --no-daemon
  ```
- **Compile instrumentation tests:**
  ```bash
  ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon
  ```
- **Important local vs CI parity note:** End-to-end signed release APK packaging (`ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD`), `gh release create`, and real-device/emulator `PackageInstaller` upgrade confirmation must be verified in GitHub Actions or on an Android device/emulator signed with the **same** release certificate. Debug APKs and release APKs use different signing keys and cannot upgrade across each other.

---

## 7. Troubleshooting

| Issue | Likely Cause | Fix |
| :--- | :--- | :--- |
| **`APK signing certificate does not match the installed PIZZORA app`** | Attempting to update a locally debug-signed APK with a production upload-keystore APK (or keystore rotated without Android key rotation). | Install a release-signed baseline build or keep `ANDROID_KEYSTORE_BASE64` / alias `upload` identical across all releases. Never switch release keystores arbitrarily. |
| **`InstallPermissionRequired` / prompt keeps asking for Settings** | "Allow from this source" is toggled off for PIZZORA in Android App Info. | Tap **Open Settings**, enable **Allow from this source**, and press Back to return to PIZZORA. |
| **GitHub API rate limit (`HTTP 403` / `429`)** | Shared NAT/IP exhausted unauthenticated GitHub REST API quota (`60 req/hr/IP`). | Conditional `ETag` / `If-None-Match` (`304 Not Modified`) requests minimize quota usage, and 24-hour auto-check throttling prevents polling loops. Retry after the reset window. |
| **`Untrusted update host` / `UNTRUSTED_URL`** | `update.json` or redirect points outside HTTPS GitHub release hosts (`api.github.com`, `github.com`, `*.githubusercontent.com`). | Re-run release publishing via `.github/workflows/security-and-ci.yml`; never host release assets on third-party HTTP or unallowlisted domains. |
| **`AndroidInstaller` reports conflict or downgrade failure** | Installed `versionCode >=` target `versionCode`, or keystore mismatch. | Always bump the semantic tag to produce a strictly higher `versionCode`. |

---

## 8. Emergency Rollback & Fixing a Bad Release (Android Non-Downgrade Rule)

**Android forbids installing an APK with a lower `versionCode` over a higher `versionCode` (`VERSION_DOWNGRADE` / `INSTALL_FAILED_VERSION_DOWNGRADE`).**

If a published release (e.g., `v0.7.0`, `versionCode = 7000`) has a critical bug:

1. **Do NOT attempt to make users downgrade back to `v0.6.0` (`versionCode = 6000`).** Android PackageInstaller and `ApkIntegrityVerifier` reject lower `versionCode` upgrades.
2. **Delete or mark the broken GitHub Release as prerelease/draft immediately** (or publish a fixed release right away) so new checks stop picking up the broken release if a fix is ready. Note: if no newer `versionCode` exists yet, marking `v0.7.0` as prerelease stops unpatched `v0.6.0` users from upgrading to `v0.7.0`.
3. **Revert or fix the faulty commit on `main`, set `mandatory: true` or update `minimumSupportedVersionCode` in `release-policy.json` so affected `v0.7.0` users are upgraded automatically:**
   - Example: revert the bad change on `main`, set `"minimumSupportedVersionCode": 7001` (or `"mandatory": true`), and tag **`v0.7.1`** (`versionCode = 7001`).
4. **Push tag `v0.7.1`** to build a new APK with `versionCode = 7001 > 7000` signed by the same `upload` keystore. Existing `v0.7.0` devices discover `v0.7.1` (`7001 > 7000`), verify the signature and SHA-256, and upgrade forward cleanly.
