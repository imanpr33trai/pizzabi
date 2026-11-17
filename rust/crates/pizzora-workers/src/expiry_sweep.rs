use std::sync::Arc;
use std::time::Duration;

use pizzora_db::{
    record_audit_log, AuditContext, CouponService, InventoryService, OutboxRepository,
};
use sqlx::{PgPool, Row as _};
use tokio_util::sync::CancellationToken;
use tracing::{debug, error, info};
use uuid::Uuid;

use crate::error::WorkerError;
use crate::metrics::{WorkerMetrics, LOOP_EXPIRY_SWEEP};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ExpirySweepSummary {
    pub expired_orders: usize,
    pub scrubbed_envelopes: u64,
    pub purged_email_rows: u64,
}

#[derive(Clone)]
pub struct ExpirySweepWorker {
    pool: PgPool,
    outbox_repo: OutboxRepository,
    batch_size: i64,
    outbox_retention_days: i64,
}

impl ExpirySweepWorker {
    pub fn new(pool: PgPool, batch_size: i64, outbox_retention_days: i64) -> Self {
        let outbox_repo = OutboxRepository::new(pool.clone());
        Self {
            pool,
            outbox_repo,
            batch_size: batch_size.clamp(1, 200),
            outbox_retention_days: outbox_retention_days.max(1),
        }
    }

    pub async fn run_once(&self) -> Result<ExpirySweepSummary, WorkerError> {
        let expired_orders = self.expire_pending_orders_once().await?;
        let scrubbed_envelopes = self.outbox_repo.scrub_expired_email_envelopes().await?;
        let purged_email_rows = self
            .outbox_repo
            .purge_expired_dispatched_email_rows(self.outbox_retention_days)
            .await?;

        Ok(ExpirySweepSummary {
            expired_orders,
            scrubbed_envelopes,
            purged_email_rows,
        })
    }

    async fn expire_pending_orders_once(&self) -> Result<usize, WorkerError> {
        let candidate_ids: Vec<Uuid> = sqlx::query_scalar(
            r#"
            SELECT id
            FROM orders
            WHERE status = 'PENDING_PAYMENT'
              AND expires_at IS NOT NULL
              AND expires_at <= NOW()
            ORDER BY expires_at ASC
            LIMIT $1
            "#,
        )
        .bind(self.batch_size)
        .fetch_all(&self.pool)
        .await?;

        let mut expired_count = 0usize;
        for order_id in candidate_ids {
            if self.expire_single_order(order_id).await? {
                expired_count += 1;
            }
        }
        Ok(expired_count)
    }

    async fn expire_single_order(&self, order_id: Uuid) -> Result<bool, WorkerError> {
        let mut tx = self.pool.begin().await?;

        let locked_order = sqlx::query(
            r#"
            SELECT id, customer_id, order_number, version
            FROM orders
            WHERE id = $1
              AND status = 'PENDING_PAYMENT'
              AND expires_at IS NOT NULL
              AND expires_at <= NOW()
            FOR UPDATE SKIP LOCKED
            "#,
        )
        .bind(order_id)
        .fetch_optional(&mut *tx)
        .await?;

        let Some(order_row) = locked_order else {
            tx.commit().await?;
            return Ok(false);
        };

        let customer_id: Uuid = order_row.try_get("customer_id")?;
        let order_number: String = order_row.try_get("order_number")?;
        let old_version: i64 = order_row.try_get("version")?;
        let new_version = old_version + 1;

        let payment_id: Option<Uuid> =
            sqlx::query_scalar("SELECT id FROM payments WHERE order_id = $1 FOR UPDATE")
                .bind(order_id)
                .fetch_optional(&mut *tx)
                .await?;

        sqlx::query("UPDATE orders SET status = 'EXPIRED', version = $1 WHERE id = $2")
            .bind(new_version)
            .bind(order_id)
            .execute(&mut *tx)
            .await?;

        sqlx::query(
            r#"
            INSERT INTO order_status_history (id, order_id, from_status, to_status, changed_by, reason)
            VALUES ($1, $2, 'PENDING_PAYMENT', 'EXPIRED', NULL, 'Payment window expired')
            "#,
        )
        .bind(pizzora_db::new_uuid_v4())
        .bind(order_id)
        .execute(&mut *tx)
        .await?;

        if let Some(pid) = payment_id {
            sqlx::query(
                r#"
                UPDATE payment_attempts
                SET status = 'EXPIRED', updated_at = NOW()
                WHERE payment_id = $1
                  AND status IN ('INITIATING', 'AWAITING_CUSTOMER_PAYMENT', 'AUTHORIZED')
                "#,
            )
            .bind(pid)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "UPDATE payments SET status = 'EXPIRED', updated_at = NOW() WHERE id = $1 AND status = 'UNPAID'",
            )
            .bind(pid)
            .execute(&mut *tx)
            .await?;
        }

