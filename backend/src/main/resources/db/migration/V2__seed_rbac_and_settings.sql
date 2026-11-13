-- PIZZORA RBAC, Default Settings & Event Stream Cursor Seed (Flyway V2)

-- 3 Fixed Roles
INSERT INTO roles (id, code) VALUES
    ('00000000-0000-7000-8000-000000000001', 'CUSTOMER'),
    ('00000000-0000-7000-8000-000000000002', 'KITCHEN_STAFF'),
    ('00000000-0000-7000-8000-000000000003', 'OWNER')
ON CONFLICT (code) DO NOTHING;

-- Granular Permissions
INSERT INTO permissions (id, code) VALUES
    ('00000000-0000-7000-8100-000000000001', 'profile:manage'),
    ('00000000-0000-7000-8100-000000000002', 'cart:manage'),
    ('00000000-0000-7000-8100-000000000003', 'checkout:create'),
    ('00000000-0000-7000-8100-000000000004', 'orders:read_own'),
    ('00000000-0000-7000-8100-000000000005', 'orders:cancel_own'),
    ('00000000-0000-7000-8100-000000000006', 'payments:retry_own'),
    ('00000000-0000-7000-8100-000000000007', 'notifications:read_own'),
    ('00000000-0000-7000-8100-000000000008', 'kds:read'),
    ('00000000-0000-7000-8100-000000000009', 'kds:update_status'),
    ('00000000-0000-7000-8100-000000000010', 'kds:prioritize'),
    ('00000000-0000-7000-8100-000000000011', 'owner:dashboard'),
    ('00000000-0000-7000-8100-000000000012', 'owner:catalog_manage'),
    ('00000000-0000-7000-8100-000000000013', 'owner:inventory_manage'),
    ('00000000-0000-7000-8100-000000000014', 'owner:recipes_manage'),
    ('00000000-0000-7000-8100-000000000015', 'owner:coupons_manage'),
    ('00000000-0000-7000-8100-000000000016', 'owner:orders_manage'),
    ('00000000-0000-7000-8100-000000000017', 'owner:refund_execute'),
    ('00000000-0000-7000-8100-000000000018', 'owner:staff_manage'),
    ('00000000-0000-7000-8100-000000000019', 'owner:zones_slots_manage'),
    ('00000000-0000-7000-8100-000000000020', 'owner:settings_manage'),
    ('00000000-0000-7000-8100-000000000021', 'owner:reports_read'),
    ('00000000-0000-7000-8100-000000000022', 'owner:audit_read')
ON CONFLICT (code) DO NOTHING;

-- CUSTOMER permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'profile:manage',
    'cart:manage',
    'checkout:create',
    'orders:read_own',
    'orders:cancel_own',
    'payments:retry_own',
    'notifications:read_own'
)
WHERE r.code = 'CUSTOMER'
ON CONFLICT DO NOTHING;

-- KITCHEN_STAFF permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'kds:read',
    'kds:update_status',
    'kds:prioritize',
    'notifications:read_own'
)
WHERE r.code = 'KITCHEN_STAFF'
ON CONFLICT DO NOTHING;

-- OWNER permissions (full operational + kitchen visibility)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'OWNER'
ON CONFLICT DO NOTHING;

-- Singleton Restaurant Settings (5% GST = 500 bps, Asia/Kolkata)
INSERT INTO restaurant_settings (id, store_name, is_accepting_orders, gst_bps, default_prep_mins, timezone)
VALUES (1, 'PIZZORA Artisanal Kitchen', TRUE, 500, 20, 'Asia/Kolkata')
ON CONFLICT (id) DO NOTHING;

-- Singleton Monotonic Event Stream Cursor
INSERT INTO event_stream_cursor (id, last_seq)
VALUES (1, 0)
ON CONFLICT (id) DO NOTHING;
