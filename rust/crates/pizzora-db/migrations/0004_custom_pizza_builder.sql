-- PIZZORA Advanced Custom Pizza Builder Schema (SQLx V4)
-- Strictly additive migration preserving V1..V3 Flyway adoption compatibility.

-- 1. Allow EXTRA_LARGE pizza size in product_variants
DO $$
DECLARE
    constraint_name_var TEXT;
BEGIN
    SELECT con.conname
    INTO constraint_name_var
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE nsp.nspname = 'public'
      AND rel.relname = 'product_variants'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) ILIKE '%size_code%';

    IF constraint_name_var IS NOT NULL THEN
        EXECUTE format('ALTER TABLE product_variants DROP CONSTRAINT %I', constraint_name_var);
    END IF;
END $$;

ALTER TABLE product_variants
    ADD CONSTRAINT product_variants_size_code_check
    CHECK (size_code IN ('REGULAR', 'MEDIUM', 'LARGE', 'EXTRA_LARGE'));

-- 2. Configurable size rules (topping limits, half-and-half support, ingredient scaling)
CREATE TABLE IF NOT EXISTS pizza_size_rules (
    size_code TEXT PRIMARY KEY CHECK (size_code IN ('REGULAR', 'MEDIUM', 'LARGE', 'EXTRA_LARGE')),
    display_name TEXT NOT NULL,
    max_toppings INT NOT NULL CHECK (max_toppings >= 0),
    max_extra_toppings INT NOT NULL CHECK (max_extra_toppings >= 0),
    supports_half_and_half BOOLEAN NOT NULL DEFAULT TRUE,
    ingredient_multiplier_bps INT NOT NULL DEFAULT 10000 CHECK (ingredient_multiplier_bps > 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO pizza_size_rules (
    size_code, display_name, max_toppings, max_extra_toppings,
    supports_half_and_half, ingredient_multiplier_bps, is_active
) VALUES
    ('REGULAR', 'Regular', 5, 3, FALSE, 7500, TRUE),
    ('MEDIUM', 'Medium', 7, 4, TRUE, 10000, TRUE),
    ('LARGE', 'Large', 9, 5, TRUE, 13500, TRUE),
    ('EXTRA_LARGE', 'Extra Large', 11, 6, TRUE, 17000, TRUE)
ON CONFLICT (size_code) DO NOTHING;

-- 3. Cheese options & recipes
CREATE TABLE IF NOT EXISTS cheese_options (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    extra_price_paise BIGINT NOT NULL DEFAULT 0 CHECK (extra_price_paise >= 0),
    extra_cheese_price_paise BIGINT NOT NULL DEFAULT 4900 CHECK (extra_cheese_price_paise >= 0),
    supports_extra BOOLEAN NOT NULL DEFAULT TRUE,
    is_vegan BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO cheese_options (
    id, name, extra_price_paise, extra_cheese_price_paise, supports_extra, is_vegan, is_active
) VALUES
    ('00000000-0000-7000-8200-000000000001', 'Mozzarella', 0, 4900, TRUE, FALSE, TRUE),
    ('00000000-0000-7000-8200-000000000002', 'Aged Cheddar & Mozzarella', 3500, 5500, TRUE, FALSE, TRUE),
    ('00000000-0000-7000-8200-000000000003', 'Creamy Burrata Blend', 7500, 6900, TRUE, FALSE, TRUE),
    ('00000000-0000-7000-8200-000000000004', 'Artisan Cashew Mozzarella', 5500, 6500, TRUE, TRUE, TRUE)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS cheese_recipes (
    id UUID PRIMARY KEY,
    cheese_id UUID NOT NULL REFERENCES cheese_options(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    UNIQUE (cheese_id, ingredient_id)
);

-- 4. Sauce options & recipes
CREATE TABLE IF NOT EXISTS sauce_options (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    extra_price_paise BIGINT NOT NULL DEFAULT 0 CHECK (extra_price_paise >= 0),
    extra_sauce_price_paise BIGINT NOT NULL DEFAULT 2500 CHECK (extra_sauce_price_paise >= 0),
    supports_extra BOOLEAN NOT NULL DEFAULT TRUE,
    is_vegan BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO sauce_options (
    id, name, extra_price_paise, extra_sauce_price_paise, supports_extra, is_vegan, is_active
) VALUES
    ('00000000-0000-7000-8300-000000000001', 'Classic San Marzano', 0, 2500, TRUE, TRUE, TRUE),
    ('00000000-0000-7000-8300-000000000002', 'Spicy Arrabbiata', 1500, 2500, TRUE, TRUE, TRUE),
    ('00000000-0000-7000-8300-000000000003', 'Genoa Basil Pesto', 3900, 3500, TRUE, FALSE, TRUE),
    ('00000000-0000-7000-8300-000000000004', 'Roasted Garlic Cream', 2900, 2900, TRUE, FALSE, TRUE)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS sauce_recipes (
    id UUID PRIMARY KEY,
    sauce_id UUID NOT NULL REFERENCES sauce_options(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    UNIQUE (sauce_id, ingredient_id)
);

-- 5. Product, crust, and topping builder metadata
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS is_customizable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS supports_half_and_half BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS default_cheese_id UUID NULL REFERENCES cheese_options(id),
    ADD COLUMN IF NOT EXISTS default_sauce_id UUID NULL REFERENCES sauce_options(id),
    ADD COLUMN IF NOT EXISTS max_toppings_override INT NULL CHECK (max_toppings_override IS NULL OR max_toppings_override >= 0);

CREATE TABLE IF NOT EXISTS product_included_toppings (
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    topping_id UUID NOT NULL REFERENCES toppings(id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, topping_id)
);

ALTER TABLE crust_options
    ADD COLUMN IF NOT EXISTS supports_half_and_half BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE toppings
    ADD COLUMN IF NOT EXISTS max_quantity INT NOT NULL DEFAULT 2 CHECK (max_quantity >= 1),
    ADD COLUMN IF NOT EXISTS supports_half_and_half BOOLEAN NOT NULL DEFAULT TRUE;

-- 6. Persisted cart customization & multi-placement topping selections
ALTER TABLE cart_items
    ADD COLUMN IF NOT EXISTS cheese_id UUID NULL REFERENCES cheese_options(id),
    ADD COLUMN IF NOT EXISTS extra_cheese BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS sauce_id UUID NULL REFERENCES sauce_options(id),
    ADD COLUMN IF NOT EXISTS extra_sauce BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_half_and_half BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS right_product_id UUID NULL REFERENCES products(id),
    ADD COLUMN IF NOT EXISTS right_variant_id UUID NULL REFERENCES product_variants(id),
    ADD COLUMN IF NOT EXISTS right_sauce_id UUID NULL REFERENCES sauce_options(id),
    ADD COLUMN IF NOT EXISTS removed_included_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS configuration_signature TEXT NOT NULL DEFAULT '';

ALTER TABLE cart_item_toppings
    ADD COLUMN IF NOT EXISTS placement TEXT NOT NULL DEFAULT 'WHOLE'
        CHECK (placement IN ('WHOLE', 'LEFT', 'RIGHT')),
    ADD COLUMN IF NOT EXISTS quantity INT NOT NULL DEFAULT 1
        CHECK (quantity >= 1);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'cart_item_toppings'::regclass
          AND contype = 'p'
    ) THEN
        ALTER TABLE cart_item_toppings DROP CONSTRAINT cart_item_toppings_pkey;
    END IF;
END $$;

ALTER TABLE cart_item_toppings
    ADD PRIMARY KEY (cart_item_id, topping_id, placement);

-- 7. Order item immutable customization & price breakdown snapshots
ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS customization_snapshot JSONB NULL,
    ADD COLUMN IF NOT EXISTS price_breakdown_snapshot JSONB NULL;
