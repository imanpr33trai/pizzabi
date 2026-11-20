# PIZZORA Phase 4 — External Android Emulator QA Handoff

> **Status:** Phase 4 implementation and automated verification are complete (`134/134` tests across `30` suites passing; `:app:assembleDebug` green; Room v3 schema unchanged; Phases 5–8 untouched).
> **Why this handoff exists:** The sandbox build environment runs under gVisor (`4.19.0-gvisor`) without `/dev/kvm`, `/dev/dri`, graphics libraries, emulator binaries, or system images. Execute this runbook on a **KVM-enabled Linux x86_64 workstation** or an **Android Emulator CI runner** (e.g., `reactivecircus/android-emulator-runner`) to complete the remaining Phase 4 Visual, Live Interaction, and Accessibility Exit-Gate checks defined in `public/implementation_plan.md`.
> **Canonical Plan:** `public/implementation_plan.md` (Phase 4 Exit Gate & Section 13.2).

---

## 1. Host & Android SDK Requirements

### 1.1 Host Workstation / CI Runner Prerequisites
- **OS & Architecture:** Linux `x86_64` with hardware virtualization enabled (`VT-x` / `AMD-V`) and `/dev/kvm` accessible (`rw`) to the current user.
- **Resources:** `>= 8 GB` RAM (`>= 16 GB` recommended when running Docker Compose backend + API 36 emulator together), `>= 25 GB` free disk space.
- **Toolchain:**
  - **JDK 21** (Temurin 21, matching `.github/workflows/security-and-ci.yml`).
  - **Android SDK Command-Line Tools** (`sdkmanager`, `avdmanager`) with `ANDROID_HOME` / `ANDROID_SDK_ROOT` exported.
  - **Docker Engine + Docker Compose v2** (for starting the local PIZZORA backend stack).

Verify KVM access on Linux before booting an API 36 emulator (software TCG emulation will time out):

```bash
ls -l /dev/kvm
# If permission denied, apply the same udev rule used in .github/workflows/security-and-ci.yml:
echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm && sudo udevadm settle
test -r /dev/kvm && test -w /dev/kvm && echo "KVM OK"
```

### 1.2 Required Android SDK Packages
App configuration (`app/build.gradle.kts`): `compileSdk = 36.1`, `minSdk = 24`, `targetSdk = 36`, ABIs `arm64-v8a` & `x86_64`. CI standard (`.github/workflows/security-and-ci.yml`): API 36 `google_apis` `x86_64` on `pixel_6`.

Install required SDK packages:

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

yes | sdkmanager --licenses
sdkmanager --install \
  "platform-tools" \
  "emulator" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "system-images;android-36;google_apis;x86_64"
```

> **Optional Accessibility Tooling:** If TalkBack is not preinstalled on the `google_apis` system image, either use `system-images;android-36;google_apis_playstore;x86_64` (includes Play Store / Android Accessibility Suite) or install the Android Accessibility Suite APK on the `google_apis` image before running Section 7.1.

---

## 2. AVD Creation, Launch & ADB Verification

### 2.1 Create the Baseline AVD (`pixel_6`, API 36)
Create one primary phone AVD; Section 3 uses runtime `adb shell wm size` / `wm density` overrides so you can cover `412dp`, `360dp`, `320dp`, and `840dp` tablet layouts cleanly on the same booted emulator without provisioning four separate AVDs (or optionally create a second tablet AVD for `840dp+`).

```bash
echo "no" | avdmanager create avd \
  --force \
  --name "pizzora_api36_qa" \
  --package "system-images;android-36;google_apis;x86_64" \
  --device "pixel_6"
```

Recommended AVD config tweaks (`~/.android/avd/pizzora_api36_qa.avd/config.ini`) for smooth Compose + software/hardware keyboard testing:

```ini
hw.ramSize=4096
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=auto
disk.dataPartition.size=4096M
```

### 2.2 Launch the Emulator
**On a desktop Linux workstation (interactive GUI for manual QA & TalkBack):**
```bash
emulator -avd pizzora_api36_qa \
  -gpu auto \
  -no-snapshot-load \
  -netdelay none \
  -netspeed full &
