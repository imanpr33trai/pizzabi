# PIZZORA Phase 8 — W9 FINAL independent re-review

Reviewer: `reviewer` subagent (`opencode/big-pickle`), read-only.
Date: 2026-11-27. Binding contract: `docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md` (authoritative). Prior FAIL: `qa-artifacts/phase8/review/W9_INITIAL_INDEPENDENT_REVIEW.md`.
Scope: committed `db41169..bb63746` (Checkpoints A-G, 7 commits, 80 task files) **plus complete uncommitted Checkpoint H working tree**. Verdict covers the whole implemented tree, not just commits.

## VERDICT: PASS

No BLOCKER or MAJOR remains in code or evidence. Both prior BLOCKERs are fixed in source **and** covered by passing tests/runtime evidence; both code/evidence MAJORs are resolved. Android runtime evidence is **OPEN/PENDING** (separate QA agent still working) and is explicitly **not** counted as a code defect. Phase 8 Exit Gate recommendation: **OPEN (conditional PASS)** — all code/exit criteria are satisfied; final sign-off requires the runtime QA artifact described in section 5. No Git publication should occur until that open evidence is attached (advisory, see section 7).

---

## 1. Prior BLOCKERs — verified fixed

**BLOCKER-1 (checkout modifiers) — FIXED, source + test + runtime.**
- `rust/crates/pizzora-api/src/services/checkout.rs:65` calls `ensure_modifier_features_enabled`; `:128` loads `load_extended_catalog_ref_lookup` (was base `load_catalog_ref_lookup`); `:129` `ensure_catalog_fk_references`; `:130` `ensure_half_and_half_config_matches`; `:131` `evaluate_cart`.
- Helpers verified: `cart.rs:45-77` (all four `PIZZORA_FEATURE_*` gates via `Phase8FeatureFlags::from_env`), `:204-353` (extended lookup loads cheeses/sauces/standard toppings/half-and-half/product_toppings), `:492-527` (exact half-config match incl. product ownership, active, left/right IDs).
- Persistence verified: `checkout.rs:465-611` inserts every modifier ID — variant/crust/cheese/sauce/half-config, topping_ids, standard selected/removed arrays, half left/right arrays, scalar-string `modifiers_snapshot`.
- Test `pg18_checkout_accepts_and_persists_phase8_modifiers` (`rust/crates/pizzora-api/tests/pg18_phase8_reorder.rs:456-618`) asserts round-trip of all IDs and exact price 47,400 (half average of 40,000/38,000 + 4,000 surcharge + 2,000 cheese + 1,500 sauce + 900 standard). Evidence: `qa-artifacts/phase8/regression/checkout_modifier_regression.log` (5/5 live-PG tests PASS). Local API smoke: `qa-artifacts/phase8/regression/local_api_smoke_final.log` (menu arrays 4/4/4/2, quote breakdown, cart resolution, checkout, reorder keys — all PASS, exit 0).

**BLOCKER-2 (Android builder + cart/nav/API wiring) — FIXED, source + tests.**
- Builder: `app/src/main/java/com/example/catalog/PizzaBuilderScreen.kt` — modifier state `:189-204`, server-quote abstraction/impl `:222-240`, additive breakdown fields `:242-265`, edit hydration from `CartItemViewDto` `:301-345`, half-and-half validation (<=10/half, <=15 distinct, no overlap) `:347-361`, exact DTO emission `:374-398`, quote-driven price `:418-505`, quote effect/gating `:672-761`, canSubmit requires `priceReady` `:892-900`, submit forwards `clientUnitPricePaise` from quote `:845-890`. UI sections for all four modifier groups `:1704-1755`; unsupported legacy slots render nothing (`isSupported=false`, `:2272-2286`) so no duplicate UI.
- Zero-credit defaults: `:298-299` defaults selected; `:454-457` removed default shown as removed; server price used (`:461-467`). Confirmed by Robolectric test `keptDefaultIsIncludedAndRemovedDefaultIsZeroCredit` (`Phase8PizzaBuilderRobolectricTest.kt:185-213`: price stays Rs 100.00 on default removal, "No charge" label).
- Cart/nav/API wiring: `CartViewModel.kt:86-102` (view-to-input preserves every ID), `:228-330` add/edit via authenticated `replaceCart`, `:344-355` read-only server cart for edit hydration, `:363-407` reorder append preserving IDs; `usesAuthenticatedModifierApi` `:119`. `AppContainer.kt:119-123` publishes `Phase8ApiLocator`, `:280-290` supplies `reorderApiClientProvider`. `PizzoraNavHost.kt:483-487` refreshes server cart on edit open; `:871`/`:884` remount with `resolvedEditItem`; `:728-734,:919-933,:983-989` forward `onConfirmAddWithModifiers`. `CustomerMenuScreen.kt:109-113,454-457`, `PizzaCustomizerSheet.kt:24,40` thread the callback without breaking legacy callers.
- Server quote + failure + edit hydration tests: `Phase8PizzaBuilderRobolectricTest.kt:216-249,250-273,430-513`; wiring tests `Phase8BuilderCartViewModelRobolectricTest.kt:280-483` and `Phase8BuilderNavWiringRobolectricTest.kt:100-170`.
- Full app suite: `qa-artifacts/phase8/visual/builder/w6_modifier_wiring.log` final clean run at 2026-11-27T14:01:18Z — **230 tests, 0 failures/errors/skipped, BUILD SUCCESSFUL, exit 0**, with per-class XML counts (incl. 9 builder, 6 cart-VM, 1 nav-wiring, 6 reorder, 14 Phase3 builder, 19 Phase4 cart). Earlier intermediate failures in that log (10 then 1) are explicitly superseded by the final run.

