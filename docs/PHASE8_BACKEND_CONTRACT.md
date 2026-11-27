# PIZZORA Phase 8 — Backend Contract

**Date:** 2026-11-25\
**Branch:** `ui-ux-changes`\
**Status:** Contract design — implementation NOT authorized\
**Decisions applied:** D8-01 (Conditionally Approved), D8-02 (Option A), D8-03 (Conditionally Approved), D8-04 (Deferred), D8-05 (Approved)

---

## 1. Executive summary

This contract defines the backend changes required for **Phase 8: Extended Pizza Modifiers & Reorder** as specified in `public/implementation_plan.md:L903-L922`. It covers:

- Four new pizza modifier types: **Cheese**, **Sauces**, **Standard Toppings** (removable defaults), **Half-and-Half**
- **Order item previews** on `OrderSummaryCard` (`itemPreviewSummary`)
- **1-tap Reorder** with modifier-ID fidelity and unavailable-item reconciliation

All changes follow the **additive SQLx V4+ migration** strategy mandated by `migrator.rs` (Flyway V1–V3 immutable). The legacy Kotlin `backend/` remains on the SQLx-migrated schema (`verifyMigrated = false`).

---

## 2. Modifier domain model and stable identifiers

### 2.1 Core design principles

| Principle | Implementation |
|-----------|----------------|
| **Stable IDs** | All modifiers use `UUID` primary keys (consistent with existing `products`, `variants`, `crusts`, `toppings`) |
| **Per-product linkage** | Modifiers are linked to products via join tables — a cheese/sauce/standard-topping is only available for specific products |
| **Separation of concerns** | `cheeses` and `sauces` are **single-choice** (radio); `standard_toppings` are **multi-choice with removable defaults** (checkboxes); `half_and_half` is a **configuration object** on the cart/order line |
| **Pricing model** | Cheese/Sauce: `extra_price_paise` (like crust); Standard Topping: `extra_price_paise` but with `is_default: true` meaning included in base price unless removed; Half-and-Half: priced as `(left_half_price + right_half_price) / 2` + `half_and_half_surcharge_paise` |
| **Availability** | Each modifier has `is_active: BOOLEAN`; inactive modifiers are filtered from customer UI but preserved for historical orders |

### 2.2 Modifier type definitions

```rust
// New Rust types (pizzora-core/src/catalog.rs or new file)

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ModifierSlotKind {
    Cheese,
    Sauce,
    StandardTopping,
    HalfAndHalf,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CheeseOptionRef {
    pub id: Uuid,
    pub product_id: Uuid,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SauceOptionRef {
    pub id: Uuid,
    pub product_id: Uuid,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StandardToppingRef {
    pub id: Uuid,
    pub product_id: Uuid,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_default: bool,  // true = included in base price; false = extra charge
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HalfAndHalfConfigRef {
    pub id: Uuid,
    pub product_id: Uuid,
    pub name: String,                    // e.g., "Half & Half"
    pub left_topping_ids: Vec<Uuid>,     // Must be subset of product's toppings
    pub right_topping_ids: Vec<Uuid>,    // Must be subset of product's toppings
    pub half_and_half_surcharge_paise: i64,
    pub is_active: bool,
}
```

### 2.3 Stable identifier strategy for Reorder (D8-02 Option A)

**All modifiers are referenced by their UUID IDs in cart/order DTOs.**

| Context | ID fields stored |
|---------|------------------|
| `CartItemInputDto` (request) | `cheese_id?`, `sauce_id?`, `standard_topping_ids: List<String>`, `half_and_half_config_id?` |
| `CartItemViewDto` (response) | Same + resolved names/prices |
| `OrderItemDto` (order history) | `variant_id`, `crust_id`, `cheese_id?`, `sauce_id?`, `standard_topping_ids: List<String>`, `half_and_half_config_id?` + snapshot names/prices |

**Backward compatibility:** All new ID fields are **nullable/optional** in DTOs. Historical orders without these fields remain valid — reorder falls back to name matching with warnings (Section 10).

---

## 3. Cheese, Sauce, Topping, and Half-and-Half representations

### 3.1 Database tables (SQLx V4+ migrations)

```sql
-- 0004_cheeses.sql
CREATE TABLE cheeses (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    extra_price_paise BIGINT NOT NULL CHECK (extra_price_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE (product_id, name)
);

-- 0005_sauces.sql
CREATE TABLE sauces (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    extra_price_paise BIGINT NOT NULL CHECK (extra_price_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE (product_id, name)
);

-- 0006_standard_toppings.sql
CREATE TABLE standard_toppings (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    is_veg BOOLEAN NOT NULL DEFAULT TRUE,
    extra_price_paise BIGINT NOT NULL CHECK (extra_price_paise >= 0),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,  -- true = included in base price
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE (product_id, name)
);

-- 0007_half_and_half.sql
CREATE TABLE half_and_half_configs (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    left_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,   -- array of topping UUIDs
    right_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,  -- array of topping UUIDs
    half_and_half_surcharge_paise BIGINT NOT NULL DEFAULT 0 CHECK (half_and_half_surcharge_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE (product_id, name)
);
```

