-- PIZZORA Phase 8: per-product cheese options (SQLx V5).

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
