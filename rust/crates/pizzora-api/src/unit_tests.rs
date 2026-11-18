use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::net::SocketAddr;
use std::sync::Arc;

use chrono::{TimeZone, Utc};
use pizzora_auth::{PasswordHasher, PizzoraPrincipal};
use pizzora_config::{AppConfig, StorageConfig};
use pizzora_core::{
    FulfillmentType, OrderStatus, PaymentMethod, PaymentStatus, ScheduleType, SizeCode,
    StorageKeyValidator, UserRole,
};
use pizzora_db::{
    connect_pg_pool_lazy, new_uuid_v4, InMemoryCacheStore, IngredientUnit, RedisStore,
};
use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::dto::{
    AvailableSlotsResponse, CartItemInputDto, CartResponseDto, CartToppingInputDto, CategoryDto,
    CheckoutResponse, CouponRoiDto, DailySalesBucketDto, DependencyHealth, EventsReplayResponse,
    HealthReadinessResponse, KdsTicketDto, KdsTicketItemDto, KdsTicketsResponse,
    OperationalStockMovementDto, OperationsReportResponse, PizzaBuilderQuoteRequest,
    PizzaBuilderQuoteResponse, PriceBreakdownDto, PricingQuoteResponse, PublicMenuResponse,
    ReadinessState, RealtimeEventDto, SalesReportResponse, ScheduledSlotDto, TopSellingProductDto,
    WsEventsFrame,
};
use crate::error::ApiErrorEnvelope;
use crate::listener::HttpValidationListener;
use crate::openapi::build_openapi_document;
use crate::rate_limit::{RateLimitDecision, ResilientRateLimiter};
use crate::router::build_router;
use crate::services::realtime::{
    authorized_rooms, is_internal_worker_command, resolve_target_rooms, to_wire_event_type,
    user_room, RealtimeHub, RedisRealtimePubSubMessage, ROOM_KDS_KITCHEN, ROOM_OWNER_DASHBOARD,
};
use crate::state::AppState;
use crate::storage::{
    detect_image_mime_type, ObjectStorageClient, S3ObjectStorageClient,
    UnavailableObjectStorageClient,
};

fn build_offline_config(env_overrides: &[(&str, &str)]) -> AppConfig {
    let mut env = HashMap::new();
    for (k, v) in env_overrides {
        env.insert((*k).to_owned(), (*v).to_owned());
    }
    AppConfig::from_env_map(&env).expect("offline AppConfig builds cleanly")
}

async fn spawn_offline_server(env_overrides: &[(&str, &str)]) -> SocketAddr {
    let config = build_offline_config(env_overrides);
    let pool = connect_pg_pool_lazy(&config.database).expect("lazy pg pool");
    let redis_store = Arc::new(RedisStore::new("redis://127.0.0.1:0", 200));
    let cache_override: Arc<dyn pizzora_db::EphemeralCacheStore> =
        Arc::new(InMemoryCacheStore::new());
    let hasher = PasswordHasher::new(4096, 1, 1, 32);

    let state = AppState::build_with_cache(
        config,
        pool,
        redis_store,
        Some(cache_override),
        Some(hasher),
        None,
        None,
    )
    .expect("AppState builds offline");

    let is_production = state.config.is_production();
    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind ephemeral port");
    let addr = listener.local_addr().expect("local_addr");
    let validated_listener = HttpValidationListener::new(listener, is_production);

    let app = build_router(state).into_make_service_with_connect_info::<SocketAddr>();
    tokio::spawn(async move {
        let _ = axum::serve(validated_listener.into_serve_listener(), app).await;
    });

    addr
}