### 3.2 Catalog DTO extensions (shared/contracts)

```kotlin
// CatalogDto.kt additions
@Serializable
data class CheeseOptionDto(
    val id: String,
    @SerialName("product_id") val productId: String,
    val name: String,
    @SerialName("extra_price_paise") val extraPricePaise: Long,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class SauceOptionDto(
    val id: String,
    @SerialName("product_id") val productId: String,
    val name: String,
    @SerialName("extra_price_paise") val extraPricePaise: Long,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class StandardToppingDto(
    val id: String,
    @SerialName("product_id") val productId: String,
    val name: String,
    @SerialName("is_veg") val isVeg: Boolean,
    @SerialName("extra_price_paise") val extraPricePaise: Long,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class HalfAndHalfConfigDto(
    val id: String,
    @SerialName("product_id") val productId: String,
    val name: String,
    @SerialName("left_topping_ids") val leftToppingIds: List<String> = emptyList(),
    @SerialName("right_topping_ids") val rightToppingIds: List<String> = emptyList(),
    @SerialName("half_and_half_surcharge_paise") val halfAndHalfSurchargePaise: Long,
    @SerialName("is_active") val isActive: Boolean,
)

// ProductDto extension
@Serializable
data class ProductDto(
    // ... existing fields
    val cheeses: List<CheeseOptionDto> = emptyList(),
    val sauces: List<SauceOptionDto> = emptyList(),
    val standardToppings: List<StandardToppingDto> = emptyList(),
    val halfAndHalfConfigs: List<HalfAndHalfConfigDto> = emptyList(),
)

// PublicMenuResponse extension
@Serializable
data class PublicMenuResponse(
    // ... existing fields
    val cheeses: List<CheeseOptionDto> = emptyList(),
    val sauces: List<SauceOptionDto> = emptyList(),
    val standardToppings: List<StandardToppingDto> = emptyList(),
    val halfAndHalfConfigs: List<HalfAndHalfConfigDto> = emptyList(),
)
```

### 3.3 Cart DTO extensions

```kotlin
// CartDto.kt — CartItemInputDto extension
@Serializable
data class CartItemInputDto(
    val id: String? = null,
    @SerialName("product_id") val productId: String,
    @SerialName("variant_id") val variantId: String,
    @SerialName("crust_id") val crustId: String,
    @SerialName("topping_ids") val toppingIds: List<String> = emptyList(),
    val quantity: Int,
    @SerialName("client_unit_price_paise") val clientUnitPricePaise: Long? = null,
    // NEW fields (all optional for backward compatibility)
    @SerialName("cheese_id") val cheeseId: String? = null,
    @SerialName("sauce_id") val sauceId: String? = null,
    @SerialName("standard_topping_ids") val standardToppingIds: List<String> = emptyList(),
    @SerialName("half_and_half_config_id") val halfAndHalfConfigId: String? = null,
)

// CartDto.kt — CartItemViewDto extension
@Serializable
data class CartItemViewDto(
    // ... existing fields
    @SerialName("cheese_id") val cheeseId: String? = null,
    @SerialName("cheese_name") val cheeseName: String? = null,
    @SerialName("cheese_extra_price_paise") val cheeseExtraPricePaise: Long = 0L,
    @SerialName("sauce_id") val sauceId: String? = null,
    @SerialName("sauce_name") val sauceName: String? = null,
    @SerialName("sauce_extra_price_paise") val sauceExtraPricePaise: Long = 0L,
    @SerialName("standard_topping_ids") val standardToppingIds: List<String> = emptyList(),
    @SerialName("standard_toppings") val standardToppings: List<CartStandardToppingViewDto> = emptyList(),
    @SerialName("half_and_half_config_id") val halfAndHalfConfigId: String? = null,
    @SerialName("half_and_half_config_name") val halfAndHalfConfigName: String? = null,
    @SerialName("half_and_half_left_toppings") val halfAndHalfLeftToppings: List<CartToppingViewDto> = emptyList(),
    @SerialName("half_and_half_right_toppings") val halfAndHalfRightToppings: List<CartToppingViewDto> = emptyList(),
    @SerialName("half_and_half_surcharge_paise") val halfAndHalfSurchargePaise: Long = 0L,
)

@Serializable
data class CartStandardToppingViewDto(
    @SerialName("topping_id") val toppingId: String,
    val name: String,
    @SerialName("is_veg") val isVeg: Boolean,
    @SerialName("extra_price_paise") val extraPricePaise: Long,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("is_available") val isAvailable: Boolean,
    val isRemoved: Boolean = false,  // true if default topping was explicitly removed
)
```

### 3.4 Order DTO extensions (for Reorder fidelity)

