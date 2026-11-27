-- PIZZORA Phase 8: lookup indexes for new tables and modifier columns (SQLx V11).

CREATE INDEX IF NOT EXISTS idx_product_toppings_topping
    ON product_toppings (topping_id);

CREATE INDEX IF NOT EXISTS idx_cheeses_product_active
    ON cheeses (product_id, sort_order) WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_sauces_product_active
    ON sauces (product_id, sort_order) WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_standard_toppings_product_active
    ON standard_toppings (product_id, sort_order) WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_half_and_half_configs_product_active
    ON half_and_half_configs (product_id, sort_order) WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_cart_items_cheese
    ON cart_items (cheese_id) WHERE cheese_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cart_items_sauce
    ON cart_items (sauce_id) WHERE sauce_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cart_items_half_and_half
    ON cart_items (half_and_half_config_id) WHERE half_and_half_config_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cart_items_standard_toppings_gin
    ON cart_items USING GIN (standard_topping_ids);

CREATE INDEX IF NOT EXISTS idx_order_items_cheese
    ON order_items (cheese_id) WHERE cheese_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_order_items_sauce
    ON order_items (sauce_id) WHERE sauce_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_order_items_modifiers_gin
    ON order_items USING GIN (topping_ids, standard_topping_ids,
                              standard_topping_removed_ids,
                              half_and_half_left_topping_ids,
                              half_and_half_right_topping_ids);
