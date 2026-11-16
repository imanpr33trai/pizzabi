use pizzora_core::{OrderStatus, PaymentAttemptStatus, PaymentStateMachine, PaymentStatus};
use pizzora_db::{
    new_uuid_v4, record_audit_log, AuditContext, CouponService, InventoryService, OrderService,
    OutboxRepository,
};
use sqlx::{PgConnection, Row as _};
use tracing::warn;
use uuid::Uuid;

use crate::dto::VerifyRazorpayPaymentResponse;
use crate::error::PaymentError;
use crate::repo::{
    insert_payment_event_idempotent, insert_refund_and_outbox_if_absent, lock_attempt_by_id,
    lock_order, lock_payment_by_order, LockedAttempt, LockedOrder, LockedPayment,
};
use crate::service::PaymentService;
use crate::status_map::{fulfillment_type_as_str, order_status_as_str};

impl PaymentService {
    #[allow(clippy::too_many_arguments)]
    pub async fn process_payment_captured(
        &self,
        source: &str,
        gateway_event_id: &str,
        gateway_order_id: &str,
        gateway_payment_id: &str,
        amount_paise: Option<i64>,
        order_id_hint: Option<Uuid>,
        actor_customer_id: Option<Uuid>,
        raw_payload_json: &serde_json::Value,
        audit: &AuditContext,
    ) -> Result<VerifyRazorpayPaymentResponse, PaymentError> {
        let mut tx = self.pool.begin().await?;

        let lookup_row = sqlx::query(
            r#"
            SELECT p.order_id, pa.payment_id, pa.id AS attempt_id
            FROM payment_attempts pa
            JOIN payments p ON p.id = pa.payment_id
            WHERE pa.gateway_order_id = $1
            "#,
        )
        .bind(gateway_order_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| PaymentError::NotFound {
            code: "PAYMENT_ATTEMPT_NOT_FOUND",
            message: format!("No payment attempt found for gateway order {gateway_order_id}."),
        })?;

        let resolved_order_id: Uuid = lookup_row.try_get("order_id")?;
        let attempt_id: Uuid = lookup_row.try_get("attempt_id")?;

        if let Some(hint) = order_id_hint {
            if resolved_order_id != hint {
                return Err(PaymentError::Conflict {
                    code: "GATEWAY_ORDER_MISMATCH",
                    message: format!(
                        "Gateway order {gateway_order_id} does not belong to order {hint}."
                    ),
                });
            }
        }

        // Fixed deadlock-free lock hierarchy: orders -> payments -> payment_attempts
        let order = lock_order(&mut tx, resolved_order_id).await?;
        if let Some(actor_cid) = actor_customer_id {
            if order.customer_id != actor_cid {
                warn!(
                    actor_user_id = %actor_cid,
                    order_id = %resolved_order_id,
                    "Blocked cross-user payment verify IDOR"
                );
                return Err(PaymentError::Forbidden {
                    code: "FORBIDDEN",
                    message: "You cannot verify payment for another customer's order.".to_owned(),
                });
            }
        }
        let payment = lock_payment_by_order(&mut tx, resolved_order_id).await?;
        let attempt = lock_attempt_by_id(&mut tx, attempt_id).await?;

        let inserted = insert_payment_event_idempotent(
            &mut tx,
            Some(payment.id),
            Some(attempt.id),
            source,
            gateway_event_id,
            "payment.captured",
            raw_payload_json,
            true,
        )
        .await?;

        if !inserted {
            let detail = OrderService::load_order_detail_in_tx(&mut tx, resolved_order_id)
                .await?
                .ok_or_else(|| PaymentError::NotFound {
                    code: "ORDER_NOT_FOUND",
                    message: format!("Order {resolved_order_id} was not found."),
                })?;
            tx.commit().await?;
            return Ok(VerifyRazorpayPaymentResponse {
                order: detail,
                already_captured: true,
                late_capture_refund_triggered: false,
            });
        }

        let effective_captured = amount_paise.unwrap_or(payment.amount_paise);

        if order.status == OrderStatus::PendingPayment && payment.status == PaymentStatus::Unpaid {
            if let Some(received) = amount_paise {
                if received != payment.amount_paise {
                    warn!(
                        order_id = %resolved_order_id,
                        expected_paise = payment.amount_paise,
                        received_paise = received,
                        "Blocked payment capture with mismatched amount"
                    );
                    return Err(PaymentError::Conflict {
                        code: "PAYMENT_AMOUNT_MISMATCH",
                        message: format!(
                            "Captured amount {received} does not match expected {}.",
                            payment.amount_paise
                        ),
                    });
                }
            }

            let response = complete_pending_payment_capture(
                &mut tx,
                &order,
                &payment,
                &attempt,
                source,
                gateway_payment_id,
                effective_captured,
                actor_customer_id,
                audit,
            )
            .await?;
            tx.commit().await?;
            return Ok(response);
        }

        if order.status == OrderStatus::Expired || order.status == OrderStatus::Cancelled {
            let response = handle_terminal_order_late_capture(
                &mut tx,
                &order,
                &payment,
                &attempt,
                gateway_payment_id,
                effective_captured,
                audit,
            )
            .await?;
            tx.commit().await?;
            return Ok(response);
        }

        let response = handle_already_settled_order_capture(
            &mut tx,
            &order,
            &payment,
            &attempt,
            gateway_payment_id,
            effective_captured,
        )
        .await?;
        tx.commit().await?;
        Ok(response)
    }
}

