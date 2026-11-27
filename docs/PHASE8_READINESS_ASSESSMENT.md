# PIZZORA Phase 8 — Readiness Assessment

**Date:** 2026-11-25\
**Branch:** `ui-ux-changes`\
**Assessed HEAD:** `db41169bf6d0852feae3dbc4ada93e0b50f85c53`\
**Assessment type:** Read-only repository and evidence audit. No Phase 8 implementation, plan edit, commit, push, merge, deployment, or infrastructure change was made.

---

## 1. Executive summary

This assessment treats `public/implementation_plan.md` as the sole source of Phase 8 scope and the accepted Phase 7 closure evidence as the baseline. The canonical Phase 8 work is bounded to **Extended Pizza Modifiers & Reorder** — activating the four dormant `CustomizationSectionSlot`s (Cheese, Sauces, Standard Toppings, Half-and-Half) in `PizzaBuilderScreen.kt`, adding item previews to `OrderSummaryCard`, and implementing a 1-tap Reorder flow from past orders.

**Critical finding:** Phase 8 is classified as **Optional Post-UI Backend Collaboration** (`public/implementation_plan.md:L903`). The plan explicitly states it depends on "backend contract sign-off" (`L920`) and requires backend/Rust/contract extensions before any Android work can proceed. The four modifier slots already exist in the Android UI architecture as `UnsupportedModifierSlot` (isSupported = false) and are gated behind server capability flags / optional nullable DTO fields (`L922`).

The repository baseline is clean, synchronized with `origin/ui-ux-changes`, and contains no source changes after the final Phase 7 commit. Phase 6 and Phase 7 are both closed with all exit gates satisfied (191/191 and 207/207 tests green respectively).

**User decisions approved (2026-11-25):**
- **D8-01:** Backend Phase 8 scope **CONDITIONALLY APPROVED** — backend work authorized for canonical scope only
- **D8-02:** Reorder strategy **OPTION A APPROVED** — extend `OrderItemDto` with stable modifier IDs
- **D8-03:** SQLx migration ownership **CONDITIONALLY APPROVED** — SQLx owns V4+; Kotlin backend compatibility audit required first
- **D8-04:** Plan-history cleanup **DEFERRED**
- **D8-05:** Visual QA **APPROVED** — use existing CI emulator workflow

**Backend contract produced:** `docs/PHASE8_BACKEND_CONTRACT.md` — defines modifier domain model, SQLx V4+ migration sequence, DTO/API extensions, pricing rules, UniFFI exports, golden parity fixtures, rollback strategy, and 6 unresolved decisions (U8-01 through U8-06).

**Independent review required:** `backend`, `test-engineer`, and `reviewer` agents must review the contract before implementation authorization.

**Readiness verdict:** **PHASE 8 SCOPE NEEDS CLARIFICATION** — Contract review pending. Backend contract sign-off (D8-01) is conditionally approved but requires independent review sign-off and resolution of 6 unresolved decisions (U8-01 through U8-06) before implementation can begin.

---

## 2. Verified repository baseline

| Item | Verified state |
|------|----------------|
| Current branch | `ui-ux-changes` |
| HEAD | `db41169bf6d0852feae3dbc4ada93e0b50f85c53` |
| Working tree | Clean; `git diff --quiet HEAD` exited `0` |
| Remote sync | HEAD equals `origin/ui-ux-changes`; `0/0` ahead/behind |
| Post-Phase-7 source changes | None after `db41169` |
| `git diff --check` | Exit `0`, empty output |
| Phase 6 baseline | `61914d962cbf5a2badf44d95a3e555e980ee73dd` (191 tests, 7/7 visual captures) |
| Phase 7 baseline | `dd6e7be3fc0cf86b901da2d2a742b68e9485d2c1` (207 tests, 13 visual captures) |
| Other worktrees | 3 external worktrees inspected and left untouched |

---

## 3. Phase 6 and Phase 7 closure status

### Phase 6 — Payment Sheet, Live Order Tracking & Order History Overhaul
- **Final commit:** `61914d962cbf5a2badf44d95a3e555e980ee73dd`
- **Exit Gate:** SATISFIED (per `public/implementation_plan.md:L886`)
- **Evidence:** 191/191 tests green across 34 XML files; focused payment 10/10, tracking 5/5, history 3/3; debug APK built; 7/7 canonical visual captures PASS; independent review: no BLOCKER/MAJOR findings, 2 MINOR closed; `P6-001` warning countdown fix verified live at `320dp/1.5x`
- **Scope locked:** `OrdersScreen.kt`, `RazorpayPaymentSheet.kt`, `OrdersViewModel.kt`, `RazorpayCheckoutLauncher.kt`, `OrderDisplayFormatters` — no Phase 8 overlap

### Phase 7 — Profile, Saved Addresses, Notifications Deep-Linking & App Updates Polish
- **Final commit:** `db41169bf6d0852feae3dbc4ada93e0b50f85c53`
- **Exit Gate:** SATISFIED (per `docs/PHASE7_IMPLEMENTATION_PROGRESS_REPORT.md:L154`)
- **Evidence:** 207 tests green across 36 XML files; debug APK built; 6 canonical + 7 conditional visual captures PASS; accessibility audit with measured touch targets (≥48dp), focus traversal, deep-link semantics, non-green notification hues, no clipped update actions at 1.5x/2.0x
- **Scope locked:** `CustomerProfileScreen.kt`, `NotificationsScreen.kt`, `UpdateScreens.kt`, `PizzoraNavHost.kt` — no Phase 8 overlap

**Both phases remain closed and are not to be reopened.**

---

## 4. Phase 8 scope extracted from the canonical plan

### 4.1 Canonical Phase 8 definition (`public/implementation_plan.md:L903-L922`)