#[test]
fn dto_round_trips_and_wire_field_names_match_ktor_and_android_contracts() {
    let mut deps = BTreeMap::new();
    deps.insert("postgres".to_owned(), DependencyHealth::Up);
    deps.insert("redis".to_owned(), DependencyHealth::Down);
    let health = HealthReadinessResponse {
        status: ReadinessState::Degraded,
        dependencies: deps,
        service: "pizzora-api".to_owned(),
    };
    let health_json = serde_json::to_value(&health).unwrap();
    assert_eq!(health_json["status"], "DEGRADED");
    assert_eq!(health_json["dependencies"]["postgres"], "UP");
    assert_eq!(health_json["dependencies"]["redis"], "DOWN");
    assert_eq!(health_json["service"], "pizzora-api");
    let decoded_health: HealthReadinessResponse = serde_json::from_value(health_json).unwrap();
    assert_eq!(decoded_health, health);

    let menu = PublicMenuResponse {
        categories: vec![CategoryDto {
            id: "cat_1".to_owned(),
            name: "Classic".to_owned(),
            sort_order: 1,
            is_active: true,
            created_at: "2026-01-01T00:00:00.000Z".to_owned(),
        }],
        products: Vec::new(),
        crusts: Vec::new(),
        toppings: Vec::new(),
        gst_bps: 500,
        store_name: "PIZZORA".to_owned(),
        is_accepting_orders: true,
        size_rules: Vec::new(),
        cheeses: Vec::new(),
        sauces: Vec::new(),
    };
    let menu_rt: PublicMenuResponse =
        serde_json::from_slice(&serde_json::to_vec(&menu).unwrap()).unwrap();
    assert_eq!(menu_rt, menu);

    let cart = CartResponseDto {
        cart_id: Some("cart_1".to_owned()),
        items: Vec::new(),
        subtotal_paise: 0,
        total_item_count: 0,
        all_available: true,
        unavailable_count: 0,
        updated_at: "2026-01-01T00:00:00.000Z".to_owned(),
    };
    let cart_rt: CartResponseDto =
        serde_json::from_slice(&serde_json::to_vec(&cart).unwrap()).unwrap();
    assert_eq!(cart_rt, cart);

    let quote = PricingQuoteResponse {
        items: Vec::new(),
        fulfillment_type: FulfillmentType::Delivery,
        breakdown: PriceBreakdownDto {
            subtotal_paise: 40_000,
            taxable_subtotal_paise: 40_000,
            discount_paise: 5_000,
            taxable_amount_paise: 35_000,
            delivery_fee_paise: 3_000,
            gst_bps: 500,
            tax_paise: 1_750,
            cgst_paise: 875,
            sgst_paise: 875,
            total_paise: 39_750,
        },
        applied_coupon: None,
        delivery_zone: None,
        is_accepting_orders: true,
        gst_bps: 500,
    };
    let quote_rt: PricingQuoteResponse =
        serde_json::from_slice(&serde_json::to_vec(&quote).unwrap()).unwrap();
    assert_eq!(quote_rt, quote);

    let kds = KdsTicketsResponse {
        tickets: vec![KdsTicketDto {
            id: "ord_1".to_owned(),
            order_number: "PZ-1001".to_owned(),
            customer_name: "Rohan".to_owned(),
            customer_phone: Some("9876543210".to_owned()),
            fulfillment_type: FulfillmentType::Delivery,
            schedule_type: ScheduleType::Asap,
            status: OrderStatus::Placed,
            version: 1,
            is_priority: true,
            payment_method: PaymentMethod::Razorpay,
            payment_status: PaymentStatus::Captured,
            total_paise: 39_750,
            delivery_area: Some("Indiranagar".to_owned()),
            accepted_at: None,
            prep_started_at: None,
            ready_at: None,
            completed_at: None,
            created_at: "2026-01-01T00:00:00.000Z".to_owned(),
            items: vec![KdsTicketItemDto {
                id: "item_1".to_owned(),
                product_id: "prod_1".to_owned(),
                product_name: "Margherita".to_owned(),
                variant_size: serde_json::from_value::<String>(
                    serde_json::to_value(SizeCode::Medium).unwrap(),
                )
                .unwrap(),
                crust_name: "Classic Hand Tossed".to_owned(),
                toppings: vec!["Extra Cheese".to_owned()],
                quantity: 1,
                customization_snapshot: None,
            }],
        }],
        server_now: "2026-01-01T00:00:00.000Z".to_owned(),
        prep_sla_minutes: 20,
        priority_count: 1,
    };
    let kds_rt: KdsTicketsResponse =
        serde_json::from_slice(&serde_json::to_vec(&kds).unwrap()).unwrap();
    assert_eq!(kds_rt, kds);

    let slots = AvailableSlotsResponse {
        date: "2026-05-01".to_owned(),
        fulfillment_type: FulfillmentType::Pickup,
        timezone: "Asia/Kolkata".to_owned(),
        server_now: "2026-05-01T04:00:00.000Z".to_owned(),
        slots: vec![ScheduledSlotDto {
            id: "slot_1".to_owned(),
            slot_date: "2026-05-01".to_owned(),
            fulfillment_type: FulfillmentType::Pickup,
            start_time_ist: "19:00".to_owned(),
            end_time_ist: "19:30".to_owned(),
            cutoff_at: "2026-05-01T13:00:00.000Z".to_owned(),
            max_capacity: 10,
            reserved_count: 3,
            remaining_capacity: 7,
            is_active: true,
            is_bookable: true,
            unavailable_reason: None,
        }],
    };
    let slots_rt: AvailableSlotsResponse =
        serde_json::from_slice(&serde_json::to_vec(&slots).unwrap()).unwrap();
    assert_eq!(slots_rt, slots);

    let sales = SalesReportResponse {
        start_date: "2026-05-01".to_owned(),
        end_date: "2026-05-07".to_owned(),
        timezone: "Asia/Kolkata".to_owned(),
        order_count: 5,
        cancelled_order_count: 1,
        subtotal_paise: 100_000,
        discount_paise: 10_000,
        delivery_fee_paise: 5_000,
        tax_paise: 4_500,
        cgst_paise: 2_250,
        sgst_paise: 2_250,
        gross_sales_paise: 99_500,
        refunded_paise: 15_000,
        pending_refunds_paise: 0,
        net_sales_paise: 84_500,
        daily_breakdown: vec![DailySalesBucketDto {
            date: "2026-05-01".to_owned(),
            order_count: 5,
            gross_sales_paise: 99_500,
            refunded_paise: 15_000,
            net_sales_paise: 84_500,
            tax_paise: 4_500,
        }],
        top_products: vec![TopSellingProductDto {
            product_id: "prod_1".to_owned(),
            product_name: "Margherita".to_owned(),
            quantity_sold: 4,
            gross_revenue_paise: 80_000,
        }],
        coupon_performance: vec![CouponRoiDto {
            coupon_id: "cpn_1".to_owned(),
            code: "PIZZA10".to_owned(),
            redemption_count: 2,
            total_discount_paise: 10_000,
            gross_order_revenue_paise: 60_000,
            net_order_revenue_paise: 50_000,
        }],
        server_now: "2026-05-07T18:29:59.000Z".to_owned(),
    };
    let sales_rt: SalesReportResponse =
        serde_json::from_slice(&serde_json::to_vec(&sales).unwrap()).unwrap();
    assert_eq!(sales_rt, sales);

    let ops = OperationsReportResponse {
        start_date: "2026-05-01".to_owned(),
        end_date: "2026-05-07".to_owned(),
        timezone: "Asia/Kolkata".to_owned(),
        prep_sla_minutes: 20,
        total_orders: 10,
        completed_orders: 8,
        cancelled_orders: 1,
        expired_orders: 1,
        delivery_orders: 6,
        pickup_orders: 4,
        scheduled_orders: 2,
        measured_prep_orders: 8,
        avg_prep_minutes: 14.5,
        max_prep_minutes: 22.0,
        avg_completion_minutes: 28.0,
        sla_breached_count: 1,
        sla_compliance_percent: 87.5,
        restocked_units: 5000,
        consumed_units: 2000,
        restored_units: 250,
        wastage_units: 0,
        stock_movements: vec![OperationalStockMovementDto {
            ingredient_id: "ing_1".to_owned(),
            ingredient_name: "Mozzarella".to_owned(),
            unit: IngredientUnit::Grams,
            restocked_qty: 5000,
            consumed_qty: 2000,
            restored_qty: 250,
            wastage_qty: 0,
        }],
        low_stock_items: Vec::new(),
        server_now: "2026-05-07T18:29:59.000Z".to_owned(),
    };
    let ops_rt: OperationsReportResponse =
        serde_json::from_slice(&serde_json::to_vec(&ops).unwrap()).unwrap();
    assert_eq!(ops_rt, ops);

    let mut payload_map = serde_json::Map::new();
    payload_map.insert("order_number".to_owned(), json!("PZ-1001"));
    let ev = RealtimeEventDto {
        event_id: "ev_1".to_owned(),
        stream_seq: 42,
        event_type: "order.created".to_owned(),
        aggregate_type: "ORDER".to_owned(),
        aggregate_id: "ord_1".to_owned(),
        aggregate_version: 1,
        payload: payload_map,
        sequenced_at: Some("2026-05-01T00:00:01.000Z".to_owned()),
        created_at: "2026-05-01T00:00:00.000Z".to_owned(),
    };
    let replay = EventsReplayResponse {
        events: vec![ev.clone()],
        server_high_water_seq: 42,
        has_more: false,
        server_now: "2026-05-01T00:00:02.000Z".to_owned(),
    };
    let replay_rt: EventsReplayResponse =
        serde_json::from_slice(&serde_json::to_vec(&replay).unwrap()).unwrap();
    assert_eq!(replay_rt, replay);

    let ws_frame = WsEventsFrame {
        frame_type: "EVENTS".to_owned(),
        events: vec![ev],
        server_high_water_seq: 42,
        server_now: "2026-05-01T00:00:02.000Z".to_owned(),
        resumed_from_seq: Some(10),
    };
    let ws_val = serde_json::to_value(&ws_frame).unwrap();
    assert_eq!(ws_val["type"], "EVENTS");
    assert_eq!(ws_val["resumed_from_seq"], 10);
    let ws_rt: WsEventsFrame = serde_json::from_value(ws_val).unwrap();
    assert_eq!(ws_rt, ws_frame);

    let quote_req = PizzaBuilderQuoteRequest {
        item: CartItemInputDto {
            product_id: "prod_1".to_owned(),
            variant_id: "var_1".to_owned(),
            crust_id: "crust_1".to_owned(),
            quantity: 2,
            extra_cheese: true,
            is_half_and_half: true,
            topping_selections: vec![CartToppingInputDto {
                topping_id: "top_1".to_owned(),
                placement: pizzora_core::ToppingPlacement::Left,
                quantity: 2,
            }],
            ..Default::default()
        },
    };
    let quote_req_rt: PizzaBuilderQuoteRequest =
        serde_json::from_slice(&serde_json::to_vec(&quote_req).unwrap()).unwrap();
    assert_eq!(quote_req_rt, quote_req);
    assert!(std::mem::size_of::<PizzaBuilderQuoteResponse>() > 0);

    // Ensure CheckoutResponse compiles cleanly in serde contract tests too
    let checkout_json_size = std::mem::size_of::<CheckoutResponse>();
    assert!(checkout_json_size > 0);
}

