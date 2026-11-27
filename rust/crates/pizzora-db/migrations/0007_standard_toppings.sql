-- PIZZORA Phase 8: per-product standard topping options (SQLx V7).
-- `is_default = true` toppings are included in product_variants.price_paise.
-- Removing a default topping produces zero credit and zero charge.
-- Selecting an `is_default = false` topping adds its extra_price_paise.

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