> ### Phase 8 (Optional Post-UI Backend Collaboration): Extended Pizza Modifiers & Reorder
> - **Objective:** Enable Cheese, Sauces, Standard Toppings, Half-and-Half pizzas, Order Card item previews, and 1-tap Reorder once backend/Rust/contract extensions are approved.
> - **Screens Affected:** `PizzaBuilderScreen.kt` (activate existing `CustomizationSectionSlot`s), `OrdersScreen.kt`.
> - **UX Improvements:** Multi-category pizza customization, removable default toppings, half-and-half visual selector, 1-tap Reorder with unavailable-item reconciliation.
> - **Visual Improvements:** Section jump bar activates automatically when `>= 4` modifier groups are present; visual half-and-half split indicator.
> - **Components Affected:** `shared/contracts` DTOs, `rust/crates/pizzora-core`, `rust/crates/pizzora-db` (`migrations/` + `migrator.rs`), `rust/crates/pizzora-api`, `rust/crates/pizzora-workers`, `PizzaBuilderScreen.kt`, `OrdersViewModel.kt` *(plus legacy Kotlin `backend/` only if retained for parity tests)*.
> - **State & Navigation Changes:** Extend `PizzaBuilderUiState` with dynamic slot selections; add Reorder-to-Cart reconciliation dialog.
> - **Verified Database Migration Strategy:** Additive SQLx V4+ migrations (`0004_*.sql`+) only; Flyway V1–V3 immutable; no competing Flyway V4+ lineage.
> - **Accessibility Requirements:** Dual-pane half-and-half accessible labelling (`"Left half"`, `"Right half"`, `"Whole pizza"`).
> - **Test Requirements:** Migration preflight/handoff test (`migrator.rs`) + full Rust `pizzora-core`/`pizzora-api` + Android Robolectric integration suite for new modifier pricing & reorder edge cases.
> - **Acceptance Criteria:** `pizzora-api --preflight-only` and `--migrate-only` succeed cleanly on both fresh DBs and Flyway V1–V3 upgraded DBs; server, Rust, and Android client produce identical paise-exact totals across all new modifier combinations; Phase Exit Gate satisfied.
> - **Dependencies on Previous Phases:** Phases 3, 4 & 6 + **backend contract sign-off**.
> - **Regression Risks:** Pricing mismatch between client preview and server quote if Rust/Kotlin engines diverge (mitigated by shared golden pricing parity tests), or breaking `verify_flyway_history()` if a competing Flyway V4+ migration were accidentally added.
> - **Rollback Strategy:** Author all SQLx `0004_*.sql`+ DDL strictly as **additive/nullable** (expand-and-contract — no destructive column drops/renames in the initial rollout) and gate new modifier slots & Reorder CTA behind backend capability flags / optional nullable DTO fields.

### 4.2 Backend Dependencies Classification (Section 11 / `L738-L741`)

| Capability | Classification | Evidence / Backend Gap |
|------------|----------------|------------------------|
| Cheese options, Sauces, Standard vs. Extra Toppings (removable defaults), Half-and-Half pizzas | **3. Backend Dependency** | Verified absent in `V1__core_schema.sql`, `CatalogDto.kt`, `CartDto.kt`, `CartService.kt`, and `rust/crates/pizzora-core/src/cart_validation.rs`. |
| Item name previews on `OrderSummaryCard` (e.g. *"Margherita + 1 more"*) | **3. Backend Dependency** | `OrderSummaryDto` currently carries `itemCount: Int` only (no `itemPreviewSummary` or `items` list). |
| 1-Tap "Reorder" from Past Order | **3. Backend Dependency (Partial)** | `OrderItemDto` stores `productName`, `variantSize`, `crustName`, `toppings: List<String>` (display names, NOT `variantId`, `crustId`, `toppingIds`!). Matching by string name is fragile if renamed. |

### 4.3 Phase 8 scope boundaries — What is IN vs OUT

| IN SCOPE (Phase 8) | OUT OF SCOPE (Phase 8) |
|--------------------|------------------------|
| Activate 4 dormant `CustomizationSectionSlot`s in `PizzaBuilderScreen` | Menu "Bestseller / Chef's Pick" tags, Favorites/Wishlist |
| Add `itemPreviewSummary` to `OrderSummaryDto` / `OrderSummaryCard` | Order Ratings & Reviews |
| 1-tap Reorder with unavailable-item reconciliation dialog | Live Driver GPS Map |
| Additive SQLx `0004_*.sql`+ migrations for new modifier tables | Order-level Cooking Instructions |
| Extend Rust `pricing.rs` & `cart_validation.rs` for new modifiers | Any destructive DB column drops/renames |
| Extend shared contracts DTOs (Catalog, Cart, Order) | Flyway V4+ migration files |
| Update UniFFI exports for new domain functions | Backend parity for legacy Kotlin `backend/` (optional) |
| Golden pricing vector parity tests for new combinations | |

---

## 5. User-facing objectives

1. **Multi-category pizza customization:** Customers can select Cheese type, Sauce, Standard Toppings (with removable defaults), and configure Half-and-Half pizzas in the Builder.
2. **Order scanning efficiency:** `OrderSummaryCard` shows a preview like *"Margherita (Regular, Thin Crust) + 1 more"* instead of just `"2 items"`.
3. **Frictionless Reorder:** One tap from Past Orders rebuilds the cart with full customization; reconciliation dialog handles unavailable items gracefully.
4. **Visual clarity:** Section jump bar (when ≥4 modifier groups) and half-and-half split indicator guide customers through complex configurations.

---

## 6. Architecture readiness

### 6.1 Android application

| Component | Current State | Phase 8 Impact |
|-----------|---------------|----------------|
| `PizzaBuilderScreen.kt` | Full-screen/2-pane builder with `CustomizationSectionSlot` architecture (lines 143-164, 1394+) — 4 slots implemented as `UnsupportedModifierSlot(isSupported=false)` | Activate slots when `isSupported=true`; render cheese/sauce/standard toppings/half-and-half UI; add section jump bar at ≥4 groups; half-and-half visual selector |
| `OrdersScreen.kt` | Active/Past tabs, `OrderSummaryCard` shows `itemCount` only | Add `itemPreviewSummary` display; add Reorder CTA on Past orders; Reorder-to-Cart reconciliation dialog |
| `PizzaBuilderUiState` | Contains Size, Crust, Extra Toppings, Quantity, breakdown | Extend with dynamic slot selections (cheese, sauce, standard toppings, half-and-half config) |
| `DomainBridge` (UniFFI) | Exports pricing, coupon, order, causal tracker, money functions | Add exports for new modifier pricing validation, half-and-half pricing |
| `shared/contracts` DTOs | `CatalogDto`, `CartDto`, `OrderDto` — no cheese/sauce/standard/half fields | Extend DTOs with new modifier fields (nullable for backward compatibility) |

**Readiness:** Android UI architecture is **ready** — the slot framework exists and is gated. Only DTO extensions and UniFFI bindings need updates.

### 6.2 Rust shared core