#[allow(clippy::too_many_arguments)]
async fn complete_pending_payment_capture(
    tx: &mut PgConnection,
    order: &LockedOrder,
    payment: &LockedPayment,
    attempt: &LockedAttempt,
    source: &str,
    gateway_payment_id: &str,
    effective_captured: i64,
    actor_customer_id: Option<Uuid>,
    audit: &AuditContext,
) -> Result<VerifyRazorpayPaymentResponse, PaymentError> {
    sqlx::query(
        r#"
        UPDATE payment_attempts
        SET status = 'CAPTURED', gateway_payment_id = $1, updated_at = NOW()
        WHERE id = $2
        "#,
    )
    .bind(gateway_payment_id)
    .bind(attempt.id)
    .execute(&mut *tx)
    .await?;

    sqlx::query(
        r#"
        UPDATE payment_attempts
        SET status = 'FAILED',
            failure_code = COALESCE(failure_code, 'SUPERSEDED'),
            failure_reason = COALESCE(failure_reason, 'Another attempt captured'),
            updated_at = NOW()
        WHERE payment_id = $1 AND id <> $2 AND status IN ('INITIATING', 'AWAITING_CUSTOMER_PAYMENT')
        "#,
    )
    .bind(payment.id)
    .bind(attempt.id)
    .execute(&mut *tx)
    .await?;

    sqlx::query(
        r#"
        UPDATE payments
        SET status = 'CAPTURED', captured_paise = $1, updated_at = NOW()
        WHERE id = $2
        "#,
    )
    .bind(effective_captured)
    .bind(payment.id)
    .execute(&mut *tx)
    .await?;

    let new_version = order.version + 1;
    sqlx::query("UPDATE orders SET status = 'PLACED', version = $1, expires_at = NULL WHERE id = $2")
        .bind(new_version)
        .bind(order.id)
        .execute(&mut *tx)
        .await?;

    sqlx::query(
        r#"
        INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
        VALUES ($1, $2, 'PENDING_PAYMENT', 'PLACED', $3, $4)
        "#,
    )
    .bind(new_uuid_v4())
    .bind(order.id)
    .bind(actor_customer_id)
    .bind(format!("Payment captured ({source})"))
    .execute(&mut *tx)
    .await?;

    InventoryService::commit_reserved_inventory(&mut *tx, order.id, Some(order.customer_id))
        .await?;
    CouponService::commit_reserved_coupon(&mut *tx, order.id).await?;

    sqlx::query(
        r#"
        UPDATE scheduled_slot_reservations
        SET status = 'COMMITTED', expires_at = NULL, updated_at = NOW()
        WHERE order_id = $1 AND status = 'ACTIVE'
        "#,
    )
    .bind(order.id)
    .execute(&mut *tx)
    .await?;

    let placed_payload = serde_json::json!({
        "order_id": order.id.to_string(),
        "order_number": order.order_number,
        "customer_id": order.customer_id.to_string(),
        "payment_id": payment.id.to_string(),
        "payment_attempt_id": attempt.id.to_string(),
        "payment_method": "RAZORPAY",
        "gateway_payment_id": gateway_payment_id,
        "amount_paise": effective_captured,
        "fulfillment_type": fulfillment_type_as_str(order.fulfillment_type),
        "status": "PLACED",
    });
    OutboxRepository::insert_outbox_event(
        &mut *tx,
        "ORDER",
        order.id,
        new_version,
        "ORDER_PLACED",
        &placed_payload,
    )
    .await?;

    let audit_after = serde_json::json!({
        "order_id": order.id.to_string(),
        "source": source,
        "gateway_payment_id": gateway_payment_id,
    });
    record_audit_log(
        &mut *tx,
        audit,
        "PAYMENT_CAPTURED",
        "payment",
        &payment.id.to_string(),
        None,
        Some(&audit_after),
    )
    .await?;

    let detail = OrderService::load_order_detail_in_tx(&mut *tx, order.id)
        .await?
        .ok_or_else(|| PaymentError::NotFound {
            code: "ORDER_NOT_FOUND",
            message: format!("Order {} was not found.", order.id),
        })?;
    Ok(VerifyRazorpayPaymentResponse {
        order: detail,
        already_captured: false,
        late_capture_refund_triggered: false,
    })
}

