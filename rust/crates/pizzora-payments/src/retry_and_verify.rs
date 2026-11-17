use chrono::Utc;
use pizzora_core::{
    OrderStatus, PaymentAttemptStatus, PaymentMethod, PaymentStateMachine, PaymentStatus,
};
use pizzora_db::{
    new_uuid_v4, record_audit_log, AuditContext, DbError, OrderService, OutboxRepository,
    EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED,
};
use sqlx::{PgConnection, Row as _};
use tracing::warn;
use uuid::Uuid;

use crate::dto::{FailPaymentAttemptRequest, OrderPaymentStatusResponse, RetryPaymentRequest};
use crate::error::PaymentError;
use crate::receipts::{compute_retry_fingerprint, format_razorpay_receipt};
use crate::repo::{
    find_attempt_by_receipt, insert_payment_event_idempotent, lock_order, lock_payment_by_order,
    LockedAttempt,
};
use crate::service::{parse_uuid, PaymentService};
use crate::status_map::{order_status_as_str, parse_payment_attempt_status};

impl PaymentService {
    pub async fn retry_payment(
        &self,
        order_id: Uuid,
        customer_id: Uuid,
        raw_idempotency_key: Option<&str>,
        request: Option<&RetryPaymentRequest>,
        audit: &AuditContext,
    ) -> Result<OrderPaymentStatusResponse, PaymentError> {
        let clean_idemp_key = raw_idempotency_key.map(str::trim).filter(|s| !s.is_empty());
        if let Some(key) = clean_idemp_key {
            if key.chars().count() > 100 {
                return Err(PaymentError::Validation {
                    code: "INVALID_IDEMPOTENCY_KEY",
                    message: "Idempotency-Key cannot exceed 100 characters.".to_owned(),
                });
            }
        }
        let clean_reason: Option<String> = request
            .and_then(|r| r.reason.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| s.chars().take(200).collect());

        let mut tx = self.pool.begin().await?;

        if let Some(idemp_key) = clean_idemp_key {
            sqlx::query("SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))")
                .bind(customer_id.to_string())
                .bind(idemp_key)
                .execute(&mut *tx)
                .await?;
        }

        let order = lock_order(&mut tx, order_id).await?;
        if order.customer_id != customer_id {
            warn!(
                actor_user_id = %customer_id,
                order_id = %order_id,
                "Blocked cross-user payment retry IDOR"
            );
            return Err(PaymentError::Forbidden {
                code: "FORBIDDEN",
                message: "You cannot retry payment for another customer's order.".to_owned(),
            });
        }

        if order.status != OrderStatus::PendingPayment {
            return Err(PaymentError::Conflict {
                code: "ORDER_NOT_RETRYABLE",
                message: format!(
                    "Order {} in status {} does not accept payment retries.",
                    order.order_number,
                    order_status_as_str(order.status)
                ),
            });
        }

        let now = Utc::now();
        if order.expires_at.is_some_and(|exp| exp <= now) {
            return Err(PaymentError::Conflict {
                code: "PAYMENT_WINDOW_EXPIRED",
                message: format!(
                    "Payment window for order {} has expired.",
                    order.order_number
                ),
            });
        }

        let payment = lock_payment_by_order(&mut tx, order_id).await?;
        if payment.method != PaymentMethod::Razorpay {
            return Err(PaymentError::Conflict {
                code: "PAYMENT_METHOD_NOT_RETRYABLE",
                message: "COD orders do not support online payment retry.".to_owned(),
            });
        }
        if payment.status != PaymentStatus::Unpaid {
            return Err(PaymentError::Conflict {
                code: "PAYMENT_ALREADY_SETTLED",
                message: format!("Payment is already in status {:?}.", payment.status),
            });
        }

        if let Some(idemp_key) = clean_idemp_key {
            let used_on_other: Option<Uuid> = sqlx::query_scalar(
                r#"
                SELECT aggregate_id
                FROM outbox_events
                WHERE event_type = 'PAYMENT_GATEWAY_ORDER_REQUESTED'
                  AND payload_json->>'customer_id' = $1
                  AND payload_json->>'idempotency_key' = $2
                  AND aggregate_id <> $3
                LIMIT 1
                "#,
            )
            .bind(customer_id.to_string())
            .bind(idemp_key)
            .bind(order_id)
            .fetch_optional(&mut *tx)
            .await?;

            if used_on_other.is_some() {
                return Err(PaymentError::Conflict {
                    code: "IDEMPOTENCY_KEY_CONFLICT",
                    message: format!(
                        "Idempotency-Key '{idemp_key}' was already used for another order."
                    ),
                });
            }
        }

        let attempt_id = new_uuid_v4();
        let attempt_id_str = attempt_id.to_string();
        let effective_receipt =
            format_razorpay_receipt(order_id, clean_idemp_key.unwrap_or(&attempt_id_str))?;
        let retry_fingerprint = compute_retry_fingerprint(
            order_id,
            payment.id,
            payment.method,
            payment.amount_paise,
            clean_reason.as_deref(),
        );

        if let Some(existing) = find_attempt_by_receipt(&mut tx, &effective_receipt).await? {
            let matches_same = existing.order_id == order_id
                && existing.payment_id == payment.id
                && existing.method == payment.method
                && existing.amount_paise == payment.amount_paise
                && existing.retry_fingerprint.as_deref() == Some(retry_fingerprint.as_str());
            if !matches_same {
                return Err(PaymentError::Conflict {
                    code: "IDEMPOTENCY_KEY_CONFLICT",
                    message: format!(
                        "Idempotency-Key '{}' conflicts with an existing payment attempt.",
                        clean_idemp_key.unwrap_or(&effective_receipt)
                    ),
                });
            }

            let detail = OrderService::load_order_detail_in_tx(&mut tx, order_id)
                .await?
                .ok_or_else(|| PaymentError::NotFound {
                    code: "ORDER_NOT_FOUND",
                    message: format!("Order {order_id} was not found."),
                })?;
            let response = self
                .build_order_payment_status_response(&mut tx, detail)
                .await?;
            tx.commit().await?;
            return Ok(response);
        }

        let max_attempt_num: i32 = sqlx::query_scalar(
            "SELECT COALESCE(MAX(attempt_number), 0)::int FROM payment_attempts WHERE payment_id = $1",
        )
        .bind(payment.id)
        .fetch_one(&mut *tx)
        .await?;

        sqlx::query(
            r#"
            UPDATE payment_attempts
            SET status = 'FAILED',
                failure_code = COALESCE(failure_code, 'SUPERSEDED'),
                failure_reason = COALESCE(failure_reason, 'Superseded by customer payment retry'),
                updated_at = NOW()
            WHERE payment_id = $1 AND status IN ('INITIATING', 'AWAITING_CUSTOMER_PAYMENT')
            "#,
        )
        .bind(payment.id)
        .execute(&mut *tx)
        .await?;

        let next_number = max_attempt_num + 1;
        let insert_res = sqlx::query(
            r#"
            INSERT INTO payment_attempts (
                id, payment_id, attempt_number, method, status, amount_paise, idempotency_receipt, expires_at
            ) VALUES ($1, $2, $3, 'RAZORPAY', 'INITIATING', $4, $5, $6)
            "#,
        )
        .bind(attempt_id)
        .bind(payment.id)
        .bind(next_number)
        .bind(payment.amount_paise)
        .bind(&effective_receipt)
        .bind(order.expires_at)
        .execute(&mut *tx)
        .await;

        if let Err(err) = insert_res {
            if DbError::is_unique_violation(&err) {
                return Err(PaymentError::Conflict {
                    code: "IDEMPOTENCY_KEY_CONFLICT",
                    message: format!(
                        "Payment retry receipt '{effective_receipt}' conflicts with an existing attempt."
                    ),
                });
            }
            return Err(PaymentError::Sqlx(err));
        }

        let mut payload = serde_json::json!({
            "order_id": order_id.to_string(),
            "order_number": order.order_number,
            "customer_id": order.customer_id.to_string(),
            "payment_id": payment.id.to_string(),
            "payment_attempt_id": attempt_id_str,
            "attempt_number": next_number,
            "payment_method": "RAZORPAY",
            "amount_paise": payment.amount_paise,
            "receipt": effective_receipt,
            "retry_fingerprint": retry_fingerprint,
        });
        if let Some(obj) = payload.as_object_mut() {
            if let Some(k) = clean_idemp_key {
                obj.insert(
                    "idempotency_key".to_owned(),
                    serde_json::Value::String(k.to_owned()),
                );
            }
            if let Some(r) = &clean_reason {
                obj.insert("reason".to_owned(), serde_json::Value::String(r.clone()));
            }
        }

        OutboxRepository::insert_outbox_event(
            &mut tx,
            "ORDER",
            order_id,
            order.version,
            EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED,
            &payload,
        )
        .await?;

        let audit_after = serde_json::json!({
            "order_id": order_id.to_string(),
            "attempt_number": next_number,
        });
        record_audit_log(
            &mut tx,
            audit,
            "PAYMENT_RETRY_INITIATED",
            "payment_attempt",
            &attempt_id_str,
            None,
            Some(&audit_after),
        )
        .await?;

        let detail = OrderService::load_order_detail_in_tx(&mut tx, order_id)
            .await?
            .ok_or_else(|| PaymentError::NotFound {
                code: "ORDER_NOT_FOUND",
                message: format!("Order {order_id} was not found."),
            })?;
        let response = self
            .build_order_payment_status_response(&mut tx, detail)
            .await?;
        tx.commit().await?;
        Ok(response)
    }