| Crate | Current State | Phase 8 Impact |
|-------|---------------|----------------|
| `pizzora-core/pricing.rs` | Handles Size (absolute), Crust (extra), Extra Toppings (sum), Quantity — no cheese/sauce/standard/half | Add pricing for Cheese (extra), Sauce (extra), Standard Toppings (removable defaults → delta pricing), Half-and-Half (left/right pricing) |
| `pizzora-core/cart_validation.rs` | Validates Size, Crust, Extra Toppings — `MAX_ITEM_QUANTITY=25`, `MAX_CART_LINE_ITEMS=50` | Add validation for new modifier IDs, half-and-half config (2x topping lists), removable standard toppings |
| `pizzora-core/money.rs` | Integer HALF_UP bps math | Unchanged (reused) |
| `pizzora-db/migrations/` | `0001_core_schema.sql`, `0002_seed...`, `0003_hot_path...` (46 tables) | Add `0004_*.sql`+ for: `cheeses`, `sauces`, `standard_toppings`, `half_and_half_configs`, product-variant linkage tables |
| `pizzora-db/migrator.rs` | `run_locked_handoff` (advisory lock 884_210_001), `verify_catalog_invariants` (46 tables), preflight-only | Update `EXPECTED_TABLES`, add new table invariants, test preflight/handoff on fresh + upgraded DBs |
| `pizzora-api` | Handlers for catalog, cart, checkout, orders — no modifier endpoints | Add catalog endpoints for new modifiers; extend pricing quote to include new modifier prices |
| `pizzora-uniffi` | Exports: `pricing_calculate_line_total_paise`, `pricing_calculate_quote`, `coupon_normalize_code`, `order_can_customer_cancel`, `money_bps_of`, `money_format_inr`, causal tracker, order/payment state machines | Export new functions for modifier pricing validation, half-and-half quote calculation |

**Readiness:** Rust core **requires implementation** — no cheese/sauce/standard/half logic exists in `pricing.rs` or `cart_validation.rs`. Database migrations must be authored first.

### 6.3 Backend and data

| Layer | Current State | Phase 8 Impact |
|-------|---------------|----------------|
| PostgreSQL (Flyway V1–V3) | 46 tables, no cheese/sauce/standard/half tables | Additive SQLx V4+ migrations only (`0004_*.sql`+) |
| Rust `pizzora-api` (production) | Mirrors Flyway V1–V3 via SQLx, runs `run_locked_handoff` pre-deploy | New migrations adopted via `--migrate-only`; new catalog routes |
| Legacy Kotlin `backend/` | Flyway V1–V3 at `backend/src/main/resources/db/migration/` | **Do NOT add Flyway V4+** — point at SQLx-migrated schema if parity tests needed |
| `shared/contracts` DTOs | `OrderSummaryDto.itemCount: Int` only; `OrderItemDto` has display names only | Add `itemPreviewSummary: String?` to `OrderSummaryDto`; add `variantId`, `crustId`, `toppingIds: List<String>` to `OrderItemDto` (or new `/v1/orders/{id}/reorder` endpoint) |
| Room database | v3, 5 tables: `cart_items`, `cart_item_toppings`, `cached_products`, `cached_menu_snapshot`, `notification_inbox` | No Room schema change needed for Phase 8 (cart uses existing `cart_item_toppings`) |

**Readiness:** Backend **requires implementation** — DTO extensions, catalog endpoints, pricing integration, and SQLx migrations all needed.

---

## 7. Code quality findings

### 7.1 Android (no new findings — Phase 8 work not started)
- `PizzaBuilderScreen.kt` already has the extensible `CustomizationSectionSlot` architecture (lines 136-164, 1394+)
- `UnsupportedModifierSlot` correctly implements `isSupported = false` for all 4 Phase 8 slots
- `CustomizationSectionSlotHost` filters to `activeSlots = slots.filter { it.isSupported }` — zero rendering impact currently
- No dead code, no technical debt in the Phase 8 touchpoints

### 7.2 Rust (pre-existing, not Phase 8 blockers)
- `pricing.rs` and `cart_validation.rs` use `DomainError` for validation — consistent error handling
- `money.rs` HALF_UP bps math shared with Kotlin `Money.kt` — parity verified via `GoldenVectorParityExportTest`
- No `unsafe` code in `pizzora-core` — purity verified in CI (`cargo tree -p pizzora-core -e no-dev`)

### 7.3 Shared contracts (pre-existing)
- `OrderSummaryDto` missing `itemPreviewSummary` — tracked as backend dependency
- `OrderItemDto` missing modifier IDs — tracked as backend dependency
- Serialization uses `@SerialName` for snake_case — consistent with API

---

## 8. Backend and data dependencies

### 8.1 Required DTO extensions (`shared/contracts/src/main/kotlin/com/pizzora/contracts/`)

| File | Current | Required Addition |
|------|---------|-------------------|
| `CatalogDto.kt` | `ProductDto` has `variants`, `crustOptions`, `toppings` | Add `cheeses: List<CheeseOptionDto>`, `sauces: List<SauceOptionDto>`, `standardToppings: List<StandardToppingDto>`, `halfAndHalfEnabled: Boolean` |
| `CartDto.kt` | `CartItemInputDto` has `variantId`, `crustId`, `toppingIds` | Add `cheeseId?`, `sauceId?`, `standardToppingIds: List<String>`, `halfAndHalfConfig: HalfAndHalfConfigDto?` |
| `OrderDto.kt` | `OrderSummaryDto` has `itemCount: Int` | Add `itemPreviewSummary: String?` |
| `OrderDto.kt` | `OrderItemDto` has `productName`, `variantSize`, `crustName`, `toppings: List<String>` | Add `variantId`, `crustId`, `toppingIds: List<String>`, `cheeseId?`, `sauceId?`, `standardToppingIds?`, `halfAndHalfConfig?` |

### 8.2 Required database migrations (`rust/crates/pizzora-db/migrations/`)

New SQLx `0004_*.sql`+ migrations (additive/nullable only):
- `0004_cheeses.sql` — cheese options table, product-variant linkage
- `0005_sauces.sql` — sauce options table, product-variant linkage
- `0006_standard_toppings.sql` — standard toppings table (with `is_default: Boolean`), product linkage
- `0007_half_and_half.sql` — half-and-half configs table (left/right topping sets), product linkage
- `0008_catalog_modifier_indexes.sql` — indexes for new modifier lookups