## 2. Prior MAJORs — verified resolved / reclassified

- **MAJOR-2 (13/33 Kotlin vectors, no digest) — RESOLVED.** `backend/src/test/kotlin/com/pizzora/backend/Phase8GoldenVectorFullParityTest.kt:29-119` executes all 33 vectors through production `PricingService` on embedded PostgreSQL; `:41-42,66-92` pins SHA-256 and writes digest file. Evidence `qa-artifacts/phase8/parity/kotlin_33_vector_parity.log`: `VERIFIED_VECTOR_COUNT=33`, all U-01..U-28 + Q-01..Q-05 observed == expected, fixture digest `a1e6f596d14c9a74ea820d20fbe7a349bbbcfab29bb02cf7464598ea9fc61623`; independently re-hashed the fixture -> identical. Kotlin backend + shared contracts final: `qa-artifacts/phase8/backend/w8_final_kotlin_backend.log` — 86 + 40 tests, 0 failures, exit 0 (2026-11-27T14:18Z).
- **MAJOR-3 (no real CLI logs) — RESOLVED.** `qa-artifacts/phase8/migrations/PHASE8_MIGRATION_CLI_EVIDENCE.md` + four logs: fresh `--migrate-only` (`mode=FreshBootstrap`, versions 1-11, exit 0), fresh `--preflight-only` (`catalog_invariants_ok: true`, exit 0), Flyway adoption `--preflight-only` (`flyway_verified: true`, V1-V3, exit 0), adoption `--migrate-only` (`mode=AdoptedExistingFlyway`, 1-11, exit 0). Empty-DB guard (exit 1, `UNSAFE_DATABASE_STATE`) is intentional per `migrator.rs:414-419`, not a failure. Verified log contents directly.
- **MAJOR-1 (no Android runtime evidence) — RECLASSIFIED as OPEN evidence** per the review mandate; not a code defect. Partial artifacts now exist in `qa-artifacts/phase8/visual/runtime/` (01_login/03_menu/04_builder/08_builder_modifiers_server_price screenshots + XML, 07_server_quote_request/response.json, apk_build/install.log, command_log.txt); see section 5 for what remains.

