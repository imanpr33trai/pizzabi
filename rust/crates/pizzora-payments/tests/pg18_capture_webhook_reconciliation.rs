mod common;

use std::sync::Arc;

use chrono::{Duration, Utc};
use pizzora_core::{OrderStatus, PaymentAttemptStatus, PaymentMethod, PaymentStatus, RefundStatus};
use pizzora_db::{new_uuid_v4, AuditContext, OutboxRepository};
use pizzora_payments::{
    compute_hmac_sha256_hex, GatewayPaymentResult, InMemoryRazorpayGateway, OwnerRefundRequest,
    PaymentError, PaymentService, RazorpayReconciliationService, VerifyRazorpayPaymentRequest,
    WebhookService,
};
use sqlx::Row as _;

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_verify_concurrent_webhook_mismatch_late_capture_and_reconciliation() {
    let pool = common::setup_pool().await;
    let cfg = common::test_razorpay_config();
    let gateway = Arc::new(InMemoryRazorpayGateway::new("rzp_test_pizzora_rs"));
    let payment_service =
        PaymentService::with_public_key_id(pool.clone(), cfg.clone(), gateway.public_key_id());
    let webhook_service = WebhookService::new(payment_service.clone(), cfg.clone());
    let outbox_repo = OutboxRepository::new(pool.clone());
    let reconciler =
        RazorpayReconciliationService::new(pool.clone(), gateway.clone(), payment_service.clone(), 20);

    let owner_id = common::seed_owner_staff(&pool).await;
    let customer_id = common::seed_user(&pool, "flow").await;
    let order1 = common::seed_pending_razorpay_order(&pool, customer_id, true).await;

    common::dispatch_all_pending_payment_outbox(&reconciler, &outbox_repo).await;
    assert_eq!(gateway.created_orders().len(), 1);

    let status1 = payment_service
        .get_order_payment_status(order1.order_id, customer_id, pizzora_core::UserRole::Customer)
        .await
        .expect("get status");
    let latest1 = status1.payment.latest_attempt.expect("latest attempt");
    assert_eq!(latest1.status, PaymentAttemptStatus::AwaitingCustomerPayment);
    let gw_order_id = latest1.gateway_order_id.expect("gw order id");

    let audit_cust = AuditContext {
        actor_user_id: Some(customer_id),
        actor_role: "CUSTOMER".to_owned(),
        correlation_id: new_uuid_v4().to_string(),
        ip_address: None,
    };

    // Invalid checkout signature -> Unauthorized + PAYMENT_SIGNATURE_INVALID audit
    let bad_sig_err = payment_service
        .verify_client_payment(
            customer_id,
            &VerifyRazorpayPaymentRequest {
                order_id: order1.order_id.to_string(),
                razorpay_order_id: gw_order_id.clone(),
                razorpay_payment_id: "pay_bad_sig".to_owned(),
                razorpay_signature: "deadbeef".to_owned(),
            },
            &audit_cust,
        )
        .await
        .expect_err("invalid signature must fail");
    assert!(matches!(
        bad_sig_err,
        PaymentError::Unauthorized {
            code: "INVALID_PAYMENT_SIGNATURE",
            ..
        }
    ));

    let gw_payment_id = format!("pay_race_{}", &new_uuid_v4().simple().to_string()[..8]);
    let wh_body = serde_json::json!({
        "id": format!("evt_race_{}", new_uuid_v4()),
        "event": "payment.captured",
        "created_at": 1_763_049_600_i64,
        "payload": {
            "payment": {
                "entity": {
                    "id": gw_payment_id,
                    "order_id": gw_order_id,
                    "amount": order1.total_paise,
                    "currency": "INR",
                    "status": "captured"
                }
            }
        }
    })
    .to_string();

    // Tampered webhook signature -> Unauthorized
    let audit_sys = AuditContext {
        actor_user_id: None,
        actor_role: "SYSTEM".to_owned(),
        correlation_id: new_uuid_v4().to_string(),
        ip_address: None,
    };
    let bad_wh_err = webhook_service
        .handle_razorpay_webhook(wh_body.as_bytes(), Some("bad_hex"), None, &audit_sys)
        .await
        .expect_err("bad webhook hmac");
    assert!(matches!(
        bad_wh_err,
        PaymentError::Unauthorized {
            code: "INVALID_WEBHOOK_SIGNATURE",
            ..
        }
    ));

    // Amount mismatch webhook -> Conflict PAYMENT_AMOUNT_MISMATCH
    let mismatch_body = serde_json::json!({
        "id": format!("evt_mismatch_{}", new_uuid_v4()),
        "event": "payment.captured",
        "created_at": 1_763_049_601_i64,
        "payload": {
            "payment": {
                "entity": {
                    "id": "pay_mismatch_1",
                    "order_id": gw_order_id,
                    "amount": order1.total_paise - 100,
                    "currency": "INR",
                    "status": "captured"
                }
            }
        }
    })
    .to_string();
    let mismatch_sig = compute_hmac_sha256_hex(mismatch_body.as_bytes(), cfg.webhook_secret());
    let mismatch_err = webhook_service
        .handle_razorpay_webhook(
            mismatch_body.as_bytes(),
            Some(&mismatch_sig),
            None,
            &audit_sys,
        )
        .await
        .expect_err("amount mismatch rejected");
    assert!(matches!(
        mismatch_err,
        PaymentError::Conflict {
            code: "PAYMENT_AMOUNT_MISMATCH",
            ..
        }
    ));

    // Concurrent client verify + valid signed webhook -> exact 1 PLACED + 1 inventory commit + 1 coupon commit
    let client_sig = compute_hmac_sha256_hex(
        format!("{gw_order_id}|{gw_payment_id}").as_bytes(),
        cfg.key_secret(),
    );
    let wh_sig = compute_hmac_sha256_hex(wh_body.as_bytes(), cfg.webhook_secret());
    let (res_v, res_w) = tokio::join!(
        payment_service.verify_client_payment(
            customer_id,
            &VerifyRazorpayPaymentRequest {
                order_id: order1.order_id.to_string(),
                razorpay_order_id: gw_order_id.clone(),
                razorpay_payment_id: gw_payment_id.clone(),
                razorpay_signature: client_sig,
            },
            &audit_cust,
        ),
        webhook_service.handle_razorpay_webhook(wh_body.as_bytes(), Some(&wh_sig), None, &audit_sys),
    );
    res_v.expect("verify succeeds");
    res_w.expect("webhook succeeds");

    let inv_row = sqlx::query(
        "SELECT on_hand_qty, reserved_qty FROM inventory WHERE ingredient_id = $1",
    )
    .bind(order1.ingredient_id.expect("ing"))
    .fetch_one(&pool)
    .await
    .expect("inv");
    assert_eq!(inv_row.get::<i64, _>("on_hand_qty"), 750);
    assert_eq!(inv_row.get::<i64, _>("reserved_qty"), 0);

    let coupon_uses: i32 = sqlx::query_scalar("SELECT usage_count FROM coupons WHERE code = $1")
        .bind(order1.coupon_code.as_deref().expect("coupon"))
        .fetch_one(&pool)
        .await
        .expect("coupon");
    assert_eq!(coupon_uses, 1);

    // Late capture on EXPIRED order2 + failed refund + owner retry + post-gateway crash + reconciler
    let order2 = common::seed_pending_razorpay_order(&pool, customer_id, false).await;
    common::dispatch_all_pending_payment_outbox(&reconciler, &outbox_repo).await;
    let st2 = payment_service
        .get_order_payment_status(order2.order_id, customer_id, pizzora_core::UserRole::Customer)
        .await
        .expect("st2");
    let gw_order2 = st2.payment.latest_attempt.expect("att2").gateway_order_id.expect("gwo2");

    // Mark order2 & payment expired directly to simulate ExpirySweepWorker completion
    sqlx::query("UPDATE orders SET status = 'EXPIRED', expires_at = $1 WHERE id = $2")
        .bind(Utc::now() - Duration::minutes(5))
        .bind(order2.order_id)
        .execute(&pool)
        .await
        .expect("expire order2");
    sqlx::query("UPDATE payments SET status = 'EXPIRED' WHERE id = $1")
        .bind(order2.payment_id)
        .execute(&pool)
        .await
        .expect("expire payment2");

    let late_pay_id = format!("pay_late_{}", &new_uuid_v4().simple().to_string()[..8]);
    let late_wh = serde_json::json!({
        "id": format!("evt_late_{}", new_uuid_v4()),
        "event": "payment.captured",
        "created_at": 1_763_050_000_i64,
        "payload": {
            "payment": {
                "entity": {
                    "id": late_pay_id,
                    "order_id": gw_order2,
                    "amount": order2.total_paise,
                    "currency": "INR",
                    "status": "captured"
                }
            }
        }
    })
    .to_string();
    let late_sig = compute_hmac_sha256_hex(late_wh.as_bytes(), cfg.webhook_secret());
    webhook_service
        .handle_razorpay_webhook(late_wh.as_bytes(), Some(&late_sig), None, &audit_sys)
        .await
        .expect("late capture webhook handled");

    let after_late = payment_service
        .get_order_payment_status(order2.order_id, customer_id, pizzora_core::UserRole::Customer)
        .await
        .expect("after late");
    assert_eq!(after_late.order_status, OrderStatus::Expired);
    assert_eq!(after_late.payment.status, PaymentStatus::RefundPending);
    assert_eq!(after_late.refunds.len(), 1);
    assert_eq!(
        after_late.refunds[0].idempotency_key,
        format!("late_capture:{}", order2.initial_attempt_id)
    );
    assert_eq!(after_late.refunds[0].status, RefundStatus::Pending);

    // Permanent refund gateway failure -> FAILED + owner alert
    gateway.set_fail_next_refunds(1, false);
    common::dispatch_all_pending_payment_outbox(&reconciler, &outbox_repo).await;
    let after_fail = payment_service
        .get_order_payment_status(order2.order_id, customer_id, pizzora_core::UserRole::Customer)
        .await
        .expect("after fail");
    assert_eq!(after_fail.refunds[0].status, RefundStatus::Failed);

    // Owner retries existing failed refund (re-queues same row) -> post-gateway crash -> reconciler recovers without duplicate
    let audit_owner = AuditContext {
        actor_user_id: Some(owner_id),
        actor_role: "OWNER".to_owned(),
        correlation_id: new_uuid_v4().to_string(),
        ip_address: None,
    };
    let retried_refund = payment_service
        .initiate_owner_refund(
            order2.order_id,
            owner_id,
            Some("owner-retry-late-1"),
            &OwnerRefundRequest {
                amount_paise: None,
                reason: "Retry late capture refund".to_owned(),
            },
            &audit_owner,
        )
        .await
        .expect("owner retry refund");
    assert_eq!(retried_refund.id, after_late.refunds[0].id);
    assert_eq!(retried_refund.status, RefundStatus::Pending);

    gateway.set_simulate_post_gateway_crash_on_create_refund(true);
    common::dispatch_all_pending_payment_outbox(&reconciler, &outbox_repo).await;

    let reconciled = reconciler.run_once().await.expect("reconciler run");
    assert_eq!(reconciled, 1);
    assert_eq!(gateway.created_refunds().len(), 1);

    let final_st = payment_service
        .get_order_payment_status(order2.order_id, customer_id, pizzora_core::UserRole::Customer)
        .await
        .expect("final state");
    assert_eq!(final_st.payment.status, PaymentStatus::Refunded);
    assert_eq!(final_st.payment.refunded_paise, order2.total_paise);
    assert_eq!(final_st.refunds[0].status, RefundStatus::Processed);
    assert!(final_st.refunds[0].gateway_refund_id.is_some());

    // Over-refund guard -> 409 ALREADY_REFUNDED
    let over_refund = payment_service
        .initiate_owner_refund(
            order2.order_id,
            owner_id,
            Some("owner-over-refund"),
            &OwnerRefundRequest {
                amount_paise: Some(100),
                reason: "Duplicate refund attempt".to_owned(),
            },
            &audit_owner,
        )
        .await
        .expect_err("already refunded must conflict");
    assert!(matches!(
        over_refund,
        PaymentError::Conflict {
            code: "ALREADY_REFUNDED",
            ..
        }
    ));

    // Seed an open attempt captured at gateway but missed by client/webhook -> open attempt reconciler catches it
    let order3 = common::seed_pending_razorpay_order(&pool, customer_id, false).await;
    common::dispatch_all_pending_payment_outbox(&reconciler, &outbox_repo).await;
    let st3 = payment_service
        .get_order_payment_status(order3.order_id, customer_id, pizzora_core::UserRole::Customer)
        .await
        .expect("st3");
    let gw_order3 = st3.payment.latest_attempt.expect("att3").gateway_order_id.expect("gwo3");
    gateway.insert_mock_payment_for_order(
        &gw_order3,
        GatewayPaymentResult {
            gateway_payment_id: format!("pay_reconcile_{}", &new_uuid_v4().simple().to_string()[..8]),
            gateway_order_id: gw_order3.clone(),
            amount_paise: order3.total_paise,
            currency: "INR".to_owned(),
            status: "captured".to_owned(),
            error_code: None,
            error_description: None,
        },
    );
    let rec3 = reconciler
        .reconcile_open_payment_attempts()
        .await
        .expect("open attempt reconciler");
    assert_eq!(rec3, 1);
    let final3 = payment_service
        .get_order_payment_status(order3.order_id, customer_id, pizzora_core::UserRole::Customer)
        .await
        .expect("final3");
    assert_eq!(final3.order_status, OrderStatus::Placed);
    assert_eq!(final3.payment.status, PaymentStatus::Captured);
    let _ = PaymentMethod::Razorpay;
}