**Constraint:** `migrator.rs` `verify_catalog_invariants` currently asserts 46 tables (`EXPECTED_TABLES`). Must update to 50+ tables and add invariant checks.

### 8.3 API contract changes

| Endpoint | Current | Phase 8 Addition |
|----------|---------|------------------|
| `GET /v1/catalog/menu` | Returns products with variants, crusts, toppings | Include cheeses, sauces, standard toppings, half-and-half flag |
| `POST /v1/pricing/quote` | Accepts `variantId`, `crustId`, `toppingIds` | Accept `cheeseId?`, `sauceId?`, `standardToppingIds`, `halfAndHalfConfig?` |
| `GET /v1/orders` / `GET /v1/orders/{id}` | `OrderSummaryDto.itemCount`, `OrderItemDto` display names | `OrderSummaryDto.itemPreviewSummary`, `OrderItemDto` with modifier IDs |
| `POST /v1/orders/{id}/reorder` | **Does not exist** | New endpoint (alternative to DTO extension) |

---

## 9. Testing readiness

### 9.1 Existing test infrastructure

| Layer | Status | Phase 8 Needs |
|-------|--------|---------------|
| **Rust unit/integration** | `pizzora-core/tests/unit_and_edge_tests.rs`, `golden_parity_tests.rs` | New tests for modifier pricing, half-and-half math, cart validation with new slots |
| **Rust migration** | `migrator.rs` tests: preflight, handoff, fresh/upgraded DBs | Extend for new tables, verify `verify_catalog_invariants` |
| **Shared contracts** | `PricingEngineTest.kt`, `GoldenVectorParityExportTest.kt` | Extend golden vectors for new modifier combinations |
| **Android Robolectric** | 36 test files, 207 tests (Phase 7 baseline) | New test files: `Phase8ModifiersRobolectricTest.kt`, `Phase8ReorderRobolectricTest.kt` |
| **Android UI/Compose** | `PizzoraDesignSystemTest`, `Phase3PizzaBuilderRobolectricTest` | Extend for new slot rendering, section jump bar, half-and-half selector |
| **Golden parity** | `tools/golden-vectors/pizzora_core_parity.json` | Regenerate with new pricing vectors |

### 9.2 Test tags to preserve (Section 10.3)

- `pizza_builder_screen`, `customizer_size_card_*`, `crust_option_*`, `topping_card_*`, `customizer_qty_plus`, `customizer_qty_value`, `add_to_cart_button`, `price_breakdown_drawer`
- `orders_screen`, `orders_lazy_column`, `order_summary_card_*`, `order_filter_*`, `order_detail_screen`
- All Phase 3/4/6/7 preservation-critical tags

### 9.3 New tests required

1. **Rust:** Modifier pricing parity (cheese + sauce + standard + half-and-half combinations), cart validation with removable defaults, half-and-half config validation
2. **Contracts:** Golden vector export for new modifier combinations
3. **Android:** Builder slot activation, section jump bar at ≥4 groups, half-and-half visual selector accessibility, Reorder reconciliation dialog, unavailable-item handling
4. **Migration:** `pizzora-api --preflight-only` and `--migrate-only` on fresh DB and Flyway V1–V3 upgraded DB

---

## 10. Visual and accessibility QA readiness

### 10.1 Visual QA matrix (Section 13.2 / `L956`)

| Phase 8 Surfaces | Representative Captures (Pairwise) |
|------------------|------------------------------------|
| `builder` with 4+ modifier groups | 1. All slots active + section jump bar (`dark_412dp_1.0x`, `light_320dp_1.5x`)<br>2. Half-and-half selector (`dark_600dp_1.0x`, `light_840dp_1.0x`)<br>3. Removable standard toppings (`dark_412dp_1.0x`) |
| `orders` Past tab + Reorder | 4. Order card with `itemPreviewSummary` (`dark_412dp_1.0x`, `light_320dp_1.0x`)<br>5. Reorder reconciliation dialog — some unavailable (`light_360dp_1.5x`) |

### 10.2 Accessibility requirements

- Half-and-half selector: `"Left half"`, `"Right half"`, `"Whole pizza"` labels (WCAG 2.2 AA)
- Section jump bar: keyboard navigable, announces active section
- Reorder dialog: focus trapping, confirmation for destructive reconciliation
- All new modifier chips: `Role.RadioButton`/`Role.Checkbox`, `contentDescription`
- Touch targets ≥48dp on all new controls

### 10.3 Tooling status

- **Roborazzi/Paparazzi:** NOT installed (per Section 13.2 `L940-L943`)
- **Mandatory path:** Deterministic Android Emulator AVD screenshots via `adb exec-out screencap -p`
- **Environment:** No KVM/emulator in current runtime (gVisor kernel); requires external KVM-enabled environment for visual QA

---

## 11. Infrastructure readiness

| Component | Status | Phase 8 Impact |
|-----------|--------|----------------|
| **CI (`.github/workflows/security-and-ci.yml`)** | Stage 4: Rust parity + PostgreSQL 18 + migration handoff tests | Extend migration tests for new tables; add golden parity vectors for modifiers |
| **Publish Rust** | `publish-rust-backend.yml` — manual dispatch, builds container | No change needed (schema migrations run at deploy via `preDeploy: pizzora-api --migrate-only`) |
| **Railway** | `.railway/railway.ts` — `partial = "pizzabi"`, owns API + worker only | No Railway changes — migrations run automatically on deploy |
| **Gradle dependency locking** | Enforced (`app/build.gradle.kts:L298-L308`) | Any new deps (e.g., for Reorder dialog) require lockfile approval |
| **UniFFI bindgen** | Runs in CI Stage `android-prep` | New exports require re-generating bindings (automatic) |
| **Release metadata** | `release-policy.json`, `metadata.json`, `VERSION` | Version bump only at release (not Phase 8 scope) |

---

## 12. Cross-phase regression risks

### 12.1 Phase 6 — Payment & Order Lifecycle (MUST NOT REGRESS)
| Test / Contract | Protection |
|-----------------|------------|
| `RazorpayCheckoutIntegrationTest` | Unaffected — no payment changes |
| `Phase11PaymentsRobolectricTest` (L531-596) | Unaffected — payment flow unchanged |
| `Phase12OrderTrackingRobolectricTest` (L346, L373, L443, L446) | Unaffected — tracking unchanged |
| `DomainBridge.canCustomerCancel` | Unaffected — order state machine unchanged |
| `MainActivity` Razorpay restoration (`KEY_PENDING_RAZORPAY_*`) | Unaffected — process-death handling unchanged |

