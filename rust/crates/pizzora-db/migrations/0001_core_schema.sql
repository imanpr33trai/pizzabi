-- PIZZORA Core PostgreSQL Schema (Flyway V1)
-- Strict topological creation order: all 46 tables follow their referenced FK targets.

-- ============================================================================
-- Group A: Identity, Verification, RBAC & Addresses (Tables 1 - 11)
-- ============================================================================

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE CHECK (code IN ('CUSTOMER', 'KITCHEN_STAFF', 'OWNER'))
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE
);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    email_verified_at TIMESTAMPTZ NULL,
    password_hash TEXT NOT NULL,
    full_name TEXT NOT NULL,
    phone TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE staff (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id),
    employee_code TEXT NOT NULL UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    ip_address TEXT NULL,
    revoked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_user_sessions_user_revoked ON user_sessions (user_id, revoked_at);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES user_sessions(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ws_tickets (
    ticket_hash TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_code TEXT NOT NULL CHECK (role_code IN ('CUSTOMER', 'KITCHEN_STAFF', 'OWNER')),
    session_id UUID NOT NULL REFERENCES user_sessions(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ws_tickets_unconsumed_expires ON ws_tickets (expires_at) WHERE consumed_at IS NULL;

CREATE TABLE addresses (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label TEXT NOT NULL,
    line1 TEXT NOT NULL,
    line2 TEXT NULL,
    landmark TEXT NULL,
    pincode TEXT NOT NULL,
    city TEXT NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_addresses_user ON addresses (user_id);

-- ============================================================================
-- Group B: Catalog, Modifiers & Images (Tables 12 - 17)
-- ============================================================================

CREATE TABLE categories (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE products (
    id UUID PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES categories(id),
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    is_veg BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_taxable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_products_category_active ON products (category_id, is_active);

CREATE TABLE product_variants (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    size_code TEXT NOT NULL CHECK (size_code IN ('REGULAR', 'MEDIUM', 'LARGE')),
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (product_id, size_code)
);

CREATE TABLE crust_options (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    extra_price_paise BIGINT NOT NULL CHECK (extra_price_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE toppings (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    is_veg BOOLEAN NOT NULL DEFAULT TRUE,
    extra_price_paise BIGINT NOT NULL CHECK (extra_price_paise >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE product_images (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    object_key TEXT NOT NULL UNIQUE,
    mime_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0 AND size_bytes <= 5242880),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'DELETED')),
    sort_order INT NOT NULL DEFAULT 0,
    uploaded_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- Group C: Ingredients, Inventory & Enforceable Relational Recipes (Tables 18 - 22)
-- ============================================================================

CREATE TABLE ingredients (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    unit TEXT NOT NULL CHECK (unit IN ('GRAMS', 'ML', 'UNITS')),
    low_stock_threshold BIGINT NOT NULL CHECK (low_stock_threshold >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE inventory (
    ingredient_id UUID PRIMARY KEY REFERENCES ingredients(id),
    on_hand_qty BIGINT NOT NULL CHECK (on_hand_qty >= 0),
    reserved_qty BIGINT NOT NULL CHECK (reserved_qty >= 0),
    available_qty BIGINT GENERATED ALWAYS AS (on_hand_qty - reserved_qty) STORED,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (reserved_qty <= on_hand_qty)
);

CREATE TABLE variant_recipes (
    id UUID PRIMARY KEY,
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    UNIQUE (variant_id, ingredient_id)
);

CREATE TABLE crust_recipes (
    id UUID PRIMARY KEY,
    crust_id UUID NOT NULL REFERENCES crust_options(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    UNIQUE (crust_id, ingredient_id)
);

CREATE TABLE topping_recipes (
    id UUID PRIMARY KEY,
    topping_id UUID NOT NULL REFERENCES toppings(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    UNIQUE (topping_id, ingredient_id)
);

-- ============================================================================
-- Group D: Carts with Relational Toppings (Tables 23 - 25)
-- ============================================================================

CREATE TABLE carts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE cart_items (
    id UUID PRIMARY KEY,
    cart_id UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id),
    variant_id UUID NOT NULL REFERENCES product_variants(id),
    crust_id UUID NOT NULL REFERENCES crust_options(id),
    quantity INT NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE cart_item_toppings (
    cart_item_id UUID NOT NULL REFERENCES cart_items(id) ON DELETE CASCADE,
    topping_id UUID NOT NULL REFERENCES toppings(id) ON DELETE RESTRICT,
    PRIMARY KEY (cart_item_id, topping_id)
);

-- ============================================================================
-- Group E: Coupons, Zones, Slots & Settings (Tables 26 - 29)
-- ============================================================================

CREATE TABLE coupons (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    discount_type TEXT NOT NULL CHECK (discount_type IN ('PERCENT_BPS', 'FLAT_PAISE')),
    discount_value BIGINT NOT NULL CHECK (discount_value > 0),
    max_discount_paise BIGINT NULL CHECK (max_discount_paise IS NULL OR max_discount_paise > 0),
    min_order_paise BIGINT NOT NULL CHECK (min_order_paise >= 0),
    max_uses INT NULL CHECK (max_uses IS NULL OR max_uses > 0),
    max_uses_per_user INT NOT NULL DEFAULT 1 CHECK (max_uses_per_user >= 1),
    usage_count INT NOT NULL DEFAULT 0 CHECK (usage_count >= 0),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE delivery_zones (
    id UUID PRIMARY KEY,
    pincode TEXT NOT NULL UNIQUE,
    area_name TEXT NOT NULL,
    min_order_paise BIGINT NOT NULL CHECK (min_order_paise >= 0),
    delivery_fee_paise BIGINT NOT NULL CHECK (delivery_fee_paise >= 0),
    estimated_mins INT NOT NULL CHECK (estimated_mins > 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE scheduled_slots (
    id UUID PRIMARY KEY,
    slot_date DATE NOT NULL,
    fulfillment_type TEXT NOT NULL CHECK (fulfillment_type IN ('DELIVERY', 'PICKUP')),
    start_time_ist TIME NOT NULL,
    end_time_ist TIME NOT NULL,
    cutoff_at TIMESTAMPTZ NOT NULL,
    max_capacity INT NOT NULL CHECK (max_capacity > 0),
    reserved_count INT NOT NULL DEFAULT 0 CHECK (reserved_count >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (reserved_count <= max_capacity),
    UNIQUE (slot_date, fulfillment_type, start_time_ist)
);

CREATE TABLE restaurant_settings (
    id INT PRIMARY KEY CHECK (id = 1),
    store_name TEXT NOT NULL,
    is_accepting_orders BOOLEAN NOT NULL DEFAULT TRUE,
    gst_bps INT NOT NULL CHECK (gst_bps BETWEEN 0 AND 2800),
    default_prep_mins INT NOT NULL CHECK (default_prep_mins > 0),
    timezone TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- Group F: Orders (Parent Table Created Before Reservation/Payment/Ledger Tables) (Tables 30 - 32)
-- ============================================================================

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    order_number TEXT NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES users(id),
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    fulfillment_type TEXT NOT NULL CHECK (fulfillment_type IN ('DELIVERY', 'PICKUP')),
    schedule_type TEXT NOT NULL CHECK (schedule_type IN ('ASAP', 'SCHEDULED')),
    scheduled_slot_id UUID NULL REFERENCES scheduled_slots(id),
    delivery_address_snapshot JSONB NULL,
    status TEXT NOT NULL CHECK (status IN (
        'PENDING_PAYMENT', 'PLACED', 'ACCEPTED', 'PREPARING',
        'READY', 'OUT_FOR_DELIVERY', 'COMPLETED', 'CANCELLED', 'EXPIRED'
    )),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    subtotal_paise BIGINT NOT NULL CHECK (subtotal_paise >= 0),
    discount_paise BIGINT NOT NULL CHECK (discount_paise >= 0),
    delivery_fee_paise BIGINT NOT NULL CHECK (delivery_fee_paise >= 0),
    tax_paise BIGINT NOT NULL CHECK (tax_paise >= 0),
    total_paise BIGINT NOT NULL CHECK (total_paise >= 0),
    gst_bps_snapshot INT NOT NULL CHECK (gst_bps_snapshot BETWEEN 0 AND 2800),
    coupon_code_snapshot TEXT NULL,
    is_priority BOOLEAN NOT NULL DEFAULT FALSE,
    accepted_at TIMESTAMPTZ NULL,
    prep_started_at TIMESTAMPTZ NULL,
    ready_at TIMESTAMPTZ NULL,
    completed_at TIMESTAMPTZ NULL,
    expires_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX idx_orders_status_created ON orders (status, created_at);
CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at DESC);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL,
    product_name_snapshot TEXT NOT NULL,
    variant_size_snapshot TEXT NOT NULL,
    crust_name_snapshot TEXT NOT NULL,
    toppings_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb,
    unit_price_paise BIGINT NOT NULL CHECK (unit_price_paise >= 0),
    quantity INT NOT NULL CHECK (quantity > 0),
    line_total_paise BIGINT NOT NULL CHECK (line_total_paise >= 0)
);

CREATE TABLE order_status_history (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status TEXT NULL,
    to_status TEXT NOT NULL,
    changed_by UUID NULL REFERENCES users(id),
    reason TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_order_status_history_order ON order_status_history (order_id, created_at);

-- ============================================================================
-- Group G: Reservations & Inventory Ledger (Tables 33 - 37)
-- ============================================================================

CREATE TABLE inventory_reservations (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED', 'EXPIRED', 'RESTORED')),
    expires_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (order_id, ingredient_id)
);
CREATE INDEX idx_inventory_reservations_active_expires ON inventory_reservations (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE inventory_ledger (
    id UUID PRIMARY KEY,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id),
    order_id UUID NULL REFERENCES orders(id),
    operation_id TEXT NOT NULL UNIQUE,
    delta_on_hand BIGINT NOT NULL,
    delta_reserved BIGINT NOT NULL,
    reason TEXT NOT NULL CHECK (reason IN (
        'RESTOCK', 'RESERVE', 'COMMIT', 'RELEASE', 'EXPIRE_RELEASE', 'CANCEL_RESTORE', 'MANUAL_ADJUST'
    )),
    actor_user_id UUID NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_inventory_ledger_ingredient_created ON inventory_ledger (ingredient_id, created_at DESC);

CREATE TABLE coupon_reservations (
    id UUID PRIMARY KEY,
    coupon_id UUID NOT NULL REFERENCES coupons(id),
    user_id UUID NOT NULL REFERENCES users(id),
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    discount_paise BIGINT NOT NULL CHECK (discount_paise >= 0),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_coupon_reservations_lookup ON coupon_reservations (coupon_id, user_id, status);

CREATE TABLE coupon_redemptions (
    id UUID PRIMARY KEY,
    coupon_id UUID NOT NULL REFERENCES coupons(id),
    user_id UUID NOT NULL REFERENCES users(id),
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
    discount_paise BIGINT NOT NULL CHECK (discount_paise >= 0),
    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE scheduled_slot_reservations (
    id UUID PRIMARY KEY,
    slot_id UUID NOT NULL REFERENCES scheduled_slots(id),
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_scheduled_slot_reservations_active_expires ON scheduled_slot_reservations (expires_at) WHERE status = 'ACTIVE';

-- ============================================================================
-- Group H: Payments, Payment Attempts, Events & Refunds (Tables 38 - 41)
-- ============================================================================

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
    method TEXT NOT NULL CHECK (method IN ('RAZORPAY', 'COD')),
    status TEXT NOT NULL CHECK (status IN (
        'UNPAID', 'COD_PENDING', 'COD_COLLECTED', 'CAPTURED',
        'EXPIRED', 'CANCELLED', 'REFUND_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED'
    )),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    captured_paise BIGINT NOT NULL DEFAULT 0 CHECK (captured_paise >= 0),
    refunded_paise BIGINT NOT NULL DEFAULT 0 CHECK (refunded_paise >= 0 AND refunded_paise <= captured_paise),
    currency TEXT NOT NULL DEFAULT 'INR',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL CHECK (attempt_number > 0),
    method TEXT NOT NULL CHECK (method IN ('RAZORPAY', 'COD')),
    status TEXT NOT NULL CHECK (status IN (
        'INITIATING', 'AWAITING_CUSTOMER_PAYMENT', 'AUTHORIZED',
        'CAPTURED', 'FAILED', 'EXPIRED', 'CANCELLED'
    )),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    idempotency_receipt TEXT NOT NULL UNIQUE,
    gateway_order_id TEXT NULL UNIQUE,
    gateway_payment_id TEXT NULL UNIQUE,
    failure_code TEXT NULL,
    failure_reason TEXT NULL,
    collected_by_user_id UUID NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (payment_id, attempt_number)
);
CREATE INDEX idx_payment_attempts_open_expires
    ON payment_attempts (expires_at)
    WHERE status IN ('INITIATING', 'AWAITING_CUSTOMER_PAYMENT');

CREATE TABLE payment_events (
    id UUID PRIMARY KEY,
    payment_id UUID NULL REFERENCES payments(id),
    payment_attempt_id UUID NULL REFERENCES payment_attempts(id),
    source TEXT NOT NULL CHECK (source IN ('CLIENT_VERIFY', 'WEBHOOK', 'RECONCILER', 'OUTBOX_WORKER')),
    gateway_event_id TEXT NOT NULL UNIQUE,
    event_type TEXT NOT NULL,
    payload_json JSONB NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id),
    payment_attempt_id UUID NULL REFERENCES payment_attempts(id),
    order_id UUID NOT NULL REFERENCES orders(id),
    idempotency_key TEXT NOT NULL UNIQUE,
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    gateway_refund_id TEXT NULL UNIQUE,
    reason TEXT NOT NULL,
    initiated_by UUID NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- Group I: Commit-Ordered Event Stream, Notifications & Audit (Tables 42 - 46)
-- ============================================================================

CREATE TABLE event_stream_cursor (
    id INT PRIMARY KEY CHECK (id = 1),
    last_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_seq >= 0)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL DEFAULT 1 CHECK (aggregate_version >= 1),
    event_type TEXT NOT NULL,
    payload_json JSONB NOT NULL,
    stream_seq BIGINT NULL UNIQUE,
    sequenced_at TIMESTAMPTZ NULL,
    dispatch_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (dispatch_status IN ('PENDING', 'PROCESSING', 'DISPATCHED', 'DEAD_LETTER')),
    published_at TIMESTAMPTZ NULL,
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (
        (dispatch_status = 'DISPATCHED' AND published_at IS NOT NULL)
        OR (dispatch_status <> 'DISPATCHED' AND published_at IS NULL)
    )
);
CREATE INDEX idx_outbox_unsequenced ON outbox_events (created_at, id) WHERE stream_seq IS NULL;
CREATE INDEX idx_outbox_stream_seq ON outbox_events (stream_seq) WHERE stream_seq IS NOT NULL;
CREATE INDEX idx_outbox_dispatch_queue
    ON outbox_events (dispatch_status, next_attempt_at)
    WHERE dispatch_status IN ('PENDING', 'PROCESSING');

CREATE TABLE notification_inbox (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_id UUID NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    category TEXT NOT NULL,
    reference_id UUID NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    delivery_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (delivery_status IN ('PENDING', 'SENT_WS', 'SYNCED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, event_id)
);
CREATE INDEX idx_notification_inbox_user_unread ON notification_inbox (user_id, is_read, created_at DESC);

CREATE TABLE notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    order_updates BOOLEAN NOT NULL DEFAULT TRUE,
    promo_alerts BOOLEAN NOT NULL DEFAULT TRUE,
    kds_sound_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_user_id UUID NULL REFERENCES users(id),
    actor_role TEXT NOT NULL,
    action TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    before_json JSONB NULL,
    after_json JSONB NULL,
    correlation_id TEXT NOT NULL,
    ip_address TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_logs_created ON audit_logs (created_at DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
