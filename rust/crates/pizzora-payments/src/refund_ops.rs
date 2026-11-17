use pizzora_core::{PaymentMethod, PaymentStateMachine, PaymentStatus, RefundStatus};
use pizzora_db::{
    new_uuid_v4, record_audit_log, AuditContext, DbError, OutboxRepository, EVENT_REFUND_REQUESTED,
};
use sqlx::Row as _;
use uuid::Uuid;

use crate::dto::{OwnerRefundRequest, RefundDto};
use crate::error::PaymentError;
use crate::repo::{
    find_captured_attempt_id, insert_payment_event_idempotent, insert_refund_and_outbox_if_absent,
    lock_order, lock_payment_by_order, lock_refund_by_id, mark_refund_processed_and_update_payment,
    notify_owners, resolve_gateway_payment_id, row_to_refund_dto,
};
use crate::service::PaymentService;

impl PaymentService {
    pub async fn process_refund_webhook(
        &self,
        gateway_event_id: &str,
        gateway_refund_id: &str,
        idempotency_key_note: Option<&str>,
        processed: bool,
        raw_payload_json: &serde_json::Value,
        audit: &AuditContext,
    ) -> Result<(), PaymentError> {
        let mut tx = self.pool.begin().await?;

        let lookup_row = sqlx::query(
            r#"
            SELECT id, order_id
            FROM refunds
            WHERE gateway_refund_id = $1 OR ($2::text IS NOT NULL AND idempotency_key = $2)
            LIMIT 1
            "#,
        )
        .bind(gateway_refund_id)
        .bind(idempotency_key_note)
        .fetch_optional(&mut *tx)
        .await?;

        let event_type = if processed {
            "refund.processed"
        } else {
            "refund.failed"
        };

        let Some(row) = lookup_row else {
            insert_payment_event_idempotent(
                &mut tx,
                None,
                None,
                "WEBHOOK",
                gateway_event_id,
                event_type,
                raw_payload_json,
                true,
            )
            .await?;
            tx.commit().await?;
            return Ok(());
        };

        let refund_uuid: Uuid = row.try_get("id")?;
        let order_uuid: Uuid = row.try_get("order_id")?;

        let order = lock_order(&mut tx, order_uuid).await?;
        let payment = lock_payment_by_order(&mut tx, order.id).await?;
        let refund = lock_refund_by_id(&mut tx, refund_uuid)
            .await?
            .ok_or_else(|| PaymentError::NotFound {
                code: "REFUND_NOT_FOUND",
                message: format!("Refund {refund_uuid} not found."),
            })?;

        let attempt_uuid = refund
            .payment_attempt_id
            .as_deref()
            .and_then(|s| Uuid::parse_str(s).ok());

        let inserted = insert_payment_event_idempotent(
            &mut tx,
            Some(payment.id),
            attempt_uuid,
            "WEBHOOK",
            gateway_event_id,
            event_type,
            raw_payload_json,
            true,
        )
        .await?;
        if !inserted {
            tx.commit().await?;
            return Ok(());
        }

        if processed {
            mark_refund_processed_and_update_payment(
                &mut tx,
                refund_uuid,
                payment.id,
                gateway_refund_id,
            )
            .await?;
            let audit_after = serde_json::json!({ "gateway_refund_id": gateway_refund_id });
            record_audit_log(
                &mut tx,
                audit,
                "REFUND_WEBHOOK_PROCESSED",
                "refund",
                &refund.id,
                None,
                Some(&audit_after),
            )
            .await?;
        } else if refund.status == RefundStatus::Pending
            || refund.status == RefundStatus::Processing
        {
            sqlx::query("UPDATE refunds SET status = 'FAILED', updated_at = NOW() WHERE id = $1")
                .bind(refund_uuid)
                .execute(&mut *tx)
                .await?;

            notify_owners(
                &mut tx,
                new_uuid_v4(),
                &format!("Refund Failed: {}", order.order_number),
                &format!(
                    "Gateway reported refund failure for order {}. Manual retry required.",
                    order.order_number
                ),
                "REFUND_ALERT",
                Some(order.id),
            )
            .await?;

            let audit_after = serde_json::json!({ "gateway_refund_id": gateway_refund_id });
            record_audit_log(
                &mut tx,
                audit,
                "REFUND_WEBHOOK_FAILED",
                "refund",
                &refund.id,
                None,
                Some(&audit_after),
            )
            .await?;
        }

        tx.commit().await?;
        Ok(())
    }