#[test]
fn openapi_document_contains_version_bearer_security_and_contract_paths() {
    let doc = build_openapi_document("https://api.pizzora.test");
    assert_eq!(doc["openapi"], "3.1.0");
    assert_eq!(doc["servers"][0]["url"], "https://api.pizzora.test");
    assert_eq!(
        doc["components"]["securitySchemes"]["BearerAuth"]["scheme"],
        "bearer"
    );

    let paths = doc["paths"].as_object().expect("paths object");
    for expected in [
        "/api/v1/health/live",
        "/api/v1/health/ready",
        "/api/v1/openapi.json",
        "/metrics",
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/ws-ticket",
        "/api/v1/catalog/menu",
        "/api/v1/cart",
        "/api/v1/pizza-builder/quote",
        "/api/v1/checkout",
        "/api/v1/webhooks/razorpay",
        "/api/v1/kds/tickets",
        "/api/v1/owner/settings",
        "/api/v1/owner/size-rules",
        "/api/v1/owner/cheeses",
        "/api/v1/owner/sauces",
        "/api/v1/owner/reports/sales",
        "/ws/v1/events",
    ] {
        assert!(
            paths.contains_key(expected),
            "Missing OpenAPI path {expected}"
        );
    }
    assert!(paths["/api/v1/owner/settings"]
        .as_object()
        .unwrap()
        .contains_key("put"));
    assert!(!paths["/api/v1/owner/settings"]
        .as_object()
        .unwrap()
        .contains_key("patch"));
}

