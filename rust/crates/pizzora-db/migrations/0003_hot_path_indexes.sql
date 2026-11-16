-- PIZZORA Hot-Path Indexes (Flyway V3)
-- Eliminates sequential scans across Catalog, KDS Board, Reporting, Workers, Cart, and Refunds.

CREATE INDEX IF NOT EXISTS idx_order_items_order_id
    ON order_items (order_id, id);

CREATE INDEX IF NOT EXISTS idx_orders_created_at
    ON orders (created_at);

CREATE INDEX IF NOT EXISTS idx_orders_pending_payment_expires
    ON orders (expires_at ASC)
    WHERE status = 'PENDING_PAYMENT' AND expires_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_orders_kds_active
    ON orders (is_priority DESC, created_at ASC, id ASC)
    WHERE status IN ('PLACED', 'ACCEPTED', 'PREPARING', 'READY', 'OUT_FOR_DELIVERY');

CREATE INDEX IF NOT EXISTS idx_refunds_order_id
    ON refunds (order_id);

CREATE INDEX IF NOT EXISTS idx_refunds_payment_status
    ON refunds (payment_id, status);

CREATE INDEX IF NOT EXISTS idx_inventory_ledger_created
    ON inventory_ledger (created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_broadcast_queue
    ON outbox_events (stream_seq)
    WHERE stream_seq IS NOT NULL AND dispatch_status IN ('PENDING', 'PROCESSING');

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_id
    ON outbox_events (aggregate_id);

CREATE INDEX IF NOT EXISTS idx_product_variants_active_product
    ON product_variants (product_id)
    WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_product_images_active_product
    ON product_images (product_id, sort_order, created_at)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_cart_items_cart_id
    ON cart_items (cart_id);

CREATE INDEX IF NOT EXISTS idx_scheduled_slot_res_slot_status
    ON scheduled_slot_reservations (slot_id, status);
