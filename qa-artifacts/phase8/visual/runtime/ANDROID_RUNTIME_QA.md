# Phase 8 Android Runtime QA — `emulator-5554`

- Date: 2026-11-27 UTC
- Device: real Android emulator instance `emulator-5554`, online via ADB
- App under test: `com.aistudio.pizzora.xkqzmw/com.example.MainActivity`
- Evidence directory: `qa-artifacts/phase8/visual/runtime/`
- All screenshots below are real `adb exec-out screencap -p` captures from this emulator.
- Orca was not installed, invoked, or used.
- No application, Rust, documentation, config, or Git source was modified. Temporary QA helpers and evidence were written only under the runtime evidence directory; ad-hoc token/request scratch files were kept under `/tmp/opencode/`.
- Before publication, captured local access/refresh tokens and the typed local QA password were redacted in the copied evidence files (`20_owner_login.json`, `86_customer_relogin.json`, `119_owner_relogin.json`, `command_log.txt`); all runtime observations remain unchanged.

## 1. Environment audit

Full command transcript with exit statuses: `125_environment_audit.txt`, `126_emulator_process.txt`, `127_emulator_backend_reachability.txt`, `128_final_state_restoration.txt`.

| Item | Result | Exact command / evidence | Exit |
|---|---|---|---|
| Kernel | `Linux ip-10-0-138-216 7.0.0-1018-aws #19~24.04.1-Ubuntu SMP PREEMPT Thu Nov 27 17:17:32 UTC 2026 x86_64` | `uname -a` | 0 |
| `lsb_release` | unavailable | `lsb_release -a` | 127 |
| Ubuntu | `24.04.5 LTS (Noble Numbat)`, `VERSION_ID="24.04"` | `grep -E '^(NAME|VERSION|ID|VERSION_ID)=' /etc/os-release` | 0 |
| CPU | 4 vCPU, `Intel(R) Xeon(R) 6975P-C`, VT-x/KVM | `nproc`, `lscpu` | 0 |
| RAM | 15 Gi total, 8.9 Gi available | `free -h` | 0 |
| Disk | 53 Gi available on `/workspace` | `df -h /workspace` | 0 |
| JDK | `openjdk 21.0.13.1`, `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` | `java -version` | 0 |
| Android SDK | `/home/remote/Android/Sdk`, sdkmanager CLI `22.0` | `sdkmanager --version` | 0 |
| Platform tools | ADB `37.0.3-16887417` | `adb version` | 0 |
| Emulator | running version `37.3.10-16970787`; headless process `/home/remote/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless -avd pizzora_api36_qa -no-window -noaudio -no-boot-anim -gpu swiftshader -camera-back none -camera-front none` | `ps -ef | grep -E 'emulator|qemu-system'` | 0 |
| Standalone emulator version command | **BLOCKED** for this command form: `libpulse.so.0: cannot open shared object file`; running process still confirms 37.3.10 | `$ANDROID_HOME/emulator/emulator -version | head -5` | pipeline 0, binary error |
| AVD | `pizzora_api36_qa`, `pixel_6 (Google)`, Android 16 / API 36, `google_apis/x86_64` | `avdmanager list avd` | 0 |
| Guest | Android 16, SDK 36, `userdebug`, `sdk_gphone64_x86_64`, `1080x2400`, density 420 | `adb shell getprop ...`, `adb shell wm size/density` | 0 |
| KVM | `/dev/kvm` `crw-rw---- root hostkvm`; user `remote` in `hostkvm` | `ls -l /dev/kvm`, `id` | 0 |
| GPU/device acceleration | **BLOCKED**: no `/dev/dri`; emulator explicitly uses `-gpu swiftshader` | `ls -l /dev/dri` | 2 |
| Docker | client `29.1.3`, Compose `2.40.3`, server containers `pizzora-pg18`, `linux-remote` up | `docker --version`, `docker compose version`, `docker ps` | 0 |
| Host backend | menu API HTTP 200, 8252 bytes | `curl ... http://127.0.0.1:8080/api/v1/catalog/menu` | 0 |
| Emulator-side direct HTTP client | **NOT TESTED**: no `curl` or `wget` on guest (`curl: inaccessible or not found`) | `adb shell curl ... http://10.0.2.2:8080/...` | 127 |
| Emulator-to-backend app path | PASS: live server quote and cart state were observed from app actions | `07_server_quote_response.json`, `13_server_cart_after_second_add.json` | — |

## 2. Scenario results

