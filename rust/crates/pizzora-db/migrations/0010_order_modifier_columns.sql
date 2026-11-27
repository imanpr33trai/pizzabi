-- PIZZORA Phase 8: additive modifier columns on order_items (SQLx V10).
-- No FKs on modifier IDs: historical orders must survive catalog deletion/deactivation.
-- The existing `toppings_snapshot`, `variant_size_snapshot`, and `crust_name_snapshot`
-- columns are unchanged and remain authoritative for historical display.

ALTER TABLE order_items
    ADD COLUMN variant_id UUID NULL,
    ADD COLUMN crust_id UUID NULL,
    ADD COLUMN cheese_id UUID NULL,
    ADD COLUMN sauce_id UUID NULL,
    ADD COLUMN topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN standard_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN standard_topping_removed_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN half_and_half_config_id UUID NULL,
    ADD COLUMN half_and_half_left_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN half_and_half_right_topping_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN modifiers_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;
