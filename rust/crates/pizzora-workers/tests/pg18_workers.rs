use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::body::to_bytes;
use axum::http::{Request, StatusCode};
use chrono::{Duration as ChronoDuration, Utc};
use pizzora_auth::{
    format_outbox_message_id, seal_outbox_email_payload, AuthService, EmailDeliveryProvider,
    EmailTemplateKind, InMemoryEmailDeliveryProvider, PasswordHasher, RegisterRequest,
    VerifyEmailRequest,
};
use pizzora_config::{AppConfig, OutboxEncryptionConfig};
use pizzora_core::{OrderStatus, PaymentAttemptStatus, PaymentStatus, RefundStatus};
use pizzora_db::{
    connect_pg_pool, new_uuid_v4, run_locked_handoff, InMemoryCacheStore, OutboxRepository,
    EVENT_EMAIL_DELIVERY_REQUESTED, EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED,
};
use pizzora_payments::{format_razorpay_receipt, GatewayPaymentResult, InMemoryRazorpayGateway};
use pizzora_workers::{
    EventSequencerWorker, WorkerHealthResponse, WorkerReadinessState, WorkerRuntime, LOOP_SEQUENCER,
};
use sqlx::{PgPool, Row as _};
use tower::ServiceExt as _;
use tracing::field::{Field, Visit};
use tracing::{Event, Metadata, Subscriber};
use tracing_subscriber::layer::{Context, SubscriberExt as _};
use tracing_subscriber::{Layer, Registry};
use uuid::Uuid;

async fn setup_pool_and_config() -> (AppConfig, PgPool) {
    let mut cfg = AppConfig::from_env().expect("AppConfig::from_env must succeed");
    cfg.worker.lease_seconds = 1;
    let pool = connect_pg_pool(&cfg.database)
        .await
        .expect("Connect to PostgreSQL 18.6");
    run_locked_handoff(&pool)
        .await
        .expect("Apply / adopt migrations cleanly");
    (cfg, pool)
}

async fn seed_customer(pool: &PgPool, prefix: &str) -> (Uuid, String) {
    let user_id = new_uuid_v4();
    let email = format!("{prefix}_{}@pizzora.test", user_id.simple());
    sqlx::query(
        r#"
        INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active)
        VALUES ($1, $2, NOW(), '$argon2id$v=19$m=4096,t=1,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', 'Worker Test Customer', '9800000099', TRUE)
        "#,
    )
    .bind(user_id)
    .bind(&email)
    .execute(pool)
    .await
    .expect("seed customer");
    (user_id, email)
}

struct LogCollectorVisitor<'a>(&'a mut String);

impl Visit for LogCollectorVisitor<'_> {
    fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
        use std::fmt::Write as _;
        let _ = write!(self.0, "{}={:?} ", field.name(), value);
    }
}

struct CapturingLayer(Arc<Mutex<Vec<String>>>);

impl<S: Subscriber> Layer<S> for CapturingLayer {
    fn enabled(&self, _metadata: &Metadata<'_>, _ctx: Context<'_, S>) -> bool {
        true
    }

    fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
        let mut buf = String::new();
        event.record(&mut LogCollectorVisitor(&mut buf));
        self.0.lock().expect("log mutex").push(buf);
    }
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_sequencer_advisory_lock_assigns_monotonic_gap_free_sequences() {
    let (_cfg, pool) = setup_pool_and_config().await;
    let agg_id = new_uuid_v4();

    let mut tx = pool.begin().await.expect("tx");
    let mut inserted_ids = Vec::new();
    for idx in 1..=6_i64 {
        let eid = OutboxRepository::insert_outbox_event(
            &mut tx,
            "OWNER",
            agg_id,
            idx,
            "OWNER_DASHBOARD_UPDATED",
            &serde_json::json!({ "seq_probe": idx }),
        )
        .await
        .expect("insert outbox");
        inserted_ids.push(eid);
    }
    tx.commit().await.expect("commit");

    let seq_worker = EventSequencerWorker::new(pool.clone(), 100);
    let barrier = Arc::new(tokio::sync::Barrier::new(2));
    let w1 = seq_worker.clone();
    let b1 = barrier.clone();
    let t1 = tokio::spawn(async move {
        b1.wait().await;
        w1.run_once().await.expect("worker 1")
    });
    let w2 = seq_worker.clone();
    let b2 = barrier.clone();
    let t2 = tokio::spawn(async move {
        b2.wait().await;
        w2.run_once().await.expect("worker 2")
    });
    let (c1, c2) = tokio::join!(t1, t2);
    assert!(c1.unwrap() + c2.unwrap() >= 6);

    let seqs: Vec<Option<i64>> = sqlx::query_scalar(
        "SELECT stream_seq FROM outbox_events WHERE id = ANY($1) ORDER BY created_at ASC, id ASC",
    )
    .bind(&inserted_ids)
    .fetch_all(&pool)
    .await
    .expect("query seqs");

    assert_eq!(seqs.len(), 6);
    let unwrapped: Vec<i64> = seqs
        .into_iter()
        .map(|s| s.expect("every row must have stream_seq"))
        .collect();
    for pair in unwrapped.windows(2) {
        assert_eq!(
            pair[1],
            pair[0] + 1,
            "stream_seq must remain strictly monotonic and gap-free"
        );
    }
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_email_post_smtp_crash_window_recovers_and_keeps_single_use_tokens() {
    let (mut cfg, pool) = setup_pool_and_config().await;
    let unique = new_uuid_v4();
    let email = format!("crash_email_{}@example.com", unique.simple());

    // Isolate this test from unrelated pre-existing unsequenced email rows by checking specific token_record_id.
    let auth = AuthService::new(
        pool.clone(),
        cfg.auth.clone(),
        cfg.outbox_encryption.clone(),
        PasswordHasher::new(4096, 1, 1, 32),
    );
    auth.register(&RegisterRequest {
        email: email.clone(),
        password: "StrongPassword123!".to_owned(),
        full_name: "Crash Recovery User".to_owned(),
        phone: "9876543210".to_owned(),
    })
    .await
    .expect("register succeeds");

    let row = sqlx::query(
        r#"
        SELECT id, payload_json
        FROM outbox_events
        WHERE event_type = $1
        ORDER BY created_at DESC
        LIMIT 1
        "#,
    )
    .bind(EVENT_EMAIL_DELIVERY_REQUESTED)
    .fetch_one(&pool)
    .await
    .expect("email outbox row exists");
    let target_event_id: Uuid = row.try_get("id").unwrap();
    let raw_payload: serde_json::Value = row.try_get("payload_json").unwrap();
    assert!(!raw_payload.to_string().contains(&email));

    let email_provider = Arc::new(InMemoryEmailDeliveryProvider::new(cfg.smtp.from_domain()));
    email_provider.inject_failure_after_accept(
        true,
        "POST_250_CRASH",
        "Simulated crash after SMTP 250 OK before DISPATCHED commit",
    );

    cfg.worker.lease_seconds = 1;
    let runtime = WorkerRuntime::from_components(
        cfg.clone(),
        pool.clone(),
        Arc::new(InMemoryCacheStore::new()),
        Arc::new(InMemoryRazorpayGateway::new("rzp_test_mem")),
        email_provider.clone(),
        None,
    );

    // 1st attempt: delivers email via mock SMTP, injects post-250 failure -> row scheduled for retry
    runtime
        .dispatcher
        .run_once()
        .await
        .expect("dispatch pass 1");
    let first_batch = email_provider.sent_emails();
    let matching_first: Vec<_> = first_batch
        .iter()
        .filter(|m| m.outbox_event_id == target_event_id)
        .collect();
    assert_eq!(matching_first.len(), 1);
    let expected_msg_id = format_outbox_message_id(target_event_id, cfg.smtp.from_domain());
    assert_eq!(matching_first[0].message_id, expected_msg_id);
    let captured_token = matching_first[0].raw_token.clone();

    // Row must NOT be DISPATCHED yet, and must also test stale PROCESSING lease recovery
    let status_after_1: String =
        sqlx::query_scalar("SELECT dispatch_status FROM outbox_events WHERE id = $1")
            .bind(target_event_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_ne!(status_after_1, "DISPATCHED");

    // Simulate leased PROCESSING crash recovery by setting status='PROCESSING' and expired lease timestamp
    sqlx::query(
        "UPDATE outbox_events SET dispatch_status = 'PROCESSING', next_attempt_at = NOW() - INTERVAL '2 seconds' WHERE id = $1",
    )
    .bind(target_event_id)
    .execute(&pool)
    .await
    .unwrap();

    // 2nd attempt recovers lease, re-sends idempotently with same outbox_event_id & Message-ID, marks DISPATCHED & scrubs envelope
    runtime
        .dispatcher
        .run_once()
        .await
        .expect("dispatch pass 2");
    let second_batch = email_provider.sent_emails();
    let matching_second: Vec<_> = second_batch
        .iter()
        .filter(|m| m.outbox_event_id == target_event_id)
        .collect();
    assert_eq!(matching_second.len(), 2);
    assert_eq!(matching_second[1].message_id, expected_msg_id);

    let after_2 = sqlx::query(
        "SELECT dispatch_status, published_at, payload_json FROM outbox_events WHERE id = $1",
    )
    .bind(target_event_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(after_2.get::<String, _>("dispatch_status"), "DISPATCHED");
    assert!(after_2
        .get::<Option<chrono::DateTime<Utc>>, _>("published_at")
        .is_some());
    let scrubbed: serde_json::Value = after_2.try_get("payload_json").unwrap();
    assert_eq!(scrubbed["sealed_envelope"], serde_json::Value::Null);
    assert_eq!(scrubbed["redacted"], serde_json::Value::Bool(true));

    // Verification token works once and is rejected on replay
    auth.verify_email(&VerifyEmailRequest {
        token: captured_token.clone(),
    })
    .await
    .expect("first verify succeeds");
    assert!(auth
        .verify_email(&VerifyEmailRequest {
            token: captured_token,
        })
        .await
        .is_err());
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_email_security_key_rotation_aad_tamper_dead_letter_scrub_purge_and_zero_log_leakage()
{
    let (mut cfg, pool) = setup_pool_and_config().await;
    let captured_logs = Arc::new(Mutex::new(Vec::new()));
    let subscriber = Registry::default().with(CapturingLayer(captured_logs.clone()));
    let _log_guard = tracing::subscriber::set_default(subscriber);

    let k1_raw = "k1:AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    let k2_raw = "k2:AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";
    let crypto_v1 = OutboxEncryptionConfig::parse_from_raw("k1", k1_raw, false).unwrap();
    let crypto_rotated =
        OutboxEncryptionConfig::parse_from_raw("k2", &format!("{k1_raw},{k2_raw}"), false).unwrap();
    cfg.outbox_encryption = crypto_rotated.clone();

    let (user_id, email) = seed_customer(&pool, "sec").await;
    let secret_tok_rot = "raw_secret_token_rotation_test_9911";
    let secret_tok_tamper = "raw_secret_token_tamper_test_8822";
    let secret_tok_perm = "raw_secret_token_perm_fail_test_7733";
    let secret_tok_exp = "raw_secret_token_ttl_expired_6644";

    // 1. Key rotation event: sealed under k1, unsealed cleanly by rotated keyring (current=k2)
    let rot_event_id = new_uuid_v4();
    let rot_payload = seal_outbox_email_payload(
        &crypto_v1,
        rot_event_id,
        user_id,
        EVENT_EMAIL_DELIVERY_REQUESTED,
        EmailTemplateKind::Verification,
        new_uuid_v4(),
        Utc::now() + ChronoDuration::hours(1),
        &email,
        "Rotation User",
        secret_tok_rot,
    )
    .unwrap();
    assert!(!serde_json::to_string(&rot_payload)
        .unwrap()
        .contains(secret_tok_rot));

    // 2. Tampered AAD event: sealed for one event_id, inserted under different event_id
    let tamper_event_id = new_uuid_v4();
    let tamper_payload = seal_outbox_email_payload(
        &crypto_v1,
        new_uuid_v4(), // wrong event_id triggers AAD tamper rejection
        user_id,
        EVENT_EMAIL_DELIVERY_REQUESTED,
        EmailTemplateKind::PasswordReset,
        new_uuid_v4(),
        Utc::now() + ChronoDuration::minutes(15),
        &email,
        "Tamper User",
        secret_tok_tamper,
    )
    .unwrap();

    // 3. Expired token event + old historical dispatched row for purge verification
    let exp_event_id = new_uuid_v4();
    let exp_payload = seal_outbox_email_payload(
        &crypto_v1,
        exp_event_id,
        user_id,
        EVENT_EMAIL_DELIVERY_REQUESTED,
        EmailTemplateKind::PasswordReset,
        new_uuid_v4(),
        Utc::now() - ChronoDuration::minutes(10),
        &email,
        "Expired User",
        secret_tok_exp,
    )
    .unwrap();

    let mut tx = pool.begin().await.unwrap();
    OutboxRepository::insert_outbox_event_with_id(
        &mut tx,
        rot_event_id,
        "USER",
        user_id,
        1,
        EVENT_EMAIL_DELIVERY_REQUESTED,
        &serde_json::to_value(&rot_payload).unwrap(),
    )
    .await
    .unwrap();
    OutboxRepository::insert_outbox_event_with_id(
        &mut tx,
        tamper_event_id,
        "USER",
        user_id,
        1,
        EVENT_EMAIL_DELIVERY_REQUESTED,
        &serde_json::to_value(&tamper_payload).unwrap(),
    )
    .await
    .unwrap();
    OutboxRepository::insert_outbox_event_with_id(
        &mut tx,
        exp_event_id,
        "USER",
        user_id,
        1,
        EVENT_EMAIL_DELIVERY_REQUESTED,
        &serde_json::to_value(&exp_payload).unwrap(),
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    // Pause expired-envelope test row from immediate dispatch so ExpirySweepWorker scrubs it in PENDING state
    sqlx::query(
        "UPDATE outbox_events SET next_attempt_at = NOW() + INTERVAL '1 hour' WHERE id = $1",
    )
    .bind(exp_event_id)
    .execute(&pool)
    .await
    .unwrap();

    let email_provider = Arc::new(InMemoryEmailDeliveryProvider::new("pizzora.local"));
    let runtime = WorkerRuntime::from_components(
        cfg.clone(),
        pool.clone(),
        Arc::new(InMemoryCacheStore::new()),
        Arc::new(InMemoryRazorpayGateway::new("rzp_test_mem")),
        email_provider.clone(),
        None,
    );

    runtime
        .dispatcher
        .run_once()
        .await
        .expect("dispatch rot + tamper");

    // Verify rotated key delivered cleanly & scrubbed
    assert!(email_provider
        .sent_emails()
        .iter()
        .any(|s| s.outbox_event_id == rot_event_id && s.raw_token == secret_tok_rot));
    let rot_status: String =
        sqlx::query_scalar("SELECT dispatch_status FROM outbox_events WHERE id = $1")
            .bind(rot_event_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(rot_status, "DISPATCHED");

    // Verify tampered AAD moved to DEAD_LETTER, published_at NULL, envelope scrubbed, and audit logged without secrets
    let tamper_row = sqlx::query(
        "SELECT dispatch_status, published_at, last_error, payload_json FROM outbox_events WHERE id = $1",
    )
    .bind(tamper_event_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(
        tamper_row.get::<String, _>("dispatch_status"),
        "DEAD_LETTER"
    );
    assert!(tamper_row
        .get::<Option<chrono::DateTime<Utc>>, _>("published_at")
        .is_none());
    let tamper_payload_db: serde_json::Value = tamper_row.try_get("payload_json").unwrap();
    assert_eq!(
        tamper_payload_db["sealed_envelope"],
        serde_json::Value::Null
    );
    assert_eq!(tamper_payload_db["redacted"], serde_json::Value::Bool(true));
    let tamper_err: String = tamper_row
        .try_get::<Option<String>, _>("last_error")
        .unwrap()
        .unwrap_or_default();
    assert!(!tamper_err.contains(secret_tok_tamper));
    assert!(!tamper_err.contains(&email));

    let audit_count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'EMAIL_DELIVERY_DEAD_LETTER' AND entity_id = $1",
    )
    .bind(tamper_event_id.to_string())
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(audit_count, 1);

    // 4. Permanent SMTP error containing sensitive tokens in raw provider error -> sanitized + DEAD_LETTER + scrubbed
    let perm_event_id = new_uuid_v4();
    let perm_payload = seal_outbox_email_payload(
        &crypto_rotated,
        perm_event_id,
        user_id,
        EVENT_EMAIL_DELIVERY_REQUESTED,
        EmailTemplateKind::PasswordReset,
        new_uuid_v4(),
        Utc::now() + ChronoDuration::minutes(15),
        &email,
        "PermFail User",
        secret_tok_perm,
    )
    .unwrap();
    let mut tx2 = pool.begin().await.unwrap();
    OutboxRepository::insert_outbox_event_with_id(
        &mut tx2,
        perm_event_id,
        "USER",
        user_id,
        1,
        EVENT_EMAIL_DELIVERY_REQUESTED,
        &serde_json::to_value(&perm_payload).unwrap(),
    )
    .await
    .unwrap();
    tx2.commit().await.unwrap();

    email_provider.inject_failure_before_send(
        false,
        "SMTP_550",
        &format!("Mailbox unavailable for {email} token={secret_tok_perm}"),
    );
    runtime
        .dispatcher
        .run_once()
        .await
        .expect("dispatch perm fail");
    let perm_row = sqlx::query(
        "SELECT dispatch_status, last_error, payload_json FROM outbox_events WHERE id = $1",
    )
    .bind(perm_event_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(perm_row.get::<String, _>("dispatch_status"), "DEAD_LETTER");
    assert_eq!(
        perm_row.get::<serde_json::Value, _>("payload_json")["sealed_envelope"],
        serde_json::Value::Null
    );
    let perm_err = perm_row
        .get::<Option<String>, _>("last_error")
        .unwrap_or_default();
    assert!(!perm_err.contains(secret_tok_perm));
    assert!(!perm_err.contains(&email));

    // 5. ExpirySweepWorker scrubs expired token_expires_at envelopes and purges rows older than retention_days
    sqlx::query("UPDATE outbox_events SET created_at = NOW() - INTERVAL '10 days' WHERE id = $1")
        .bind(rot_event_id)
        .execute(&pool)
        .await
        .unwrap();

    let sweep = runtime.expiry_sweep.run_once().await.expect("expiry sweep");
    assert!(sweep.scrubbed_envelopes >= 1);
    assert!(sweep.purged_email_rows >= 1);

    let exp_payload_db: serde_json::Value =
        sqlx::query_scalar("SELECT payload_json FROM outbox_events WHERE id = $1")
            .bind(exp_event_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(exp_payload_db["sealed_envelope"], serde_json::Value::Null);
    assert_eq!(exp_payload_db["redacted"], serde_json::Value::Bool(true));

    let purged_exists: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM outbox_events WHERE id = $1")
        .bind(rot_event_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(purged_exists, 0);

    // 6. Assert zero plaintext tokens or emails ever reached tracing logs
    let all_logs = captured_logs.lock().unwrap().join("\n");
    for secret in [
        secret_tok_rot,
        secret_tok_tamper,
        secret_tok_perm,
        secret_tok_exp,
        &email,
    ] {
        assert!(
            !all_logs.contains(secret),
            "Captured tracing logs leaked sensitive value!"
        );
    }
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_expiry_sweep_reconciler_broadcast_health_metrics_and_graceful_shutdown() {
    let (cfg, pool) = setup_pool_and_config().await;
    let (customer_id, _) = seed_customer(&pool, "exp_cust").await;

    // Seed expired PENDING_PAYMENT order with inventory, coupon, scheduled slot, and pending gateway outbox row
    let order_id = new_uuid_v4();
    let payment_id = new_uuid_v4();
    let attempt_id = new_uuid_v4();
    let slot_id = new_uuid_v4();
    let ing_id = new_uuid_v4();
    let coupon_id = new_uuid_v4();
    let receipt = format_razorpay_receipt(order_id, &attempt_id.to_string()).unwrap();

    let mut tx = pool.begin().await.unwrap();
    sqlx::query(
        r#"
        INSERT INTO scheduled_slots (
            id, slot_date, fulfillment_type, start_time_ist, end_time_ist, cutoff_at, max_capacity, reserved_count, is_active
        ) VALUES ($1, CURRENT_DATE + 1, 'DELIVERY', '19:00', '19:30', NOW() + INTERVAL '2 hours', 10, 1, TRUE)
        ON CONFLICT (slot_date, fulfillment_type, start_time_ist)
        DO UPDATE SET reserved_count = GREATEST(scheduled_slots.reserved_count, 1)
        RETURNING id
        "#,
    )
    .bind(slot_id)
    .fetch_one(&mut *tx)
    .await
    .ok();

    // Re-read slot_id in case date collided with existing slot from previous run
    let actual_slot_id: Uuid = sqlx::query_scalar(
        "SELECT id FROM scheduled_slots WHERE slot_date = CURRENT_DATE + 1 AND fulfillment_type = 'DELIVERY' AND start_time_ist = '19:00'",
    )
    .fetch_one(&mut *tx)
    .await
    .unwrap();
    let initial_reserved: i32 =
        sqlx::query_scalar("SELECT reserved_count FROM scheduled_slots WHERE id = $1")
            .bind(actual_slot_id)
            .fetch_one(&mut *tx)
            .await
            .unwrap();

    sqlx::query(
        r#"
        INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash,
            fulfillment_type, schedule_type, scheduled_slot_id, status, version,
            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise,
            gst_bps_snapshot, expires_at
        ) VALUES ($1, $2, $3, $4, 'hash', 'DELIVERY', 'SCHEDULED', $5, 'PENDING_PAYMENT', 1, 50000, 0, 0, 0, 50000, 500, NOW() - INTERVAL '5 minutes')
        "#,
    )
    .bind(order_id)
    .bind(format!("PZ-EXP-{}", &order_id.simple().to_string()[..6].to_uppercase()))
    .bind(customer_id)
    .bind(format!("exp-{order_id}"))
    .bind(actual_slot_id)
    .execute(&mut *tx)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO payments (id, order_id, method, status, amount_paise) VALUES ($1, $2, 'RAZORPAY', 'UNPAID', 50000)",
    )
    .bind(payment_id)
    .bind(order_id)
    .execute(&mut *tx)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO payment_attempts (id, payment_id, attempt_number, method, status, amount_paise, idempotency_receipt) VALUES ($1, $2, 1, 'RAZORPAY', 'INITIATING', 50000, $3)",
    )
    .bind(attempt_id)
    .bind(payment_id)
    .bind(&receipt)
    .execute(&mut *tx)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO ingredients (id, name, unit, low_stock_threshold) VALUES ($1, $2, 'GRAMS', 100)",
    )
    .bind(ing_id)
    .bind(format!("Cheese-{}", &ing_id.simple().to_string()[..6]))
    .execute(&mut *tx)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO inventory (ingredient_id, on_hand_qty, reserved_qty) VALUES ($1, 1000, 200)",
    )
    .bind(ing_id)
    .execute(&mut *tx)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO inventory_reservations (id, order_id, ingredient_id, quantity, status, expires_at) VALUES ($1, $2, $3, 200, 'ACTIVE', NOW() - INTERVAL '1 minute')",
    )
    .bind(new_uuid_v4())
    .bind(order_id)
    .bind(ing_id)
    .execute(&mut *tx)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO coupons (id, code, discount_type, discount_value, min_order_paise, starts_at, ends_at) VALUES ($1, $2, 'FLAT_PAISE', 5000, 10000, NOW() - INTERVAL '1 day', NOW() + INTERVAL '1 day')",
    )
    .bind(coupon_id)
    .bind(format!("EXP-{}", &coupon_id.simple().to_string()[..6]))
    .execute(&mut *tx)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO coupon_reservations (id, coupon_id, user_id, order_id, discount_paise, status, expires_at) VALUES ($1, $2, $3, $4, 5000, 'ACTIVE', NOW() - INTERVAL '1 minute')",
    )
    .bind(new_uuid_v4())
    .bind(coupon_id)
    .bind(customer_id)
    .bind(order_id)
    .execute(&mut *tx)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO scheduled_slot_reservations (id, slot_id, order_id, status, expires_at) VALUES ($1, $2, $3, 'ACTIVE', NOW() - INTERVAL '1 minute')",
    )
    .bind(new_uuid_v4())
    .bind(actual_slot_id)
    .bind(order_id)
    .execute(&mut *tx)
    .await
    .unwrap();

    let gw_outbox_id = OutboxRepository::insert_outbox_event(
        &mut tx,
        "ORDER",
        order_id,
        1,
        EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED,
        &serde_json::json!({
            "order_id": order_id.to_string(),
            "payment_attempt_id": attempt_id.to_string(),
            "amount_paise": 50000
        }),
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    let cache = Arc::new(InMemoryCacheStore::new());
    let gateway = Arc::new(InMemoryRazorpayGateway::new("rzp_test_mem"));
    let email_provider: Arc<dyn EmailDeliveryProvider> =
        Arc::new(InMemoryEmailDeliveryProvider::new("pizzora.local"));

    let runtime = WorkerRuntime::from_components(
        cfg,
        pool.clone(),
        cache.clone(),
        gateway.clone(),
        email_provider,
        None,
    );

    let report = runtime.run_all_once().await.expect("run_all_once succeeds");
    assert!(report.expiry.expired_orders >= 1);
    assert!(report.sequenced_events >= 1);

    // Check order EXPIRED & reservations released
    let ord_status: String = sqlx::query_scalar("SELECT status FROM orders WHERE id = $1")
        .bind(order_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(ord_status, OrderStatus::Expired.as_str());

    let reserved_qty: i64 =
        sqlx::query_scalar("SELECT reserved_qty FROM inventory WHERE ingredient_id = $1")
            .bind(ing_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(reserved_qty, 0);

    let slot_reserved_after: i32 =
        sqlx::query_scalar("SELECT reserved_count FROM scheduled_slots WHERE id = $1")
            .bind(actual_slot_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(slot_reserved_after, initial_reserved - 1);

    let gw_outbox_status: String =
        sqlx::query_scalar("SELECT dispatch_status FROM outbox_events WHERE id = $1")
            .bind(gw_outbox_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(gw_outbox_status, "DISPATCHED");

    // Check notification_inbox created for expired order with SENT_WS status
    let inbox_count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM notification_inbox WHERE user_id = $1 AND reference_id = $2 AND delivery_status = 'SENT_WS'",
    )
    .bind(customer_id)
    .bind(order_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(inbox_count, 1);

    // Also test Redis unavailable fallback marks notification_inbox as PENDING
    cache.set_unavailable(true);
    let mut tx_notif = pool.begin().await.unwrap();
    let placed_id = OutboxRepository::insert_outbox_event(
        &mut tx_notif,
        "ORDER",
        order_id,
        3,
        "ORDER_PLACED",
        &serde_json::json!({
            "order_number": "PZ-FALLBACK",
            "customer_id": customer_id.to_string(),
        }),
    )
    .await
    .unwrap();
    tx_notif.commit().await.unwrap();
    runtime.sequencer.run_once().await.unwrap();
    runtime.dispatcher.run_once().await.unwrap();

    let fallback_status: String = sqlx::query_scalar(
        "SELECT delivery_status FROM notification_inbox WHERE user_id = $1 AND event_id = $2",
    )
    .bind(customer_id)
    .bind(placed_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(fallback_status, "PENDING");

    // Verify reconciler catches open AWAITING_CUSTOMER_PAYMENT capture
    let order2 = new_uuid_v4();
    let pay2 = new_uuid_v4();
    let att2 = new_uuid_v4();
    let gwo2 = format!("order_reconcile_{}", &order2.simple().to_string()[..8]);
    let gwp2 = format!("pay_reconcile_{}", &order2.simple().to_string()[..8]);
    gateway.insert_mock_payment_for_order(
        &gwo2,
        GatewayPaymentResult {
            gateway_payment_id: gwp2.clone(),
            gateway_order_id: gwo2.clone(),
            amount_paise: 42_000,
            currency: "INR".to_owned(),
            status: "captured".to_owned(),
            error_code: None,
            error_description: None,
        },
    );
    let mut tx3 = pool.begin().await.unwrap();
    sqlx::query(
        r#"
        INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash,
            fulfillment_type, schedule_type, status, version,
            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise,
            gst_bps_snapshot, expires_at
        ) VALUES ($1, $2, $3, $4, 'hash', 'PICKUP', 'ASAP', 'PENDING_PAYMENT', 1, 42000, 0, 0, 0, 42000, 500, NOW() + INTERVAL '10 minutes')
        "#,
    )
    .bind(order2)
    .bind(format!("PZ-REC-{}", &order2.simple().to_string()[..6].to_uppercase()))
    .bind(customer_id)
    .bind(format!("rec-{order2}"))
    .execute(&mut *tx3)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO payments (id, order_id, method, status, amount_paise) VALUES ($1, $2, 'RAZORPAY', 'UNPAID', 42000)",
    )
    .bind(pay2)
    .bind(order2)
    .execute(&mut *tx3)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO payment_attempts (id, payment_id, attempt_number, method, status, amount_paise, idempotency_receipt, gateway_order_id) VALUES ($1, $2, 1, 'RAZORPAY', 'AWAITING_CUSTOMER_PAYMENT', 42000, $3, $4)",
    )
    .bind(att2)
    .bind(pay2)
    .bind(format_razorpay_receipt(order2, &att2.to_string()).unwrap())
    .bind(&gwo2)
    .execute(&mut *tx3)
    .await
    .unwrap();
    tx3.commit().await.unwrap();

    let reconciled = runtime.reconciler.run_once().await.expect("reconcile");
    assert!(reconciled >= 1);
    let reconciled_pay_status: String =
        sqlx::query_scalar("SELECT status FROM payments WHERE id = $1")
            .bind(pay2)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(reconciled_pay_status, PaymentStatus::Captured.as_str());
    let reconciled_att_status: String =
        sqlx::query_scalar("SELECT status FROM payment_attempts WHERE id = $1")
            .bind(att2)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(
        reconciled_att_status,
        PaymentAttemptStatus::Captured.as_str()
    );

    // Spawn background loops -> verify HTTP /health/worker + /metrics -> graceful shutdown_and_drain
    let handles = runtime.spawn_background_loops();
    tokio::time::sleep(Duration::from_millis(250)).await;

    let app = runtime.build_http_router();
    let health_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/health/worker")
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(health_resp.status(), StatusCode::OK);
    let health_json: WorkerHealthResponse =
        serde_json::from_slice(&to_bytes(health_resp.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(health_json.status, WorkerReadinessState::Healthy);
    assert_eq!(health_json.service, "pizzora-worker");

    let metrics_resp = app
        .oneshot(
            Request::builder()
                .uri("/metrics")
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(metrics_resp.status(), StatusCode::OK);
    let metrics_text = String::from_utf8(
        to_bytes(metrics_resp.into_body(), usize::MAX)
            .await
            .unwrap()
            .to_vec(),
    )
    .unwrap();
    assert!(metrics_text.contains("pizzora_worker_uptime_seconds"));
    assert!(metrics_text.contains("pizzora_stream_last_seq"));

    runtime.shutdown_and_drain(handles).await;
    let loop_snapshot = runtime.metrics.loop_health_snapshot();
    assert_eq!(loop_snapshot.get(LOOP_SEQUENCER), Some(&false));

    // Keep RefundStatus / HashMap referenced cleanly
    let _: (&str, usize) = (
        RefundStatus::Failed.as_str(),
        HashMap::<String, String>::new().len(),
    );
}
