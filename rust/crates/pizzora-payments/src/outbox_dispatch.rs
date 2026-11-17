use std::collections::BTreeMap;

use pizzora_core::{PaymentAttemptStatus, RefundStatus};
use pizzora_db::{record_audit_log, AuditContext, ClaimedOutboxEvent, OutboxRepository};
use sqlx::Row as _;
use uuid::Uuid;

use crate::error::PaymentError;
use crate::reconciliation::RazorpayReconciliationService;
use crate::repo::{
    insert_payment_event_idempotent, mark_refund_processed_and_update_payment, notify_owners,
    resolve_gateway_payment_id,
};
use crate::status_map::{parse_payment_attempt_status, parse_refund_status};

#[derive(Debug, Clone)]
struct RefundDispatchPrep {
    refund_id: Uuid,
    payment_id: Uuid,
    attempt_id: Option<Uuid>,
    order_id: Uuid,
    order_number: String,
    idempotency_key: String,
    amount_paise: i64,
    gateway_payment_id: Option<String>,
}

impl RazorpayReconciliationService {
    /// Outbox dispatch helper for `PAYMENT_GATEWAY_ORDER_REQUESTED` events.
    /// Executes the Razorpay HTTP call strictly outside any PostgreSQL transaction.
    pub async fn dispatch_gateway_order_outbox_event(
        &self,
        outbox: &OutboxRepository,
        event: &ClaimedOutboxEvent,
        max_attempts: i32,
    ) -> Result<(), PaymentError> {
        let Some(obj) = event.payload_json.as_object() else {
            outbox
                .mark_dead_letter(
                    event.id,
                    "Malformed PAYMENT_GATEWAY_ORDER_REQUESTED JSON payload",
                    None,
                )
                .await?;
            return Ok(());
        };

        let attempt_id_str = obj
            .get("payment_attempt_id")
            .and_then(|v| v.as_str())
            .unwrap_or_default();
        let order_id_str = obj
            .get("order_id")
            .and_then(|v| v.as_str())
            .map(str::to_owned)
            .unwrap_or_else(|| event.aggregate_id.to_string());
        let amount_paise = obj.get("amount_paise").and_then(|v| v.as_i64());
        let receipt = obj
            .get("receipt")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .unwrap_or_else(|| {
                if attempt_id_str.is_empty() {
                    event.id.to_string()
                } else {
                    attempt_id_str.to_owned()
                }
            });

        let Ok(attempt_uuid) = Uuid::parse_str(attempt_id_str) else {
            outbox
                .mark_dead_letter(
                    event.id,
                    "Invalid payment_attempt_id in outbox payload",
                    None,
                )
                .await?;
            return Ok(());
        };
        let Some(amount_paise) = amount_paise.filter(|&a| a > 0) else {
            outbox
                .mark_dead_letter(event.id, "Invalid amount_paise in outbox payload", None)
                .await?;
            return Ok(());
        };

        let status_row: Option<String> =
            sqlx::query_scalar("SELECT status FROM payment_attempts WHERE id = $1")
                .bind(attempt_uuid)
                .fetch_optional(&self.pool)
                .await?;
        if status_row.as_deref() != Some("INITIATING") {
            outbox.mark_dispatched(event.id, None).await?;
            return Ok(());
        }

        let mut notes = BTreeMap::new();
        notes.insert("order_id".to_owned(), order_id_str.clone());
        notes.insert("payment_attempt_id".to_owned(), attempt_id_str.to_owned());

        let result = match self
            .gateway
            .create_order(amount_paise, "INR", &receipt, notes)
            .await
        {
            Ok(res) => res,
            Err(err) => {
                if err.is_transient && event.attempts < max_attempts {
                    let shift = (event.attempts - 1).clamp(0, 6) as u32;
                    let backoff = (2_i64 << shift).min(300);
                    outbox
                        .schedule_retry(event.id, backoff, &err.message)
                        .await?;
                } else {
                    let order_uuid = Uuid::parse_str(&order_id_str).unwrap_or(event.aggregate_id);
                    self.mark_gateway_order_dead_letter(
                        event.id,
                        attempt_uuid,
                        order_uuid,
                        &err.message,
                    )
                    .await?;
                }
                return Ok(());
            }
        };

        let mut tx = self.pool.begin().await?;
        let attempt_row = sqlx::query(
            "SELECT id, payment_id, status FROM payment_attempts WHERE id = $1 FOR UPDATE",
        )
        .bind(attempt_uuid)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(row) = attempt_row {
            let payment_id: Uuid = row.try_get("payment_id")?;
            let status_raw: String = row.try_get("status")?;
            if parse_payment_attempt_status(&status_raw)? == PaymentAttemptStatus::Initiating {
                sqlx::query(
                    r#"
                    UPDATE payment_attempts
                    SET status = 'AWAITING_CUSTOMER_PAYMENT', gateway_order_id = $1, updated_at = NOW()
                    WHERE id = $2
                    "#,
                )
                .bind(&result.gateway_order_id)
                .bind(attempt_uuid)
                .execute(&mut *tx)
                .await?;

                let pay_event_payload = serde_json::json!({
                    "gateway_order_id": result.gateway_order_id,
                    "receipt": result.receipt,
                    "amount_paise": result.amount_paise,
                });
                insert_payment_event_idempotent(
                    &mut tx,
                    Some(payment_id),
                    Some(attempt_uuid),
                    "OUTBOX_WORKER",
                    &format!("outbox_order:{}", result.gateway_order_id),
                    "order.created",
                    &pay_event_payload,
                    true,
                )
                .await?;

                let order_uuid = Uuid::parse_str(&order_id_str).unwrap_or(event.aggregate_id);
                let ready_payload = serde_json::json!({
                    "order_id": order_id_str,
                    "payment_attempt_id": attempt_id_str,
                    "gateway_order_id": result.gateway_order_id,
                });
                OutboxRepository::insert_outbox_event(
                    &mut tx,
                    "ORDER",
                    order_uuid,
                    event.aggregate_version,
                    "PAYMENT_GATEWAY_READY",
                    &ready_payload,
                )
                .await?;
            }
        }

        OutboxRepository::mark_dispatched_in_tx(&mut tx, event.id, None).await?;
        tx.commit().await?;
        Ok(())
    }