| Scenario | Status | Evidence / observed result |
|---|---|---|
| Builder shows all Phase 8 modifier sections | PASS | `08_builder_modifiers_server_price.png/.xml`: Cheese, Sauce, Standard toppings, Half and half, Extra Toppings, Price details, Add to Cart. |
| Builder live server price | PASS | UI `Add to Cart · ₹486.00`; `07_server_quote_request.json` + `07_server_quote_response.json` returned `unit_price_paise=48600`, matching exactly. |
| Authenticated add-to-cart with resolved modifiers | PASS | `08...` → `14_cart_after_authenticated_add.png/.xml`; `13_server_cart_after_second_add.json` shows Mozzarella/Pesto IDs+names, 2 standard toppings, half-and-half, unit 48600. |
| Cart price breakdown | PASS | `14_cart_after_authenticated_add.xml`: Item Subtotal, Delivery Fee, CGST/SGST, Total Payable; `116_cart_after_edit_api.json` confirms edited unit price and modifier preservation. |
| Reorder dialog: available + unavailable + exact copy | PASS | `39_reorder_dialog_available_unavailable.png/.xml`: `Available to add`, `1x Margherita Royale`, `Unavailable modifiers`, `Margherita Royale: Extra Cheese — topping unavailable`, `Margherita Royale: not available`, and exact button text `Add available items only`. Server precheck `33_reorder_api_precheck.json` had one available and one unavailable line. |
| Reorder confirm adds available items only | PASS | `40_after_reorder_confirm.png/.xml`, `41_cart_after_reorder.json`: added the plain item only; no Extra Cheese topping on any added line. |
| Unavailable saved topping | PASS | Reorder dialog and `33_reorder_api_precheck.json` / `121_reorder_variant_unavailable.json` report `TOPPING_UNAVAILABLE` for Extra Cheese. QA catalog state was restored afterwards (`123_topping_restored_final.json`). |
| Unavailable saved variant | PASS (API) / NOT TESTED (UI dialog) | `120_variant_deactivated.json`, `121_reorder_variant_unavailable.json`: `can_reorder=false`, both lines `VARIANT_UNAVAILABLE`. Variant restored (`122_variant_restored.json`). UI dialog was not recaptured after variant deactivation. |
| Cart editing and preservation | PASS | `113_cart_edit_probe.xml` → `114_cart_edit_modified_probe.xml` (`Update Item · ₹368.00`) → `115_after_cart_edit.png/.xml`; `116_cart_after_edit_api.json` shows Cheese Burst + Mozzarella preserved with unit 36800. |
| Duplicate merging | INVALID PROBE — NOT A DEFECT; CORRECTED PROBE PASS | `111_duplicate_merge.png/.xml`, `112_cart_duplicate_api.json`: the qty‑24 line came from a direct API `PUT` with no `standard_topping_ids`, while the qty‑1 builder line included default Basil. The reviewed contract requires merging configurations **including modifiers** (`docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md:420`), so two different modifier sets must remain separate lines. Corrected probe `129_merge_same_modifier_config_request.json` submits two identical Basil-bearing lines and `129_merge_same_modifier_config.json` confirms **one line at qty 25** with Basil preserved. |
| Quantity cap 25 | PASS | `104_cart_qty24_after_login.xml` qty 24 → `105_quantity_25.png/.xml` qty 25 → `106_quantity_after_extra_tap.xml` still qty 25 after another increase tap. |
| Remove + undo | PASS | `108_after_remove_snackbar.png/.xml` showed `Removed Margherita Royale` and `Undo`; `109_after_undo.xml` restored the item at qty 24 (the state at removal time). |
| Clear-cart confirmation | PASS | `88_clear_cart_confirmation.png/.xml`: `Clear your cart?`, `This will remove all items from your cart.`, `Cancel`, `Clear Cart`; `89_cart_empty_after_clear.xml` confirmed empty tray. |
| Coupon grouping | PASS | `78_coupon_picker.png`, `77_coupon_picker_probe.xml`: `Eligible Coupons (2)`, RS-3568C4 and RS-BF853F with eligibility and savings. |
| Coupon application/removal | PASS | `79_coupon_applied.png/.xml`: RS-3568C4 applied, `Saved ₹50.00`, total ₹939.75; `80_coupon_removed.png/.xml`: coupon field returned and discount banner removed. |
| IME keyboard behavior / system insets | PASS (show/hide) | `69_address_ime_visible.png/.xml` with `mInputShown=true`; `71_after_back_ime_hidden.xml` with `mInputShown=false`; `74_cart_ime_state.txt`, `75_cart_ime_visible.png/.xml`, `76_cart_after_ime_hidden.xml`. |
| IME-aware sticky checkout dock | PASS — EXPECTED BEHAVIOR | While IME was visible, the cart sticky dock `Proceed to Checkout` was absent from the UIAutomator hierarchy (`75_cart_ime_visible.xml`) and returned after Back (`76_cart_after_ime_hidden.xml`). This is specified pre-existing behavior (`public/implementation_plan.md:411,539-541,625,823,829-830`) implemented by `CartScreen.kt:170,324-325,430-431`; it is not a Phase 8 acceptance defect. |
| Back navigation | PASS | Builder → Menu (`117_builder_before_back.xml`, `118_menu_after_back.xml`), checkout → cart, reorder dialog Cancel, order detail → order list (`34`–`37`). |
| Focus restoration | PASS (unit-test coverage); runtime instrumentation limitation | UIAutomator exposed no node with `focused="true"` on the checked Compose screens. The implementation is covered by Robolectric `assertIsFocused` assertions in `Phase4CartAndReEditRobolectricTest.kt:699,757,1375`; no app fix is warranted. |
| TalkBack availability | PASS | Package installed, service `com.google.android.marvin.talkback/.TalkBackService`; `52_talkback_services_enabled.txt`, `53_talkback_enabled.txt`, `54_talkback_dumpsys.txt` showed bound/enabled and `touchExplorationEnabled=true`. `55_talkback_builder.png/.xml` captured real TalkBack state. Restored to disabled/null. |
| 1.5x font scale | PASS | `56_font_1_5_setting.txt`, `57_builder_font_1_5.png/.xml`, `58_builder_font_1_5_scrolled.png/.xml`; rendered at 1.5. Restored to 1.0. |
| 2.0x font scale | PASS | `59_font_2_0_setting.txt`, `60_menu_font_2_0.png/.xml`, `61_builder_font_2_0.png/.xml`; rendered at 2.0. Restored to 1.0. |
| Large display size | PASS | `63_density_320.txt`, `64_menu_large_display.png/.xml`, `65_builder_large_display.png/.xml` at density 320; restored to 420. |
| 48dp touch targets at default density | PASS — analysis corrected | `51_builder_default_scale.xml` at density 420: Close, three modifier chips, price-details row, and quantity +/- are each 126px = exactly 48dp (`48 × 420/160`); Add to Cart is 131px ≈ 50dp. The earlier “below 140px” threshold was an analysis error, not a layout defect. |
| 48dp touch targets at large display | PASS | `65_builder_large_display.xml` at density 320: 0 interactive nodes below 96px (48dp). |
| Persistence across process restart | PASS | Cart quantity/state and authenticated session survived `am force-stop` + relaunch (`104_cart_qty24_after_login.xml`, `109_after_undo.xml`, `116_cart_after_edit_api.json`). |