```

**On a headless Linux server / CI runner (no display server):**
```bash
emulator -avd pizzora_api36_qa \
  -no-window \
  -no-audio \
  -gpu swiftshader_indirect \
  -no-boot-anim \
  -no-snapshot-load &
```

### 2.3 Verify Boot Readiness via ADB
You can run the repository's bounded readiness gate directly (expects API 36):

```bash
bash .github/scripts/android-emulator-readiness.sh
```

Or verify manually with ADB:

```bash
adb start-server
adb devices -l
adb wait-for-device
adb get-state                                        # Expected: device
adb shell getprop sys.boot_completed                 # Expected: 1
adb shell getprop ro.build.version.sdk               # Expected: 36
adb shell pm list packages | head -n 5               # Verifies PackageManager is responsive
```

---

## 3. Recommended Emulator Resolution, Density, Theme & Font Configurations

Width math: `width_dp = width_px / (density_dpi / 160)`.

| Target Profile | Required Phase 4 Captures | `wm size` | `wm density` | Resulting Viewport (`dp`) | Theme (`cmd uimode night`) | Font Scale (`font_scale`) |
|---|---|---|---|---|---|---|
| **Standard Phone (Dark `412dp`, `1.0x`)** | Cart w/ coupon, CouponPickerSheet, Undo snackbar, Discontinued Edit Mode | `824x1830` *(or Pixel 6 native `1080x2400` @ `420` = `411.4dp`)* | `320` *(or `420`)* | `412dp × 915dp` | `yes` (Dark) | `1.0` |
| **Compact Tight-Width Phone (Light `320dp`, `1.5x`)** | Cart w/ coupon (`320dp`, `1.5x`) | `640x1280` | `320` | `320dp × 640dp` | `no` (Light) | `1.5` |
| **Small Phone (Dark `360dp`, `1.0x`)** | Empty Cart, Clear Cart confirmation dialog | `720x1600` | `320` | `360dp × 800dp` | `yes` (Dark) | `1.0` |
| **Tablet Split (Light `840dp`, `1.0x`)** | 60/40 Tablet Cart (`>= 840dp × 480dp`) | `1680x1200` | `320` | `840dp × 600dp` | `no` (Light) | `1.0` |
| **Stress Accessibility (`2.0x` + Largest Display)** | Manual accessibility check (Section 7.2) | `720x1600` | `360` *(or Settings → Display size = Largest)* | `320dp` effective | Both | `2.0` |

### Copy-Paste Configuration Commands

```bash
# Profile 1: Dark, 412dp, 1.0x
adb shell wm size 824x1830
adb shell wm density 320
adb shell cmd uimode night yes
adb shell settings put system font_scale 1.0

# Profile 2: Light, 320dp, 1.5x
adb shell wm size 640x1280
adb shell wm density 320
adb shell cmd uimode night no
adb shell settings put system font_scale 1.5

# Profile 3: Dark, 360dp, 1.0x
adb shell wm size 720x1600
adb shell wm density 320
adb shell cmd uimode night yes
adb shell settings put system font_scale 1.0

# Profile 4: Light, 840dp Tablet (triggers 60/40 Cart split at >= 840dp x 480dp)
adb shell wm size 1680x1200
adb shell wm density 320
adb shell cmd uimode night no
adb shell settings put system font_scale 1.0

# Reset back to AVD defaults when finished
adb shell wm size reset
adb shell wm density reset
adb shell settings put system font_scale 1.0
adb shell cmd uimode night yes
```

> **Note:** After changing `wm size` / `wm density` / `font_scale`, wait ~1 second for Compose recomposition (or press Recents and return to PIZZORA if the status bar inset needs a refresh).

---

## 4. Backend Startup & Host-to-Emulator Networking

### 4.1 Verified App Network Configuration (Zero Code Changes)
- **Debug API Endpoint (`app/build.gradle.kts` L29):** `BuildConfig.API_BASE_URL = "http://10.0.2.2:8080"` (`10.0.2.2` is the Android Emulator alias for host `127.0.0.1`).
- **Debug Cleartext Policy (`app/src/debug/res/xml/network_security_config.xml`):** Cleartext HTTP is permitted strictly for `10.0.2.2`, `localhost`, and `127.0.0.1`.
- **Host-to-Emulator Alignment:**
  - Start the backend on host port **`8080`** — the debug APK connects to `http://10.0.2.2:8080` automatically with zero edits.
  - **MinIO Product Thumbnails (`S3_PUBLIC_URL`):** `infra/docker-compose.yml` defaults `S3_PUBLIC_URL` to `http://localhost:9000/pizzora-product-images`. On the emulator, `localhost:9000` points to the emulator itself unless you either:
    1. **Recommended:** Export `S3_PUBLIC_URL=http://10.0.2.2:9000/pizzora-product-images` when starting Docker Compose, **OR**
    2. Run `adb reverse tcp:9000 tcp:9000` (allowed by `network_security_config.xml` because `localhost` is permitted for cleartext).
  - **Physical USB Device Note:** `10.0.2.2` only resolves inside an Android Emulator. Do **not** change `API_BASE_URL` for this QA pass; run against an Android Emulator so the exact verified debug APK is tested unmodified.