### 12.2 Phase 7 — Profile, Notifications, Updates (MUST NOT REGRESS)
| Test / Contract | Protection |
|-----------------|------------|
| `Phase15ZonesAddressesSlotsRobolectricTest` | Unaffected — address/profile unchanged |
| `Phase16NotificationsRobolectricTest` | Unaffected — notifications unchanged |
| `Phase20UpdateRobolectricTest` / `Phase20UpdateUnitTest` | Unaffected — updates unchanged |
| `onOpenOrderFromNotification` shell callback | Unaffected — deep-linking unchanged |

### 12.3 Phase 3/4 — Builder & Cart (Phase 8 TOUCHES THESE)
| Risk | Mitigation |
|------|------------|
| `PizzaBuilderScreen` slot activation breaks existing Size/Crust/Toppings flow | `activeSlots` filter preserves current behavior; new slots only render when `isSupported=true` |
| Cart Re-Edit (`Phase4CartAndReEditRobolectricTest`) with new modifiers | `CartDao.replaceOrMergeItemWithToppings` handles child rows atomically; extend for new modifier columns |
| `PizzaBuilderPricingTest` (5/5) & `Phase3PizzaBuilderRobolectricTest` (14/14) | Extend tests for new modifier pricing parity; preserve existing assertions |

### 12.4 Specific tests Phase 8 must preserve or extend
- `Phase3PizzaBuilderRobolectricTest` — all 14 tests
- `PizzaBuilderPricingTest` — all 5 tests
- `Phase4CartAndReEditRobolectricTest` — all 19 tests
- `Phase4CartDaoAndRepositoryTest` — all 10 tests
- `Phase6PaymentSheetRobolectricTest` — all tests
- `Phase6OrdersHistoryRobolectricTest` — all tests
- `Phase6OrderTrackingRobolectricTest` — all tests
- `Phase7ProfileNotificationsUpdatesRobolectricTest` — all 14 tests
- `Phase7NotificationDeepLinkRobolectricTest` — both tests
- `Phase15ZonesAddressesSlotsRobolectricTest` — all 3 tests
- `Phase16NotificationsRobolectricTest` — all 5 tests
- `Phase19EndToEndRobolectricTest` — 1 test
- `Phase20UpdateRobolectricTest` / `Phase20UpdateUnitTest` — all tests

---

## 13. Risk and dependency register

| ID | Finding | Severity | Evidence | Affected Components | Consequences | Mitigation | Blocks Implementation? |
|----|---------|----------|----------|---------------------|--------------|------------|------------------------|
| R8-001 | **Backend contract sign-off not confirmed** | **BLOCKER** | Plan `L903`: "once backend/Rust/contract extensions are approved"; `L920`: "Dependencies: Phases 3, 4 & 6 + backend contract sign-off" | All Phase 8 work | Cannot begin any implementation without approved backend scope, timeline, and DTO contracts | Obtain explicit stakeholder approval for backend Phase 8 scope before Android work starts | **YES** |
| R8-002 | **Database schema changes required** | **BLOCKER** | Plan `L914`: "Author Additive SQLx V4+ Migrations (`0004_*.sql`+)"; `migrator.rs` expects 46 tables | `pizzora-db/migrations/`, `migrator.rs`, `pizzora-api` | Cannot activate modifiers without catalog tables; migration preflight will fail if `EXPECTED_TABLES` not updated | Author `0004_*.sql`+ migrations first; update `EXPECTED_TABLES` and `verify_catalog_invariants`; test preflight/handoff | **YES** (backend) |
| R8-003 | **Rust pricing engine lacks modifier logic** | **MAJOR** | `pricing.rs` only handles Size/Crust/Extra Toppings; no cheese/sauce/standard/half | `pizzora-core/pricing.rs`, `cart_validation.rs` | Client/server pricing mismatch; golden parity tests will fail | Implement pricing for 4 new modifier types; add unit tests; verify parity with Kotlin `PricingEngine` | **YES** (backend) |
| R8-004 | **Shared contracts DTOs missing fields** | **MAJOR** | `OrderSummaryDto.itemCount` only; `OrderItemDto` display names only (no IDs) | `shared/contracts/CatalogDto.kt`, `CartDto.kt`, `OrderDto.kt` | Reorder cannot reliably match items; Order cards cannot show previews | Add nullable fields to DTOs; maintain backward compatibility; version DTOs if needed | **YES** (backend) |
| R8-005 | **UniFFI exports need extension** | **MAJOR** | `pizzora-uniffi/src/lib.rs` exports current pricing/coupon/order functions only | `pizzora-uniffi`, `DomainBridge.kt` | Android cannot call new modifier pricing validation | Add `[uniffi::export]` functions for modifier pricing, half-and-half quote; regenerate bindings | **YES** (backend) |
| R8-006 | **Legacy Kotlin `backend/` parity** | **MEDIUM** | Plan `L915`: "If legacy Kotlin backend parity tests remain active, point them at SQLx-migrated schema" | `backend/`, Flyway migrations | Accidental Flyway V4+ addition breaks `verify_flyway_history()` | **Do not add Flyway V4+**; configure legacy tests to use `verifyMigrated = false` | No (process guard) |
| R8-007 | **Golden parity vectors outdated** | **MEDIUM** | `tools/golden-vectors/pizzora_core_parity.json` covers current modifiers only | `pizzora-core/tests/golden_parity_tests.rs`, `GoldenVectorParityExportTest.kt` | CI parity gate fails until vectors regenerated | Regenerate golden vectors after Rust pricing implementation; update both test suites | No (post-impl) |
| R8-008 | **Visual QA environment unavailable** | **MEDIUM** | Current runtime: gVisor, no KVM, no emulator, no AVDs (`qa-artifacts` empty for live captures) | Phase 8 visual exit gate | Cannot capture mandatory screenshots without KVM-enabled environment | Provision external KVM environment or use CI emulator workflow (`.github/workflows/phase4-android-emulator-qa.yml`) | No (external) |
| R8-009 | **Reorder reconciliation UX complexity** | **MEDIUM** | Plan: "1-tap Reorder with unavailable-item reconciliation dialog" | `OrdersScreen.kt`, `OrdersViewModel.kt`, `CartViewModel.kt` | Poor UX if unavailable items not handled gracefully | Design reconciliation dialog with: list unavailable items, offer "Add available items only" / "Cancel" | No (design) |
| R8-010 | **Section jump bar at ≥4 groups** | **MINOR** | Plan `L907`: "Section jump bar activates automatically when `>= 4` modifier groups are present" | `PizzaBuilderScreen.kt` | Missing navigation aid for complex pizzas | Implement scrollspy + jump bar; test at `320dp/1.5x` | No (UI detail) |