    pub async fn report_attempt_failure(
        &self,
        order_id: Uuid,
        customer_id: Uuid,
        request: &FailPaymentAttemptRequest,
        audit: &AuditContext,
    ) -> Result<OrderPaymentStatusResponse, PaymentError> {
        let mut tx = self.pool.begin().await?;
        let order = lock_order(&mut tx, order_id).await?;
        if order.customer_id != customer_id {
            warn!(
                actor_user_id = %customer_id,
                order_id = %order_id,
                "Blocked cross-user payment failure report IDOR"
            );
            return Err(PaymentError::Forbidden {
                code: "FORBIDDEN",
                message: "You cannot modify another customer's payment attempt.".to_owned(),
            });
        }
        let payment = lock_payment_by_order(&mut tx, order_id).await?;

        if order.status == OrderStatus::PendingPayment && payment.status == PaymentStatus::Unpaid {
            if let Some(target) =
                find_target_attempt_for_failure(&mut tx, payment.id, request).await?
            {
                if PaymentStateMachine::can_transition_attempt(
                    target.status,
                    PaymentAttemptStatus::Failed,
                ) {
                    let clean_code: String = request
                        .failure_code
                        .as_deref()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .map(|s| s.chars().take(64).collect())
                        .unwrap_or_else(|| "GATEWAY_ERROR".to_owned());
                    let clean_reason: String = request
                        .failure_reason
                        .as_deref()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .map(|s| s.chars().take(250).collect())
                        .unwrap_or_else(|| "Payment cancelled or failed".to_owned());

                    sqlx::query(
                        r#"
                        UPDATE payment_attempts
                        SET status = 'FAILED', failure_code = $1, failure_reason = $2, updated_at = NOW()
                        WHERE id = $3
                        "#,
                    )
                    .bind(&clean_code)
                    .bind(&clean_reason)
                    .bind(target.id)
                    .execute(&mut *tx)
                    .await?;

                    let event_payload = serde_json::json!({
                        "order_id": order_id.to_string(),
                        "attempt_id": target.id.to_string(),
                        "failure_code": clean_code,
                        "failure_reason": clean_reason,
                    });
                    insert_payment_event_idempotent(
                        &mut tx,
                        Some(payment.id),
                        Some(target.id),
                        "CLIENT_VERIFY",
                        &format!("client_fail:{}", target.id),
                        "payment.failed",
                        &event_payload,
                        true,
                    )
                    .await?;

                    let audit_after = serde_json::json!({
                        "order_id": order_id.to_string(),
                        "failure_code": clean_code,
                    });
                    record_audit_log(
                        &mut tx,
                        audit,
                        "PAYMENT_ATTEMPT_FAILED",
                        "payment_attempt",
                        &target.id.to_string(),
                        None,
                        Some(&audit_after),
                    )
                    .await?;
                }
            }
        }

        let detail = OrderService::load_order_detail_in_tx(&mut tx, order_id)
            .await?
            .ok_or_else(|| PaymentError::NotFound {
                code: "ORDER_NOT_FOUND",
                message: format!("Order {order_id} was not found."),
            })?;
        let response = self
            .build_order_payment_status_response(&mut tx, detail)
            .await?;
        tx.commit().await?;
        Ok(response)
    }
}