#[tokio::test]
async fn rate_limiter_classification_and_redis_unavailable_fallback_work() {
    let cfg = build_offline_config(&[]);
    let cache = Arc::new(InMemoryCacheStore::new());
    let limiter = ResilientRateLimiter::new(cache.clone(), cfg.security.clone());

    assert!(limiter.is_enabled());
    assert!(limiter
        .classify_route_group("/api/v1/health/live")
        .is_none());
    assert!(limiter
        .classify_route_group("/api/v1/health/ready")
        .is_none());
    assert!(limiter
        .classify_route_group("/api/v1/openapi.json")
        .is_none());
    assert!(limiter.classify_route_group("/metrics").is_none());
    assert!(limiter.classify_route_group("/api/v1/auth/login").is_none());
    assert!(limiter
        .classify_route_group("/api/v1/auth/register")
        .is_none());

    let checkout_group = limiter
        .classify_route_group("/api/v1/checkout")
        .expect("checkout group");
    assert_eq!(checkout_group.group, "checkout_payments");
    assert_eq!(
        checkout_group.max_per_minute,
        cfg.security.checkout_payment_per_minute
    );

    let pay_verify_group = limiter
        .classify_route_group("/api/v1/payments/razorpay/verify")
        .expect("payments verify group");
    assert_eq!(pay_verify_group.group, "checkout_payments");

    let pay_retry_group = limiter
        .classify_route_group("/api/v1/orders/1234/payments/retry")
        .expect("payments retry group");
    assert_eq!(pay_retry_group.group, "checkout_payments");

    // Simulate Redis unavailable -> falls back cleanly to in-process local windows
    cache.set_unavailable(true);
    assert_eq!(
        limiter.check("unit:rl:ip1", 2, 30).await,
        RateLimitDecision::Allowed
    );
    assert_eq!(
        limiter.check("unit:rl:ip1", 2, 30).await,
        RateLimitDecision::Allowed
    );
    assert_eq!(
        limiter.check("unit:rl:ip1", 2, 30).await,
        RateLimitDecision::Rejected {
            retry_after_secs: 30
        }
    );
}

