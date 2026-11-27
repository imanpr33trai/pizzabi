# PIZZORA Phase 8 — Reviewed Contract Resolutions (Binding Addendum)

**Date:** 2026-11-27\
**Branch:** `ui-ux-changes`\
**Baseline:** `db41169bf6d0852feae3dbc4ada93e0b50f85c53`\
**Status:** APPROVED FOR IMPLEMENTATION

---

## 1. Why this addendum exists

`docs/PHASE8_BACKEND_CONTRACT.md` on disk is the **pre-review draft**. Its status line still says
"implementation NOT authorized", its U8 decisions are marked OPEN, it specifies 4 new tables
(46 + 4), and its golden matrix contains 10 scenarios. The independent review session
(`ses_deea9b4e0ffe1h1FWMfAle9H5y`, 2026-11-25) rejected that draft with three reviewer verdicts
(`backend`, `test-engineer`, `reviewer`).

The user subsequently authorized implementation against the **reviewed contract**, with these
verified outcomes:

- Six U8 decisions resolved.
- Seven backend blockers contractually resolved.
- 51-table inventory (46 + 5).
- Two-profile invariant verification.
- Reorder API design approved.
- 33 golden pricing parity vectors.
- 11 acceptance criteria with tests and evidence.
- Five-service Kotlin raw-SQL compatibility audit.
- Backend reviewer PASS, test engineer PASS, independent reviewer PASS.
- Zero remaining BLOCKER or MAJOR contract findings.

This addendum records those resolutions explicitly so implementation, tests, and review use one
unambiguous specification. Where this addendum and the pre-review draft disagree, **this addendum
wins**. The pre-review draft remains in the repository as historical design input.

---

## 2. Resolved U8 decisions

| ID | Decision | Binding resolution |
|----|----------|--------------------|
| **U8-01** | Standard topping `is_default` pricing | `product_variants.price_paise` includes every `is_default = true` standard topping. Keeping a default topping adds **0**. Removing a default topping adds **0** (no credit, no charge). A selected `is_default = false` topping adds its `extra_price_paise`. |
| **U8-02** | Half-and-half topping source | Half-and-half halves are drawn from the global `toppings` table (extra toppings). A new **`product_toppings`** join table links products to available toppings and is the authority for "subset of the product's toppings" validation. |
| **U8-03** | Cheese/Sauce required? | **Optional in Phase 8.** Both slots render a "None" choice. Per-product mandatory selection is deferred; no `is_required` column is added in Phase 8. |
| **U8-04** | Half-and-half surcharge model | Fixed `half_and_half_surcharge_paise` per config. No percentage surcharge. |
| **U8-05** | Reorder unavailable behavior | **No automatic substitution.** The reorder response reports unavailable/unresolved items and the client shows a reconciliation dialog with "Add available items only" and "Cancel". |
| **U8-06** | Standard topping removal pricing | **Zero credit / zero charge.** Removal never produces a negative line contribution and never charges the topping's `extra_price_paise`. |

### 2.1 Consequence for the old draft's example

The old draft's vector "default topping removed → +1000" is **superseded**. Under the reviewed
contract, removing a default topping leaves the unit price unchanged at the base price.

---

## 3. Resolved backend blockers (7/7)

| # | Blocker from review | Binding resolution |
|---|---------------------|--------------------|
| 1 | `product_toppings` missing; half-and-half subset impossible | Add `product_toppings(product_id, topping_id, sort_order)` in `0004_product_toppings.sql`, with FKs to `products` and `toppings`, and seed the existing product × topping cross-product so current availability is preserved. |
| 2 | `OrderDetailDto` not extended | Extend `OrderItemDto` and `OrderDetailDto` with modifier IDs, snapshots, and preview data; `OrderDetailDto.items` uses the extended item type. |
| 3 | Reorder endpoint missing | Implement `POST /v1/orders/{id}/reorder` with ownership checks and a reconciliation response. Option A DTO fidelity remains in place. |
| 4 | UniFFI incomplete | Export FFI records for all modifier refs, extended line input, half-and-half breakdown, standard-topping pricing, availability checks, and reorder validation. |
| 5 | `EXPECTED_TABLES` count wrong | Set `EXPECTED_TABLES` to **51** (46 existing + `product_toppings`, `cheeses`, `sauces`, `standard_toppings`, `half_and_half_configs`). |
| 6 | `verify_catalog_invariants` not updated | Verify the 5 new tables, required columns, FKs, CHECKs, UNIQUEs, and indexes on both migration profiles. |
| 7 | Kotlin raw-SQL compatibility unspecified | Audit and update the five services: `PricingService`, `CartService`, `OrderService`, `CatalogService`/`CatalogRoutes`, `CartRoutes`; no Flyway V4+ files. |

