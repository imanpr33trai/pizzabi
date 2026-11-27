-- PIZZORA Phase 8: product x topping availability join table (SQLx V4).
-- The global `toppings` table is the source of topping metadata; this join table is
-- the authority for which toppings are available on a given product. Existing
-- behavior (all toppings available on all products) is preserved by seeding the
-- cross-product. No V1-V3 file is modified.

CREATE TABLE product_toppings (
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    topping_id UUID NOT NULL REFERENCES toppings(id) ON DELETE CASCADE,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, topping_id)
);

-- Preserve current catalog behavior: every topping remains selectable on every
-- product until per-product availability is explicitly configured.
INSERT INTO product_toppings (product_id, topping_id, sort_order)
SELECT p.id, t.id, 0
FROM products p
CROSS JOIN toppings t;