async fn find_target_attempt_for_failure(
    conn: &mut PgConnection,
    payment_id: Uuid,
    request: &FailPaymentAttemptRequest,
) -> Result<Option<LockedAttempt>, PaymentError> {
    let parsed_attempt_id = match request
        .attempt_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(raw) => Some(parse_uuid(raw, "attempt_id")?),
        None => None,
    };
    let gateway_order_id = request
        .gateway_order_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());

    let row = if let Some(aid) = parsed_attempt_id {
        sqlx::query(
            "SELECT id, status FROM payment_attempts WHERE id = $1 AND payment_id = $2 FOR UPDATE",
        )
        .bind(aid)
        .bind(payment_id)
        .fetch_optional(&mut *conn)
        .await?
    } else if let Some(gw_oid) = gateway_order_id {
        sqlx::query(
            "SELECT id, status FROM payment_attempts WHERE gateway_order_id = $1 AND payment_id = $2 FOR UPDATE",
        )
        .bind(gw_oid)
        .bind(payment_id)
        .fetch_optional(&mut *conn)
        .await?
    } else {
        sqlx::query(
            "SELECT id, status FROM payment_attempts WHERE payment_id = $1 ORDER BY attempt_number DESC LIMIT 1 FOR UPDATE",
        )
        .bind(payment_id)
        .fetch_optional(&mut *conn)
        .await?
    };

    let Some(r) = row else {
        return Ok(None);
    };
    let status_raw: String = r.try_get("status")?;
    Ok(Some(LockedAttempt {
        id: r.try_get("id")?,
        status: parse_payment_attempt_status(&status_raw)?,
    }))
}