The review's optional suggestions that would add a sixth table (`cart_item_standard_toppings`) or an
`is_required` column are **not adopted**: standard topping selections are stored as JSONB ID arrays
on `cart_items`/`order_items`, preserving the 51-table contract and the additive strategy.

---

## 4. Database contract

### 4.1 Table inventory — 51

Existing 46 tables are unchanged. The five new tables are:

1. `product_toppings`
2. `cheeses`
3. `sauces`
4. `standard_toppings`
5. `half_and_half_configs`

### 4.2 Migration sequence (SQLx owns V4+)

V1–V3 (`0001`–`0003`) remain byte-identical and immutable.

| File | Purpose |
|------|---------|
| `0004_product_toppings.sql` | Product↔topping availability join table; seed current cross-product. |
| `0005_cheeses.sql` | Per-product cheese options. |
| `0006_sauces.sql` | Per-product sauce options. |
| `0007_standard_toppings.sql` | Per-product standard toppings with `is_default`. |
| `0008_half_and_half.sql` | Per-product half-and-half configs (JSONB topping ID arrays). |
| `0009_cart_modifier_columns.sql` | Additive nullable columns on `cart_items`. |
| `0010_order_modifier_columns.sql` | Additive nullable columns/snapshots on `order_items`. |
| `0011_modifier_indexes.sql` | Lookup indexes for new FKs/JSONB/order queries. |

### 4.3 New table definitions

```sql
CREATE TABLE product_toppings (
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    topping_id UUID NOT NULL REFERENCES toppings(id) ON DELETE CASCADE,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, topping_id)
);

CREATE TABLE cheeses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    extra_price_paise BIGINT NOT NULL CHECK (extra_price_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (product_id, name)
);

CREATE TABLE sauces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    extra_price_paise BIGINT NOT NULL CHECK (extra_price_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (product_id, name)
);

CREATE TABLE standard_toppings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    is_veg BOOLEAN NOT NULL DEFAULT TRUE,
    extra_price_paise BIGINT NOT NULL CHECK (extra_price_paise >= 0),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (product_id, name)
);

CREATE TABLE half_and_half_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    left_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    right_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    half_and_half_surcharge_paise BIGINT NOT NULL DEFAULT 0
        CHECK (half_and_half_surcharge_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (product_id, name)
);
```

### 4.4 Cart and order columns

`cart_items` (all nullable/defaulted, additive):

- `cheese_id UUID REFERENCES cheeses(id) ON DELETE SET NULL`
- `sauce_id UUID REFERENCES sauces(id) ON DELETE SET NULL`
- `standard_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb`
- `half_and_half_config_id UUID REFERENCES half_and_half_configs(id) ON DELETE SET NULL`
- `half_and_half_left_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb`
- `half_and_half_right_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb`

`order_items` (all nullable/defaulted, historical rows remain valid):

- `variant_id UUID`, `crust_id UUID`, `cheese_id UUID`, `sauce_id UUID`
- `topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb`
- `standard_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb`
- `standard_topping_removed_ids JSONB NOT NULL DEFAULT '[]'::jsonb`
- `half_and_half_config_id UUID`
- `half_and_half_left_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb`
- `half_and_half_right_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb`
- `modifiers_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb`

`order_items` deliberately has **no FKs** on modifier IDs so historical orders survive catalog
deletion/deactivation.

### 4.5 Two-profile invariant verification

`migrator.rs` must verify both profiles:

1. **Fresh bootstrap** — empty database → `run_locked_handoff` applies V1–V8+ → 51 tables,
   new constraints/indexes, catalog invariants.
2. **Flyway V1–V3 adoption** — database with pinned Flyway history/checksums → handoff adopts
   V1–V3 → applies V4+ → same 51-table invariants.

Both profiles must also pass `--preflight-only` and `--migrate-only`. A rollback/reapply test must
drop V4+ objects, remove V4+ SQLx rows, and re-run handoff successfully.

---

## 5. Pricing contract

### 5.1 Linear modifiers

```
unit_price =
    variant_price_paise
  + crust_extra_paise
  + cheese_extra_paise
  + sauce_extra_paise
  + standard_toppings_extra_paise
  + sum(extra_topping.extra_price_paise)
```