**Confirmed defects:** None found in current codebase (Phase 8 not started).\
**Potential risks:** R8-001 through R8-010 as above.\
**Recommendations:** Obtain backend contract sign-off (R8-001) as the single unblocking decision.

---

## 14. Proposed Phase 8 workstreams

### Workstream 1: Backend Contract & Schema (BACKEND-LED — **Prerequisite for all others**)

| Item | Detail |
|------|--------|
| **Objective** | Finalize DTO contracts, author SQLx migrations, implement Rust pricing/validation for 4 modifiers |
| **Scope** | `shared/contracts` DTO extensions, `rust/crates/pizzora-db/migrations/0004_*.sql`+, `pizzora-core/pricing.rs`, `cart_validation.rs`, `pizzora-api` catalog/pricing routes, `pizzora-uniffi` exports, `migrator.rs` invariants |
| **Likely affected files** | 15+ files across `shared/contracts`, `rust/crates/pizzora-core`, `pizzora-db`, `pizzora-api`, `pizzora-uniffi` |
| **Dependencies** | Stakeholder approval (R8-001) |
| **Approach** | 1. Design DTO extensions (nullable fields)<br>2. Author SQLx V4+ migrations (additive)<br>3. Implement Rust pricing & validation<br>4. Add API endpoints<br>5. Export via UniFFI<br>6. Update `migrator.rs` `EXPECTED_TABLES` + invariants<br>7. Run preflight/handoff tests on fresh + upgraded DBs<br>8. Regenerate golden parity vectors |
| **Testing** | Rust unit/integration, migration preflight, golden parity, API contract tests |
| **Visual QA** | N/A (backend) |
| **Accessibility** | N/A |
| **Acceptance** | `pizzora-api --preflight-only` && `--migrate-only` pass on fresh + Flyway V1–V3 DB; golden parity 100%; all new Rust tests green |

### Workstream 2: Android Builder Modifier Activation (ANDROID — **After Workstream 1 DTOs land**)

| Item | Detail |
|------|--------|
| **Objective** | Activate 4 `CustomizationSectionSlot`s in `PizzaBuilderScreen`; add section jump bar, half-and-half selector |
| **Scope** | `PizzaBuilderScreen.kt`, `PizzaBuilderUiState`, `DomainBridge.kt` (new UniFFI calls) |
| **Likely affected files** | `app/src/main/java/com/example/catalog/PizzaBuilderScreen.kt`, `DomainBridge.kt` (generated), `shared/contracts` (consumed) |
| **Dependencies** | Workstream 1 complete (DTOs, UniFFI exports, backend APIs) |
| **Approach** | 1. Update `PizzaBuilderUiState` with new slot selections<br>2. Implement `SupportedModifierSlot` data classes for Cheese, Sauce, Standard Toppings, Half-and-Half<br>3. Wire UniFFI pricing validation for new modifiers<br>4. Add section jump bar (activates at ≥4 `isSupported` slots)<br>5. Build half-and-half visual selector (left/right/whole)<br>6. Update `CustomizationSectionSlotHost` to render supported slots<br>7. Add accessibility labels for half-and-half |
| **Testing** | Extend `Phase3PizzaBuilderRobolectricTest`, `PizzaBuilderPricingTest`; new `Phase8ModifiersRobolectricTest.kt` |
| **Visual QA** | 3 captures: all slots + jump bar (dark 412dp, light 320dp/1.5x); half-and-half selector (tablet 600dp/840dp); removable standard toppings |
| **Accessibility** | Half-and-half: "Left half"/"Right half"/"Whole pizza"; jump bar keyboard nav; Role.RadioButton/Checkbox |
| **Acceptance** | All existing Phase 3/4 tests pass; new modifier pricing matches server quote; section jump bar works at ≥4 groups; half-and-half selector accessible; zero regression |

### Workstream 3: Android Order Preview & Reorder (ANDROID — **After Workstream 1 DTOs land**)

| Item | Detail |
|------|--------|
| **Objective** | Add `itemPreviewSummary` to `OrderSummaryCard`; implement 1-tap Reorder with reconciliation dialog |
| **Scope** | `OrdersScreen.kt`, `OrdersViewModel.kt`, `CartViewModel.kt` (reorder-to-cart), `PizzoraNavHost.kt` (if new dialog) |
| **Likely affected files** | `OrdersScreen.kt`, `OrdersViewModel.kt`, `CartViewModel.kt`, `DomainBridge.kt` (if needed) |
| **Dependencies** | Workstream 1 complete (OrderSummaryDto.itemPreviewSummary, OrderItemDto modifier IDs, or `/v1/orders/{id}/reorder` endpoint) |
| **Approach** | 1. Render `itemPreviewSummary` on `OrderSummaryCard` (Past tab)<br>2. Add Reorder CTA button on Past order cards<br>3. Implement `OrdersViewModel.reorder(orderId)` → fetches order detail → builds cart items<br>4. Create Reorder reconciliation dialog: shows unavailable items, "Add available items only" / "Cancel"<br>5. Call `CartViewModel.addReorderItems(items)` with merge logic (reuse `replaceOrMergeItemWithToppings`)<br>6. Handle missing modifier IDs gracefully (fallback to name matching with warning) |
| **Testing** | New `Phase8ReorderRobolectricTest.kt`; extend `Phase6OrdersHistoryRobolectricTest` |
| **Visual QA** | 2 captures: Order card with preview (dark 412dp, light 320dp); Reorder reconciliation dialog with unavailable items (light 360dp/1.5x) |
| **Accessibility** | Reorder button: contentDescription; reconciliation dialog: focus trap, ≥48dp targets, clear unavailable item list |
| **Acceptance** | Reorder adds correct items to Cart; unavailable items surfaced in dialog; merge respects 25-unit cap; existing order history tests pass |

### Workstream 4: Regression & QA (CROSS-CUTTING — **Parallel with 2 & 3**)