```kotlin
// OrderDto.kt — OrderItemDto extension (Option A: modifier IDs)
@Serializable
data class OrderItemDto(
    val id: String,
    @SerialName("product_id") val productId: String,
    @SerialName("product_name") val productName: String,
    @SerialName("variant_size") val variantSize: String,
    @SerialName("crust_name") val crustName: String,
    val toppings: List<String> = emptyList(),  // legacy display names (preserved)
    @SerialName("unit_price_paise") val unitPricePaise: Long,
    val quantity: Int,
    @SerialName("line_total_paise") val lineTotalPaise: Long,
    // NEW fields for reorder fidelity (all optional for historical orders)
    @SerialName("variant_id") val variantId: String? = null,
    @SerialName("crust_id") val crustId: String? = null,
    @SerialName("cheese_id") val cheeseId: String? = null,
    @SerialName("sauce_id") val sauceId: String? = null,
    @SerialName("topping_ids") val toppingIds: List<String> = emptyList(),  // extra toppings
    @SerialName("standard_topping_ids") val standardToppingIds: List<String> = emptyList(),
    @SerialName("standard_topping_removed_ids") val standardToppingRemovedIds: List<String> = emptyList(),
    @SerialName("half_and_half_config_id") val halfAndHalfConfigId: String? = null,
    @SerialName("half_and_half_left_topping_ids") val halfAndHalfLeftToppingIds: List<String> = emptyList(),
    @SerialName("half_and_half_right_topping_ids") val halfAndHalfRightToppingIds: List<String> = emptyList(),
)

// OrderDto.kt — OrderSummaryDto extension (preview)
@Serializable
data class OrderSummaryDto(
    // ... existing fields
    @SerialName("item_preview_summary") val itemPreviewSummary: String? = null,  // e.g., "Margherita (Regular, Thin) + 1 more"
)
```

---

## 4. Pricing rules, rounding, quantities, and validation

### 4.1 Pricing formulas

| Modifier Type | Unit Price Contribution |
|---------------|------------------------|
| **Cheese** | `+ cheese.extra_price_paise` (single choice, like crust) |
| **Sauce** | `+ sauce.extra_price_paise` (single choice, like crust) |
| **Standard Topping** | If `is_default=true` and NOT removed: `+ 0` (included in base). If `is_default=false` or removed: `+ extra_price_paise` per topping |
| **Half-and-Half** | `((left_half_total + right_half_total) / 2) + half_and_half_surcharge_paise`<br>Where `left_half_total = variant_price + crust_extra + Σ(left_toppings_extra)`<br>and `right_half_total = variant_price + crust_extra + Σ(right_toppings_extra)` |

### 4.2 Pricing invariants

- All prices in **paise (i64)**, integer arithmetic only
- **HALF_UP rounding** at every division step (bps, proportional discount, half-and-half average)
- **Overflow protection**: `checked_add`, `checked_mul` everywhere — return `DomainError::ArithmeticOverflow`
- **Max quantity**: 25 per line item (existing `MAX_ITEM_QUANTITY`)
- **Max cart lines**: 50 distinct configurations (existing `MAX_CART_LINE_ITEMS`)
- **Half-and-Half topping limits**: Each half can have ≤ 10 toppings; total distinct toppings across both halves ≤ 15

### 4.3 Validation rules

```rust
// New validation codes (add to CartValidationIssueCode)
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CartValidationIssueCode {
    // ... existing
    CheeseUnavailable,
    SauceUnavailable,
    StandardToppingUnavailable,
    HalfAndHalfUnavailable,
    HalfAndHalfToppingLimitExceeded,
    HalfAndHalfLeftRightOverlap,      // same topping on both halves
    StandardToppingNotInProduct,       // standard topping not linked to product
    CheeseNotInProduct,
    SauceNotInProduct,
    HalfAndHalfConfigNotInProduct,
}
```

---

## 5. Rust pricing engine interfaces

### 5.1 Extended `PricedLineInput`

```rust
// pizzora-core/src/pricing.rs
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PricedLineInput {
    pub unit_price_paise: i64,          // final computed unit price (variant + crust + modifiers)
    pub quantity: i32,
    pub is_taxable: bool,
    // NEW: breakdown for audit/drift detection
    pub variant_price_paise: i64,
    pub crust_extra_paise: i64,
    pub cheese_extra_paise: i64,
    pub sauce_extra_paise: i64,
    pub standard_toppings_extra_paise: i64,  // sum of non-default + removed defaults
    pub half_and_half_surcharge_paise: i64,
    pub half_and_half_half_split: bool,
}
```

### 5.2 New pricing functions

```rust
// pizzora-core/src/pricing.rs

impl PricingEngine {
    /// Calculate unit price for a single line with all modifiers
    pub fn calculate_unit_price_paise(
        variant_price_paise: i64,
        crust_extra_paise: i64,
        cheese_extra_paise: i64,
        sauce_extra_paise: i64,
        standard_toppings_extra_paise: i64,
        half_and_half: Option<&HalfAndHalfBreakdown>,
    ) -> Result<i64, DomainError> { ... }

    /// Half-and-half breakdown for pricing transparency
    #[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
    pub struct HalfAndHalfBreakdown {
        pub left_half_base_paise: i64,      // variant + crust + left toppings
        pub right_half_base_paise: i64,     // variant + crust + right toppings
        pub surcharge_paise: i64,
    }

    /// Validate half-and-half configuration
    pub fn validate_half_and_half(
        config: &HalfAndHalfConfigRef,
        variant_price: i64,
        crust_extra: i64,
    ) -> Result<HalfAndHalfBreakdown, DomainError> { ... }
}
```