`standard_toppings_extra_paise` is the sum of selected `is_default = false` toppings only.
Selected defaults and removed defaults contribute 0.

### 5.2 Half-and-half

```
left_half_base  = variant_price + crust_extra + Σ(left_topping extras)
right_half_base = variant_price + crust_extra + Σ(right_topping extras)
average         = ROUND_HALF_UP((left_half_base + right_half_base) / 2)
unit_price      = average + half_and_half_surcharge
                + cheese_extra + sauce_extra + standard_toppings_extra
```

`ROUND_HALF_UP` for non-negative integers: `(left + right + 1) / 2`, evaluated in `i128` and
converted back with overflow checks.

### 5.3 Validation

- Half-and-half: ≤10 toppings per half, ≤15 distinct total, no left/right overlap.
- All topping IDs must belong to `product_toppings` for the product.
- Cheese/sauce/standard topping/half-and-half config must belong to the product and be active.
- Inactive or foreign modifiers are rejected; historical order display is preserved.
- Quantities 1–25, cart lines ≤50.
- All arithmetic checked; overflow returns `DomainError::ArithmeticOverflow`.

New issue codes: `CheeseUnavailable`, `SauceUnavailable`, `StandardToppingUnavailable`,
`HalfAndHalfUnavailable`, `HalfAndHalfToppingLimitExceeded`, `HalfAndHalfLeftRightOverlap`,
`StandardToppingNotInProduct`, `CheeseNotInProduct`, `SauceNotInProduct`,
`HalfAndHalfConfigNotInProduct`.

---

## 6. DTO and API contract

### 6.1 Shared DTOs

- `CheeseOptionDto`, `SauceOptionDto`, `StandardToppingDto`, `HalfAndHalfConfigDto`.
- `ProductDto` and `PublicMenuResponse` gain the four modifier arrays.
- `CartItemInputDto` gains `cheeseId?`, `sauceId?`, `standardToppingIds`, `halfAndHalfConfigId?`.
- `CartItemViewDto` gains resolved IDs, names, prices, standard topping views, half-and-half views.
- `OrderItemDto` gains the ID/snapshot fields listed in Section 4.4.
- `OrderSummaryDto` gains `itemPreviewSummary: String?`.
- All new fields are optional/defaulted for historical and old-client compatibility.

### 6.2 Endpoints

| Endpoint | Change |
|----------|--------|
| `GET /v1/catalog/menu` | Add modifier arrays (respecting feature flags). |
| `POST /v1/pricing/quote` | Accept modifier IDs; return modifier breakdown and issues. |
| `GET/PUT /v1/cart`, `POST /v1/cart/validate` | Accept/persist/return modifiers. |
| `GET /v1/orders`, `GET /v1/orders/{id}` | `itemPreviewSummary`; extended `OrderItemDto`/`OrderDetailDto`. |
| `POST /v1/orders/{id}/reorder` | New. Ownership-checked. Returns `ReorderResponseDto`. |

### 6.3 Reorder response

```json
{
  "orderId": "uuid",
  "canReorder": true,
  "items": [
    {
      "orderItemId": "uuid",
      "productId": "uuid",
      "productName": "Margherita",
      "variantId": "uuid|null",
      "crustId": "uuid|null",
      "cheeseId": "uuid|null",
      "sauceId": "uuid|null",
      "toppingIds": ["uuid"],
      "standardToppingIds": ["uuid"],
      "standardToppingRemovedIds": ["uuid"],
      "halfAndHalfConfigId": "uuid|null",
      "halfAndHalfLeftToppingIds": ["uuid"],
      "halfAndHalfRightToppingIds": ["uuid"],
      "quantity": 1,
      "isAvailable": true,
      "issues": []
    }
  ],
  "unavailable": [
    {
      "orderItemId": "uuid",
      "productName": "Margherita",
      "modifierType": "CHEESE",
      "modifierId": "uuid",
      "modifierName": "Mozzarella",
      "reason": "CHEESE_UNAVAILABLE"
    }
  ],
  "warnings": ["Historical order: cheese/sauce could not be reconstructed."]
}
```

Historical fallback resolves variant by `(productId, variantSize)`, crust by name, and extra
toppings by name. New modifiers cannot be reconstructed for historical orders and are reported as
warnings; they are never silently substituted. The endpoint does not mutate the cart.

### 6.4 Feature flags