## 3. Primary screenshot index

All PNGs below are `1080 x 2400`.

| Screenshot | Meaning |
|---|---|
| `01_login.png` | Login state (pre-existing evidence, preserved) |
| `03_menu.png` | Menu (pre-existing evidence, preserved) |
| `04_builder.png` | Builder state (pre-existing evidence; do **not** pair with stale `04_builder.xml`) |
| `08_builder_modifiers_server_price.png` | Builder with all Phase 8 modifier sections and settled server price ₹486.00 |
| `14_cart_after_authenticated_add.png` | Authenticated cart after modifier-rich add |
| `15_orders_screen.png` | Orders list |
| `27_reorder_dialog.png` | Reorder dialog from older single-item order (no available item after topping deactivation) |
| `39_reorder_dialog_available_unavailable.png` | Required reorder dialog with available + unavailable items |
| `40_after_reorder_confirm.png` | State after confirming available-items-only reorder |
| `46_coupon_applied.png` | Early coupon-input attempt (not used as final evidence) |
| `51_builder_default_scale.png` | Builder at default scale for touch-target audit |
| `55_talkback_builder.png` | TalkBack-enabled builder |
| `57_builder_font_1_5.png`, `58_builder_font_1_5_scrolled.png` | 1.5x font scale |
| `60_menu_font_2_0.png`, `61_builder_font_2_0.png` | 2.0x font scale |
| `64_menu_large_display.png`, `65_builder_large_display.png` | Large display simulation (density 320) |
| `69_address_ime_visible.png` | Address form with IME visible |
| `75_cart_ime_visible.png` | Cart coupon field with IME visible; dock absent |
| `78_coupon_picker.png` | Coupon sheet with grouped eligible coupons |
| `79_coupon_applied.png`, `80_coupon_removed.png` | Coupon applied and removed |
| `88_clear_cart_confirmation.png` | Clear-cart confirmation dialog |
| `96_quantity_25_final.png`, `105_quantity_25.png` | Quantity 25 states |
| `107_after_remove_undo_snackbar.png`, `108_after_remove_snackbar.png` | Remove and undo snackbar |
| `111_duplicate_merge.png` | Duplicate lines (FAIL evidence) |
| `115_after_cart_edit.png` | Edited cart item with updated price |