### 5.3 Cart validation extensions

```rust
// pizzora-core/src/cart_validation.rs
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartItemInput {
    // ... existing fields
    pub cheese_id: Option<String>,
    pub sauce_id: Option<String>,
    pub standard_topping_ids: Vec<String>,
    pub half_and_half_config_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedCartItem {
    // ... existing fields
    pub cheese_id: Option<Uuid>,
    pub sauce_id: Option<Uuid>,
    pub standard_topping_ids: Vec<Uuid>,  // sorted, distinct
    pub half_and_half_config_id: Option<Uuid>,
}

impl CartValidation {
    pub fn validate_and_parse_inputs(
        items: &[CartItemInput],
    ) -> Result<Vec<ParsedCartItem>, DomainError> { ... }

    pub fn evaluate_cart(
        items: &[ParsedCartItem],
        lookup: &ExtendedCatalogRefLookup,
    ) -> Result<EvaluatedCartSummary, DomainError> { ... }
}

#[derive(Debug, Clone, Default)]
pub struct ExtendedCatalogRefLookup {
    pub products: HashMap<Uuid, CatalogProductRef>,
    pub variants: HashMap<Uuid, CatalogVariantRef>,
    pub crusts: HashMap<Uuid, CatalogCrustRef>,
    pub toppings: HashMap<Uuid, CatalogToppingRef>,
    // NEW
    pub cheeses: HashMap<Uuid, CheeseOptionRef>,
    pub sauces: HashMap<Uuid, SauceOptionRef>,
    pub standard_toppings: HashMap<Uuid, StandardToppingRef>,
    pub half_and_half: HashMap<Uuid, HalfAndHalfConfigRef>,
    // Product -> modifier IDs lookup for validation
    pub product_cheeses: HashMap<Uuid, Vec<Uuid>>,
    pub product_sauces: HashMap<Uuid, Vec<Uuid>>,
    pub product_standard_toppings: HashMap<Uuid, Vec<Uuid>>,
    pub product_half_and_half: HashMap<Uuid, Vec<Uuid>>,
}
```

---

## 6. SQLx V4+ migration sequence

### 6.1 Migration files (rust/crates/pizzora-db/migrations/)

| File | Description | Depends On |
|------|-------------|------------|
| `0004_cheeses.sql` | Cheese options table + indexes | V1 (products) |
| `0005_sauces.sql` | Sauce options table + indexes | V1 (products) |
| `0006_standard_toppings.sql` | Standard toppings table + indexes | V1 (products) |
| `0007_half_and_half.sql` | Half-and-half configs table + indexes | V1 (products, toppings) |
| `0008_modifier_indexes.sql` | Cross-table indexes for lookup performance | 0004–0007 |

### 6.2 Migration details

```sql
-- 0004_cheeses.sql
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
CREATE INDEX idx_cheeses_product_active ON cheeses (product_id, is_active);

-- 0005_sauces.sql
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
CREATE INDEX idx_sauces_product_active ON sauces (product_id, is_active);

-- 0006_standard_toppings.sql
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
CREATE INDEX idx_standard_toppings_product_active ON standard_toppings (product_id, is_active);

-- 0007_half_and_half.sql
CREATE TABLE half_and_half_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    left_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    right_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    half_and_half_surcharge_paise BIGINT NOT NULL DEFAULT 0 CHECK (half_and_half_surcharge_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (product_id, name)
);
CREATE INDEX idx_half_and_half_product_active ON half_and_half_configs (product_id, is_active);

-- 0008_modifier_indexes.sql
-- Additional indexes for cart/order query performance
CREATE INDEX idx_cart_items_cheese ON cart_items (cheese_id) WHERE cheese_id IS NOT NULL;
CREATE INDEX idx_cart_items_sauce ON cart_items (sauce_id) WHERE sauce_id IS NOT NULL;
-- Note: standard_toppings and half_and_half_config are stored in JSONB or separate tables
-- For cart_items, we add columns via ALTER TABLE (additive):
ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS cheese_id UUID REFERENCES cheeses(id);
ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS sauce_id UUID REFERENCES sauces(id);
ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS half_and_half_config_id UUID REFERENCES half_and_half_configs(id);
-- standard_toppings remain in cart_item_toppings with a new column is_standard BOOLEAN DEFAULT FALSE
ALTER TABLE cart_item_toppings ADD COLUMN IF NOT EXISTS is_standard BOOLEAN NOT NULL DEFAULT FALSE;
-- For half-and-half, we store left/right topping IDs in new JSONB columns on cart_items
ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS half_and_half_left_topping_ids JSONB DEFAULT '[]'::jsonb;
ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS half_and_half_right_topping_ids JSONB DEFAULT '[]'::jsonb;
-- order_items extensions (additive)
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS cheese_id UUID;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS sauce_id UUID;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS standard_topping_ids JSONB DEFAULT '[]'::jsonb;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS standard_topping_removed_ids JSONB DEFAULT '[]'::jsonb;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS half_and_half_config_id UUID;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS half_and_half_left_topping_ids JSONB DEFAULT '[]'::jsonb;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS half_and_half_right_topping_ids JSONB DEFAULT '[]'::jsonb;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS variant_id UUID;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS crust_id UUID;
```

