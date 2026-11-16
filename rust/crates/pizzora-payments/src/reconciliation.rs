use std::collections::BTreeMap;
use std::sync::Arc;

use pizzora_core::RefundStatus;
use pizzora_db::{record_audit_log, AuditContext};
use sqlx::{PgPool, Row as _};
use tracing::warn;
use uuid::Uuid;

use crate::error::PaymentError;
use crate::gateway::RazorpayGateway;
use crate::repo::{insert_payment_event_idempotent, mark_refund_processed_and_update_payment};
use crate::service::PaymentService;
use crate::status_map::parse_refund_status;

/// Reconciler and outbox gateway dispatch support shared with `pizzora-workers` (Phase 8).
#[derive(Clone)]
pub struct RazorpayReconciliationService {
    pub(crate) pool: PgPool,
    pub(crate) gateway: Arc<dyn RazorpayGateway>,
    pub(crate) payment_service: PaymentService,
    pub(crate) batch_size: i64,
}

#[derive(Debug, Clone)]
struct RefundReconcileCandidate {
    refund_id: Uuid,
    payment_id: Uuid,
    attempt_id: Option<Uuid>,
    order_id: Uuid,
    idempotency_key: String,
    amount_paise: i64,
    status: RefundStatus,
    gateway_refund_id: Option<String>,
    gateway_payment_id: String,
}

#[derive(Debug, Clone)]
struct OpenAttemptCandidate {
    attempt_id: Uuid,
    gateway_order_id: String,
    order_id: Uuid,
}

impl RazorpayReconciliationService {
    pub fn new(
        pool: PgPool,
        gateway: Arc<dyn RazorpayGateway>,
        payment_service: PaymentService,
        batch_size: i64,
    ) -> Self {
        Self {
            pool,
            gateway,
            payment_service,
            batch_size: batch_size.max(1),
        }
    }

    pub async fn run_once(&self) -> Result<usize, PaymentError> {
        let mut actions = 0usize;
        actions += self.reconcile_refunds().await?;
        actions += self.reconcile_open_payment_attempts().await?;
        Ok(actions)
    }

    pub async fn reconcile_refunds(&self) -> Result<usize, PaymentError> {
        let rows = sqlx::query(
            r#"
            SELECT r.id, r.payment_id, r.payment_attempt_id, r.order_id, r.idempotency_key,
                   r.amount_paise, r.status, r.gateway_refund_id,
                   COALESCE(
                     (SELECT pa.gateway_payment_id FROM payment_attempts pa WHERE pa.id = r.payment_attempt_id),
                     (SELECT pa2.gateway_payment_id FROM payment_attempts pa2
                      WHERE pa2.payment_id = r.payment_id AND pa2.gateway_payment_id IS NOT NULL
                      ORDER BY pa2.attempt_number DESC LIMIT 1)
                   ) AS gateway_payment_id
            FROM refunds r
            WHERE r.status IN ('FAILED', 'PROCESSING')
            ORDER BY r.updated_at ASC
            LIMIT $1
            "#,
        )
        .bind(self.batch_size)
        .fetch_all(&self.pool)
        .await?;

        let mut candidates = Vec::new();
        for row in rows {
            let gw_pay_id: Option<String> = row.try_get("gateway_payment_id")?;
            let Some(gateway_payment_id) = gw_pay_id else {
                continue;
            };
            let status_raw: String = row.try_get("status")?;
            candidates.push(RefundReconcileCandidate {
                refund_id: row.try_get("id")?,
                payment_id: row.try_get("payment_id")?,
                attempt_id: row.try_get("payment_attempt_id")?,
                order_id: row.try_get("order_id")?,
                idempotency_key: row.try_get("idempotency_key")?,
                amount_paise: row.try_get("amount_paise")?,
                status: parse_refund_status(&status_raw)?,
                gateway_refund_id: row.try_get("gateway_refund_id")?,
                gateway_payment_id,
            });
        }

        let mut reconciled = 0usize;
        for candidate in candidates {
            let remote_refunds = match self
                .gateway
                .fetch_refunds_for_payment(&candidate.gateway_payment_id)
                .await
            {
                Ok(list) => list,
                Err(err) => {
                    warn!(
                        refund_id = %candidate.refund_id,
                        error = %err,
                        "Reconciliation fetch_refunds failed"
                    );
                    continue;
                }
            };

            let matched = remote_refunds.into_iter().find(|remote| {
                candidate
                    .gateway_refund_id
                    .as_deref()
                    .is_some_and(|gid| gid == remote.gateway_refund_id)
                    || remote
                        .notes
                        .get("idempotency_key")
                        .map(String::as_str)
                        == Some(candidate.idempotency_key.as_str())
            });

            if let Some(remote) = matched {
                if remote.status.eq_ignore_ascii_case("processed")
                    || remote.status.eq_ignore_ascii_case("succeeded")
                {
                    if let Err(err) = self
                        .mark_refund_reconciled_processed(&candidate, &remote.gateway_refund_id)
                        .await
                    {
                        warn!(
                            refund_id = %candidate.refund_id,
                            error = %err,
                            "Failed persisting reconciled processed refund"
                        );
                    } else {
                        reconciled += 1;
                    }
                }
            } else if candidate.status == RefundStatus::Failed {
                let mut notes = BTreeMap::new();
                notes.insert(
                    "idempotency_key".to_owned(),
                    candidate.idempotency_key.clone(),
                );
                notes.insert("order_id".to_owned(), candidate.order_id.to_string());
                notes.insert("refund_id".to_owned(), candidate.refund_id.to_string());

                match self
                    .gateway
                    .create_refund(
                        &candidate.gateway_payment_id,
                        candidate.amount_paise,
                        &candidate.idempotency_key,
                        notes,
                    )
                    .await
                {
                    Ok(created) => {
                        if let Err(err) = self
                            .mark_refund_reconciled_processed(
                                &candidate,
                                &created.gateway_refund_id,
                            )
                            .await
                        {
                            warn!(
                                refund_id = %candidate.refund_id,
                                error = %err,
                                "Failed marking retried refund as processed"
                            );
                        } else {
                            reconciled += 1;
                        }
                    }
                    Err(err) => {
                        warn!(
                            refund_id = %candidate.refund_id,
                            error = %err,
                            "Reconciliation create_refund retry failed"
                        );
                    }
                }
            }
        }

        Ok(reconciled)
    }

