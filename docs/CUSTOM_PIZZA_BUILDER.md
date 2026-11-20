# Feature 01: Advanced Custom Pizza Builder

## 1. Overview
The **Advanced Custom Pizza Builder** extends PIZZORA's existing product catalog, cart persistence, dynamic pricing, checkout, inventory reservation, and Kitchen Display System (KDS) across:

- **PostgreSQL (`pizzora-db` / SQLx migration `0004_custom_pizza_builder.sql`)**
- **Rust Domain & HTTP API (`pizzora-core`, `pizzora-uniffi`, `pizzora-api`)**
- **Shared Kotlin Multiplatform Contracts (`:shared:contracts`)**
- **Android App (`app` — Room v4, Repositories, ViewModels, `DomainBridge`, Jetpack Compose UI)**

No parallel catalog, cart, or pricing system was introduced; all builder logic reuses and extends the existing catalog/cart/order pipeline and deterministic integer-paise math.

---

## 2. Half-and-Half Pricing & Ingredient Scaling Policies

### 2.1 Half-and-Half Pricing Policy
1. **Base Size + Half-and-Half Surcharge (Anti-Arbitrage Max-Half Rule)**:
   - Both halves must share the same `size_code` and have active variants.
   - `base_price_paise = left_variant.price_paise`
   - `half_and_half_surcharge_paise = max(0, right_variant.price_paise - left_variant.price_paise)`
   - Because `base_price_paise + half_and_half_surcharge_paise == max(left_variant.price_paise, right_variant.price_paise)`, selecting a cheaper pizza first and a premium pizza second cannot lower the pizza price.
2. **Sauce & Extra Sauce Pricing**:
   - Whole pizza or Half-and-Half with identical sauces (`eff_sauce_id == eff_right_sauce_id`): full `sauce.extra_price_paise` and full `sauce.extra_sauce_price_paise` (if `extra_sauce == true`).
   - Half-and-Half with distinct sauces (`eff_sauce_id != eff_right_sauce_id`): `Money::bps_of(left_sauce.price, 5_000) + Money::bps_of(right_sauce.price, 5_000)` using exact integer `HALF_UP` rounding (`5000 bps = 50%`).
3. **Toppings & Included Toppings**:
   - Each product can define `included_topping_ids` (default toppings included at `quantity = 1` without extra charge). Customers can remove included toppings (`removed_included_topping_ids`).
   - Selecting an included topping on an eligible side (`WHOLE` when included on both active halves, `LEFT` when included on left, `RIGHT` when included on right) makes the first unit free (`billable_qty = max(0, quantity - 1)`), while doubling (`quantity = 2`) charges only for the extra unit.
   - `WHOLE` placement charges `topping.extra_price_paise * billable_qty`.
   - `LEFT` or `RIGHT` placement charges `Money::bps_of(topping.extra_price_paise, 5_000) * billable_qty`.
4. **Total Unit & Line Price**:
   - `unit_price_paise = base_price_paise + half_and_half_surcharge_paise + crust_price_paise + cheese_price_paise + extra_cheese_price_paise + sauce_price_paise + extra_sauce_price_paise + toppings_price_paise`
   - `line_total_paise = unit_price_paise * quantity`
   - Backend checkout & cart services **always recompute prices server-side** and ignore `client_unit_price_paise`.

### 2.2 Recipe Stock Reservation & Ingredient Scaling Policy (`pizzora-api/src/services/recipes.rs`)
During transactional checkout (`compute_required_ingredients_for_items`), ingredient demand is scaled conservatively using `scale_ceil(per_unit, qty, bps) = (per_unit * qty * bps + 9_999) / 10_000`:
- **Base Dough / Variant**: For distinct half-and-half variants (`left_variant != right_variant`), each half reserves 50% (`5_000 bps`) ceiling-rounded of its variant recipe; single-recipe pizzas reserve 100%.
- **Crust**: 100% of crust recipe per pizza unit.
- **Cheese**: Scaled by `size_rule.ingredient_multiplier_bps` (`7500` Regular, `10000` Medium, `13500` Large, `17000` Extra Large), multiplied by `1.5x` (`3 / 2`) when `extra_cheese == true`.
- **Sauces**: Scaled by `size_rule.ingredient_multiplier_bps` (`1.5x` when `extra_sauce == true`); split `50% / 50%` (`(sauce_bps + 1) / 2`) when left and right sauces differ.
- **Toppings**: Scaled by `size_rule.ingredient_multiplier_bps` for `WHOLE` or `(size_bps + 1) / 2` for `LEFT`/`RIGHT`, multiplied by selection `quantity`.