Primary XML companions use the same basenames, e.g. `08_builder_modifiers_server_price.xml`, `39_reorder_dialog_available_unavailable.xml`, `79_coupon_applied.xml`, `105_quantity_25.xml`.

## 4. Exact commands and exit statuses (key subset)

The complete ADB command/exit log is `command_log.txt`; QA step output is in `94_quantity_cap_steps.txt`. Representative exact commands:

```bash
$ adb devices
List of devices attached
emulator-5554	device
rc=0

$ curl -sS -o /dev/null -w 'menu_http_code=%{http_code} size=%{size_download} time=%{time_total}\n' http://127.0.0.1:8080/api/v1/catalog/menu
menu_http_code=200 size=8252 time=1.513046
rc=0

$ python3 p8_runtime_helper.py shot 08_builder_modifiers_server_price.png
wrote 08_builder_modifiers_server_price.png
rc=0

$ python3 p8_runtime_helper.py dump 08_builder_modifiers_server_price.xml
wrote 08_builder_modifiers_server_price.xml
rc=0

$ curl -sS -X POST http://127.0.0.1:8080/api/v1/pricing/quote \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data-binary @/tmp/opencode/p8_quote_request.json \
    -o 07_server_quote_response.json -w 'http_code=%{http_code}\n'
http_code=200
rc=0

$ curl -sS -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/cart \
    -o 13_server_cart_after_second_add.json -w 'http_code=%{http_code}\n'
http_code=200
rc=0

$ curl -sS -X POST "http://127.0.0.1:8080/api/v1/orders/$ORDER/reorder" \
    -H "Authorization: Bearer $TOKEN" -o 33_reorder_api_precheck.json -w 'http_code=%{http_code}\n'
http_code=200
rc=0

$ python3 p8_runtime_helper.py tapxy 572 1798        # Reorder
rc=0
$ python3 p8_runtime_helper.py shot 39_reorder_dialog_available_unavailable.png
rc=0
$ python3 p8_runtime_helper.py dump 39_reorder_dialog_available_unavailable.xml
rc=0

$ adb shell settings put secure enabled_accessibility_services \
    com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
rc=0
$ adb shell settings put secure accessibility_enabled 1
rc=0
$ adb shell settings get secure accessibility_enabled
1
rc=0

$ adb shell settings put system font_scale 1.5
rc=0
$ adb shell settings put system font_scale 2.0
rc=0
$ adb shell settings put system font_scale 1.0
rc=0

$ adb shell wm density 320
rc=0
$ adb shell wm density 420
rc=0

$ adb shell dumpsys input_method | grep -iE 'mInputShown|mImeWindowVis'
      mImeWindowVis=3
      mInputShown=true
rc=0

$ adb logcat -d -t 3000 | grep -iE 'pizzora|aistudio|CartRepo|CartViewModel|reorder|pricing|quote|Ktor|HttpClient|AndroidRuntime|FATAL|E/' > 124_logcat_filtered.txt
rc=0
```

Final restoration:

```bash
$ adb unroot
restarting adbd as non root
rc=0
$ adb shell id
uid=2000(shell) ... context=u:r:shell:s0
rc=0
$ adb shell settings get secure accessibility_enabled
0
rc=0
$ adb shell settings get secure enabled_accessibility_services
null
rc=0
$ adb shell settings get system font_scale
1.0
rc=0
$ adb shell wm density
Physical density: 420
rc=0
```

## 5. Relevant logcat

`124_logcat_filtered.txt` contains 57 filtered lines. No `FATAL`, no `AndroidRuntime: E`, and no `E/*` application crash lines were found. Representative lines:

```text
11-27 14:24:24.493   664  1330 D CoreBackPreview: Window{a5df15e u0 com.aistudio.pizzora.xkqzmw/com.example.MainActivity}: Setting back callback ...
11-27 14:45:40.185 12610 12616 I .pizzora.xkqzmw: Compiler allocated 8370KB to compile void com.example.catalog.PizzaBuilderScreenKt.PizzaBuilderScreen(...)
11-27 14:45:40.200 12610 12616 I .pizzora.xkqzmw: Compiler allocated 8280KB to compile void com.example.catalog.PizzaBuilderScreenKt.BuilderCustomizationSections(...)
```