### 6.3 `migrator.rs` updates

```rust
// Update EXPECTED_TABLES to 51 (46 + 4 new + cart_items/order_items ALTER columns are not new tables)
pub const EXPECTED_TABLES: &[&str] = &[
    // ... existing 46
    "cheeses",
    "sauces",
    "standard_toppings",
    "half_and_half_configs",
];

// Update verify_catalog_invariants to check new tables exist and have required constraints
// No V3 index additions required for Phase 8 (modifier indexes in 0008 are performance, not required)
```

---

## 7. Kotlin backend compatibility and migration ownership

### 7.1 Current ownership audit

| Component | Owner | Database Access Pattern |
|-----------|-------|------------------------|
| **Rust `pizzora-api`** | **Primary** | SQLx compile-time checked queries, `run_locked_handoff` for migrations |
| **Kotlin `backend/`** | **Secondary (legacy)** | Raw JDBC via `DatabaseFactory.withJdbcTransaction`, manual SQL strings |

### 7.2 Coordination strategy (D8-03)

1. **SQLx owns V4+ migrations** — Kotlin `backend/` does NOT add Flyway V4+ files
2. **Kotlin `backend/` points at SQLx-migrated schema** — configure `verifyMigrated = false` for Flyway (or disable Flyway entirely in test profiles)
3. **Shared query patterns** — Kotlin services (`PricingService`, `CartService`, `OrderService`) must be updated to:
   - Query new modifier tables (`cheeses`, `sauces`, `standard_toppings`, `half_and_half_configs`)
   - Include new columns in `cart_items` and `order_items` SELECT/INSERT
   - Validate modifier FK references before insert
4. **No partially migrated schema observation** — migrations run under advisory lock; both runtimes see either pre-migration or post-migration state, never intermediate

### 7.3 Required Kotlin backend changes

| Service | Changes |
|---------|---------|
| `PricingService` | Load cheeses/sauces/standard_toppings/half_and_half in `evaluateItemsForQuote`; compute unit price with new modifier pricing |
| `CartService` | Persist new modifier IDs in `cart_items` and `cart_item_toppings` (is_standard flag); evaluate availability |
| `OrderService` | Store modifier IDs in `order_items` new columns; include in `OrderDetailDto` response |
| `CatalogService` / `CatalogRoutes` | Return new modifier arrays in `PublicMenuResponse` and `ProductDto` |
| `CartRoutes` | Accept new optional fields in `CartItemInputDto` |

---

## 8. API request and response DTO changes

### 8.1 Catalog API (`GET /v1/catalog/menu`)

**Request:** Unchanged

**Response:** `PublicMenuResponse` now includes:
```json
{
  "categories": [...],
  "products": [...],
  "crusts": [...],
  "toppings": [...],
  "cheeses": [...],
  "sauces": [...],
  "standardToppings": [...],
  "halfAndHalfConfigs": [...],
  "gstBps": 500,
  "storeName": "Pizzora",
  "isAcceptingOrders": true
}
```

### 8.2 Pricing quote (`POST /v1/pricing/quote`)

**Request:** `PricingQuoteRequest` items now accept optional modifier IDs:
```json
{
  "items": [
    {
      "id": "line-1",
      "productId": "uuid",
      "variantId": "uuid",
      "crustId": "uuid",
      "toppingIds": ["uuid1", "uuid2"],
      "quantity": 1,
      "cheeseId": "uuid",                    // NEW
      "sauceId": "uuid",                     // NEW
      "standardToppingIds": ["uuid3"],       // NEW
      "halfAndHalfConfigId": "uuid",         // NEW
      "clientUnitPricePaise": null
    }
  ],
  "fulfillmentType": "DELIVERY",
  "pincode": "560001",
  "couponCode": "WELCOME50"
}
```

**Response:** `PricingQuoteResponse` items include modifier breakdown:
```json
{
  "items": [{
    "id": "line-1",
    "productId": "...",
    "variantId": "...",
    "crustId": "...",
    "toppingIds": [...],
    "quantity": 1,
    "cheeseId": "...",                     // NEW
    "cheeseName": "Mozzarella",
    "cheeseExtraPricePaise": 2000,
    "sauceId": "...",                      // NEW
    "sauceName": "Marinara",
    "sauceExtraPricePaise": 1500,
    "standardToppingIds": [...],           // NEW
    "standardToppings": [...],             // NEW (with isDefault, isRemoved)
    "halfAndHalfConfigId": "...",          // NEW
    "halfAndHalfConfigName": "Half & Half",
    "halfAndHalfLeftToppings": [...],
    "halfAndHalfRightToppings": [...],
    "halfAndHalfSurchargePaise": 3000,
    "unitPricePaise": 45000,
    "lineTotalPaise": 45000,
    "isAvailable": true,
    "issues": []
  }],
  "breakdown": { ... },
  "appliedCoupon": { ... },
  "deliveryZone": { ... },
  "isAcceptingOrders": true,
  "gstBps": 500
}
```