---

## 3. Domain Parity & Validation Rules

Both Rust (`pizzora_core::pizza_builder::evaluate_custom_pizza`) and Shared Kotlin (`PizzaBuilderEngine.evaluate` + `DomainBridge`) share identical integer math and validation issue codes (`CartValidationIssueCode`):

- `INVALID_QUANTITY`: Pizza quantity not in `1..=25` or topping quantity not in `1..=topping.max_quantity`.
- `PRODUCT_UNAVAILABLE` / `VARIANT_UNAVAILABLE` / `VARIANT_PRODUCT_MISMATCH`: Missing, disabled, or mismatched product/variant.
- `SIZE_UNAVAILABLE`: Missing or inactive `pizza_size_rules` row for the variant's `size_code`.
- `CRUST_UNAVAILABLE` / `CHEESE_UNAVAILABLE` / `SAUCE_UNAVAILABLE` / `TOPPING_UNAVAILABLE`: Disabled or non-existent modifier.
- `EXTRA_CHEESE_UNAVAILABLE` / `EXTRA_SAUCE_UNAVAILABLE`: Extra cheese/sauce requested when modifier `supports_extra == false` or none selected.
- `HALF_AND_HALF_UNSUPPORTED`: Enabled when left product, right product, `pizza_size_rules.supports_half_and_half`, or `crust.supports_half_and_half` is `false`.
- `RIGHT_HALF_INVALID`: Missing/inactive right product or right variant, or right variant `size_code != left_variant.size_code`.
- `INCLUDED_TOPPING_INVALID`: Attempt to remove an ID not present in the left (or active right) product's `included_topping_ids`.
- `TOPPING_PLACEMENT_INVALID`: `LEFT`/`RIGHT` placement when `is_half_and_half == false` or `topping.supports_half_and_half == false`.
- `DUPLICATE_SELECTION`: Duplicate `(topping_id, placement)` pair or duplicate entry in `removed_included_topping_ids`.
- `TOPPING_LIMIT_EXCEEDED`: `max(left_half_load, right_half_load) > min(size_rule.max_toppings, product_overrides)`.
- `EXTRA_TOPPING_LIMIT_EXCEEDED`: Billable extra topping half-weight units exceed `size_rule.max_extra_toppings * 2`.

---

## 4. Database Migrations & Persistence

### 4.1 PostgreSQL (`0004_custom_pizza_builder.sql`)
- Strictly additive migration preserving `0001`–`0003`:
  - Expands `product_variants.size_code` CHECK constraint to include `EXTRA_LARGE`.
  - Adds `pizza_size_rules`, `cheese_options`, `cheese_recipes`, `sauce_options`, `sauce_recipes`, and `product_included_toppings`.
  - Extends `products` (`is_customizable`, `supports_half_and_half`, `default_cheese_id`, `default_sauce_id`, `max_toppings_override`), `crust_options` (`supports_half_and_half`), and `toppings` (`max_quantity`, `supports_half_and_half`).
  - Extends `cart_items` with builder selections + `configuration_signature`; updates `cart_item_toppings` primary key to `(cart_item_id, topping_id, placement)` with `quantity`.
  - Adds immutable JSONB columns `order_items.customization_snapshot` and `order_items.price_breakdown_snapshot`.

