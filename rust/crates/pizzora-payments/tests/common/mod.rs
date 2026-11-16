use pizzora_config::{AppConfig, RazorpayConfig};
use pizzora_db::{
    connect_pg_pool, new_uuid_v4, run_locked_handoff, OutboxRepository,
    EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED,
};
use pizzora_payments::{format_razorpay_receipt, RazorpayReconciliationService};
use sqlx::PgPool;
use uuid::Uuid;

pub fn test_razorpay_config() -> RazorpayConfig {
    RazorpayConfig::new(
        "rzp_test_pizzora_rs",
        "rzp_secret_pizzora_rs_secret",
        "rzp_webhook_pizzora_rs_secret",
        "https://api.razorpay.com/v1",
    )
}

pub async fn setup_pool() -> PgPool {
    let cfg = AppConfig::from_env().expect("env config for PG 18.6 test");
    let pool = connect_pg_pool(&cfg.database)
        .await
        .expect("connect PG 18.6");
    run_locked_handoff(&pool).await.expect("handoff migrations");
    pool
}

#[allow(dead_code)]
pub struct SeededCustomerOrder {
    pub customer_id: Uuid,
    pub order_id: Uuid,
    pub payment_id: Uuid,
    pub initial_attempt_id: Uuid,
    pub total_paise: i64,
    pub ingredient_id: Option<Uuid>,
    pub coupon_code: Option<String>,
}

pub async fn seed_user(pool: &PgPool, prefix: &str) -> Uuid {
    let user_id = new_uuid_v4();
    let email = format!("{prefix}-{}@pizzora.test", user_id);
    sqlx::query(
        r#"
        INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active)
        VALUES ($1, $2, NOW(), '$argon2id$v=19$m=4096,t=1,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', 'Test User', '9800000001', TRUE)
        "#,
    )
    .bind(user_id)
    .bind(email)
    .execute(pool)
    .await
    .expect("seed user");
    user_id
}

#[allow(dead_code)]
pub async fn seed_owner_staff(pool: &PgPool) -> Uuid {
    let owner_id = seed_user(pool, "owner");
    let role_id: Uuid = sqlx::query_scalar("SELECT id FROM roles WHERE code = 'OWNER'")
        .fetch_one(pool)
        .await
        .expect("OWNER role");
    sqlx::query(
        "INSERT INTO staff (user_id, role_id, employee_code, is_active) VALUES ($1, $2, $3, TRUE)",
    )
    .bind(owner_id)
    .bind(role_id)
    .bind(format!("OWN-{}", &owner_id.simple().to_string()[..6]))
    .execute(pool)
    .await
    .expect("seed owner staff");
    owner_id
}