### 4.2 Start the Backend Stack

#### Option A — Full Local Docker Compose Stack (Recommended, includes Postgres + Redis + MinIO + Mailpit + Backend + Worker)
```bash
# Ensure MinIO image URLs are reachable from the Android emulator via 10.0.2.2:9000
S3_PUBLIC_URL="http://10.0.2.2:9000/pizzora-product-images" \
  docker compose -f infra/docker-compose.yml up -d

# Check container health
docker compose -f infra/docker-compose.yml ps
```

If you prefer keeping default `S3_PUBLIC_URL=http://localhost:9000/...`, forward port 9000 to the emulator:
```bash
adb reverse tcp:9000 tcp:9000
```

#### Option B — Run Ktor Backend Locally via Gradle (with Compose infrastructure only)
Use this if `ghcr.io/imanpr33trai/pizzora-backend:main` is unavailable or you want to run directly from the checked-out repository:

```bash
# 1. Start infrastructure services only
docker compose -f infra/docker-compose.yml up -d postgres redis minio minio-init mailpit

# 2. Run the Ktor backend on :8080 (auto-runs Flyway V1–V3 in development mode)
PIZZORA_ENV=development \
PORT=8080 \
DATABASE_URL="jdbc:postgresql://localhost:5432/pizzora" \
DATABASE_USER=pizzora \
DATABASE_PASSWORD=pizzora_dev_password \
REDIS_URL="redis://localhost:6379" \
S3_ENDPOINT="http://localhost:9000" \
S3_BUCKET=pizzora-product-images \
S3_ACCESS_KEY=pizzora_minio_admin \
S3_SECRET_KEY=pizzora_minio_dev_secret \
S3_PUBLIC_URL="http://10.0.2.2:9000/pizzora-product-images" \
SMTP_HOST=localhost \
SMTP_PORT=1025 \
./gradlew :backend:run
```

#### Option C — Rust Backend Stack (Alternative)
If testing against the Rust backend (`infra/docker-compose.rust-staging.yml`), ensure `STAGING_API_PORT=8080` and configure `infra/.env.rust-staging` from `infra/.env.rust-staging.example`, then run:
```bash
docker compose -f infra/docker-compose.rust-staging.yml up -d --build
```
*(Note: `docker-compose.rust-staging.yml` sets `S3_ENABLED=false` by default, so product images will render the warm fallback placeholder unless MinIO/S3 is enabled.)*

### 4.3 Verify Backend Health & Emulator Connectivity
```bash
# On host:
curl -fsS http://127.0.0.1:8080/api/v1/health/live && echo ""
curl -fsS http://127.0.0.1:8080/api/v1/health/ready && echo ""

# Mailpit UI (for reading customer email verification links/tokens):
# Open http://127.0.0.1:8025 in your host browser
```

Verify emulator-to-host reachability from inside ADB (if `toybox`/`curl` or `nc` is present, or by opening PIZZORA and checking Menu/Auth responses):
```bash
adb shell "cmd connectivity airplane-mode"  # Should be disabled
```

### 4.4 Seed QA Data on a Fresh Database
Flyway `V2__seed_rbac_and_settings.sql` seeds RBAC roles (`CUSTOMER`, `KITCHEN_STAFF`, `OWNER`), permissions, and default `restaurant_settings` (`is_accepting_orders = TRUE`, `gst_bps = 500`), but does **not** seed menu products, crusts, toppings, or coupons.

