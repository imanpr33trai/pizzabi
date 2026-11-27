use axum::extract::DefaultBodyLimit;
use axum::http::{header, HeaderName, Method, StatusCode};
use axum::middleware as axum_mw;
use axum::response::Response;
use axum::routing::{delete, get, patch, post};
use axum::Router;
use tower_http::cors::{Any, CorsLayer};

use crate::error::ApiError;
use crate::extractors::RequestMeta;
use crate::middleware::{
    body_size_limit_middleware, correlation_id_middleware, metrics_and_logging_middleware,
    rate_limit_middleware, security_headers_middleware,
};
use crate::routes::{
    auth, catalog, commerce, kitchen, ops, orders_payments, owner_ops, realtime, users,
};
use crate::state::AppState;

async fn json_not_found(meta: RequestMeta) -> Response {
    ApiError::not_found("NOT_FOUND", "The requested endpoint was not found.")
        .into_response_with_correlation(&meta.correlation_id)
}

async fn json_method_not_allowed(meta: RequestMeta) -> Response {
    ApiError::new(
        StatusCode::METHOD_NOT_ALLOWED,
        "METHOD_NOT_ALLOWED",
        "HTTP method is not allowed for this endpoint.",
    )
    .into_response_with_correlation(&meta.correlation_id)
}