#[tokio::test]
async fn storage_key_validation_unavailable_client_sigv4_and_magic_bytes() {
    StorageKeyValidator::validate_object_key(
        "products/11111111-2222-4333-8444-555555555555/img.jpg",
    )
    .expect("valid object key");
    assert!(StorageKeyValidator::validate_object_key("../etc/passwd").is_err());
    assert!(StorageKeyValidator::validate_object_key("/leading/slash.png").is_err());

    let unavailable = UnavailableObjectStorageClient;
    assert!(!unavailable.is_enabled());
    assert_eq!(unavailable.resolve_public_url("products/a/b.png"), None);
    let err = unavailable
        .generate_presigned_put_url("products/a/b.png", "image/png", 300)
        .await
        .expect_err("unavailable storage rejects presign");
    assert_eq!(err.code, "OBJECT_STORAGE_UNAVAILABLE");

    let mut env = HashMap::new();
    env.insert("S3_ENABLED".to_owned(), "true".to_owned());
    env.insert("S3_ENDPOINT".to_owned(), "http://127.0.0.1:9000".to_owned());
    env.insert("S3_BUCKET".to_owned(), "pizzora-product-images".to_owned());
    env.insert("S3_REGION".to_owned(), "ap-south-1".to_owned());
    env.insert("S3_ACCESS_KEY".to_owned(), "minio_key".to_owned());
    env.insert("S3_SECRET_KEY".to_owned(), "minio_secret".to_owned());
    env.insert(
        "S3_PUBLIC_URL".to_owned(),
        "https://cdn.pizzora.example/".to_owned(),
    );
    let storage_cfg = StorageConfig::from_env_map(&env, false).expect("s3 config");
    let client = S3ObjectStorageClient::new(storage_cfg).expect("s3 client");
    assert!(client.is_enabled());
    assert_eq!(
        client.resolve_public_url("/products/1/a.webp").as_deref(),
        Some("https://cdn.pizzora.example/products/1/a.webp")
    );

    let fixed_now = Utc.with_ymd_and_hms(2026, 5, 1, 10, 30, 0).unwrap();
    let p1 = client
        .presign_put_at("products/1/a.jpg", "image/jpeg", 999_999, fixed_now)
        .expect("presign 1");
    let p2 = client
        .presign_put_at("products/1/a.jpg", "image/jpeg", 999_999, fixed_now)
        .expect("presign 2");
    assert_eq!(p1, p2);
    assert_eq!(p1.expires_in_seconds, 3600);
    assert!(p1.upload_url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
    assert!(p1.upload_url.contains("X-Amz-Signature="));
    assert_eq!(
        p1.required_headers.get("Content-Type").map(String::as_str),
        Some("image/jpeg")
    );

    assert_eq!(
        detect_image_mime_type(&[0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10]),
        Some("image/jpeg")
    );
    assert_eq!(
        detect_image_mime_type(&[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00]),
        Some("image/png")
    );
    assert_eq!(
        detect_image_mime_type(b"RIFF\x24\x00\x00\x00WEBPVP8 "),
        Some("image/webp")
    );
    assert_eq!(detect_image_mime_type(b"<html>not an image</html>"), None);
    assert_eq!(detect_image_mime_type(&[0xFF, 0xD8]), None);
}

#[test]
fn realtime_hub_rooms_filtering_fanout_and_monotonic_dedup() {
    let customer_a_id = new_uuid_v4();
    let customer_b_id = new_uuid_v4();
    let kds_id = new_uuid_v4();
    let owner_id = new_uuid_v4();

    let p_a = PizzoraPrincipal {
        user_id: customer_a_id,
        session_id: new_uuid_v4(),
        role: UserRole::Customer,
    };
    let p_b = PizzoraPrincipal {
        user_id: customer_b_id,
        session_id: new_uuid_v4(),
        role: UserRole::Customer,
    };
    let p_kds = PizzoraPrincipal {
        user_id: kds_id,
        session_id: new_uuid_v4(),
        role: UserRole::KitchenStaff,
    };
    let p_owner = PizzoraPrincipal {
        user_id: owner_id,
        session_id: new_uuid_v4(),
        role: UserRole::Owner,
    };

    assert_eq!(
        authorized_rooms(&p_a),
        BTreeSet::from([user_room(customer_a_id)])
    );
    assert_eq!(
        authorized_rooms(&p_kds),
        BTreeSet::from([user_room(kds_id), ROOM_KDS_KITCHEN.to_owned()])
    );
    assert_eq!(
        authorized_rooms(&p_owner),
        BTreeSet::from([
            user_room(owner_id),
            ROOM_KDS_KITCHEN.to_owned(),
            ROOM_OWNER_DASHBOARD.to_owned(),
        ])
    );

    assert!(is_internal_worker_command(
        "PAYMENT_GATEWAY_ORDER_REQUESTED"
    ));
    assert!(is_internal_worker_command("REFUND_REQUESTED"));
    assert!(is_internal_worker_command("EMAIL_DELIVERY_REQUESTED"));
    assert!(!is_internal_worker_command("ORDER_PLACED"));
    assert_eq!(to_wire_event_type("ORDER_PLACED"), "order.created");
    assert_eq!(
        to_wire_event_type("KITCHEN_ACKNOWLEDGED"),
        "kitchen.acknowledged"
    );

    let mut pending_payload = serde_json::Map::new();
    pending_payload.insert("from_status".to_owned(), json!("PENDING_PAYMENT"));
    pending_payload.insert("to_status".to_owned(), json!("EXPIRED"));
    let expired_rooms = resolve_target_rooms(
        "order.status_changed",
        Some(customer_a_id),
        Some("PENDING_PAYMENT"),
        &pending_payload,
    );
    assert!(expired_rooms.contains(&user_room(customer_a_id)));
    assert!(expired_rooms.contains(ROOM_OWNER_DASHBOARD));
    assert!(!expired_rooms.contains(ROOM_KDS_KITCHEN));

    let hub = RealtimeHub::new();
    let mut h_a = hub.register_session(&p_a, 10);
    let mut h_b = hub.register_session(&p_b, 10);
    let mut h_kds = hub.register_session(&p_kds, 10);
    let mut h_owner = hub.register_session(&p_owner, 10);
    assert_eq!(hub.connected_session_count(), 4);
    assert_eq!(hub.ws_session_counts(), (4, 4));

    let mut payload_a = serde_json::Map::new();
    payload_a.insert("customer_id".to_owned(), json!(customer_a_id.to_string()));
    let ev_seq_11 = RealtimeEventDto {
        event_id: new_uuid_v4().to_string(),
        stream_seq: 11,
        event_type: "order.created".to_owned(),
        aggregate_type: "ORDER".to_owned(),
        aggregate_id: new_uuid_v4().to_string(),
        aggregate_version: 1,
        payload: payload_a.clone(),
        sequenced_at: Some("2026-05-01T00:00:01.000Z".to_owned()),
        created_at: "2026-05-01T00:00:00.000Z".to_owned(),
    };
    let pubsub_msg = RedisRealtimePubSubMessage {
        wire_event: ev_seq_11.clone(),
        rooms: BTreeSet::from([
            user_room(customer_a_id),
            ROOM_KDS_KITCHEN.to_owned(),
            ROOM_OWNER_DASHBOARD.to_owned(),
        ]),
    };
    assert!(hub.dispatch_serialized(&serde_json::to_string(&pubsub_msg).unwrap()));

    // Customer A, KDS, and Owner receive seq 11; Customer B stays isolated
    assert_eq!(h_a.outbound_rx.try_recv().unwrap().events[0].stream_seq, 11);
    assert_eq!(
        h_kds.outbound_rx.try_recv().unwrap().events[0].stream_seq,
        11
    );
    assert_eq!(
        h_owner.outbound_rx.try_recv().unwrap().events[0].stream_seq,
        11
    );
    assert!(h_b.outbound_rx.try_recv().is_err());

    // Duplicate/stale seq <= 11 must be dropped monotonically
    assert!(!hub.dispatch_serialized(&serde_json::to_string(&pubsub_msg).unwrap()));
    assert!(h_a.outbound_rx.try_recv().is_err());

    // Internal worker command in raw event form is never broadcast
    let internal_cmd = RealtimeEventDto {
        event_id: new_uuid_v4().to_string(),
        stream_seq: 12,
        event_type: "PAYMENT_GATEWAY_ORDER_REQUESTED".to_owned(),
        aggregate_type: "ORDER".to_owned(),
        aggregate_id: new_uuid_v4().to_string(),
        aggregate_version: 1,
        payload: payload_a,
        sequenced_at: None,
        created_at: "2026-05-01T00:00:02.000Z".to_owned(),
    };
    assert!(!hub.dispatch_serialized(&serde_json::to_string(&internal_cmd).unwrap()));

    hub.unregister_session(h_b.session_id);
    assert_eq!(hub.connected_session_count(), 3);
}

#[tokio::test]
async fn http_middleware_health_openapi_correlation_errors_and_security_headers() {
    let dev_addr = spawn_offline_server(&[("RATE_LIMIT_ENABLED", "false")]).await;
    let client = reqwest::Client::new();

    // 1. Live probe
    let live_resp = client
        .get(format!("http://{dev_addr}/api/v1/health/live"))
        .header("x-request-id", "req-custom-123456")
        .send()
        .await
        .expect("live request");
    assert_eq!(live_resp.status(), reqwest::StatusCode::OK);
    assert_eq!(
        live_resp
            .headers()
            .get("x-request-id")
            .and_then(|v| v.to_str().ok()),
        Some("req-custom-123456")
    );
    assert_eq!(
        live_resp
            .headers()
            .get("cache-control")
            .and_then(|v| v.to_str().ok()),
        Some("no-store")
    );
    assert_eq!(
        live_resp
            .headers()
            .get("x-content-type-options")
            .and_then(|v| v.to_str().ok()),
        Some("nosniff")
    );
    assert_eq!(
        live_resp
            .headers()
            .get("x-frame-options")
            .and_then(|v| v.to_str().ok()),
        Some("DENY")
    );
    assert!(live_resp
        .headers()
        .get("strict-transport-security")
        .is_none());
    let live_body: HealthReadinessResponse = live_resp.json().await.expect("live JSON");
    assert_eq!(live_body.status, ReadinessState::Healthy);
    assert_eq!(
        live_body.dependencies.get("server"),
        Some(&DependencyHealth::Up)
    );
    assert_eq!(live_body.service, "pizzora-api");

    // 2. OpenAPI spec endpoint + invalid x-request-id replaced
    let openapi_resp = client
        .get(format!("http://{dev_addr}/api/v1/openapi.json"))
        .header("x-request-id", "bad")
        .send()
        .await
        .expect("openapi request");
    assert_eq!(openapi_resp.status(), reqwest::StatusCode::OK);
    let generated_cid = openapi_resp
        .headers()
        .get("x-request-id")
        .and_then(|v| v.to_str().ok())
        .expect("generated x-request-id")
        .to_owned();
    assert_ne!(generated_cid, "bad");
    let openapi_val: serde_json::Value = openapi_resp.json().await.unwrap();
    assert_eq!(openapi_val["openapi"], "3.1.0");

    // 3. 404 JSON envelope + matching correlation_id
    let not_found_resp = client
        .get(format!("http://{dev_addr}/api/v1/does-not-exist"))
        .header("x-request-id", "corr-notfound-01")
        .send()
        .await
        .expect("404 request");
    assert_eq!(not_found_resp.status(), reqwest::StatusCode::NOT_FOUND);
    assert_eq!(
        not_found_resp
            .headers()
            .get("x-request-id")
            .and_then(|v| v.to_str().ok()),
        Some("corr-notfound-01")
    );
    let nf_env: ApiErrorEnvelope = not_found_resp.json().await.unwrap();
    assert_eq!(nf_env.error.code, "NOT_FOUND");
    assert_eq!(nf_env.correlation_id, "corr-notfound-01");

    // 4. 405 JSON envelope + matching correlation_id
    let method_not_allowed_resp = client
        .delete(format!("http://{dev_addr}/api/v1/health/live"))
        .header("x-request-id", "corr-method-001")
        .send()
        .await
        .expect("405 request");
    assert_eq!(
        method_not_allowed_resp.status(),
        reqwest::StatusCode::METHOD_NOT_ALLOWED
    );
    let mna_env: ApiErrorEnvelope = method_not_allowed_resp.json().await.unwrap();
    assert_eq!(mna_env.error.code, "METHOD_NOT_ALLOWED");
    assert_eq!(mna_env.correlation_id, "corr-method-001");

    // 5. Missing/invalid Bearer JWT -> 401 with matching x-request-id
    let unauth_resp = client
        .get(format!("http://{dev_addr}/api/v1/me"))
        .header("x-request-id", "corr-unauth-001")
        .header("authorization", "Bearer not.a.jwt")
        .send()
        .await
        .expect("401 request");
    assert_eq!(unauth_resp.status(), reqwest::StatusCode::UNAUTHORIZED);
    assert_eq!(
        unauth_resp
            .headers()
            .get("x-request-id")
            .and_then(|v| v.to_str().ok()),
        Some("corr-unauth-001")
    );
    let unauth_env: ApiErrorEnvelope = unauth_resp.json().await.unwrap();
    assert_eq!(unauth_env.correlation_id, "corr-unauth-001");
    assert_eq!(unauth_env.error.code, "INVALID_TOKEN");

    // 6. Metrics endpoint available offline
    let metrics_resp = client
        .get(format!("http://{dev_addr}/metrics"))
        .send()
        .await
        .expect("metrics request");
    assert_eq!(metrics_resp.status(), reqwest::StatusCode::OK);
    let metrics_txt = metrics_resp.text().await.unwrap();
    assert!(metrics_txt.contains("http_requests_total"));

    // 7. Production mode includes Strict-Transport-Security
    let prod_addr = spawn_offline_server(&[
        ("PIZZORA_ENV", "production"),
        (
            "DATABASE_URL",
            "postgresql://prod_u:Str0ng!Pass@127.0.0.1:5432/pizzora",
        ),
        ("JWT_SECRET", "prod-signing-secret-with-more-than-32-chars"),
        ("OUTBOX_EMAIL_KEY_CURRENT_ID", "k2026_01"),
        (
            "OUTBOX_EMAIL_KEYS",
            "k2026_01:AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        ),
        ("RAZORPAY_KEY_ID", "rzp_test_123"),
        ("RAZORPAY_KEY_SECRET", "sec_test_123"),
        ("RAZORPAY_WEBHOOK_SECRET", "whsec_test_123"),
        ("RATE_LIMIT_ENABLED", "false"),
    ])
    .await;

    let prod_live = client
        .get(format!("http://{prod_addr}/api/v1/health/live"))
        .send()
        .await
        .expect("prod live request");
    assert_eq!(prod_live.status(), reqwest::StatusCode::OK);
    assert_eq!(
        prod_live
            .headers()
            .get("strict-transport-security")
            .and_then(|v| v.to_str().ok()),
        Some("max-age=31536000; includeSubDomains")
    );
}

#[tokio::test]
async fn http_body_size_limits_chunked_overflow_and_rate_limit_429() {
    let addr = spawn_offline_server(&[
        ("MAX_JSON_BODY_BYTES", "128"),
        ("MAX_WEBHOOK_BODY_BYTES", "512"),
        ("RATE_LIMIT_ENABLED", "true"),
    ])
    .await;
    let client = reqwest::Client::new();

    // 1. Oversized Content-Length on normal JSON endpoint -> 413 PAYLOAD_TOO_LARGE
    let oversized_json = format!("{{\"email\":\"{}@example.com\"}}", "a".repeat(200));
    let cl_resp = client
        .post(format!("http://{addr}/api/v1/auth/login"))
        .header("content-type", "application/json")
        .body(oversized_json)
        .send()
        .await
        .expect("oversized content-length request");
    assert_eq!(cl_resp.status(), reqwest::StatusCode::PAYLOAD_TOO_LARGE);
    let cl_err: ApiErrorEnvelope = cl_resp.json().await.unwrap();
    assert_eq!(cl_err.error.code, "PAYLOAD_TOO_LARGE");

    // 2. Oversized Transfer-Encoding: chunked body without Content-Length -> 413 PAYLOAD_TOO_LARGE
    let mut stream = TcpStream::connect(addr).await.expect("connect raw tcp");
    let big_chunk = "x".repeat(200);
    let raw_req = format!(
        "POST /api/v1/auth/verify-email HTTP/1.1\r\n\
         Host: 127.0.0.1\r\n\
         Content-Type: application/json\r\n\
         Transfer-Encoding: chunked\r\n\
         Connection: close\r\n\r\n\
         {:x}\r\n{big_chunk}\r\n0\r\n\r\n",
        big_chunk.len()
    );
    stream.write_all(raw_req.as_bytes()).await.unwrap();
    let mut raw_resp = String::new();
    stream.read_to_string(&mut raw_resp).await.unwrap();
    assert!(
        raw_resp.starts_with("HTTP/1.1 413 "),
        "Expected 413 for oversized chunked body, got: {raw_resp}"
    );
    assert!(raw_resp.contains("PAYLOAD_TOO_LARGE"));

    // 3. Invalid Content-Length header -> 400 INVALID_CONTENT_LENGTH
    let mut bad_cl_stream = TcpStream::connect(addr).await.expect("connect raw tcp");
    let bad_cl_req = "POST /api/v1/auth/verify-email HTTP/1.1\r\n\
                      Host: 127.0.0.1\r\n\
                      Content-Type: application/json\r\n\
                      Content-Length: not_a_number\r\n\
                      Connection: close\r\n\r\n{}";
    bad_cl_stream
        .write_all(bad_cl_req.as_bytes())
        .await
        .unwrap();
    let mut bad_cl_resp = String::new();
    bad_cl_stream
        .read_to_string(&mut bad_cl_resp)
        .await
        .unwrap();
    assert!(
        bad_cl_resp.starts_with("HTTP/1.1 400 "),
        "Expected 400 for invalid Content-Length, got: {bad_cl_resp}"
    );
    assert!(bad_cl_resp.contains("INVALID_CONTENT_LENGTH"));
    let (_, bad_cl_body) = bad_cl_resp
        .split_once("\r\n\r\n")
        .expect("http header/body separator");
    let bad_cl_env: ApiErrorEnvelope =
        serde_json::from_str(bad_cl_body).expect("structured ApiErrorEnvelope for bad CL");
    assert_eq!(bad_cl_env.error.code, "INVALID_CONTENT_LENGTH");

    // 3b. Negative / duplicate mismatched Content-Length preserves x-request-id + structured 400
    for (req_id, cl_headers) in [
        ("corr-neg-cl-01", "Content-Length: -5\r\n"),
        (
            "corr-dup-cl-01",
            "Content-Length: 2\r\nContent-Length: 9\r\n",
        ),
    ] {
        let mut s = TcpStream::connect(addr).await.expect("connect raw tcp");
        let req = format!(
            "POST /api/v1/auth/verify-email HTTP/1.1\r\n\
             Host: 127.0.0.1\r\n\
             Content-Type: application/json\r\n\
             X-Request-Id: {req_id}\r\n\
             {cl_headers}\
             Connection: close\r\n\r\n{{}}"
        );
        s.write_all(req.as_bytes()).await.unwrap();
        let mut resp = String::new();
        s.read_to_string(&mut resp).await.unwrap();
        assert!(
            resp.starts_with("HTTP/1.1 400 "),
            "Expected 400 for invalid Content-Length ({cl_headers}), got: {resp}"
        );
        let (head, body) = resp.split_once("\r\n\r\n").expect("http separator");
        let expected_hdr = format!("x-request-id: {req_id}");
        assert!(
            head.to_ascii_lowercase().contains(&expected_hdr),
            "Expected x-request-id {req_id} in headers: {head}"
        );
        let env: ApiErrorEnvelope = serde_json::from_str(body).expect("ApiErrorEnvelope json");
        assert_eq!(env.error.code, "INVALID_CONTENT_LENGTH");
        assert_eq!(env.correlation_id, req_id);
    }

    // 4. Webhook endpoint permits >128 bytes up to MAX_WEBHOOK_BODY_BYTES (512), fails at >512 bytes
    let mid_webhook = format!("{{\"event\":\"{}\"}}", "w".repeat(250));
    let mid_wh_resp = client
        .post(format!("http://{addr}/api/v1/webhooks/razorpay"))
        .header("content-type", "application/json")
        .body(mid_webhook)
        .send()
        .await
        .expect("mid webhook request");
    assert_ne!(
        mid_wh_resp.status(),
        reqwest::StatusCode::PAYLOAD_TOO_LARGE,
        "250-byte webhook must bypass 128-byte JSON cap"
    );

    let big_webhook = format!("{{\"event\":\"{}\"}}", "w".repeat(600));
    let big_wh_resp = client
        .post(format!("http://{addr}/api/v1/webhooks/razorpay"))
        .header("content-type", "application/json")
        .body(big_webhook)
        .send()
        .await
        .expect("big webhook request");
    assert_eq!(big_wh_resp.status(), reqwest::StatusCode::PAYLOAD_TOO_LARGE);

    // 5. Burst against /api/v1/checkout triggers 429 RATE_LIMITED with Retry-After + group=checkout_payments
    let mut hit_429 = false;
    for _ in 0..85 {
        let resp = client
            .post(format!("http://{addr}/api/v1/checkout"))
            .header("x-forwarded-for", "203.0.113.77")
            .header("content-type", "application/json")
            .body("{}")
            .send()
            .await
            .expect("checkout burst request");
        if resp.status() == reqwest::StatusCode::TOO_MANY_REQUESTS {
            let retry_after = resp
                .headers()
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .unwrap_or("")
                .to_owned();
            assert!(!retry_after.is_empty());
            let env: ApiErrorEnvelope = resp.json().await.unwrap();
            assert_eq!(env.error.code, "RATE_LIMITED");
            assert_eq!(
                env.error.details.get("group").map(String::as_str),
                Some("checkout_payments")
            );
            hit_429 = true;
            break;
        }
    }
    assert!(hit_429, "Expected 429 RATE_LIMITED within 85 requests");
}