### 8.3 Cart API (`GET /v1/cart`, `PUT /v1/cart`, `POST /v1/cart/validate`)

**Request/Response:** `CartItemInputDto` and `CartItemViewDto` include new optional modifier fields (see Section 3.3)

### 8.4 Orders API (`GET /v1/orders`, `GET /v1/orders/{id}`)

**Response:** `OrderSummaryDto.itemPreviewSummary` (NEW), `OrderItemDto` includes modifier IDs (see Section 3.4)

---

## 9. `OrderItemDto` modifier-ID extension (D8-02 Option A)

### 9.1 Field summary

| Field | Type | Nullable | Purpose |
|-------|------|----------|---------|
| `variantId` | String? | Yes | Stable variant reference for reorder |
| `crustId` | String? | Yes | Stable crust reference for reorder |
| `cheeseId` | String? | Yes | Selected cheese |
| `sauceId` | String? | Yes | Selected sauce |
| `toppingIds` | List<String> | No (empty) | Extra toppings (existing behavior) |
| `standardToppingIds` | List<String> | No (empty) | Standard toppings (non-removed) |
| `standardToppingRemovedIds` | List<String> | No (empty) | Default standard toppings explicitly removed |
| `halfAndHalfConfigId` | String? | Yes | Half-and-half config reference |
| `halfAndHalfLeftToppingIds` | List<String> | No (empty) | Snapshot of left half toppings |
| `halfAndHalfRightToppingIds` | List<String> | No (empty) | Snapshot of right half toppings |

### 9.2 Reorder reconstruction logic

```kotlin
// OrdersViewModel.reorder(orderId) pseudocode
fun reorder(orderId: String) {
    val orderDetail = getOrderDetail(orderId)
    val reorderItems = orderDetail.items.map { item ->
        CartItemInputDto(
            productId = item.productId,
            variantId = item.variantId ?: item.productId + "_" + item.variantSize, // fallback
            crustId = item.crustId ?: findCrustIdByName(item.crustName),
            toppingIds = item.toppingIds.map { findToppingIdByName(it) },
            quantity = item.quantity,
            cheeseId = item.cheeseId,
            sauceId = item.sauceId,
            standardToppingIds = item.standardToppingIds,
            halfAndHalfConfigId = item.halfAndHalfConfigId,
        )
    }
    // Validate availability; collect unavailable items for reconciliation dialog
    val (available, unavailable) = cartRepository.validateCart(reorderItems)
    if (unavailable.isNotEmpty()) {
        showReorderReconciliationDialog(unavailable) { userChoice ->
            if (userChoice == ADD_AVAILABLE_ONLY) {
                cartRepository.replaceCart(available)
            }
        }
    } else {
        cartRepository.replaceCart(reorderItems)
    }
}
```

---

## 10. Reorder behavior for current and historical orders

### 10.1 Current orders (placed after Phase 8 deployment)

- Full modifier IDs present in `OrderItemDto`
- Reorder reconstructs exact configuration
- Unavailable modifiers flagged in reconciliation dialog

### 10.2 Historical orders (placed before Phase 8 deployment)

- Modifier ID fields are `null`/`empty`
- **Fallback strategy:**
  1. Match `variantId` via `variantSize` + `productId` lookup
  2. Match `crustId` via `crustName` lookup
  3. Match `toppingIds` via display name lookup
  4. For new modifiers (cheese/sauce/standard/half): **cannot reconstruct** — show warning in reconciliation dialog
  5. Default: `cheeseId=null`, `sauceId=null`, `standardToppingIds=empty`, `halfAndHalfConfigId=null`

### 10.3 Reconciliation dialog UX

```
┌─────────────────────────────────────────┐
│  Some items from this order cannot be   │
│  added exactly as before:               │
│                                         │
│  ⚠ "Margherita (Regular, Thin Crust)"  │
│     • Mozzarella cheese no longer avail │
│     • "Half & Half" config unavailable  │
│                                         │
│  [ Add available items only ]  [ Cancel ]│
└─────────────────────────────────────────┘
```

---

## 11. UniFFI exports and Android integration boundaries

### 11.1 New UniFFI exports (rust/crates/pizzora-uniffi/src/lib.rs)

