use pizzora_core::{PaymentAttemptStatus, PaymentStateMachine};
use pizzora_db::{record_audit_log, AuditContext};
use sqlx::Row as _;
use uuid::Uuid;

use crate::error::PaymentError;
use crate::repo::{
    insert_payment_event_idempotent, lock_attempt_by_id, lock_order, lock_payment_by_order,
};
use crate::service::PaymentService;

impl PaymentService {
    #[allow(clippy::too_many_arguments)]
    pub async fn process_payment_failed_webhook(
        &self,
        gateway_event_id: &str,
        gateway_order_id: &str,
        gateway_payment_id: Option<&str>,
        failure_code: Option<&str>,
        failure_reason: Option<&str>,
        raw_payload_json: &serde_json::Value,
        audit: &AuditContext,
    ) -> Result<(), PaymentError> {
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
        .await?;

        let Some(row) = lookup_row else {
            insert_payment_event_idempotent(
                &mut tx,
                None,
                None,
                "WEBHOOK",
                gateway_event_id,
                "payment.failed",
                raw_payload_json,
                true,
            )
            .await?;
            tx.commit().await?;
            return Ok(());
        };

        let order_id: Uuid = row.try_get("order_id")?;
        let payment_id: Uuid = row.try_get("payment_id")?;
        let attempt_id: Uuid = row.try_get("attempt_id")?;

        lock_order(&mut tx, order_id).await?;
        lock_payment_by_order(&mut tx, order_id).await?;
        let attempt = lock_attempt_by_id(&mut tx, attempt_id).await?;

        let inserted = insert_payment_event_idempotent(
            &mut tx,
            Some(payment_id),
            Some(attempt.id),
            "WEBHOOK",
            gateway_event_id,
            "payment.failed",
            raw_payload_json,
            true,
        )
        .await?;
        if !inserted {
            tx.commit().await?;
            return Ok(());
        }

        if PaymentStateMachine::can_transition_attempt(attempt.status, PaymentAttemptStatus::Failed)
        {
            let eff_code: String = failure_code
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(|s| s.chars().take(64).collect())
                .unwrap_or_else(|| "PAYMENT_FAILED".to_owned());
            let eff_reason: String = failure_reason
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(|s| s.chars().take(250).collect())
                .unwrap_or_else(|| "Gateway reported payment failure".to_owned());

            sqlx::query(
                r#"
                UPDATE payment_attempts
                SET status = 'FAILED',
                    gateway_payment_id = COALESCE(gateway_payment_id, $1),
                    failure_code = $2,
                    failure_reason = $3,
                    updated_at = NOW()
                WHERE id = $4
                "#,
            )
            .bind(gateway_payment_id)
            .bind(&eff_code)
            .bind(&eff_reason)
            .bind(attempt.id)
            .execute(&mut *tx)
            .await?;

            let audit_after = serde_json::json!({
                "order_id": order_id.to_string(),
                "failure_code": eff_code,
            });
            record_audit_log(
                &mut tx,
                audit,
                "PAYMENT_WEBHOOK_FAILED",
                "payment_attempt",
                &attempt.id.to_string(),
                None,
                Some(&audit_after),
            )
            .await?;
        }

        tx.commit().await?;
        Ok(())
    }
}