| Item | Detail |
|------|--------|
| **Objective** | Full regression suite, visual QA captures, accessibility audit, golden parity |
| **Scope** | All test suites, CI, emulator QA |
| **Dependencies** | Workstreams 2 & 3 code complete |
| **Approach** | 1. Run full `:app:testDebugUnitTest` (target: 207+ tests green)<br>2. Run Rust test suite (`cargo test --workspace --locked`)<br>3. Run shared contracts tests<br>4. Capture 5 mandatory Phase 8 screenshots per Section 13.2<br>5. Accessibility audit: touch targets, focus, TalkBack semantics<br>6. Verify golden parity vectors regenerated and match |
| **Testing** | All existing + new test files |
| **Visual QA** | 5 mandatory captures (Section 13.2) |
| **Accessibility** | Full WCAG 2.2 AA verification for new UI |
| **Acceptance** | Phase Exit Gate: 4/4 criteria satisfied |

### Workstream 5: Documentation (OPTIONAL — **Post-merge**)

| Item | Detail |
|------|--------|
| **Objective** | Update `public/implementation_plan.md` revision history; clean stale Phase 8 references |
| **Scope** | `public/implementation_plan.md` (Sections 11, 12, 16) |
| **Dependencies** | Phase 8 implementation complete |
| **Approach** | Document Phase 8 execution updates; correct any stale references (e.g., revision history `L1094` mentions "Phase 8 Migration Reference") |

---

### 14.1 Parallelization map

```
Workstream 1 (BACKEND) ──► [DTO contracts + UniFFI exports + migrations + pricing]
                              │
                    ┌─────────┴─────────┐
                    ▼                   ▼
         Workstream 2 (ANDROID)    Workstream 3 (ANDROID)
         Builder Modifiers         Order Preview + Reorder
                    │                   │
                    └─────────┬─────────┘
                              ▼
                    Workstream 4 (QA/REGRESSION)
                    (runs after 2 & 3 merge)
                              │
                              ▼
                    Workstream 5 (DOCS) — optional
```

- **Workstream 1 is strictly sequential and blocking** — no Android work can begin until DTOs, UniFFI exports, and backend APIs are available.
- **Workstreams 2 & 3 can run in parallel** once Workstream 1 delivers — they touch different screens (`PizzaBuilderScreen` vs `OrdersScreen`) and different ViewModels.
- **Workstream 4 runs after 2 & 3** — needs complete implementation for regression/visual QA.
- **File ownership for parallel agents:**
  - Workstream 2 owns: `PizzaBuilderScreen.kt`, `PizzaBuilderUiState`, any new modifier slot classes
  - Workstream 3 owns: `OrdersScreen.kt`, `OrdersViewModel.kt`, `CartViewModel.kt` (reorder method), Reorder dialog
  - No overlapping files — safe for parallel Android agents

---

## 15. Agent coordination proposal

### 15.1 Available agents (from tooling)

| Agent | Description | Phase 8 Role |
|-------|-------------|--------------|
| `backend` | Read-only investigation of PIZZORA backend API, product pricing, variants, and update metadata | **Workstream 1 lead** — implements DTO extensions, SQLx migrations, Rust pricing, API routes, UniFFI exports, migration tests |
| `android-ui` | Read-only investigation of PIZZORA Android Compose visual and interaction findings | **Workstream 2** — activates modifier slots, section jump bar, half-and-half selector in `PizzaBuilderScreen` |
| `android-qa` | Executes PIZZORA Android runtime QA using Ubuntu, Android SDK, ADB, emulator, screenshot tools | **Workstream 4** — emulator captures, TalkBack traversal, visual evidence |
| `test-engineer` | Reviews and executes PIZZORA Phase 4 regression tests, Gradle unit tests, and build verification | **Workstream 4** — full test suite execution, Rust/CI test runs, golden parity verification |
| `reviewer` | Independent read-only review of findings, fixes, QA evidence, and exit-gate recommendation | **Final exit-gate review** — assesses all 4 exit criteria |
| `git-docs` | Read-only investigation of Git state, worktrees, GitHub sync, implementation plan, QA documentation | **Workstream 5** — plan revision history updates (if approved) |
| `general` / `explore` | General-purpose / fast codebase exploration | Support as needed |

**Note:** No "Primary Coordinator" agent type exists in the tooling. The human coordinator (this session) performs planning, integration, and final acceptance.

### 15.2 Coordination boundaries

| Parallelizable | Requires Coordination |
|----------------|----------------------|
| Workstream 2 & 3 (different screens/ViewModels) | Workstream 1 → 2/3 handoff (DTO/UniFFI delivery) |
| Workstream 4 test execution (after 2&3 merge) | Workstream 4 visual QA needs emulator access |
| Rust tests & Android tests (separate toolchains) | Golden parity vectors must match (Rust ↔ Kotlin) |

### 15.3 Recommended agent assignments

| Workstream | Agent(s) | Notes |
|------------|----------|-------|
| 1. Backend Contract & Schema | `backend` (lead) | Must complete before any Android agent starts |
| 2. Builder Modifiers | `android-ui` | Owns `PizzaBuilderScreen.kt` entirely |
| 3. Order Preview & Reorder | `android-ui` (second) or `general` | Owns `OrdersScreen.kt`, `OrdersViewModel.kt`, `CartViewModel.kt` |
| 4. Regression & QA | `test-engineer` + `android-qa` | `test-engineer`: unit/Robolectric/Rust/CI; `android-qa`: emulator screenshots, TalkBack |
| 5. Exit Review | `reviewer` | Independent assessment after Workstream 4 |
| 6. Docs (if approved) | `git-docs` | Only if separate plan-cleanup approval granted |

---

## 16. Acceptance criteria (Phase Exit Gate — Section 12 `L761-L766`)

All four criteria must pass for Phase 8 completion:

1. **Functional Verification:**
   - All new Robolectric tests pass (`Phase8ModifiersRobolectricTest`, `Phase8ReorderRobolectricTest`)
   - All existing regression tests pass (207+ baseline + new)
   - Rust `cargo test --workspace --locked` green
   - Shared contracts tests green
   - Golden parity vectors match 100%
   - `pizzora-api --preflight-only` && `--migrate-only` pass on fresh + Flyway V1–V3 DBs

2. **Visual Evidence Sign-Off:**
   - 5 mandatory Phase 8 captures attached (Section 13.2 matrix)
   - Zero clipping, overlap, token drift
   - Dark & Light parity verified