async fn handle_terminal_order_late_capture(
    tx: &mut PgConnection,
    order: &LockedOrder,
    payment: &LockedPayment,
    attempt: &LockedAttempt,
    gateway_payment_id: &str,
    effective_captured: i64,
    audit: &AuditContext,
) -> Result<VerifyRazorpayPaymentResponse, PaymentError> {
    // Exceptional late-capture path: NEVER resurrect a terminal order!
    sqlx::query(
        r#"
        UPDATE payment_attempts
        SET status = 'CAPTURED',
            gateway_payment_id = COALESCE(gateway_payment_id, $1),
            updated_at = NOW()
        WHERE id = $2
        "#,
    )
    .bind(gateway_payment_id)
    .bind(attempt.id)
    .execute(&mut *tx)
    .await?;

    if PaymentStateMachine::can_transition_payment(payment.status, PaymentStatus::RefundPending)
        || payment.captured_paise < effective_captured
    {
        sqlx::query(
            r#"
            UPDATE payments
            SET captured_paise = GREATEST(captured_paise, $1),
                status = CASE WHEN status IN ('EXPIRED', 'CANCELLED', 'UNPAID') THEN 'REFUND_PENDING' ELSE status END,
                updated_at = NOW()
            WHERE id = $2
            "#,
        )
        .bind(effective_captured)
        .bind(payment.id)
        .execute(&mut *tx)
        .await?;
    }

    let refund_id = new_uuid_v4();
    let idempotency_key = format!("late_capture:{}", attempt.id);
    let reason = format!(
        "LATE_CAPTURE_AFTER_ORDER_{}",
        order_status_as_str(order.status)
    );
    let inserted_refund = insert_refund_and_outbox_if_absent(
        &mut *tx,
        refund_id,
        payment.id,
        Some(attempt.id),
        order.id,
        order.version,
        &idempotency_key,
        effective_captured,
        &reason,
        None,
        Some(gateway_payment_id),
    )
    .await?;

    if let Some(ref_id) = inserted_refund {
        let audit_after = serde_json::json!({
            "order_id": order.id.to_string(),
            "terminal_status": order_status_as_str(order.status),
            "gateway_payment_id": gateway_payment_id,
            "amount_paise": effective_captured,
        });
        record_audit_log(
            &mut *tx,
            audit,
            "LATE_CAPTURE_REFUND_QUEUED",
            "refund",
            &ref_id.to_string(),
            None,
            Some(&audit_after),
        )
        .await?;
    }

    let detail = OrderService::load_order_detail_in_tx(&mut *tx, order.id)
        .await?
        .ok_or_else(|| PaymentError::NotFound {
            code: "ORDER_NOT_FOUND",
            message: format!("Order {} was not found.", order.id),
        })?;
    Ok(VerifyRazorpayPaymentResponse {
        order: detail,
        already_captured: inserted_refund.is_none(),
        late_capture_refund_triggered: inserted_refund.is_some(),
    })
}