`Phase8FeatureFlags` (cheese, sauce, standard toppings, half-and-half, reorder) are configurable via
`PIZZORA_FEATURE_*` environment variables. Phase 8 tests and local runs default to enabled; setting
a flag to `false` hides the corresponding catalog data and rejects the corresponding writes.

---

## 7. UniFFI contract

New records:

- `FfiCheeseOption`, `FfiSauceOption`, `FfiStandardTopping`, `FfiHalfAndHalfConfig`
- `FfiHalfAndHalfBreakdown`
- `FfiStandardToppingSelection`
- `FfiPricedLineInputExtended`
- `FfiCartItemInputExtended`
- `FfiModifierAvailability`
- `FfiReorderItem`, `FfiReorderValidation`

New exports:

- `pricing_calculate_unit_price_paise(...)`
- `pricing_calculate_standard_toppings_extra_paise(...)`
- `pricing_validate_half_and_half(...)`
- `cart_validate_with_modifiers(...)`
- `catalog_check_modifier_availability(...)`
- `reorder_validate_items(...)`

All exports map `DomainError` to `UniffiDomainError` consistently and return paise-exact values
identical to the Rust engine.

---

## 8. 33 golden pricing parity vectors

Canonical fixture: `tools/golden-vectors/pizzora_phase8_modifier_vectors.json`.
Rust test: `pizzora-core/tests/phase8_golden_parity_tests.rs`.
Kotlin test: `GoldenVectorParityExportTest` / `PricingEngineTest`.
Android test: `PizzaBuilderPricingTest`.

| # | ID | Scenario | Expected |
|---|----|----------|----------|
| 1 | U-01 | Base 10000, no modifiers | 10000 |
| 2 | U-02 | + cheese 2000 | 12000 |
| 3 | U-03 | + sauce 1500 | 11500 |
| 4 | U-04 | + cheese 2000 + sauce 1500 | 13500 |
| 5 | U-05 | + crust 1000 | 11000 |
| 6 | U-06 | + non-default standard 1000 | 11000 |
| 7 | U-07 | + non-default standard 1000 + 500 | 11500 |
| 8 | U-08 | Default standard kept (extra 1000 ignored) | 10000 |
| 9 | U-09 | Default standard removed (zero credit) | 10000 |
| 10 | U-10 | Default kept + non-default 1000 | 11000 |
| 11 | U-11 | Default removed + non-default 1000 | 11000 |
| 12 | U-12 | Crust 1000 + cheese 2000 + sauce 1500 + standard 1000 | 15500 |
| 13 | U-13 | Half 14000/15000 + surcharge 1000 | 15500 |
| 14 | U-14 | Half 10001/10002 + surcharge 0 (odd HALF_UP) | 10002 |
| 15 | U-15 | Half 10001/10002 + surcharge 999 | 11001 |
| 16 | U-16 | Half 15500 + cheese 2000 + sauce 1500 | 19000 |
| 17 | U-17 | Half 15500 + non-default standard 700 | 16200 |
| 18 | U-18 | Half 15500 + cheese 2000 + sauce 1500 + standard 700 | 19700 |
| 19 | U-19 | Half 10000/10001 (1-paise difference) | 10001 |
| 20 | U-20 | Half 10000/10003 (3-paise difference) | 10002 |
| 21 | U-21 | Base 10000 × quantity 2 | 20000 |
| 22 | U-22 | All-modifier 19700 × quantity 25 | 492500 |
| 23 | U-23 | Half 10002 × quantity 3 | 30006 |
| 24 | U-24 | Half-all 19700 × quantity 25 | 492500 |
| 25 | U-25 | Half 12000/12000 + surcharge 0 | 12000 |
| 26 | U-26 | Half 10000/10000 + surcharge 5000 | 15000 |
| 27 | U-27 | Unit 1,000,000,000 × quantity 25 | 25,000,000,000 |
| 28 | U-28 | Unit `i64::MAX` × quantity 2 | `ARITHMETIC_OVERFLOW` |
| 29 | Q-01 | Quote 14500, GST 500, delivery 0 | tax 725, total 15225 |
| 30 | Q-02 | Quote 14500, 10% coupon | discount 1450, tax 653, total 13703 |
| 31 | Q-03 | Quote 10001, 12.5% coupon | discount 1250, tax 438, total 9189 |
| 32 | Q-04 | Quote 14500, flat 5000 cap 3000, delivery 3000 | discount 3000, tax 575, total 15075 |
| 33 | Q-05 | 19700 × 2, 10% coupon, GST 500, delivery 3000 | discount 3940, tax 1773, total 40233 |