    pub async fn reconcile_open_payment_attempts(&self) -> Result<usize, PaymentError> {
        let rows = sqlx::query(
            r#"
            SELECT pa.id, pa.gateway_order_id, p.order_id
            FROM payment_attempts pa
            JOIN payments p ON p.id = pa.payment_id
            WHERE pa.method = 'RAZORPAY'
              AND pa.status = 'AWAITING_CUSTOMER_PAYMENT'
              AND pa.gateway_order_id IS NOT NULL
            ORDER BY pa.updated_at ASC
            LIMIT $1
            "#,
        )
        .bind(self.batch_size)
        .fetch_all(&self.pool)
        .await?;

        let mut open_attempts = Vec::with_capacity(rows.len());
        for row in rows {
            open_attempts.push(OpenAttemptCandidate {
                attempt_id: row.try_get("id")?,
                gateway_order_id: row.try_get("gateway_order_id")?,
                order_id: row.try_get("order_id")?,
            });
        }

        let mut reconciled = 0usize;
        for attempt in open_attempts {
            let gateway_payments = match self
                .gateway
                .fetch_payments_for_order(&attempt.gateway_order_id)
                .await
            {
                Ok(list) => list,
                Err(err) => {
                    warn!(
                        attempt_id = %attempt.attempt_id,
                        error = %err,
                        "Reconciliation fetch_payments_for_order failed"
                    );
                    continue;
                }
            };

            let Some(captured) = gateway_payments
                .into_iter()
                .find(|p| p.status.eq_ignore_ascii_case("captured"))
            else {
                continue;
            };

            let payload = serde_json::json!({
                "gateway_order_id": attempt.gateway_order_id,
                "gateway_payment_id": captured.gateway_payment_id,
                "amount_paise": captured.amount_paise,
                "status": captured.status,
            });
            let audit = AuditContext {
                actor_user_id: None,
                actor_role: "SYSTEM".to_owned(),
                correlation_id: attempt.attempt_id.to_string(),
                ip_address: None,
            };

            if let Err(err) = self
                .payment_service
                .process_payment_captured(
                    "RECONCILER",
                    &format!("reconciler_capture:{}", captured.gateway_payment_id),
                    &attempt.gateway_order_id,
                    &captured.gateway_payment_id,
                    (captured.amount_paise > 0).then_some(captured.amount_paise),
                    Some(attempt.order_id),
                    None,
                    &payload,
                    &audit,
                )
                .await
            {
                warn!(
                    attempt_id = %attempt.attempt_id,
                    error = %err,
                    "Reconciliation capture persistence failed"
                );
            } else {
                reconciled += 1;
            }
        }

        Ok(reconciled)
    }

    async fn mark_refund_reconciled_processed(
        &self,
        candidate: &RefundReconcileCandidate,
        gateway_refund_id: &str,
    ) -> Result<(), PaymentError> {
        let mut tx = self.pool.begin().await?;
        mark_refund_processed_and_update_payment(
            &mut tx,
            candidate.refund_id,
            candidate.payment_id,
            gateway_refund_id,
        )
        .await?;

        let event_payload = serde_json::json!({
            "refund_id": candidate.refund_id.to_string(),
            "gateway_refund_id": gateway_refund_id,
        });
        insert_payment_event_idempotent(
            &mut tx,
            Some(candidate.payment_id),
            candidate.attempt_id,
            "RECONCILER",
            &format!("reconciler_refund:{gateway_refund_id}"),
            "refund.processed",
            &event_payload,
            true,
        )
        .await?;

        let audit = AuditContext {
            actor_user_id: None,
            actor_role: "SYSTEM".to_owned(),
            correlation_id: candidate.refund_id.to_string(),
            ip_address: None,
        };
        let after = serde_json::json!({ "gateway_refund_id": gateway_refund_id });
        record_audit_log(
            &mut tx,
            &audit,
            "REFUND_RECONCILED_PROCESSED",
            "refund",
            &candidate.refund_id.to_string(),
            None,
            Some(&after),
        )
        .await?;

        tx.commit().await?;
        Ok(())
    }
}