pub async fn seed_pending_razorpay_order(
    pool: &PgPool,
    customer_id: Uuid,
    with_inventory_and_coupon: bool,
) -> SeededCustomerOrder {
    let order_id = new_uuid_v4();
    let payment_id = new_uuid_v4();
    let attempt_id = new_uuid_v4();
    let total_paise = 50_000_i64;
    let order_num = format!("PZ-{}", &order_id.simple().to_string()[..8].to_uppercase());
    let receipt = format_razorpay_receipt(order_id, &attempt_id.to_string()).expect("receipt");

    let mut tx = pool.begin().await.expect("begin seed tx");
    sqlx::query(
        r#"
        INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash,
            fulfillment_type, schedule_type, status, version,
            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise,
            gst_bps_snapshot, expires_at
        ) VALUES ($1, $2, $3, $4, 'req_hash', 'PICKUP', 'ASAP', 'PENDING_PAYMENT', 1, $5, 0, 0, 0, $5, 500, NOW() + INTERVAL '15 minutes')
        "#,
    )
    .bind(order_id)
    .bind(&order_num)
    .bind(customer_id)
    .bind(format!("chk-{order_id}"))
    .bind(total_paise)
    .execute(&mut *tx)
    .await
    .expect("insert order");

    sqlx::query(
        r#"
        INSERT INTO payments (id, order_id, method, status, amount_paise, captured_paise, refunded_paise, currency)
        VALUES ($1, $2, 'RAZORPAY', 'UNPAID', $3, 0, 0, 'INR')
        "#,
    )
    .bind(payment_id)
    .bind(order_id)
    .bind(total_paise)
    .execute(&mut *tx)
    .await
    .expect("insert payment");

    sqlx::query(
        r#"
        INSERT INTO payment_attempts (
            id, payment_id, attempt_number, method, status, amount_paise, idempotency_receipt, expires_at
        ) VALUES ($1, $2, 1, 'RAZORPAY', 'INITIATING', $3, $4, NOW() + INTERVAL '15 minutes')
        "#,
    )
    .bind(attempt_id)
    .bind(payment_id)
    .bind(total_paise)
    .bind(&receipt)
    .execute(&mut *tx)
    .await
    .expect("insert payment attempt");

    let payload = serde_json::json!({
        "order_id": order_id.to_string(),
        "order_number": order_num,
        "customer_id": customer_id.to_string(),
        "payment_id": payment_id.to_string(),
        "payment_attempt_id": attempt_id.to_string(),
        "attempt_number": 1,
        "payment_method": "RAZORPAY",
        "amount_paise": total_paise,
        "receipt": receipt,
    });
    OutboxRepository::insert_outbox_event(
        &mut tx,
        "ORDER",
        order_id,
        1,
        EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED,
        &payload,
    )
    .await
    .expect("insert initial outbox event");

    let mut ingredient_id = None;
    let mut coupon_code = None;
    if with_inventory_and_coupon {
        let ing = new_uuid_v4();
        sqlx::query(
            "INSERT INTO ingredients (id, name, unit, low_stock_threshold, is_active) VALUES ($1, $2, 'GRAMS', 200, TRUE)",
        )
        .bind(ing)
        .bind(format!("Dough-{ing}"))
        .execute(&mut *tx)
        .await
        .expect("insert ingredient");
        sqlx::query(
            "INSERT INTO inventory (ingredient_id, on_hand_qty, reserved_qty) VALUES ($1, 1000, 250)",
        )
        .bind(ing)
        .execute(&mut *tx)
        .await
        .expect("insert inventory");
        sqlx::query(
            "INSERT INTO inventory_reservations (id, order_id, ingredient_id, quantity, status, expires_at) VALUES ($1, $2, $3, 250, 'ACTIVE', NOW() + INTERVAL '15 minutes')",
        )
        .bind(new_uuid_v4())
        .bind(order_id)
        .bind(ing)
        .execute(&mut *tx)
        .await
        .expect("insert reservation");
        ingredient_id = Some(ing);

        let cid = new_uuid_v4();
        let code = format!("RS-{}", &cid.simple().to_string()[..6].to_uppercase());
        sqlx::query(
            r#"
            INSERT INTO coupons (
                id, code, discount_type, discount_value, min_order_paise, max_uses,
                max_uses_per_user, usage_count, starts_at, ends_at, is_active
            ) VALUES ($1, $2, 'FLAT_PAISE', 5000, 10000, 10, 2, 0, NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 day', TRUE)
            "#,
        )
        .bind(cid)
        .bind(&code)
        .execute(&mut *tx)
        .await
        .expect("insert coupon");
        sqlx::query(
            "INSERT INTO coupon_reservations (id, coupon_id, user_id, order_id, discount_paise, status, expires_at) VALUES ($1, $2, $3, $4, 5000, 'ACTIVE', NOW() + INTERVAL '15 minutes')",
        )
        .bind(new_uuid_v4())
        .bind(cid)
        .bind(customer_id)
        .bind(order_id)
        .execute(&mut *tx)
        .await
        .expect("insert coupon reservation");
        coupon_code = Some(code);
    }

    tx.commit().await.expect("commit seed");
    SeededCustomerOrder {
        customer_id,
        order_id,
        payment_id,
        initial_attempt_id: attempt_id,
        total_paise,
        ingredient_id,
        coupon_code,
    }
}

#[allow(dead_code)]
pub async fn dispatch_all_pending_payment_outbox(
    reconciler: &RazorpayReconciliationService,
    outbox: &OutboxRepository,
) {
    let claimed = outbox.claim_payment_events(20, 30).await.expect("claim");
    for ev in claimed {
        match ev.event_type.as_str() {
            "PAYMENT_GATEWAY_ORDER_REQUESTED" => reconciler
                .dispatch_gateway_order_outbox_event(outbox, &ev, 5)
                .await
                .expect("dispatch order"),
            "REFUND_REQUESTED" => reconciler
                .dispatch_refund_outbox_event(outbox, &ev, 5)
                .await
                .expect("dispatch refund"),
            _ => {}
        }
    }
}