1. **Bootstrap an Owner account** (once on a fresh DB):
   - Via Docker Compose:
     ```bash
     docker compose -f infra/docker-compose.yml exec \
       -e OWNER_EMAIL="owner@pizzora.local" \
       -e OWNER_PASSWORD="OwnerQaPassword123!" \
       -e OWNER_NAME="PIZZORA QA Owner" \
       -e OWNER_PHONE="9999999999" \
       backend java -cp "/app/libs/*" com.pizzora.backend.ApplicationKt --bootstrap-owner
     ```
   - Or via Gradle (Option B):
     ```bash
     OWNER_EMAIL="owner@pizzora.local" OWNER_PASSWORD="OwnerQaPassword123!" \
       ./gradlew :backend:run --args="--bootstrap-owner"
     ```
2. **Sign in as Owner inside the app (or via Owner APIs) and create the minimal QA catalog:**
   - **2+ Pizzas:**
     - *Margherita Royale* (Veg, with uploaded product photo -> tests `64dp` real thumbnail; Regular `₹249`, Medium `₹399`, Large `₹549`).
     - *Smoky Pepperoni* (Non-Veg, **no image** -> tests warm fallback thumbnail; Regular `₹349`, Medium `₹499`).
   - **2+ Crusts:** *Hand Tossed* (`₹0` / Included), *Cheese Burst* (`₹99`).
   - **3+ Extra Toppings:** *Extra Cheese* (Veg, `₹55`), *Jalapenos* (Veg, `₹35`), *Smoked Olives* (Veg, `₹45` — optionally deactivate later to verify the unavailable saved topping warning in Edit Mode).
   - **2 Active Coupons:**
     - **Eligible coupon** (e.g., `SAVE50`, flat `₹50` off, `minOrderPaise = 20000` / `₹200`) -> appears in **Available to Apply**.
     - **Locked coupon** (e.g., `FEAST200`, flat `₹200` off, `minOrderPaise = 150000` / `₹1,500`) -> appears in **Unlock More Savings** with `"Add ₹X more to unlock"`.
3. **Register & Verify a Customer Account:**
   - Sign out of Owner, tap **Create Account** in PIZZORA, register e.g. `qa.customer@pizzora.local`, open Mailpit at `http://127.0.0.1:8025`, verify the email token, and sign in as the customer.

---

## 5. Build, Install & Launch the Debug APK

If `app/build/outputs/apk/debug/app-debug.apk` is not already present on the QA workstation, build it without modifying code:

```bash
./gradlew :app:assembleDebug --console=plain
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

Install, verify package registration, and launch PIZZORA:

```bash
# Install (replaces existing debug install while preserving user data; add -d if downgrading)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verify installation
adb shell pm list packages | grep com.aistudio.pizzora.xkqzmw