3. **Accessibility Verification:**
   - WCAG 2.2 AA contrast on all new modifier chips, half-and-half selector, Reorder dialog
   - ≥48dp touch targets on all new controls
   - Semantic roles: `Role.RadioButton` (cheese/sauce), `Role.Checkbox` (standard toppings), half-and-half labels
   - Focus trapping in Reorder reconciliation dialog
   - TalkBack sanity: section jump bar announces, half-and-half labels spoken correctly

4. **Regression Verification:**
   - All Phase 3/4/6/7 preservation-critical tags reachable
   - All 12+ Phase 6/7 test suites green
   - `DomainBridge` pricing parity maintained
   - No Room schema change (verified)

---

## 17. User decisions and approvals required

| Decision | Status | Required Before | Options | Recommendation |
|----------|--------|-----------------|---------|----------------|
| **D8-01: Backend Phase 8 contract sign-off** | **CONDITIONALLY APPROVED** | **Any Phase 8 work** | Approve / Defer / Scope-reduce | **Approve** — unblocks entire phase; canonical scope only |
| **D8-02: Reorder implementation strategy** | **OPTION A APPROVED** | Workstream 3 start | A) Extend `OrderItemDto` with modifier IDs (preferred)<br>B) New `/v1/orders/{id}/reorder` endpoint | **Option A** — simpler client logic, reusable for other features |
| **D8-03: Legacy Kotlin `backend/` parity** | **CONDITIONALLY APPROVED** | Workstream 1 migrations | A) Point at SQLx schema (`verifyMigrated = false`)<br>B) Add Flyway V4+ (FORBIDDEN by `migrator.rs`) | **Option A** — mandated by plan `L915` |
| **D8-04: Plan cleanup commit** | **DEFERRED** | Post-Phase 8 | Yes / No | **Defer** — separate approval per Phase 7 precedent (`L143`) |
| **D8-05: Visual QA environment provisioning** | **APPROVED** | Workstream 4 | Use CI emulator workflow / Provision external KVM | **CI workflow** (`.github/workflows/phase4-android-emulator-qa.yml`) — already exists |

**Pending before implementation:**
- Independent contract review by `backend`, `test-engineer`, `reviewer` agents
- Resolution of 6 unresolved decisions (U8-01 through U8-06) documented in `docs/PHASE8_BACKEND_CONTRACT.md:Section 15`

---

## 18. Final readiness verdict

### Verdict: **PHASE 8 CONTRACT REVIEW REQUIRED**

### Supporting evidence

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Repository baseline verified | ✅ PASS | HEAD = `db41169`, clean tree, 0/0 sync |
| Phase 6 closed | ✅ PASS | 191 tests, 7/7 visual, exit gate SATISFIED |
| Phase 7 closed | ✅ PASS | 207 tests, 13 visual, exit gate SATISFIED |
| Canonical Phase 8 scope identified | ✅ PASS | `public/implementation_plan.md:L903-L922` |
| **Backend contract sign-off** | ⚠️ **CONDITIONALLY APPROVED** | D8-01 approved for canonical scope; pending independent review |
| Backend contract document | ✅ PRODUCED | `docs/PHASE8_BACKEND_CONTRACT.md` |
| Backend schema migrations authored | ❌ NOT STARTED | `rust/crates/pizzora-db/migrations/` only has `0001-0003` |
| Rust pricing extended for modifiers | ❌ NOT STARTED | `pricing.rs` only handles Size/Crust/Extra Toppings |
| Shared contracts DTOs extended | ❌ NOT STARTED | `OrderSummaryDto` lacks `itemPreviewSummary`; `OrderItemDto` lacks modifier IDs |
| UniFFI exports extended | ❌ NOT STARTED | `pizzora-uniffi/src/lib.rs` exports current functions only |
| Android UI architecture ready | ✅ PASS | `CustomizationSectionSlot` exists, gated by `isSupported=false` |
| CI/CD infrastructure ready | ✅ PASS | Migration preflight/handoff tests exist; golden parity framework exists |
| Cross-phase regression risks documented | ✅ PASS | Section 12 register; Phase 6/7 test preservation mapped |
| Visual QA environment | ⚠️ EXTERNAL | No KVM in current runtime; CI workflow available |
| Unresolved decisions documented | ⚠️ 6 OPEN | U8-01 through U8-06 in contract Section 15 |

### Blockers summary

**Primary blocker:** Independent contract review by `backend`, `test-engineer`, and `reviewer` agents must complete and sign off on `docs/PHASE8_BACKEND_CONTRACT.md`.

**Secondary blockers (6 unresolved decisions in contract):**
- U8-01: Standard topping `is_default` pricing model
- U8-02: Half-and-half topping source (toppings vs standard_toppings)
- U8-03: Cheese/Sauce required per product
- U8-04: Half-and-half surcharge model
- U8-05: Reorder unavailable behavior (substitute vs manual)
- U8-06: Standard topping removal pricing (charge vs credit)

### Conditions for readiness

Phase 8 becomes **PHASE 8 READY WITH CONDITIONS** when:

1. ✅ D8-01 conditionally approved (canonical scope only)
2. ✅ D8-02 Option A approved (OrderItemDto modifier IDs)
3. ✅ D8-03 conditionally approved (SQLx owns V4+; Kotlin audit first)
4. ✅ D8-05 approved (CI emulator workflow)
5. ⬜ **Independent contract review complete** (`backend`, `test-engineer`, `reviewer`)
6. ⬜ **Unresolved decisions U8-01 through U8-06 resolved** (or documented as constraints)
7. ⬜ Workstream 1 (Backend) completes first — delivers DTOs, migrations, UniFFI exports, pricing parity
8. ⬜ Workstreams 2 & 3 (Android) run in parallel after Workstream 1 delivery
9. ⬜ Workstream 4 (QA) runs after 2 & 3 merge
10. ⬜ Golden parity vectors regenerated and verified

---

## 19. Report location

This assessment is saved at: **`docs/PHASE8_READINESS_ASSESSMENT.md`**

**Backend contract:** **`docs/PHASE8_BACKEND_CONTRACT.md`** — defines modifier domain model, SQLx V4+ migrations, DTO/API extensions, pricing rules, UniFFI exports, golden parity, rollback, and 6 unresolved decisions.

No other files were modified. The canonical `public/implementation_plan.md` remains untouched per safety restrictions.

---

**End of assessment.** Awaiting independent contract review by `backend`, `test-engineer`, and `reviewer` agents before any Phase 8 implementation begins.