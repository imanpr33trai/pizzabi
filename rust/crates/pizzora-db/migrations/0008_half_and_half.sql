-- PIZZORA Phase 8: per-product half-and-half configurations (SQLx V8).
-- Topping IDs are drawn from the global `toppings` table and validated against
-- `product_toppings` for the config's product. Surcharge is a fixed paise amount.

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