```rust
// New FFI records
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiHalfAndHalfBreakdown {
    pub left_half_base_paise: i64,
    pub right_half_base_paise: i64,
    pub surcharge_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiPricedLineInputExtended {
    pub unit_price_paise: i64,
    pub quantity: i32,
    pub is_taxable: bool,
    pub variant_price_paise: i64,
    pub crust_extra_paise: i64,
    pub cheese_extra_paise: i64,
    pub sauce_extra_paise: i64,
    pub standard_toppings_extra_paise: i64,
    pub half_and_half_surcharge_paise: i64,
    pub half_and_half_half_split: bool,
}

// New exported functions
#[uniffi::export]
pub fn pricing_calculate_unit_price_paise(
    variant_price_paise: i64,
    crust_extra_paise: i64,
    cheese_extra_paise: i64,
    sauce_extra_paise: i64,
    standard_toppings_extra_paise: i64,
    half_and_half: Option<FfiHalfAndHalfBreakdown>,
) -> Result<i64, UniffiDomainError> { ... }

#[uniffi::export]
pub fn pricing_validate_half_and_half(
    left_half_base_paise: i64,
    right_half_base_paise: i64,
    surcharge_paise: i64,
) -> Result<FfiHalfAndHalfBreakdown, UniffiDomainError> { ... }

#[uniffi::export]
pub fn cart_validate_with_modifiers(
    items: Vec<FfiCartItemInputExtended>,
    // ... catalog lookup data as FFI records
) -> Result<FfiEvaluatedCartSummary, UniffiDomainError> { ... }
```

### 11.2 Android integration boundaries

| Android Component | Responsibility |
|-------------------|----------------|
| `PizzaBuilderScreen.kt` | Renders supported modifier slots; calls UniFFI for price preview |
| `DomainBridge.kt` (generated) | Wraps new UniFFI exports |
| `CartViewModel` | Sends modifier IDs in `CartItemInputDto` to backend |
| `OrdersViewModel` | Calls reorder endpoint; handles reconciliation dialog |

**No Android changes to:** `DomainBridge` manual code (generated), `PizzaBuilderUiState` (extends with new fields), UniFFI bindings (auto-generated).

---

## 12. Golden pricing parity fixtures and expected results

### 12.1 Test matrix (extend `tools/golden-vectors/pizzora_core_parity.json`)

| Scenario | Inputs | Expected Total (paise) |
|----------|--------|------------------------|
| Base only | REGULAR (10000), no crust, no modifiers, qty=1 | 10000 |
| + Cheese (2000) | + cheese extra_price_paise=2000 | 12000 |
| + Sauce (1500) | + sauce extra_price_paise=1500 | 11500 |
| + Standard topping non-default (1000) | + topping extra_price_paise=1000, is_default=false | 11000 |
| + Standard topping default removed | base includes topping (is_default=true), removed → +1000 | 11000 |
| + Standard topping default kept | base includes topping (is_default=true), kept → +0 | 10000 |
| Half-and-Half | left: variant 12000 + toppings 2000 = 14000<br>right: variant 12000 + toppings 3000 = 15000<br>avg = 14500 + surcharge 1000 = 15500 | 15500 |
| Quantity × 2 | any above × qty=2 | 2 × unit |
| Coupon % on modified | 10% bps on subtotal | HALF_UP discount |
| Delivery + GST | delivery_fee=3000, gst_bps=500 | breakdown matches formula |

### 12.2 Parity test requirements

- **Rust**: `cargo test --workspace --locked` includes new `pricing_modifiers_test.rs`
- **Kotlin**: `GoldenVectorParityExportTest` regenerates vectors; `PricingEngineTest` validates
- **Android**: `PizzaBuilderPricingTest` asserts local preview matches server quote

---

## 13. Backward compatibility and rollback considerations

### 13.1 Additive-only guarantee

| Change | Rollback Impact |
|--------|-----------------|
| New tables (cheeses, sauces, standard_toppings, half_and_half_configs) | `DROP TABLE` — no data loss (empty on rollout) |
| New columns on `cart_items`, `order_items` | `ALTER TABLE ... DROP COLUMN` — safe, no dependent objects |
| New DTO fields (all optional) | Ignored by old clients; old servers ignore unknown fields |
| New UniFFI exports | Old Android clients don't call them; new clients handle `null` gracefully |

### 13.2 Feature flags (server-side)

```rust
// pizzora-core/src/config.rs or similar
pub struct FeatureFlags {
    pub enable_cheese_modifier: bool,
    pub enable_sauce_modifier: bool,
    pub enable_standard_toppings_modifier: bool,
    pub enable_half_and_half_modifier: bool,
    pub enable_reorder_endpoint: bool,
}

// Default: all false until explicit rollout
```

### 13.3 Rollback procedure

1. Disable feature flags → new modifiers hidden from API/UI
2. Deploy old Android client (ignores new DTO fields)
3. If needed: `pizzora-api --migrate-only` does NOT run destructive down-migrations (additive only)
4. Manual `DROP TABLE` / `DROP COLUMN` only after confirmation

---

## 14. Test strategy and acceptance criteria

### 14.1 Rust test matrix