    pub async fn initiate_owner_refund(
        &self,
        order_id: Uuid,
        owner_id: Uuid,
        raw_idempotency_key: Option<&str>,
        request: &OwnerRefundRequest,
        audit: &AuditContext,
    ) -> Result<RefundDto, PaymentError> {
        let clean_reason = request.reason.trim();
        if clean_reason.is_empty() || clean_reason.chars().count() > 250 {
            return Err(PaymentError::Validation {
                code: "INVALID_REFUND_REASON",
                message: "Refund reason (1..250 chars) is required.".to_owned(),
            });
        }
        let clean_idemp_key = raw_idempotency_key.map(str::trim).filter(|s| !s.is_empty());

        let mut tx = self.pool.begin().await?;
        let order = lock_order(&mut tx, order_id).await?;
        let payment = lock_payment_by_order(&mut tx, order_id).await?;

        if payment.method != PaymentMethod::Razorpay {
            return Err(PaymentError::Unprocessable {
                code: "REFUND_UNSUPPORTED_METHOD",
                message: "Only Razorpay orders support gateway refunds.".to_owned(),
            });
        }
        if payment.captured_paise <= 0 {
            return Err(PaymentError::Conflict {
                code: "NO_CAPTURED_FUNDS",
                message: format!(
                    "Order {} has no captured funds to refund.",
                    order.order_number
                ),
            });
        }

        if let Some(idemp_key) = clean_idemp_key {
            let scoped_key = format!("owner_refund:{order_id}:{idemp_key}");
            let row = sqlx::query(
                r#"
                SELECT id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
                       status, gateway_refund_id, reason, initiated_by, created_at, updated_at
                FROM refunds
                WHERE idempotency_key = $1
                "#,
            )
            .bind(&scoped_key)
            .fetch_optional(&mut *tx)
            .await?;

            if let Some(r) = row {
                let existing = row_to_refund_dto(&r)?;
                if existing.status != RefundStatus::Failed {
                    tx.commit().await?;
                    return Ok(existing);
                }
            }
        }

        let failed_row = sqlx::query(
            r#"
            SELECT id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
                   status, gateway_refund_id, reason, initiated_by, created_at, updated_at
            FROM refunds
            WHERE order_id = $1 AND status = 'FAILED'
            ORDER BY created_at ASC
            LIMIT 1
            FOR UPDATE
            "#,
        )
        .bind(order_id)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(r) = failed_row {
            let failed_refund = row_to_refund_dto(&r)?;
            if request.amount_paise.is_none()
                || request.amount_paise == Some(failed_refund.amount_paise)
            {
                let failed_id = Uuid::parse_str(&failed_refund.id).map_err(|_| {
                    PaymentError::Db(DbError::CatalogInvariantViolation(
                        "Invalid refund UUID in database".to_owned(),
                    ))
                })?;
                let requeued_row = sqlx::query(
                    r#"
                    UPDATE refunds
                    SET status = 'PENDING', reason = $1, initiated_by = COALESCE(initiated_by, $2), updated_at = NOW()
                    WHERE id = $3
                    RETURNING id, payment_id, payment_attempt_id, order_id, idempotency_key, amount_paise,
                              status, gateway_refund_id, reason, initiated_by, created_at, updated_at
                    "#,
                )
                .bind(clean_reason)
                .bind(owner_id)
                .bind(failed_id)
                .fetch_one(&mut *tx)
                .await?;
                let requeued = row_to_refund_dto(&requeued_row)?;

                let attempt_uuid = failed_refund
                    .payment_attempt_id
                    .as_deref()
                    .and_then(|s| Uuid::parse_str(s).ok());
                let gateway_payment_id =
                    resolve_gateway_payment_id(&mut tx, payment.id, attempt_uuid).await?;

                let mut outbox_payload = serde_json::json!({
                    "refund_id": requeued.id,
                    "order_id": order_id.to_string(),
                    "payment_id": payment.id.to_string(),
                    "amount_paise": requeued.amount_paise,
                    "idempotency_key": requeued.idempotency_key,
                });
                if let Some(obj) = outbox_payload.as_object_mut() {
                    if let Some(aid) = &failed_refund.payment_attempt_id {
                        obj.insert(
                            "payment_attempt_id".to_owned(),
                            serde_json::Value::String(aid.clone()),
                        );
                    }
                    if let Some(gpid) = gateway_payment_id {
                        obj.insert(
                            "gateway_payment_id".to_owned(),
                            serde_json::Value::String(gpid),
                        );
                    }
                }
                OutboxRepository::insert_outbox_event(
                    &mut tx,
                    "ORDER",
                    order_id,
                    order.version,
                    EVENT_REFUND_REQUESTED,
                    &outbox_payload,
                )
                .await?;

                let audit_after = serde_json::json!({
                    "order_id": order_id.to_string(),
                    "amount_paise": requeued.amount_paise,
                });
                record_audit_log(
                    &mut tx,
                    audit,
                    "OWNER_REFUND_RETRIED",
                    "refund",
                    &requeued.id,
                    None,
                    Some(&audit_after),
                )
                .await?;

                tx.commit().await?;
                return Ok(requeued);
            }
        }

        let committed_refund_total: i64 = sqlx::query_scalar(
            r#"
            SELECT COALESCE(SUM(amount_paise), 0)::bigint
            FROM refunds
            WHERE payment_id = $1 AND status IN ('PENDING', 'PROCESSING', 'PROCESSED')
            "#,
        )
        .bind(payment.id)
        .fetch_one(&mut *tx)
        .await?;

        let remaining_refundable = payment.captured_paise - committed_refund_total;
        if remaining_refundable <= 0 {
            return Err(PaymentError::Conflict {
                code: "ALREADY_REFUNDED",
                message: format!(
                    "No remaining refundable balance on order {}.",
                    order.order_number
                ),
            });
        }

        let requested_amount = request.amount_paise.unwrap_or(remaining_refundable);
        if requested_amount <= 0 || requested_amount > remaining_refundable {
            return Err(PaymentError::Unprocessable {
                code: "INVALID_REFUND_AMOUNT",
                message: format!(
                    "Refund amount ({requested_amount}) must be between 1 and remaining refundable ({remaining_refundable}) paise."
                ),
            });
        }

        let refund_id = new_uuid_v4();
        let effective_idemp_key = match clean_idemp_key {
            Some(k) => format!("owner_refund:{order_id}:{k}"),
            None => format!("owner_refund:{refund_id}"),
        };
        let attempt_id = find_captured_attempt_id(&mut tx, payment.id).await?;
        let gateway_payment_id =
            resolve_gateway_payment_id(&mut tx, payment.id, attempt_id).await?;

        let inserted_id = insert_refund_and_outbox_if_absent(
            &mut tx,
            refund_id,
            payment.id,
            attempt_id,
            order_id,
            order.version,
            &effective_idemp_key,
            requested_amount,
            clean_reason,
            Some(owner_id),
            gateway_payment_id.as_deref(),
        )
        .await?
        .ok_or_else(|| PaymentError::Conflict {
            code: "REFUND_CONFLICT",
            message: "Duplicate refund request detected.".to_owned(),
        })?;

        if payment.status != PaymentStatus::RefundPending
            && PaymentStateMachine::can_transition_payment(
                payment.status,
                PaymentStatus::RefundPending,
            )
        {
            sqlx::query(
                "UPDATE payments SET status = 'REFUND_PENDING', updated_at = NOW() WHERE id = $1",
            )
            .bind(payment.id)
            .execute(&mut *tx)
            .await?;
        }

        let audit_after = serde_json::json!({
            "order_id": order_id.to_string(),
            "amount_paise": requested_amount,
        });
        record_audit_log(
            &mut tx,
            audit,
            "OWNER_REFUND_INITIATED",
            "refund",
            &inserted_id.to_string(),
            None,
            Some(&audit_after),
        )
        .await?;

        let refund_dto = lock_refund_by_id(&mut tx, inserted_id)
            .await?
            .ok_or_else(|| PaymentError::NotFound {
                code: "REFUND_NOT_FOUND",
                message: format!("Refund {inserted_id} was not found after insert."),
            })?;
        tx.commit().await?;
        Ok(refund_dto)
    }
}