    /// Outbox dispatch helper for `REFUND_REQUESTED` events.
    /// Executes Razorpay refund creation strictly outside any PostgreSQL transaction.
    pub async fn dispatch_refund_outbox_event(
        &self,
        outbox: &OutboxRepository,
        event: &ClaimedOutboxEvent,
        max_attempts: i32,
    ) -> Result<(), PaymentError> {
        let Some(obj) = event.payload_json.as_object() else {
            outbox
                .mark_dead_letter(event.id, "Malformed REFUND_REQUESTED JSON payload", None)
                .await?;
            return Ok(());
        };
        let Some(refund_uuid) = obj
            .get("refund_id")
            .and_then(|v| v.as_str())
            .and_then(|s| Uuid::parse_str(s).ok())
        else {
            outbox
                .mark_dead_letter(
                    event.id,
                    "Missing refund_id in REFUND_REQUESTED payload",
                    None,
                )
                .await?;
            return Ok(());
        };

        let prep = {
            let mut tx = self.pool.begin().await?;
            let row = sqlx::query(
                r#"
                SELECT r.id, r.payment_id, r.payment_attempt_id, r.order_id, r.idempotency_key,
                       r.amount_paise, r.status, o.order_number
                FROM refunds r
                JOIN orders o ON o.id = r.order_id
                WHERE r.id = $1
                FOR UPDATE OF r
                "#,
            )
            .bind(refund_uuid)
            .fetch_optional(&mut *tx)
            .await?;

            let Some(r) = row else {
                tx.commit().await?;
                outbox
                    .mark_dead_letter(event.id, &format!("Refund {refund_uuid} not found"), None)
                    .await?;
                return Ok(());
            };

            let status_raw: String = r.try_get("status")?;
            let status = parse_refund_status(&status_raw)?;
            if status == RefundStatus::Processed {
                OutboxRepository::mark_dispatched_in_tx(&mut tx, event.id, None).await?;
                tx.commit().await?;
                return Ok(());
            }

            let payment_id: Uuid = r.try_get("payment_id")?;
            let attempt_id: Option<Uuid> = r.try_get("payment_attempt_id")?;
            let fallback_gw_payment = obj
                .get("gateway_payment_id")
                .and_then(|v| v.as_str())
                .map(str::to_owned);
            let gateway_payment_id = resolve_gateway_payment_id(&mut tx, payment_id, attempt_id)
                .await?
                .or(fallback_gw_payment);

            if status == RefundStatus::Pending || status == RefundStatus::Failed {
                sqlx::query(
                    "UPDATE refunds SET status = 'PROCESSING', updated_at = NOW() WHERE id = $1",
                )
                .bind(refund_uuid)
                .execute(&mut *tx)
                .await?;
            }

            let prep = RefundDispatchPrep {
                refund_id: r.try_get("id")?,
                payment_id,
                attempt_id,
                order_id: r.try_get("order_id")?,
                order_number: r.try_get("order_number")?,
                idempotency_key: r.try_get("idempotency_key")?,
                amount_paise: r.try_get("amount_paise")?,
                gateway_payment_id,
            };
            tx.commit().await?;
            prep
        };

        let Some(gateway_payment_id) = prep
            .gateway_payment_id
            .as_deref()
            .filter(|s| !s.trim().is_empty())
        else {
            self.mark_refund_permanent_failure(
                event.id,
                &prep,
                &format!(
                    "No captured gateway_payment_id available for refund {}",
                    prep.refund_id
                ),
            )
            .await?;
            return Ok(());
        };

        let mut notes = BTreeMap::new();
        notes.insert("idempotency_key".to_owned(), prep.idempotency_key.clone());
        notes.insert("order_id".to_owned(), prep.order_id.to_string());
        notes.insert("refund_id".to_owned(), prep.refund_id.to_string());

        let refund_result = match self
            .gateway
            .create_refund(
                gateway_payment_id,
                prep.amount_paise,
                &prep.idempotency_key,
                notes,
            )
            .await
        {
            Ok(res) => res,
            Err(err) => {
                if err.is_transient && event.attempts < max_attempts {
                    let shift = (event.attempts - 1).clamp(0, 6) as u32;
                    let backoff = (2_i64 << shift).min(300);
                    let mut tx = self.pool.begin().await?;
                    sqlx::query(
                        "UPDATE refunds SET status = 'PENDING', updated_at = NOW() WHERE id = $1 AND status = 'PROCESSING'",
                    )
                    .bind(prep.refund_id)
                    .execute(&mut *tx)
                    .await?;
                    tx.commit().await?;
                    outbox
                        .schedule_retry(event.id, backoff, &err.message)
                        .await?;
                } else {
                    self.mark_refund_permanent_failure(event.id, &prep, &err.message)
                        .await?;
                }
                return Ok(());
            }
        };

        let mut tx = self.pool.begin().await?;
        mark_refund_processed_and_update_payment(
            &mut tx,
            prep.refund_id,
            prep.payment_id,
            &refund_result.gateway_refund_id,
        )
        .await?;

        let pay_event_payload = serde_json::json!({
            "refund_id": prep.refund_id.to_string(),
            "gateway_refund_id": refund_result.gateway_refund_id,
            "amount_paise": prep.amount_paise,
        });
        insert_payment_event_idempotent(
            &mut tx,
            Some(prep.payment_id),
            prep.attempt_id,
            "OUTBOX_WORKER",
            &format!("outbox_refund:{}", refund_result.gateway_refund_id),
            "refund.processed",
            &pay_event_payload,
            true,
        )
        .await?;

        let audit = AuditContext {
            actor_user_id: None,
            actor_role: "SYSTEM".to_owned(),
            correlation_id: event.id.to_string(),
            ip_address: None,
        };
        let audit_after = serde_json::json!({
            "gateway_refund_id": refund_result.gateway_refund_id,
            "amount_paise": prep.amount_paise,
        });
        record_audit_log(
            &mut tx,
            &audit,
            "REFUND_PROCESSED",
            "refund",
            &prep.refund_id.to_string(),
            None,
            Some(&audit_after),
        )
        .await?;

        OutboxRepository::mark_dispatched_in_tx(&mut tx, event.id, None).await?;
        tx.commit().await?;
        Ok(())
    }

