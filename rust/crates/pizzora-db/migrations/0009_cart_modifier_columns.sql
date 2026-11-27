-- PIZZORA Phase 8: additive modifier columns on cart_items (SQLx V9).
-- All columns are nullable or defaulted so existing rows and old clients remain valid.

ALTER TABLE cart_items
    ADD COLUMN cheese_id UUID NULL REFERENCES cheeses(id) ON DELETE SET NULL,
    ADD COLUMN sauce_id UUID NULL REFERENCES sauces(id) ON DELETE SET NULL,
    ADD COLUMN standard_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN half_and_half_config_id UUID NULL REFERENCES half_and_half_configs(id) ON DELETE SET NULL,
    ADD COLUMN half_and_half_left_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN half_and_half_right_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