## 3. Eleven acceptance criteria — status against artifacts

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | SQLx V4+ on fresh DB | PASS | CLI fresh migrate/preflight exit 0; `FRESH_SCHEMA_REPORT.md` 51 tables; `pg18_integration_tests.log` 3/3 |
| 2 | Flyway V1-V3 adoption | PASS | `p8cli_adopt_*.log` both flags exit 0; `pg18_locked_handoff_fresh_adopt_tamper_and_concurrency ... ok` in `w8_live_postgres_tests_final.log:84` |
| 3 | 51-table inventory + invariants both profiles | PASS | `migrator.rs:21-77` EXPECTED_TABLES includes 5 new; `:918` asserts len 51; CLI verify logs 51 on both profiles |
| 4 | Rollback/reapply | PASS | `pg18_phase8_rollback_and_reapply_succeeds ... ok` (`w8_live_postgres_tests_final.log:85`; `pg18_integration_tests.log`) |
| 5 | 33 vectors Rust + Kotlin + fixture hash | PASS | `parity/w2_rust_pricing.log` 11/11 incl. `phase8_golden_vectors_all_pass`; Kotlin log section 2; digest re-hash matched |
| 6 | Pricing rules (zero credit, extras, HALF_UP, overflow) | PASS | `pricing.rs:246-265` (defaults/removed = 0), `:271-402` (checked, i128 HALF_UP, fixed surcharge); `regression/checked_breakdown_arithmetic.log` 11/11 + 8/8 |
| 7 | Cart validation rejects unavailable/foreign/over-limit/overlap | PASS | `phase8_golden_parity_tests` validation tests 11/11 (`checked_breakdown_arithmetic.log`); `validation_rejects_inactive_and_foreign_modifiers ... ok`; API 5/5 live |
| 8 | DTO/API backward compatibility | PASS | `cart_item_input_deserializes_legacy_payload_without_modifiers ... ok`; additive defaults in `CartDto.kt:65-77`, `OrderDto.kt:31-49,110`, `CatalogDto.kt:181`; `Phase8SerializationTest` in 40 shared-contract tests; historical reorder test (5/5) |
| 9 | Reorder ownership/IDs/reconciliation | PASS | `pg18_phase8_reorder.rs` 5/5 live; `orders.rs:301-311` IDOR check, `:542-576` preserves IDs, `can_reorder=any available`, `:561-564` no substitution; `can_reorder=false` when all unavailable (`:376` test assertion) |
| 10 | Android builder/reorder UI, accessible, server pricing | PARTIAL -> OPEN | Unit/Robolectric PASS (230 tests); emulator captures/UIAutomator/XML accessibility evidence still being produced by separate QA agent (section 5) |
| 11 | Phase 3/4/6/7 regression + review no BLOCKER/MAJOR | PASS (code) | 230 app tests include Phase3 (14), Phase4 cart (19), Phase6 (22), Phase7 (21); Rust full workspace `w8_rust_workspace_tests_final.log` exit 0; backend 86 + shared 40 exit 0; this report is the reviewer verdict |

## 4. Contract invariants — independently checked