    async fn mark_gateway_order_dead_letter(
        &self,
        event_id: Uuid,
        attempt_id: Uuid,
        order_id: Uuid,
        error_message: &str,
    ) -> Result<(), PaymentError> {
        let mut tx = self.pool.begin().await?;
        let truncated_reason: String = error_message.chars().take(250).collect();
        let truncated_outbox: String = error_message.chars().take(500).collect();
        let truncated_body: String = error_message.chars().take(180).collect();

        sqlx::query(
            r#"
            UPDATE payment_attempts
            SET status = 'FAILED',
                failure_code = 'GATEWAY_ORDER_FAILED',
                failure_reason = $1,
                updated_at = NOW()
            WHERE id = $2 AND status = 'INITIATING'
            "#,
        )
        .bind(&truncated_reason)
        .bind(attempt_id)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "UPDATE outbox_events SET dispatch_status = 'DEAD_LETTER', last_error = $1 WHERE id = $2",
        )
        .bind(&truncated_outbox)
        .bind(event_id)
        .execute(&mut *tx)
        .await?;

        notify_owners(
            &mut tx,
            event_id,
            "Payment Gateway Init Failed",
            &format!("Razorpay order creation failed for order {order_id}: {truncated_body}"),
            "PAYMENT_ALERT",
            Some(order_id),
        )
        .await?;

