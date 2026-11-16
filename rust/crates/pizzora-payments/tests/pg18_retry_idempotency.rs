mod common;

use pizzora_db::{new_uuid_v4, AuditContext};
use pizzora_payments::{
    format_razorpay_receipt, PaymentError, PaymentService, RetryPaymentRequest,
};
use sqlx::Row as _;

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_concurrent_retry_receipt_idempotency_and_conflicts() {
    let pool = common::setup_pool().await;
    let cfg = common::test_razorpay_config();
    let payment_service = PaymentService::new(pool.clone(), cfg);

    let customer_id = common::seed_user(&pool, "retry").await;
    let order1 = common::seed_pending_razorpay_order(&pool, customer_id, false).await;
    let order2 = common::seed_pending_razorpay_order(&pool, customer_id, false).await;

    let shared_key = format!("retry-race-{}-{}", new_uuid_v4(), "x".repeat(40));
    let retry_req = RetryPaymentRequest {
        reason: Some("Switching to UPI".to_owned()),
    };

    let mut handles = Vec::new();
    for _ in 0..6 {
        let svc = payment_service.clone();
        let oid = order1.order_id;
        let cid = customer_id;
        let k = shared_key.clone();
        let r = retry_req.clone();
        handles.push(tokio::spawn(async move {
            let audit = AuditContext {
                actor_user_id: Some(cid),
                actor_role: "CUSTOMER".to_owned(),
                correlation_id: new_uuid_v4().to_string(),
                ip_address: None,
            };
            svc.retry_payment(oid, cid, Some(&k), Some(&r), &audit).await
        }));
    }

    let mut attempt_ids = std::collections::HashSet::new();
    for h in handles {
        let resp = h.await.expect("join").expect("concurrent retry succeeds");
        attempt_ids.insert(
            resp.payment
                .latest_attempt
                .expect("latest_attempt")
                .id,
        );
    }
    assert_eq!(attempt_ids.len(), 1);

    let expected_receipt =
        format_razorpay_receipt(order1.order_id, &shared_key).expect("canonical receipt");
    assert_eq!(expected_receipt.len(), 37);

    let row = sqlx::query(
        r#"
        SELECT COUNT(*)::bigint AS total_attempts,
               MAX(attempt_number)::int AS max_num,
               (SELECT idempotency_receipt FROM payment_attempts WHERE payment_id = $1 AND attempt_number = 2) AS r2
        FROM payment_attempts
        WHERE payment_id = $1
        "#,
    )
    .bind(order1.payment_id)
    .fetch_one(&pool)
    .await
    .expect("count attempts");
    assert_eq!(row.get::<i64, _>("total_attempts"), 2);
    assert_eq!(row.get::<i32, _>("max_num"), 2);
    assert_eq!(row.get::<String, _>("r2"), expected_receipt);

    // Conflicting reason on same (order1, shared_key) -> 409 IDEMPOTENCY_KEY_CONFLICT
    let audit = AuditContext {
        actor_user_id: Some(customer_id),
        actor_role: "CUSTOMER".to_owned(),
        correlation_id: new_uuid_v4().to_string(),
        ip_address: None,
    };
    let conflict_reason = payment_service
        .retry_payment(
            order1.order_id,
            customer_id,
            Some(&shared_key),
            Some(&RetryPaymentRequest {
                reason: Some("Different reason".to_owned()),
            }),
            &audit,
        )
        .await
        .expect_err("different reason must return 409");
    assert!(matches!(
        conflict_reason,
        PaymentError::Conflict {
            code: "IDEMPOTENCY_KEY_CONFLICT",
            ..
        }
    ));

    // Cross-order Idempotency-Key replay on order2 -> 409 IDEMPOTENCY_KEY_CONFLICT
    let cross_order = payment_service
        .retry_payment(
            order2.order_id,
            customer_id,
            Some(&shared_key),
            Some(&retry_req),
            &audit,
        )
        .await
        .expect_err("cross-order key reuse must return 409");
    assert!(matches!(
        cross_order,
        PaymentError::Conflict {
            code: "IDEMPOTENCY_KEY_CONFLICT",
            ..
        }
    ));
}