pub fn build_router(state: AppState) -> Router {
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods([
            Method::GET,
            Method::POST,
            Method::PUT,
            Method::PATCH,
            Method::DELETE,
            Method::OPTIONS,
        ])
        .allow_headers([
            header::AUTHORIZATION,
            header::CONTENT_TYPE,
            header::ACCEPT,
            HeaderName::from_static("idempotency-key"),
            HeaderName::from_static("x-request-id"),
        ]);

    Router::new()
        // Operational & Discovery
        .route("/api/v1/health/live", get(ops::health_live))
        .route("/api/v1/health/ready", get(ops::health_ready))
        .route("/api/v1/openapi.json", get(ops::openapi_json))
        .route("/metrics", get(ops::metrics_scrape))
        // Authentication & Session Management
        .route("/api/v1/auth/register", post(auth::register))
        .route("/api/v1/auth/verify-email", post(auth::verify_email))
        .route(
            "/api/v1/auth/resend-verification",
            post(auth::resend_verification),
        )
        .route("/api/v1/auth/login", post(auth::login))
        .route("/api/v1/auth/refresh", post(auth::refresh))
        .route("/api/v1/auth/forgot-password", post(auth::forgot_password))
        .route("/api/v1/auth/reset-password", post(auth::reset_password))
        .route("/api/v1/auth/logout", post(auth::logout))
        .route("/api/v1/auth/ws-ticket", post(auth::mint_ws_ticket))
        // Customer Profile, Addresses, and Owner Staff
        .route("/api/v1/me", get(users::get_me).patch(users::update_me))
        .route(
            "/api/v1/me/addresses",
            get(users::list_my_addresses).post(users::create_my_address),
        )
        .route(
            "/api/v1/me/addresses/{id}",
            patch(users::update_my_address).delete(users::delete_my_address),
        )
        .route(
            "/api/v1/owner/staff",
            get(users::list_staff).post(users::create_staff),
        )
        .route("/api/v1/owner/staff/{userId}", patch(users::update_staff))
        // Public Storefront & Discovery
        .route("/api/v1/config/public", get(owner_ops::get_public_config))
        .route(
            "/api/v1/delivery-zones/check",
            get(owner_ops::check_delivery_zone),
        )
        .route("/api/v1/slots", get(owner_ops::list_public_slots))
        .route(
            "/api/v1/catalog/categories",
            get(catalog::list_public_categories),
        )
        .route("/api/v1/catalog/menu", get(catalog::get_public_menu))
        // Cart, Coupons, Pricing & Checkout
        .route(
            "/api/v1/cart",
            get(commerce::get_cart).put(commerce::replace_cart),
        )
        .route("/api/v1/cart/validate", post(commerce::validate_cart))
        .route(
            "/api/v1/coupons/available",
            get(commerce::list_available_coupons),
        )
        .route("/api/v1/pricing/quote", post(commerce::quote_pricing))
        .route("/api/v1/checkout", post(commerce::checkout))
        // Customer Orders & Payments & Webhooks
        .route("/api/v1/orders", get(orders_payments::list_my_orders))
        .route("/api/v1/orders/{id}", get(orders_payments::get_my_order))
        .route(
            "/api/v1/orders/{id}/cancel",
            post(orders_payments::cancel_my_order),
        )
        .route(
            "/api/v1/orders/{id}/payment",
            get(orders_payments::get_order_payment),
        )
        .route(
            "/api/v1/orders/{id}/payments/retry",
            post(orders_payments::retry_order_payment),
        )
        .route(
            "/api/v1/orders/{id}/payments/fail",
            post(orders_payments::fail_order_payment),
        )
        // Phase 8: Reorder endpoint
        .route(
            "/api/v1/orders/{id}/reorder",
            post(orders_payments::reorder_order),
        )
        .route(
            "/api/v1/payments/razorpay/verify",
            post(orders_payments::verify_razorpay_payment),
        )
        .route(
            "/api/v1/webhooks/razorpay",
            post(orders_payments::razorpay_webhook),
        )
        // Notifications
        .route("/api/v1/notifications", get(owner_ops::list_notifications))
        .route(
            "/api/v1/notifications/{id}/read",
            post(owner_ops::mark_notification_read),
        )
        .route(
            "/api/v1/notifications/read-all",
            post(owner_ops::mark_all_notifications_read),
        )
        .route(
            "/api/v1/notifications/preferences",
            get(owner_ops::get_notification_preferences)
                .put(owner_ops::update_notification_preferences),
        )
        // Realtime Replay & WebSocket
        .route("/api/v1/events/replay", get(realtime::replay_events))
        .route("/ws/v1/events", get(realtime::ws_events))
        // Kitchen Display System (KDS)
        .route("/api/v1/kds/tickets", get(kitchen::list_tickets))
        .route("/api/v1/kds/orders/{id}/ack", post(kitchen::ack_order))
        .route(
            "/api/v1/kds/orders/{id}/start-prep",
            post(kitchen::start_prep),
        )
        .route("/api/v1/kds/orders/{id}/ready", post(kitchen::mark_ready))
        .route(
            "/api/v1/kds/orders/{id}/dispatch",
            post(kitchen::dispatch_order),
        )
        .route(
            "/api/v1/kds/orders/{id}/complete",
            post(kitchen::complete_order),
        )
        .route(
            "/api/v1/kds/orders/{id}/priority",
            patch(kitchen::update_priority),
        )
        // Owner Operations, Catalog, Inventory, Recipes, Reports & Audit
        .route(
            "/api/v1/owner/dashboard",
            get(owner_ops::get_owner_dashboard),
        )
        .route(
            "/api/v1/owner/settings",
            get(owner_ops::get_owner_settings).put(owner_ops::update_owner_settings),
        )
        .route(
            "/api/v1/owner/orders",
            get(orders_payments::list_owner_orders),
        )
        .route(
            "/api/v1/owner/orders/{id}/status",
            patch(orders_payments::transition_owner_order_status),
        )
        .route(
            "/api/v1/owner/orders/{id}/refund",
            post(orders_payments::initiate_owner_refund),
        )
        .route(
            "/api/v1/owner/categories",
            get(catalog::list_owner_categories).post(catalog::create_category),
        )
        .route(
            "/api/v1/owner/categories/{id}",
            patch(catalog::update_category).delete(catalog::delete_category),
        )
        .route(
            "/api/v1/owner/products",
            get(catalog::list_owner_products).post(catalog::create_product),
        )
        .route(
            "/api/v1/owner/products/{id}",
            patch(catalog::update_product).delete(catalog::delete_product),
        )
        .route(
            "/api/v1/owner/products/{id}/variants",
            post(catalog::add_variant),
        )
        .route(
            "/api/v1/owner/products/{id}/images/presign",
            post(catalog::presign_product_image),
        )
        .route(
            "/api/v1/owner/products/{id}/images/confirm",
            post(catalog::confirm_product_image),
        )
        .route(
            "/api/v1/owner/products/{id}/images/{imgId}",
            delete(catalog::delete_product_image),
        )
        .route(
            "/api/v1/owner/variants/{id}",
            patch(catalog::update_variant),
        )
        .route(
            "/api/v1/owner/crusts",
            get(catalog::list_owner_crusts).post(catalog::create_crust),
        )
        .route("/api/v1/owner/crusts/{id}", patch(catalog::update_crust))
        .route(
            "/api/v1/owner/toppings",
            get(catalog::list_owner_toppings).post(catalog::create_topping),
        )
        .route(
            "/api/v1/owner/toppings/{id}",
            patch(catalog::update_topping),
        )
        .route(
            "/api/v1/owner/coupons",
            get(commerce::list_owner_coupons).post(commerce::create_coupon),
        )
        .route(
            "/api/v1/owner/coupons/{id}",
            patch(commerce::update_coupon).delete(commerce::delete_coupon),
        )
        .route(
            "/api/v1/owner/delivery-zones",
            get(owner_ops::list_owner_zones).post(owner_ops::create_zone),
        )
        .route(
            "/api/v1/owner/delivery-zones/{id}",
            patch(owner_ops::update_zone).delete(owner_ops::delete_zone),
        )
        .route(
            "/api/v1/owner/slots",
            get(owner_ops::list_owner_slots).post(owner_ops::create_slot),
        )
        .route(
            "/api/v1/owner/slots/{id}",
            patch(owner_ops::update_slot).delete(owner_ops::delete_slot),
        )
        .route(
            "/api/v1/owner/inventory",
            get(owner_ops::list_inventory).post(owner_ops::create_ingredient),
        )
        .route(
            "/api/v1/owner/inventory/{id}",
            patch(owner_ops::update_ingredient),
        )
        .route(
            "/api/v1/owner/inventory/adjust",
            post(owner_ops::adjust_inventory),
        )
        .route(
            "/api/v1/owner/inventory/ledger",
            get(owner_ops::list_inventory_ledger),
        )
        .route(
            "/api/v1/owner/recipes",
            get(owner_ops::list_recipes).put(owner_ops::put_recipe),
        )
        .route(
            "/api/v1/owner/reports/sales",
            get(owner_ops::get_sales_report),
        )
        .route(
            "/api/v1/owner/reports/operations",
            get(owner_ops::get_operations_report),
        )
        .route("/api/v1/owner/audit-logs", get(owner_ops::list_audit_logs))
        .fallback(json_not_found)
        .method_not_allowed_fallback(json_method_not_allowed)
        .layer(DefaultBodyLimit::disable())
        .layer(axum_mw::from_fn_with_state(
            state.clone(),
            metrics_and_logging_middleware,
        ))
        .layer(axum_mw::from_fn_with_state(
            state.clone(),
            rate_limit_middleware,
        ))
        .layer(axum_mw::from_fn_with_state(
            state.clone(),
            body_size_limit_middleware,
        ))
        .layer(axum_mw::from_fn_with_state(
            state.clone(),
            security_headers_middleware,
        ))
        .layer(axum_mw::from_fn(correlation_id_middleware))
        .layer(cors)
        .with_state(state)
}