- **Checked arithmetic:** authoritative path `pricing.rs:308-402` all `checked_add`/`try_from` -> `DomainError::ArithmeticOverflow`; Checkpoint H closed display paths too — `pricing.rs:424-476` (breakdown) and `cart_validation.rs:855-905` (view) replaced `saturating_add`/`unwrap_or(i64::MAX)` with checked ops. Evidence `checked_breakdown_arithmetic.log`. No remaining saturating/clamping path in Phase 8 math.
- **can_reorder:** `any(|item| item.is_available)` (`orders.rs:566`), true if >=1 available, false if none (`pg18_phase8_reorder.rs:312,376`). Contract-consistent.
- **No auto-substitution / no cart mutation on reorder:** `orders.rs:561-564` comment + code; only SELECTs then `tx.commit()` (`:292-568`); test asserts unavailable topping not replaced (`:344,364`) and cart unchanged (`:216`).
- **51 tables:** `EXPECTED_TABLES` = 51 (`migrator.rs:21-77,918`); both CLI profiles verified at 51 application tables.
- **Additive DTO defaults:** all new fields `= null` / `= emptyList()` / `#[serde(default)]` (shared DTOs section 3 #8; Rust `dto.rs:597-644`).
- **Half-and-half fixed surcharge:** `pricing.rs:387` adds `hah.surcharge_paise` constant; no percentage path. ROUND_HALF_UP via `(l+r+1)/2` in i128 (`:371-379`).
- **Standard toppings in base price:** defaults contribute 0 whether kept or removed (`pricing.rs:246-265`); only selected non-defaults add (`evaluate_cart`); UI test confirms Rs 100 flat on default removal.
- **snake_case compatibility / camelCase scope:** all legacy DTOs use `@SerialName("snake_case")` with additive defaults (`CartDto.kt:65-77`, `OrderDto.kt:31-49,110`); camelCase is confined to reorder DTOs (`OrderDto.kt:193-225`; Rust `dto.rs:591-645`). Smoke test reorder top-level keys are camelCase only. Confirmed no Phase 6/7 wire-shape change.
- **Feature flags:** `Phase8FeatureFlags::from_env()` gates catalog arrays (`CatalogService.kt:688-689`) and writes (`cart.rs:45-77`); tests/local default enabled.

## 5. OPEN / PENDING — Android runtime evidence (not a code defect)

Partial artifacts exist in `qa-artifacts/phase8/visual/runtime/` (login/menu/builder/all-modifiers/server-price screenshots + XML dumps, quote request/response JSON). The separate android-qa agent is still producing final evidence. For final sign-off the reviewer needs a signed runtime report (`ANDROID_QA_READINESS.md` update or equivalent) demonstrating:
1. Screenshots + UIAutomator XML showing all four modifier sections (cheese, sauce, standard toppings, half-and-half) with a selection on a seeded product, and the server-quoted price.
2. Add-to-cart -> cart screen showing the exact saved modifiers; edit-open hydration restoring the same selections; reorder reconciliation dialog with "Add available items only"/"Cancel".
3. Accessibility evidence: TalkBack labels, >=1.3/1.4 font scale, >=48dp touch targets across the new builder controls (per contract AC-10).
4. APK build/install success and emulator id already logged — confirm final debug APK matches current working tree (post-Checkpoint-H).

Until (1)-(4) are attached, Criterion 10 stays OPEN; this does **not** block the code verdict.

## 6. New findings

**MINOR-1 — checkout idempotency hash omits Phase 8 modifier IDs.** `rust/crates/pizzora-api/src/services/checkout.rs:795-810` builds each item part from `product_id:variant_id:crust_id:topping_ids:quantity` only; `compute_request_hash` (`:782-832`) therefore does not include `cheese_id`, `sauce_id`, `standard_topping_ids`, `half_and_half_config_id`, or half left/right IDs. Reusing one `Idempotency-Key` with a different modifier set yields the same hash -> the first order is returned as `idempotent_replay: true` instead of `409 IDEMPOTENCY_CONFLICT`. No wrong order is created; impact is a stale response / missed conflict detection. Not covered by any acceptance criterion. **Minimal remediation:** append sorted `cheese_id`, `sauce_id`, `standard_topping_ids`, `half_and_half_config_id`, and sorted half left/right IDs to each `item_parts` entry (Rust change in one function + one idempotency-conflict test). Can be deferred; not a BLOCKER/MAJOR.

**Advisories (no action required for verdict):**
- `docs/PHASE8_IMPLEMENTATION_PROGRESS_REPORT.md` is stale: section 9 still says "all 11 criteria remain open", evidence table says "final captures pending" and "84 backend" (actual 86), W9 row says "IN PROGRESS". Documentation debt only; all evidence exists.
- `/workspace/pizzabi/.opencode/opencode.jsonc` is modified (model `nvidia/...` -> `opencode/big-pickle`). It is **not** task-related; the progress report (`:167`) already warns not to stage it.
- `Phase18SecurityRobolectricTest.kt:116-120,240-247` uses reflection to reset a DataStore singleton to fix an order-dependent Robolectric failure — test-only, green, but fragile.
- Carried-forward advisories from initial review: "Unknown" names in unavailable reconciliation entries; Gradle ktlint auto-format warning for generated UniFFI bindings (direct bindgen succeeded).
- Kotlin backend surfaces U-28 overflow as `ArithmeticException` (observed in parity log) rather than a typed domain code — Kotlin equivalent of checked `ArithmeticOverflow`; test passes.

## 7. Phase 3 / Phase 5 / Git publication

- **Phase 3 manual QA tracked separately:** historical Phase 3 manual QA lives in its own docs (`docs/PHASE4_EMULATOR_QA_HANDOFF.md`, `docs/PHASE7_READINESS_ASSESSMENT.md`); Phase 8 does not fold it in. Phase 3 *automated* regression is preserved and green (14 builder + 19 cart tests in the 230-suite; XML under `qa-artifacts/phase8/unit-test-xml/app/`). No conflation found.
- **Phase 5 kept out of scope:** no Phase 5 production files appear in the Checkpoint H diff; Phase 5 tests remain green only as regression. Phase 8 evidence is isolated under `qa-artifacts/phase8/`.
- **Git publication safety:** committed range `db41169..bb63746` contains 80 task files, no `.opencode/`, no secrets/credentials (verified `git diff --name-only`). All Checkpoint H work is currently **uncommitted**. Publication is safe **if** it is limited to the listed task files and evidence and **excludes** `.opencode/opencode.jsonc`. Nothing found indicating an unsafe commit/push has occurred. Advisory: do not publish the branch as "Phase 8 complete" until the section 5 runtime evidence is attached.

## 8. Exit Gate recommendation

**Conditional PASS / OPEN pending runtime evidence.** Code, migration, pricing, API, backend, and unit-test exit criteria are fully satisfied with genuine, current, re-executed logs (final runs: Android 230/230 at 14:01Z, Kotlin backend 86+40 at 14:18Z, parity digest re-verified, CLI 4/4 exit 0, live-PG 5/5 + workspace exit 0, local smoke exit 0). Close the gate when the android-qa agent attaches the four runtime artifacts in section 5; then publish only task-related files, excluding `.opencode/opencode.jsonc`. MINOR-1 may be fixed in the same change set or tracked as follow-up.

---

## 9. Addendum — MINOR-1 (idempotency hash) closed — follow-up re-review (2026-11-27)

**Verdict: PASS (unchanged). No new BLOCKER or MAJOR introduced.**

MINOR-1 from section 6 is verified fixed in the working tree:

- **Source** (`rust/crates/pizzora-api/src/services/checkout.rs:795-846`): `compute_request_hash` now uses a `sorted_ids` helper for `topping_ids`, `standard_topping_ids`, `half_and_half_left_topping_ids`, and `half_and_half_right_topping_ids`, and an `optional_id` helper (stable `none` marker) for `cheese_id`, `sauce_id`, and `half_and_half_config_id`. All seven Phase 8 modifier fields are now in the canonical hash string; the diff vs `HEAD` is confined to this function. The call site (`:76`) passes `merged_items` (the parsed/merged items that retain every modifier ID).
- **Test** (`rust/crates/pizzora-api/tests/pg18_phase8_reorder.rs`, `pg18_checkout_accepts_and_persists_phase8_modifiers`): seeds a second active sauce linked to the product, performs a first checkout with `Idempotency-Key=checkout-modifiers-{tag}`, then reuses the same key with only `sauce_id` changed and asserts HTTP 409 with body containing `IDEMPOTENCY_CONFLICT`. The first checkout helper (`checkout_cod_pickup`, `:69-99`) uses identical fields (Pickup/Scheduled/same slot/COD/no coupon/`clear_cart=false`), so `sauce_id` is the sole differing input — the 409 is genuine conflict detection, not a validation artifact.

**Evidence verified (genuine, current, re-executed 14:29-14:30Z):**
- `qa-artifacts/phase8/regression/idempotency_hash_unit.log` — API lib `7/7` tests pass.
- `qa-artifacts/phase8/regression/checkout_modifier_regression_final.log` — live-PG `5/5` pass incl. the new conflict assertion; `exit: 0`.
- `qa-artifacts/phase8/regression/w8_rust_workspace_tests_checkpoint_h.log` — full workspace, all suites green, `exit: 0`.
- `qa-artifacts/phase8/regression/w8_live_postgres_tests_checkpoint_h.log` — fresh DB, all previously-ignored integration tests actually executed and passed (checkout/reorder 5/5, http contracts 3/3, ws 3/3, migrator 3/3, workers 4/4, retry/idempotency 1/1, capture/reconciliation 1/1, auth 1/1); `exit: 0`.
- `cargo fmt --all --manifest-path rust/Cargo.toml -- --check` re-run independently by this reviewer: **exit 0** (clean).

No source/docs/Git state were modified by this reviewer; only this addendum was appended. The section 5 runtime-evidence item remains OPEN/PENDING with the separate android-qa agent and remains non-blocking for the code verdict. Exit Gate recommendation is unchanged: **Conditional PASS / OPEN pending runtime evidence**; publish only task-related files, excluding `.opencode/opencode.jsonc`.

---

## 10. Addendum — runtime gate CLOSED — final exit-gate verdict (2026-11-27)

**FINAL VERDICT: PASS — unconditional. Phase 8 Exit Gate: CLOSED. All 11 acceptance criteria satisfied. No BLOCKER or MAJOR open.**

The conditional item from sections 5/8 is closed by `qa-artifacts/phase8/visual/runtime/ANDROID_RUNTIME_QA.md` (real `emulator-5554`, ADB, all PNGs are `adb exec-out screencap -p`). Independently verified:

1. **Builder/cart/reorder flows PASS, server-backed.** `08_builder_modifiers_server_price.png/.xml` contains all four sections (Cheese, Sauce, Standard toppings, Half and half) plus `Add to Cart · ₹486.00`; `07_server_quote_request.json` → `07_server_quote_response.json` returns `unit_price_paise=48600` — exact match. Auth add `14_cart_after_authenticated_add.png/.xml` + `13_server_cart_after_second_add.json` (Mozzarella/Pesto IDs, 2 standard toppings, half-half, 48600). Edit `113`/`114`/`115`/`116` preserves Cheese Burst + Mozzarella at 36800. Reorder `39_reorder_dialog_available_unavailable.png/.xml` contains `Available to add`, `Unavailable modifiers`, `Margherita Royale: Extra Cheese`, `Add available items only`, `Cancel`; `33_reorder_api_precheck.json` = one available + one unavailable; `41_cart_after_reorder.json` adds the plain line only, with no toppings/cheese/sauce/half-half substituted. Quantity cap 25, remove/undo, clear-cart, coupons, navigation, persistence across force-stop also PASS.
2. **Duplicate-merge probe corrected.** Original `112_cart_duplicate_api.json` = two *different* configs (qty 24 line has empty `standard_toppings`, qty 1 line has Basil) — correctly NOT merged per contract (`docs/PHASE8_REVIEWED_CONTRACT_RESOLUTIONS.md:420`). Corrected `129_merge_same_modifier_config_request.json` submits two identical Basil-bearing lines (qty 20 + 10) and `129_merge_same_modifier_config.json` confirms **one line, qty 25, Basil preserved**. Not a defect.
3. **Touch targets PASS.** Parsed XML: `51_builder_default_scale.xml` at density 420 has 12 clickable nodes, **0** with either dimension < 126px (= 48dp × 420/160); minimum 126×126px, Add to Cart 131px. `65_builder_large_display.xml` at density 320 has 21 clickable nodes, **0** below 96px. `PizzaBuilderScreen.kt` uses `minimumInteractiveComponentSize()` (`:1303,1794,1827,1851,1858,1888,1967,1990,2037`). Earlier 140px threshold was an analysis error.
4. **IME dock is specified pre-existing behavior, not a Phase 8 defect.** `75_cart_ime_visible.xml` has 0 `Proceed to Checkout` nodes; `76_cart_after_ime_hidden.xml` has 1. Specified in `public/implementation_plan.md:411,539-541,625,823,829-830`; implemented by `CartScreen.kt:170,324-325,430-431` (`AnimatedVisibility(visible = !isImeVisible)`); Robolectric-covered at `Phase4CartAndReEditRobolectricTest.kt:990-1043`.
5. **Focus restoration covered by unit tests.** UIAutomator exposed no `focused="true"` node (Compose limitation); `Phase4CartAndReEditRobolectricTest.kt:699,757,1375` assert `assertIsFocused` directly. No source fix warranted.
6. **Redaction verified.** `20_owner_login.json`, `86_customer_relogin.json`, `119_owner_relogin.json` show `access_token`/`refresh_token` = `[redacted]`; `command_log.txt:352` typed password `[redacted]`; `qa-artifacts/phase8/visual/local_qa_seed.log:15` `[redacted]`. No password/Bearer-token patterns remain in runtime evidence.
7. **APK provenance verified.** `apk_build.log` = `BUILD SUCCESSFUL`/`exit: 0`, `apk_install.log` = `Success`. No `app/src`/`backend/src`/`shared/contracts/src` Kotlin/XML file is newer than `apk_build.log`; subsequent Checkpoint H changes were Rust/docs/QA only.
8. **Environment/caveats non-blocking.** TalkBack, 1.5x/2.0x font scale, density 320, final settings/root restoration, logcat with no FATAL/errors, QA catalog mutations restored (`122`/`123`). Remaining caveats (swiftshader/no `/dev/dri`, absent guest curl, intermittent input-channel loss, standalone `emulator -version` libpulse error) are environmental, with app-level reachability proven by live quote/cart/reorder responses.

Criterion 10 is upgraded PARTIAL/OPEN -> **PASS**; criterion 11 is now fully **PASS**. Sections 5/8 and the MINOR-1 item in section 6 are all closed. Publication is now safe for task-related files only — continue to exclude `.opencode/opencode.jsonc`. No source, docs, QA evidence, or Git state were modified by this reviewer; only this addendum was appended.