        InventoryService::release_reserved_inventory(&mut tx, order_id, true, None).await?;
        CouponService::release_reserved_coupon(&mut tx, order_id, true).await?;

        // Guarded slot release: decrement only when reservation transitions ACTIVE -> EXPIRED
        let released_slot_id: Option<Uuid> = sqlx::query_scalar(
            r#"
            UPDATE scheduled_slot_reservations
            SET status = 'EXPIRED', expires_at = NULL, updated_at = NOW()
            WHERE order_id = $1 AND status = 'ACTIVE'
            RETURNING slot_id
            "#,
        )
        .bind(order_id)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(slot_id) = released_slot_id {
            sqlx::query(
                "UPDATE scheduled_slots SET reserved_count = GREATEST(0, reserved_count - 1) WHERE id = $1",
            )
            .bind(slot_id)
            .execute(&mut *tx)
            .await?;
        }

        sqlx::query(
            r#"
            UPDATE outbox_events
            SET dispatch_status = 'DISPATCHED',
                published_at = NOW(),
                last_error = 'Order expired prior to gateway dispatch'
            WHERE aggregate_id = $1
              AND event_type = 'PAYMENT_GATEWAY_ORDER_REQUESTED'
              AND dispatch_status IN ('PENDING', 'PROCESSING')
            "#,
        )
        .bind(order_id)
        .execute(&mut *tx)
        .await?;

        let expired_payload = serde_json::json!({
            "order_id": order_id.to_string(),
            "order_number": order_number,
            "customer_id": customer_id.to_string(),
            "from_status": "PENDING_PAYMENT",
            "to_status": "EXPIRED",
        });
        OutboxRepository::insert_outbox_event(
            &mut tx,
            "ORDER",
            order_id,
            new_version,
            "ORDER_EXPIRED",
            &expired_payload,
        )
        .await?;

        let audit = AuditContext {
            actor_user_id: None,
            actor_role: "SYSTEM".to_owned(),
            correlation_id: order_id.to_string(),
            ip_address: None,
        };
        let before_val = serde_json::json!({
            "status": "PENDING_PAYMENT",
            "version": old_version,
        });
        let after_val = serde_json::json!({
            "status": "EXPIRED",
            "version": new_version,
        });
        record_audit_log(
            &mut tx,
            &audit,
            "ORDER_EXPIRED",
            "order",
            &order_id.to_string(),
            Some(&before_val),
            Some(&after_val),
        )
        .await?;

        tx.commit().await?;
        Ok(true)
    }

    pub async fn run_loop(
        &self,
        sweep_interval: Duration,
        metrics: Arc<WorkerMetrics>,
        shutdown: CancellationToken,
    ) {
        let mut consecutive_failures: u32 = 0;
        loop {
            if shutdown.is_cancelled() {
                break;
            }

            match self.run_once().await {
                Ok(summary) => {
                    consecutive_failures = 0;
                    metrics.record_loop_success(LOOP_EXPIRY_SWEEP);
                    if summary.expired_orders > 0 {
                        metrics.inc_orders_expired(summary.expired_orders as u64);
                    }
                    if summary.scrubbed_envelopes > 0 {
                        metrics.inc_envelopes_scrubbed(summary.scrubbed_envelopes);
                    }
                    if summary.purged_email_rows > 0 {
                        metrics.inc_email_rows_purged(summary.purged_email_rows);
                    }
                    if summary.expired_orders > 0
                        || summary.scrubbed_envelopes > 0
                        || summary.purged_email_rows > 0
                    {
                        info!(
                            expired_orders = summary.expired_orders,
                            scrubbed_envelopes = summary.scrubbed_envelopes,
                            purged_email_rows = summary.purged_email_rows,
                            "ExpirySweepWorker completed sweep with changes"
                        );
                    } else {
                        debug!("ExpirySweepWorker completed sweep with no expired records");
                    }
                }
                Err(err) => {
                    consecutive_failures = consecutive_failures.saturating_add(1);
                    metrics.record_loop_error(LOOP_EXPIRY_SWEEP, consecutive_failures >= 3);
                    error!(
                        consecutive_failures,
                        error = %err,
                        "ExpirySweepWorker loop iteration failed"
                    );
                }
            }

            tokio::select! {
                _ = shutdown.cancelled() => break,
                _ = tokio::time::sleep(sweep_interval) => {}
            }
        }
        metrics.set_loop_healthy(LOOP_EXPIRY_SWEEP, false);
    }
}