### 4.2 Android Room v4 (`PizzoraDatabase.MIGRATION_3_4`)
- Extends `cart_items` with `cheese_id`, `extra_cheese`, `sauce_id`, `extra_sauce`, `is_half_and_half`, `right_product_id`, `right_product_name`, `right_variant_id`, `right_sauce_id`, `removed_included_topping_ids_json`, `customization_json`, `price_breakdown_json`, `configuration_signature`.
- Rebuilds `cart_item_toppings` with composite primary key `(cart_item_id, topping_id, placement)` and `quantity`, preserving all v3 rows as `(WHOLE, 1)` with foreign key `ON DELETE CASCADE` preserved.
- Schema exported to `/app/schemas/com.example.core.database.PizzoraDatabase/4.json`.

### 4.3 Cart Deduplication & Offline-to-Online Sync
- Canonical `configuration_signature` (13 pipe-delimited fields including effective cheese/sauce IDs, half-and-half target, sorted removed included toppings, and sorted `topping_id:placement:quantity` selections) merges identical items (capped at `25`) while separating any configuration difference.
- `NetworkCartRepository` preserves `removedIncludedToppingIds` cleanly across server `PUT /api/v1/cart` round-trips via local match and signature decoding.

---

## 5. API & UI Integration

- **Public & Quote Endpoints**:
  - `GET /api/v1/catalog/menu` includes `size_rules`, `cheeses`, `sauces`, and product builder fields (`included_topping_ids`, `default_cheese_id`, `default_sauce_id`, `supports_half_and_half`, `max_toppings_override`).
  - `POST /api/v1/pizza-builder/quote` accepts `PizzaBuilderQuoteRequest` and returns live server-evaluated `PizzaBuilderQuoteResponse`.
- **Owner Management Endpoints**:
  - `GET /api/v1/owner/size-rules`, `PATCH /api/v1/owner/size-rules/{sizeCode}`
  - `GET/POST /api/v1/owner/cheeses`, `PATCH /api/v1/owner/cheeses/{id}`
  - `GET/POST /api/v1/owner/sauces`, `PATCH /api/v1/owner/sauces/{id}`
- **Android UI Screens**:
  - `PizzaCustomizerSheet`: Configurable sizes with topping limits, crusts with half-and-half support indicators, Half-and-Half switcher + compatible second-half selector, cheeses + Extra Cheese toggle, whole/left/right sauces + Extra Sauce toggle, removable included toppings, extra toppings with `Whole`/`Left`/`Right` placement + quantity stepper, live validation issue banner, expandable itemized price breakdown, and live line total.
  - `CartScreen`, `OrdersScreen`, and `KdsScreen`: Render structured customization summaries, removed included toppings (`NO: ...`), left/right/multi-quantity toppings, and price breakdowns.

---

## 6. Verification Scope

- **Gradle / Kotlin / Android Verification (Passed)**:
  - `:shared:contracts:test` — **8 suites / 27 tests passed** (0 failures, 0 skipped), including `PizzaBuilderEngineTest`, pricing rounding, contracts, and golden parity vectors.
  - `:app:testDebugUnitTest` — **24 suites / 71 tests passed** (0 failures, 0 skipped), including `CustomPizzaBuilderRobolectricTest` (5 UI & end-to-end persistence/checkout tests), `Phase1RoomMigrationTest` (v2→v4 and v3→v4 Room migrations), `Phase4UniFfiDomainBridgeRobolectricTest` (`KotlinContractsDomainBridge` parity), and `Phase7CartAndMenuRobolectricTest`.
- **Rust & PostgreSQL Verification Note**:
  - `cargo`, `rustc`, `psql`, and `git` are not installed in this container environment; Rust crate changes (`pizzora-core`, `pizzora-uniffi`, `pizzora-db`, `pizzora-api`), SQL migration `0004_custom_pizza_builder.sql`, and integration suite `pg18_custom_pizza_builder.rs` were reviewed and verified statically. Run `cargo test` inside the workspace with PostgreSQL 18 available to execute live backend integration assertions.