async fn handle_already_settled_order_capture(
    tx: &mut PgConnection,
    order: &LockedOrder,
    payment: &LockedPayment,
    attempt: &LockedAttempt,
    gateway_payment_id: &str,
    effective_captured: i64,
) -> Result<VerifyRazorpayPaymentResponse, PaymentError> {
    let existing_same_gw: Option<Uuid> = sqlx::query_scalar(
        "SELECT id FROM payment_attempts WHERE payment_id = $1 AND gateway_payment_id = $2",
    )
    .bind(payment.id)
    .bind(gateway_payment_id)
    .fetch_optional(&mut *tx)
    .await?;

    if existing_same_gw.is_some() || attempt.status == PaymentAttemptStatus::Captured {
        let detail = OrderService::load_order_detail_in_tx(&mut *tx, order.id)
            .await?
            .ok_or_else(|| PaymentError::NotFound {
                code: "ORDER_NOT_FOUND",
                message: format!("Order {} was not found.", order.id),
            })?;
        return Ok(VerifyRazorpayPaymentResponse {
            order: detail,
            already_captured: true,
            late_capture_refund_triggered: false,
        });
    }

    // Duplicate distinct payment capture on an already-settled order -> trigger duplicate refund
    sqlx::query(
        r#"
        UPDATE payment_attempts
        SET status = 'CAPTURED', gateway_payment_id = $1, updated_at = NOW()
        WHERE id = $2
        "#,
    )
    .bind(gateway_payment_id)
    .bind(attempt.id)
    .execute(&mut *tx)
    .await?;

    sqlx::query(
        r#"
        UPDATE payments
        SET captured_paise = captured_paise + $1,
            status = CASE WHEN status = 'CAPTURED' THEN 'REFUND_PENDING' ELSE status END,
            updated_at = NOW()
        WHERE id = $2
        "#,
    )
    .bind(effective_captured)
    .bind(payment.id)
    .execute(&mut *tx)
    .await?;

    let refund_id = new_uuid_v4();
    let idempotency_key = format!("duplicate_capture:{}", attempt.id);
    insert_refund_and_outbox_if_absent(
        &mut *tx,
        refund_id,
        payment.id,
        Some(attempt.id),
        order.id,
        order.version,
        &idempotency_key,
        effective_captured,
        "DUPLICATE_GATEWAY_CAPTURE",
        None,
        Some(gateway_payment_id),
    )
    .await?;

    let detail = OrderService::load_order_detail_in_tx(&mut *tx, order.id)
        .await?
        .ok_or_else(|| PaymentError::NotFound {
            code: "ORDER_NOT_FOUND",
            message: format!("Order {} was not found.", order.id),
        })?;
    Ok(VerifyRazorpayPaymentResponse {
        order: detail,
        already_captured: true,
        late_capture_refund_triggered: true,
    })
}