| Test File | Coverage |
|-----------|----------|
| `pricing_modifiers_test.rs` | Unit price with all modifier combos, half-and-half math, overflow |
| `cart_validation_modifiers_test.rs` | Modifier FK validation, availability, half-and-half topping limits |
| `migrator_test.rs` | Preflight/handoff with 51 tables, V4+ migrations applied |
| `golden_parity_tests.rs` | Extended vectors match Kotlin/Android |

### 14.2 Kotlin test matrix

| Test File | Coverage |
|-----------|----------|
| `PricingEngineTest.kt` | New modifier pricing parity with Rust |
| `GoldenVectorParityExportTest.kt` | Regenerates `pizzora_core_parity.json` |
| `CartServiceTest.kt` | Modifier persistence, merge, availability |
| `PricingServiceTest.kt` | Quote with modifiers, min-order, zone |

### 14.3 Android test matrix

| Test File | Coverage |
|-----------|----------|
| `PizzaBuilderPricingTest.kt` | Local preview matches server with modifiers |
| `Phase8ModifiersRobolectricTest.kt` | Slot rendering, half-and-half selector, jump bar |
| `Phase8ReorderRobolectricTest.kt` | Reorder reconstruction, reconciliation dialog |

### 14.4 Acceptance criteria (Phase Exit Gate)

1. **Functional**: All new + existing tests pass (Rust, Kotlin, Android)
2. **Visual**: 5 mandatory Phase 8 captures (Section 13.2 plan) — modifier slots, half-and-half selector, order preview, reorder dialog
3. **Accessibility**: Modifier chips ≥48dp, half-and-half labels ("Left half"/"Right half"), focus trap in reorder dialog
4. **Regression**: 207+ baseline tests green; `DomainBridge` parity 100%; migration preflight passes on fresh + Flyway V1–V3 DBs

---

## 15. Explicit unresolved decisions

| ID | Decision | Status | Notes |
|----|----------|--------|-------|
| U8-01 | **Standard topping `is_default` pricing**: Is a default topping included in `product_variants.price_paise` or tracked separately? | **OPEN** | Current assumption: `price_paise` includes default toppings; removing adds `extra_price_paise`. Requires product team confirmation. |
| U8-02 | **Half-and-half topping source**: Are half-and-half toppings drawn from `toppings` table or `standard_toppings`? | **OPEN** | Current contract uses `toppings` (extra toppings). If standard toppings needed, schema changes. |
| U8-03 | **Cheese/Sauce required?**: Are cheese and sauce mandatory selections per product? | **OPEN** | Current: `isRequired: Boolean` per slot. Default false. Product team to confirm per-product rules. |
| U8-04 | **Half-and-half surcharge model**: Fixed per-config or percentage? | **OPEN** | Current: `half_and_half_surcharge_paise` fixed per config. |
| U8-05 | **Reorder unavailable behavior**: Auto-substitute similar modifier or require manual selection? | **OPEN** | Current: reconciliation dialog with "Add available only" / "Cancel". |
| U8-06 | **Standard topping removal pricing**: If `is_default=true` and removed, is `extra_price_paise` charged? | **OPEN** | Current: yes (treating removal as adding the "absence" cost). Alternative: credit. |

---

## 16. Implementation risk register

| ID | Risk | Severity | Mitigation |
|----|------|----------|------------|
| R8-001 | **Backend contract sign-off pending** | BLOCKER | Cannot start until stakeholder approves this contract |
| R8-002 | **Rust/Kotlin pricing divergence** | HIGH | Golden parity tests + CI gate; mandatory parity before merge |
| R8-003 | **Migration advisory lock contention** | MEDIUM | CI runs migrations on isolated test DBs; production uses `run_locked_handoff` |
| R8-004 | **Kotlin backend query breakage** | HIGH | Update all raw SQL in `PricingService`, `CartService`, `OrderService` atomically with migrations |
| R8-005 | **Historical order reorder ambiguity** | MEDIUM | Clear fallback strategy + reconciliation UX; log unresolved items |
| R8-006 | **Half-and-half pricing complexity** | MEDIUM | Exhaustive unit tests for avg + surcharge; parity vectors for all combos |
| R8-007 | **Android UniFFI binding regeneration** | LOW | Automated in CI `android-prep` stage; verify generated bindings compile |

---

## 17. Sign-off checklist

Before implementation begins, the following must be confirmed:

- [ ] **Stakeholder approves this contract** (D8-01)
- [ ] **Unresolved decisions U8-01 through U8-06 resolved** (or documented as known constraints)
- [ ] **Kotlin backend team confirms migration coordination plan** (D8-03)
- [ ] **Golden parity baseline captured** (current `pizzora_core_parity.json` archived)
- [ ] **CI test DB provisioning verified** for 51-table migration tests
- [ ] **Android team confirms `PizzaBuilderScreen` slot activation readiness**

---

**Document location:** `docs/PHASE8_BACKEND_CONTRACT.md`\
**Next step:** Independent review by `backend`, `test-engineer`, and `reviewer` agents. Implementation gated on user approval of reviewed contract.