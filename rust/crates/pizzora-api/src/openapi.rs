use serde_json::{json, Value};

pub fn build_openapi_document(server_url: &str) -> Value {
    let server = if server_url.trim().is_empty() {
        "/"
    } else {
        server_url
    };
    json!({
        "openapi": "3.1.0",
        "info": {
            "title": "PIZZORA Platform API",
            "version": "1.0.0",
            "description": "Firebase-free self-hosted Rust Axum REST & WebSocket API for PIZZORA customer ordering, Kitchen Display System (KDS), and Owner operations."
        },
        "servers": [{ "url": server }],
        "components": {
            "securitySchemes": {
                "BearerAuth": {
                    "type": "http",
                    "scheme": "bearer",
                    "bearerFormat": "JWT"
                }
            },
            "schemas": {
                "ApiErrorEnvelope": {
                    "type": "object",
                    "required": ["error", "correlation_id", "timestamp"],
                    "properties": {
                        "error": {
                            "type": "object",
                            "properties": {
                                "code": { "type": "string" },
                                "message": { "type": "string" },
                                "details": { "type": "object" }
                            }
                        },
                        "correlation_id": { "type": "string" },
                        "timestamp": { "type": "string" }
                    }
                },
                "HealthReadinessResponse": {
                    "type": "object",
                    "required": ["status", "dependencies", "service"],
                    "properties": {
                        "status": {
                            "type": "string",
                            "enum": ["HEALTHY", "DEGRADED", "UNHEALTHY"]
                        },
                        "dependencies": { "type": "object" },
                        "service": { "type": "string" }
                    }
                }
            }
        },
        "paths": {
            "/api/v1/health/live": {
                "get": { "summary": "API process liveness probe", "tags": ["Health"] }
            },
            "/api/v1/health/ready": {
                "get": { "summary": "Dependency readiness probe (Postgres required; Redis & MinIO optional unless REDIS_REQUIRED=true)", "tags": ["Health"] }
            },
            "/api/v1/openapi.json": {
                "get": { "summary": "OpenAPI 3.1 specification document", "tags": ["Docs"] }
            },
            "/metrics": {
                "get": { "summary": "Prometheus text exposition metrics", "tags": ["Ops"] }
            },
            "/api/v1/auth/register": { "post": { "summary": "Register customer account and queue verification email", "tags": ["Auth"] } },
            "/api/v1/auth/verify-email": { "post": { "summary": "Verify email address using single-use token", "tags": ["Auth"] } },
            "/api/v1/auth/resend-verification": { "post": { "summary": "Resend email verification link", "tags": ["Auth"] } },
            "/api/v1/auth/login": { "post": { "summary": "Authenticate user with email & password", "tags": ["Auth"] } },
            "/api/v1/auth/refresh": { "post": { "summary": "Rotate refresh token and mint fresh JWT access token", "tags": ["Auth"] } },
            "/api/v1/auth/forgot-password": { "post": { "summary": "Request password reset token via outbox email", "tags": ["Auth"] } },
            "/api/v1/auth/reset-password": { "post": { "summary": "Reset password using single-use token", "tags": ["Auth"] } },
            "/api/v1/auth/logout": { "post": { "summary": "Revoke active session", "tags": ["Auth"] } },
            "/api/v1/auth/ws-ticket": { "post": { "summary": "Mint single-use short-lived WebSocket connection ticket", "tags": ["Auth"] } },
            "/api/v1/me": {
                "get": { "summary": "Get authenticated user profile", "tags": ["Users"] },
                "patch": { "summary": "Update authenticated user profile", "tags": ["Users"] }
            },
            "/api/v1/me/addresses": {
                "get": { "summary": "List authenticated customer delivery addresses", "tags": ["Users"] },
                "post": { "summary": "Create delivery address", "tags": ["Users"] }
            },
            "/api/v1/owner/staff": {
                "get": { "summary": "List staff accounts (Owner)", "tags": ["Owner"] },
                "post": { "summary": "Create kitchen or owner staff user (Owner)", "tags": ["Owner"] }
            },
            "/api/v1/catalog/categories": {
                "get": { "summary": "List active menu categories", "tags": ["Catalog"] }
            },
            "/api/v1/catalog/menu": {
                "get": { "summary": "Fetch full active public pizza menu, variants, crusts, toppings, and active images", "tags": ["Catalog"] }
            },
            "/api/v1/owner/categories": {
                "get": { "summary": "List all categories (Owner)", "tags": ["Owner"] },
                "post": { "summary": "Create category (Owner)", "tags": ["Owner"] }
            },
            "/api/v1/owner/products": {
                "get": { "summary": "List all products and variants (Owner)", "tags": ["Owner"] },
                "post": { "summary": "Create product and size variants atomically (Owner)", "tags": ["Owner"] }
            },
            "/api/v1/owner/products/{id}/images/presign": {
                "post": { "summary": "Generate S3/MinIO presigned PUT URL for product image upload (Owner)", "tags": ["Images"] }
            },
            "/api/v1/owner/products/{id}/images/confirm": {
                "post": { "summary": "Verify uploaded object via S3 HEAD + magic bytes and activate image (Owner)", "tags": ["Images"] }
            },
            "/api/v1/cart": {
                "get": { "summary": "Fetch authenticated customer relational cart with live catalog validation", "tags": ["Cart"] },
                "put": { "summary": "Replace authenticated customer cart items and toppings", "tags": ["Cart"] }
            },
            "/api/v1/cart/validate": {
                "post": { "summary": "Validate local offline cart items against server catalog and prices", "tags": ["Cart"] }
            },
            "/api/v1/coupons/available": {
                "get": { "summary": "List active non-expired coupons with customer usage limits and eligibility", "tags": ["Coupons"] }
            },
            "/api/v1/pricing/quote": {
                "post": { "summary": "Compute authoritative integer-paise price breakdown", "tags": ["Pricing"] }
            },
            "/api/v1/owner/coupons": {
                "get": { "summary": "List all coupons (Owner)", "tags": ["Owner"] },
                "post": { "summary": "Create promotional coupon (Owner)", "tags": ["Owner"] }
            },
            "/api/v1/owner/inventory": {
                "get": { "summary": "List all ingredients with on-hand, reserved, and available stock (Owner)", "tags": ["Inventory"] },
                "post": { "summary": "Create ingredient with initial stock and ledger entry (Owner)", "tags": ["Inventory"] }
            },
            "/api/v1/owner/inventory/adjust": {
                "post": { "summary": "Idempotently restock or adjust ingredient stock (Owner)", "tags": ["Inventory"] }
            },
            "/api/v1/owner/inventory/ledger": {
                "get": { "summary": "List immutable append-only inventory ledger movements (Owner)", "tags": ["Inventory"] }
            },
            "/api/v1/owner/recipes": {
                "get": { "summary": "List relational ingredient recipes for variants, crusts, and toppings (Owner)", "tags": ["Recipes"] },
                "put": { "summary": "Replace relational ingredient recipe for a variant, crust, or topping (Owner)", "tags": ["Recipes"] }
            },
            "/api/v1/checkout": {
                "post": { "summary": "Create transactional order, reservations, payment attempt, and outbox event (requires Idempotency-Key)", "tags": ["Checkout"] }
            },
            "/api/v1/orders": { "get": { "summary": "List customer order history", "tags": ["Orders"] } },
            "/api/v1/orders/{id}": { "get": { "summary": "Get detailed order breakdown", "tags": ["Orders"] } },
            "/api/v1/orders/{id}/cancel": { "post": { "summary": "Cancel order with atomic stock/slot restoration and refund enqueueing", "tags": ["Orders"] } },
            "/api/v1/orders/{id}/payment": { "get": { "summary": "Poll order payment status", "tags": ["Payments"] } },
            "/api/v1/orders/{id}/payments/retry": { "post": { "summary": "Create a new payment attempt", "tags": ["Payments"] } },
            "/api/v1/orders/{id}/payments/fail": { "post": { "summary": "Report failed Razorpay attempt", "tags": ["Payments"] } },
            "/api/v1/payments/razorpay/verify": { "post": { "summary": "Verify Razorpay HMAC-SHA256 checkout signature", "tags": ["Payments"] } },
            "/api/v1/webhooks/razorpay": { "post": { "summary": "Handle Razorpay signed webhooks", "tags": ["Webhooks"] } },
            "/api/v1/owner/orders/{id}/refund": { "post": { "summary": "Initiate or retry full/partial Razorpay refund (Owner)", "tags": ["Owner"] } },
            "/api/v1/owner/orders/{id}/status": { "patch": { "summary": "Transition order lifecycle status (Owner)", "tags": ["Owner"] } },
            "/api/v1/kds/tickets": { "get": { "summary": "List active kitchen display tickets", "tags": ["KDS"] } },
            "/api/v1/kds/orders/{id}/ack": { "post": { "summary": "Acknowledge placed order", "tags": ["KDS"] } },
            "/api/v1/kds/orders/{id}/start-prep": { "post": { "summary": "Start oven preparation", "tags": ["KDS"] } },
            "/api/v1/kds/orders/{id}/ready": { "post": { "summary": "Mark order ready", "tags": ["KDS"] } },
            "/api/v1/kds/orders/{id}/dispatch": { "post": { "summary": "Dispatch delivery order", "tags": ["KDS"] } },
            "/api/v1/kds/orders/{id}/complete": { "post": { "summary": "Complete order and settle COD", "tags": ["KDS"] } },
            "/api/v1/kds/orders/{id}/priority": { "patch": { "summary": "Toggle expedited kitchen priority flag", "tags": ["KDS"] } },
            "/api/v1/config/public": { "get": { "summary": "Public store status, name, GST bps, and prep SLA", "tags": ["Config"] } },
            "/api/v1/owner/dashboard": { "get": { "summary": "Owner KPI dashboard", "tags": ["Owner"] } },
            "/api/v1/owner/settings": {
                "get": { "summary": "Get store & GST settings (Owner)", "tags": ["Owner"] },
                "put": { "summary": "Update store settings (Owner)", "tags": ["Owner"] }
            },
            "/api/v1/owner/orders": { "get": { "summary": "List all orders across customers (Owner)", "tags": ["Owner"] } },
            "/api/v1/events/replay": { "get": { "summary": "Replay room-authorized sequenced realtime events since stream_seq", "tags": ["Realtime"] } },
            "/ws/v1/events": { "get": { "summary": "WebSocket event stream authenticated via single-use ws-ticket or Bearer JWT", "tags": ["Realtime"] } },
            "/api/v1/delivery-zones/check": { "get": { "summary": "Check pincode delivery serviceability", "tags": ["Zones"] } },
            "/api/v1/slots": { "get": { "summary": "List active scheduled time slots for an Asia/Kolkata date", "tags": ["Slots"] } },
            "/api/v1/owner/delivery-zones": {
                "get": { "summary": "List all delivery zones (Owner)", "tags": ["Owner"] },
                "post": { "summary": "Create delivery zone (Owner)", "tags": ["Owner"] }
            },
            "/api/v1/owner/slots": {
                "get": { "summary": "List scheduled slots with capacity usage (Owner)", "tags": ["Owner"] },
                "post": { "summary": "Create Asia/Kolkata scheduled time slot (Owner)", "tags": ["Owner"] }
            },
            "/api/v1/notifications": { "get": { "summary": "List user notification inbox", "tags": ["Notifications"] } },
            "/api/v1/notifications/{id}/read": { "post": { "summary": "Mark notification as read", "tags": ["Notifications"] } },
            "/api/v1/notifications/read-all": { "post": { "summary": "Mark all notifications as read", "tags": ["Notifications"] } },
            "/api/v1/notifications/preferences": {
                "get": { "summary": "Fetch notification preferences", "tags": ["Notifications"] },
                "put": { "summary": "Update notification preferences", "tags": ["Notifications"] }
            },
            "/api/v1/owner/reports/sales": { "get": { "summary": "Asia/Kolkata range sales and GST report (Owner)", "tags": ["Reports"] } },
            "/api/v1/owner/reports/operations": { "get": { "summary": "Asia/Kolkata range operational SLA and stock report (Owner)", "tags": ["Reports"] } },
            "/api/v1/owner/audit-logs": { "get": { "summary": "Paginated immutable audit trail (Owner)", "tags": ["Audit"] } }
        }
    })
}