        tx.commit().await?;
        Ok(())
    }

    async fn mark_refund_permanent_failure(
        &self,
        event_id: Uuid,
        prep: &RefundDispatchPrep,
        error_message: &str,
    ) -> Result<(), PaymentError> {
        let mut tx = self.pool.begin().await?;
        let truncated_outbox: String = error_message.chars().take(500).collect();
        let truncated_body: String = error_message.chars().take(160).collect();

        sqlx::query(
            "UPDATE refunds SET status = 'FAILED', updated_at = NOW() WHERE id = $1 AND status IN ('PENDING', 'PROCESSING')",
        )
        .bind(prep.refund_id)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "UPDATE outbox_events SET dispatch_status = 'DEAD_LETTER', last_error = $1 WHERE id = $2",
        )
        .bind(&truncated_outbox)
        .bind(event_id)
        .execute(&mut *tx)
        .await?;

        notify_owners(
            &mut tx,
            event_id,
            &format!("Refund Failed: {}", prep.order_number),
            &format!(
                "Refund of {} paise failed: {truncated_body}",
                prep.amount_paise
            ),
            "REFUND_ALERT",
            Some(prep.order_id),
        )
        .await?;

        let audit = AuditContext {
            actor_user_id: None,
            actor_role: "SYSTEM".to_owned(),
            correlation_id: event_id.to_string(),
            ip_address: None,
        };
        let after = serde_json::json!({
            "error": error_message.chars().take(200).collect::<String>(),
        });
        record_audit_log(
            &mut tx,
            &audit,
            "REFUND_FAILED_REQUIRES_ACTION",
            "refund",
            &prep.refund_id.to_string(),
            None,
            Some(&after),
        )
        .await?;

        tx.commit().await?;
        Ok(())
    }
}