# Launch MainActivity
adb shell am start -W -n com.aistudio.pizzora.xkqzmw/com.example.MainActivity
```

Useful ADB logcat filter during live QA:
```bash
adb logcat -c
adb logcat --pid="$(adb shell pidof -s com.aistudio.pizzora.xkqzmw)"
```

---

## 6. Required Phase 4 Visual QA Captures (Section 13.2 Naming & Criteria)

Create the artifact output directory:

```bash
mkdir -p qa-artifacts/phase4
```

All filenames follow `public/implementation_plan.md` Section 13.2 (`p<phase>_<surface>_<state>_<theme>_<width>dp_<fontScale>.png`).

### 6.1 Capture Commands & Per-Screenshot Pass/Fail Criteria

| # | State | Emulator Profile | Exact Capture Command | Visual Pass / Fail Inspection Criteria |
|---|---|---|---|---|
| **1** | **Cart w/ items, thumbnails & applied coupon** *(Dark, `412dp`, `1.0x`)* | `824x1830`, `320dpi`, Dark (`night yes`), `font_scale 1.0` | `adb exec-out screencap -p > qa-artifacts/phase4/p4_cart_items_coupon_dark_412dp_1.0x.png` | **Pass:** `64dp` photo & warm fallback thumbnails align cleanly; `Edit` button + qty stepper readable; basil-green coupon savings callout visible; sticky dock has single coral `Proceed to Checkout` CTA + aligned tabular mono totals; visible `outlineInteractive` card borders; zero M3 lavender `errorContainer` or muddy dark `tonalElevation`. |
| **2** | **Cart w/ items & applied coupon** *(Light, `320dp`, `1.5x`)* | `640x1280`, `320dpi`, Light (`night no`), `font_scale 1.5` | `adb exec-out screencap -p > qa-artifacts/phase4/p4_cart_items_coupon_light_320dp_1.5x.png` | **Pass:** Zero text clipping or horizontal overlap across `Edit`, qty stepper, fulfillment chips, coupon banner, and sticky bottom dock; price breakdown rows stay readable; Light-mode text/border contrast clean; zero 3-button dock crowding. |
| **3** | **CouponPickerSheet (Eligible vs Locked grouping)** *(Dark, `412dp`, `1.0x`)* | `824x1830`, `320dpi`, Dark (`night yes`), `font_scale 1.0` | `adb exec-out screencap -p > qa-artifacts/phase4/p4_coupons_grouped_dark_412dp_1.0x.png` | **Pass:** Distinct **Available to Apply** (eligible) and **Unlock More Savings** (locked) sections; eligible coupon has enabled Apply CTA; locked coupon is visually muted with `"Add ₹X more to unlock"` message and disabled button; badges wrap cleanly without overlapping coupon codes. |
| **4** | **Tablet Cart 60/40 Split** *(Light, `840dp`, `1.0x`)* | `1680x1200`, `320dpi`, Light (`night no`), `font_scale 1.0` | `adb exec-out screencap -p > qa-artifacts/phase4/p4_cart_tablet_split_light_840dp_1.0x.png` | **Pass:** 60/40 2-pane layout activates (`>= 840dp × 480dp`): left 60% scrolls cart items cleanly, right 40% holds delivery/coupon/price summary + `Proceed to Checkout` CTA without overlapping bottom chrome or `NavigationRail`. |
| **5** | **Empty Cart** *(Dark, `360dp`, `1.0x`)* | `720x1600`, `320dpi`, Dark (`night yes`), `font_scale 1.0` | `adb exec-out screencap -p > qa-artifacts/phase4/p4_cart_empty_dark_360dp_1.0x.png` | **Pass:** Centered `PizzoraEmptyStateCard` with warm iconography, clear copy, and single primary **`Browse Menu`** CTA; sticky checkout dock hidden; no double bottom navigation padding. |
| **6** | **Clear Cart Confirmation Modal** *(Dark, `360dp`, `1.0x`)* | `720x1600`, `320dpi`, Dark (`night yes`), `font_scale 1.0` | `adb exec-out screencap -p > qa-artifacts/phase4/p4_cart_clear_dialog_dark_360dp_1.0x.png` | **Pass:** `PizzoraConfirmationDialog` centered with crisp scrim, clear title/body copy, and non-clipped `Cancel` + destructive `Clear Cart` actions; tapping Clear does not immediately wipe cart without confirmation. |
| **7** | **Undo Removal Snackbar** *(Dark, `412dp`, `1.0x`)* | `824x1830`, `320dpi`, Dark (`night yes`), `font_scale 1.0` | `adb exec-out screencap -p > qa-artifacts/phase4/p4_cart_undo_snackbar_dark_412dp_1.0x.png` | **Pass:** Decrement a qty-1 item (or remove item) and immediately capture while the 4s `SnackbarDuration.Long` snackbar is visible; snackbar sits cleanly above the sticky dock / navigation bar with zero overlap and legible **`Undo`** action. |
| **8** | **Unavailable / Discontinued Edit Mode** *(Dark, `412dp`, `1.0x`)* | `824x1830`, `320dpi`, Dark (`night yes`), `font_scale 1.0` | `adb exec-out screencap -p > qa-artifacts/phase4/p4_cart_edit_discontinued_dark_412dp_1.0x.png` | **Pass:** Full-screen `PizzaBuilderScreen` Edit Mode shows clear discontinued/unavailable error state using `errorContainer` (no M3 lavender), disables/blocks update submission, and provides safe Back / Remove recovery with zero crash. |

#### How to Trigger Capture #8 (Discontinued Edit Mode) Reliably
1. Add *Margherita Royale* to Cart and confirm it appears on the Cart tab.
2. Deactivate that product in the backend (either via Owner API/UI or directly in Postgres):
   ```bash
   docker compose -f infra/docker-compose.yml exec postgres \
     psql -U pizzora -d pizzora -c \
     "UPDATE products SET is_active = FALSE WHERE LOWER(name) LIKE '%margherita%';"
   ```
3. Return to the **Menu** tab and pull-to-refresh (so the app's catalog cache picks up `is_active = false`), switch back to **Cart**, and tap **Edit** on *Margherita Royale*.
4. Capture `p4_cart_edit_discontinued_dark_412dp_1.0x.png`, verify recovery, then re-activate the product:
   ```bash
   docker compose -f infra/docker-compose.yml exec postgres \
     psql -U pizzora -d pizzora -c \
     "UPDATE products SET is_active = TRUE WHERE LOWER(name) LIKE '%margherita%';"
   ```

Verify all 8 PNGs exist and are non-empty:
```bash
ls -lh qa-artifacts/phase4/p4_*.png
```

---

## 7. Manual QA Checklist (Interactions & Accessibility)

Record **PASS / FAIL** and notes for every item below.

### 7.1 Live Interaction Flows (11 Core Flows + Edge Cases)
- [ ] **1. Open Cart Item in Edit Mode:** From Cart with a customized pizza (e.g., Medium + Cheese Burst + 2 Extra Toppings + Qty 2), tap **Edit**. Verify `PizzaBuilderScreen` opens full-screen above bottom nav/rail with primary CTA copy **`Update Item · ₹X.XX`**.
- [ ] **2. Configuration Restoration:** Verify Size, Crust, selected Extra Toppings, and Quantity (`2`) match the exact Cart item configuration on open, and the expandable price breakdown matches the Cart line price.
- [ ] **3. Unavailable Saved Topping Warning (Edge Case):** Deactivate one topping previously saved on a cart item, refresh Menu, open **Edit**, and verify the unavailable-topping warning banner appears, the inactive topping cannot be re-selected, and saving removes the unavailable topping cleanly.
- [ ] **4. Update Cart Item & Persistence:** Modify Size, Crust, Toppings, or Qty in Edit Mode, tap **Update Item**, verify the Cart item updates immediately with accurate subtotal/total, then force-stop and relaunch PIZZORA (`adb shell am force-stop com.aistudio.pizzora.xkqzmw && adb shell am start -n com.aistudio.pizzora.xkqzmw/com.example.MainActivity`) and confirm the updated item and toppings persist cleanly in Room.
- [ ] **5. Duplicate Configuration Merge (Under 25 & Capped at 25):**
  - *Under 25:* Have Item A (Qty 2) and Item B (Qty 3, different crust) in Cart. Edit Item B to match Item A's exact Size + Crust + Toppings and save. Verify Cart merges them into a single row with Qty `5` and no duplicate rows.
  - *Capped at 25:* Edit an item with Qty `20` into a configuration matching an existing row with Qty `10`. Verify Cart merges into one row capped at Qty `25`, displays the max-qty cap snackbar notice, and disables the `+` stepper at `25`.
- [ ] **6. Remove Single Item & Undo:** Decrement a Qty-1 item in Cart. Verify the item disappears immediately, the 4s `SnackbarDuration.Long` snackbar appears with **Undo**, tapping **Undo** restores the exact item + toppings + quantity, and rapid back-to-back removals of two items do not corrupt state or crash.
- [ ] **7. Clear Cart — Cancel Confirmation:** Tap **Clear Cart** -> verify `PizzoraConfirmationDialog` opens -> tap **Cancel** -> verify all cart items remain intact.
- [ ] **8. Clear Cart — Confirm:** Tap **Clear Cart** -> tap **Clear Cart** in the dialog -> verify Cart empties cleanly and displays Empty Cart with **Browse Menu** CTA (which switches to the Menu tab).
- [ ] **9. CouponPickerSheet Grouping & Apply/Remove:** Open **View Coupons** / **Change Coupon**. Verify coupons are grouped into eligible (**Available to Apply**) vs locked (**Unlock More Savings**). Apply an eligible coupon -> confirm basil-green savings banner and updated Tier 2 server total on Cart. Remove coupon -> confirm total reverts cleanly.
- [ ] **10. Soft Keyboard (IME) Sticky Dock Hide / Show:** Tap the coupon code or pincode `OutlinedTextField` on Cart so the Android soft keyboard slides open. Verify the sticky bottom checkout dock hides smoothly with zero layout jump or overlap over the focused field. Dismiss the keyboard (Back / IME Done) and verify the sticky dock returns cleanly.
- [ ] **11. Back Navigation, Discard Guard & Focus Restoration:**
  - Open **Edit Mode**, make **no** changes, press system Back -> closes immediately and returns to Cart.
  - Open **Edit Mode**, change a topping or size, press system Back -> verify unsaved-changes discard confirmation dialog appears; **Keep Editing** stays in Builder; **Discard** returns to Cart with original item unchanged.
  - Verify focus returns cleanly to the Cart surface after closing Edit Mode, `CouponPickerSheet`, and Clear Cart dialog.

### 7.2 Live Accessibility, Scaling, Motion & Hardware Keyboard QA
- [ ] **TalkBack Traversal & Announcements:**
  - Enable TalkBack on emulator (`Settings → Accessibility → TalkBack` or `adb shell settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`).
  - Swipe right sequentially through Cart: verify thumbnails/cards, `"Remove item"` announcement when qty is `1` (vs `"Decrease quantity"` when qty > `1`), `Role.RadioButton` announcements on Delivery/Pickup chips and Builder Size/Crust options, `Role.Checkbox` announcements on Toppings, `LiveRegionMode.Polite` price announcements when changing options, and zero TalkBack bleed-through to underlying Menu/Cart nodes while Edit Mode or Clear dialog is open.
- [ ] **Touch Target Audit (`>= 48dp`):** Verify Edit button, `+`/`-` stepper buttons, Remove icon, Clear Cart button, fulfillment chips, and Coupon apply buttons are comfortably tappable without mis-taps at `320dp`, `360dp`, and `412dp`.
- [ ] **Large Font (`1.5x` & `2.0x`) + Display Size (`Largest`):**
  ```bash
  adb shell settings put system font_scale 2.0
  # Also set Settings -> Display -> Display size and text -> Display size = Largest
  ```
  Verify Cart, `CouponPickerSheet`, Clear Cart modal, and Edit Mode remain vertically scrollable with zero clipped primary CTAs or overlapping text.
- [ ] **Reduced Motion (`ANIMATOR_DURATION_SCALE = 0`):**
  ```bash
  adb shell settings put global animator_duration_scale 0
  adb shell settings put global transition_animation_scale 0
  adb shell settings put global window_animation_scale 0
  ```
  Verify Edit Mode open/close, Breakdown drawer expand/collapse, and snackbar transitions remain crisp with zero stuck animations. Reset scales back to `1.0` after testing.
- [ ] **External Hardware Keyboard Navigation:**
  - Using host keyboard forwarded into the emulator (`hw.keyboard=yes`): press `Tab` / `Shift+Tab` across Cart controls, `Enter` / `Space` to trigger **Edit**, navigate Builder Size/Crust/Toppings/Update CTA, press `Esc` to dismiss dialogs/Edit Mode, and verify visible focus indicators and clean focus restoration on dismiss.

---

## 8. Phase 4 Exit-Gate Sign-Off Procedure

Do **not** mark Phase 4 complete until all items below are satisfied and recorded in `public/implementation_plan.md` under `### Phase 4: Cart Polish & Custom Pizza Re-Edit Flow (Stage 2)`:

1. All **8 required PNGs** in `qa-artifacts/phase4/` are visually reviewed against Section 6.1 Pass/Fail criteria (attach to PR / QA report).
2. Any genuine visual or interaction defect discovered on the emulator is fixed, re-captured, and re-verified with `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`.
3. All **11 live interaction flows** (Section 7.1) and **Accessibility / IME / `2.0x` font / hardware-keyboard checks** (Section 7.2) pass on the booted API 36 emulator.
4. Update `public/implementation_plan.md` with the emulator AVD spec, screenshot paths, defects found/fixed (if any), and flip Phase 4 Exit Gate from **NOT SATISFIED (OPEN)** to **SATISFIED**.