Vectors U-08–U-11 additionally exercise
`calculate_standard_toppings_extra_paise` with explicit `is_default`/`is_removed` selections.

---

## 9. 11 acceptance criteria

| # | Criterion | Required evidence |
|---|-----------|-------------------|
| 1 | SQLx V4+ migrations apply on a fresh database | `migrator` test log, 51 tables, `--migrate-only` exit 0 |
| 2 | SQLx V4+ migrations adopt a Flyway V1–V3 database | adopt-profile test log, 51 tables, `--preflight-only`/`--migrate-only` |
| 3 | 51-table inventory and catalog invariants hold on both profiles | invariant test output, migration report |
| 4 | Rollback/reapply safety verified | rollback test log (drop V4+ objects, re-run handoff) |
| 5 | All 33 golden pricing vectors match Rust and Kotlin | parity test output, fixture hash |
| 6 | Pricing rules (default removal zero credit, extras charged, HALF_UP, overflow) verified | Rust unit tests + UniFFI parity |
| 7 | Cart validation rejects unavailable, foreign, over-limit, and overlapping modifiers | Rust validation tests + API tests |
| 8 | DTO/API backward compatibility for old clients and historical orders | serialization tests + historical reorder test |
| 9 | Reorder endpoint enforces ownership, preserves IDs, and reports reconciliation | API tests (200/403/404, unavailable cases) |
| 10 | Android builder and reorder UI work, are accessible, and match server pricing | Robolectric tests, emulator captures, UIAutomator XML |
| 11 | Phase 3/4/6/7 regression remains green and independent review has no BLOCKER/MAJOR | full test XML, visual evidence, reviewer report |

---

## 10. Five-service Kotlin raw-SQL audit

The legacy Kotlin backend must run against the SQLx-migrated schema with
`verifyMigrated = false`. No Flyway V4+ files. The five services and required work:

| Service | Required compatibility work |
|---------|-----------------------------|
| `PricingService` | Load cheeses/sauces/standard toppings/half-and-half; compute extended unit price; return breakdown; preserve existing fields. |
| `CartService` | Persist/load new modifier columns and JSONB arrays; validate IDs; merge configurations including modifiers. |
| `OrderService` | Persist modifier IDs/snapshots; return extended `OrderItemDto`/`OrderDetailDto`; reorder reconstruction. |
| `CatalogService` / `CatalogRoutes` | Return modifier arrays in menu/product responses; respect flags. |
| `CartRoutes` | Accept/validate new optional `CartItemInputDto` fields; return extended views. |

Exact SQL statements and mapping points are recorded in the implementation progress report.

---

## 11. File ownership and workstreams

| Workstream | Owner | Write scope |
|------------|-------|-------------|
| W1 Schema/DB | Coordinator + `p8-backend` | `rust/crates/pizzora-db/migrations/*`, `migrator.rs`, migration tests |
| W2 Rust pricing | `p8-rust-pricing` | `rust/crates/pizzora-core/src/pricing.rs`, `cart_validation.rs`, core tests, golden fixture |
| W3 API/DTO | `p8-api-contracts` | `shared/contracts/**`, `rust/crates/pizzora-api/**` |
| W4 UniFFI | `p8-uniffi` | `rust/crates/pizzora-uniffi/**`, generated binding inputs |
| W5 Kotlin backend | `p8-backend` | `backend/src/main/kotlin/**` (five services), backend tests |
| W6 Android builder | `p8-builder-ui` | `PizzaBuilderScreen.kt`, builder state/VM, builder tests |
| W7 Android orders | `p8-orders-ui` | `OrdersScreen.kt`, `OrdersViewModel.kt`, `CartViewModel.kt`, reorder dialog/tests |
| W8 QA | `p8-test-engineer`, `android-qa` | test execution, evidence only; no production edits |
| W9 Review | `p8-reviewer` | read-only review report |

No two writers own the same file. The coordinator owns Git, integration, and final conflict
resolution.

---

## 12. Dependency gates

1. W1 completes and passes migration tests → Checkpoint A.
2. W2 and W3 may proceed in parallel after W1; shared interfaces are fixed by Sections 4–6.
3. W4 depends on W2 + W3.
4. W5 depends on W1 + W3.
5. W6/W7 depend on W3 + W4.
6. W8 runs after W6/W7; W9 after W8.

Android agents must not implement against provisional contracts; the DTO shapes in this addendum are
frozen.