Search used:

```bash
grep -iE 'FATAL|AndroidRuntime: E|E/[A-Za-z]+:' 124_logcat_filtered.txt
# no output, rc=1 (no fatal/error matches)
```

## 6. Blockers and caveats

1. **Intermittent app input-channel loss.** `adb shell input tap` returned 0 but some taps did not reach the app, while taps in Settings worked. Workarounds used: Settings→app refresh (`p8_runtime_helper.py wake`) and `adb shell am force-stop` + relaunch. Several test steps were repeated after relaunch. This is an environment/app focus issue, not a server failure.
2. **No GPU device acceleration.** `/dev/dri` is absent; the emulator ran with `-gpu swiftshader`.
3. **Standalone `emulator -version` could not load `libpulse.so.0`.** The actual running emulator process and crashpad annotation prove version 37.3.10; emulator execution was real.
4. **No guest HTTP client.** `curl`/`wget` are absent on the emulator, so direct `10.0.2.2` CLI reachability is NOT TESTED. App-level reachability is proven by live server quote/cart responses.
5. **UIAutomator focus limitation.** Compose focus restoration could not be asserted; no node reported `focused="true"`. Robolectric `assertIsFocused` assertions in `Phase4CartAndReEditRobolectricTest.kt:699,757,1375` cover the implementation directly.
6. **Follow-up triage closed all observed FAIL rows:** duplicate cart lines were an invalid probe with two different modifier configurations; the 126px default-density targets are exactly 48dp at 420dpi; the IME-hidden cart dock is specified, pre-existing behavior. Details are in section 7.
7. QA catalog mutations were restored: Extra Cheese topping `is_active=true` (`123_topping_restored_final.json`), REGULAR variant `is_active=true` (`122_variant_restored.json`). Accessibility settings, font scale, display density, and `adbd` root state were restored (`128_final_state_restoration.txt`). No commit or push was performed.

## 7. Follow-up triage (coordinator, 2026-11-27)

- **Duplicate merging — invalid probe, not a defect.** `112_cart_duplicate_api.json` proves the qty‑24 line was seeded by direct `PUT` with no `standard_topping_ids`, while the qty‑1 builder line included default Basil. The reviewed contract requires merge keys to include modifiers (`docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md:420`), so two different modifier sets must remain separate lines. Corrected same-config probe: `129_merge_same_modifier_config_request.json` → `129_merge_same_modifier_config.json`, which shows **one line, qty 25, Basil preserved**. The corrected probe intentionally updated the local QA cart to that final state.
- **Published-evidence redaction.** Captured local access/refresh tokens in `20_owner_login.json`, `86_customer_relogin.json`, and `119_owner_relogin.json`, the typed password in `command_log.txt`, and the seeded password in `local_qa_seed.log` were replaced with `[redacted]` before Checkpoint H. The seed script now requires `PIZZORA_QA_OWNER_PASSWORD` / `PIZZORA_QA_CUSTOMER_PASSWORD` from the environment instead of embedding local passwords.
- **Default-density touch targets — PASS, analysis corrected.** At density 420, `48dp = 48 × 420/160 = 126px`. The flagged nodes are exactly 126px (`FilterChip`/`IconButton`/clickable `Surface` with `minimumInteractiveComponentSize()`) or 131px (`Button(heightIn(min = 50.dp))`) in `PizzaBuilderScreen.kt`. The earlier comparison against a fixed 140px threshold was incorrect; density 320 passes at its correct 96px threshold.
- **IME-aware cart dock — expected behavior.** `CartScreen.kt:170,324-325,430-431` intentionally hides the dock while IME is visible and restores it afterward; this is required by `public/implementation_plan.md:411,539-541,625,823,829-830` and covered by `Phase4CartAndReEditRobolectricTest.kt:990-1043`. It is not a Phase 8 defect.
- **Focus restoration — unit-test covered.** UIAutomator cannot expose Compose focus state here, but `Phase4CartAndReEditRobolectricTest.kt:699,757,1375` assert focus restoration directly. No source change is required.
- **APK provenance.** `apk_build.log` and `apk_install.log` record the debug APK built from this working tree and installed as `com.aistudio.pizzora.xkqzmw` on `emulator-5554`. No Android/Kotlin source changed after that build; subsequent Checkpoint H changes were Rust, documentation, and QA evidence only.
