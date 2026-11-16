use chrono::{DateTime, Utc};
use sqlx::{PgConnection, PgPool, Row as _};
use uuid::Uuid;

use crate::error::{new_uuid_v4, DbError};

pub const EVENT_EMAIL_DELIVERY_REQUESTED: &str = "EMAIL_DELIVERY_REQUESTED";
pub const EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED: &str = "PAYMENT_GATEWAY_ORDER_REQUESTED";
pub const EVENT_REFUND_REQUESTED: &str = "REFUND_REQUESTED";

#[derive(Debug, Clone, PartialEq)]
pub struct ClaimedOutboxEvent {
    pub id: Uuid,
    pub aggregate_type: String,
    pub aggregate_id: Uuid,
    pub aggregate_version: i64,
    pub event_type: String,
    pub payload_json: serde_json::Value,
    pub stream_seq: Option<i64>,
    pub sequenced_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub attempts: i32,
}

#[derive(Clone)]
pub struct OutboxRepository {
    pool: PgPool,
}

impl OutboxRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn insert_outbox_event(
        conn: &mut PgConnection,
        aggregate_type: &str,
        aggregate_id: Uuid,
        aggregate_version: i64,
        event_type: &str,
        payload_json: &serde_json::Value,
    ) -> Result<Uuid, DbError> {
        let event_id = new_uuid_v4();
        Self::insert_outbox_event_with_id(
            conn,
            event_id,
            aggregate_type,
            aggregate_id,
            aggregate_version,
            event_type,
            payload_json,
        )
        .await?;
        Ok(event_id)
    }

    pub async fn insert_outbox_event_with_id(
        conn: &mut PgConnection,
        event_id: Uuid,
        aggregate_type: &str,
        aggregate_id: Uuid,
        aggregate_version: i64,
        event_type: &str,
        payload_json: &serde_json::Value,
    ) -> Result<(), DbError> {
        sqlx::query(
            r#"
            INSERT INTO outbox_events (
                id, aggregate_type, aggregate_id, aggregate_version, event_type,
                payload_json, stream_seq, sequenced_at, dispatch_status, published_at
            ) VALUES ($1, $2, $3, $4, $5, $6, NULL, NULL, 'PENDING', NULL)
            "#,
        )
        .bind(event_id)
        .bind(aggregate_type)
        .bind(aggregate_id)
        .bind(aggregate_version)
        .bind(event_type)
        .bind(payload_json)
        .execute(&mut *conn)
        .await?;

        Ok(())
    }

    pub async fn claim_email_events(
        &self,
        batch_size: i64,
        lease_seconds: i64,
    ) -> Result<Vec<ClaimedOutboxEvent>, DbError> {
        let mut tx = self.pool.begin().await?;
        let rows = sqlx::query(
            r#"
            SELECT id, aggregate_type, aggregate_id, aggregate_version, event_type,
                   payload_json, stream_seq, sequenced_at, created_at, attempts
            FROM outbox_events
            WHERE event_type = $1
              AND dispatch_status IN ('PENDING', 'PROCESSING')
              AND next_attempt_at <= NOW()
            ORDER BY created_at ASC, id ASC
            LIMIT $2
            FOR UPDATE SKIP LOCKED
            "#,
        )
        .bind(EVENT_EMAIL_DELIVERY_REQUESTED)
        .bind(batch_size.max(1))
        .fetch_all(&mut *tx)
        .await?;

        let claimed = lease_claimed_rows(&mut tx, rows, lease_seconds).await?;
        tx.commit().await?;
        Ok(claimed)
    }

    pub async fn claim_payment_events(
        &self,
        batch_size: i64,
        lease_seconds: i64,
    ) -> Result<Vec<ClaimedOutboxEvent>, DbError> {
        let mut tx = self.pool.begin().await?;
        let rows = sqlx::query(
            r#"
            SELECT id, aggregate_type, aggregate_id, aggregate_version, event_type,
                   payload_json, stream_seq, sequenced_at, created_at, attempts
            FROM outbox_events
            WHERE event_type IN ($1, $2)
              AND dispatch_status IN ('PENDING', 'PROCESSING')
              AND next_attempt_at <= NOW()
            ORDER BY created_at ASC, id ASC
            LIMIT $3
            FOR UPDATE SKIP LOCKED
            "#,
        )
        .bind(EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED)
        .bind(EVENT_REFUND_REQUESTED)
        .bind(batch_size.max(1))
        .fetch_all(&mut *tx)
        .await?;

        let claimed = lease_claimed_rows(&mut tx, rows, lease_seconds).await?;
        tx.commit().await?;
        Ok(claimed)
    }

    pub async fn claim_broadcast_events(
        &self,
        batch_size: i64,
        lease_seconds: i64,
    ) -> Result<Vec<ClaimedOutboxEvent>, DbError> {
        let mut tx = self.pool.begin().await?;
        let rows = sqlx::query(
            r#"
            SELECT id, aggregate_type, aggregate_id, aggregate_version, event_type,
                   payload_json, stream_seq, sequenced_at, created_at, attempts
            FROM outbox_events
            WHERE stream_seq IS NOT NULL
              AND event_type NOT IN ($1, $2, $3)
              AND dispatch_status IN ('PENDING', 'PROCESSING')
              AND next_attempt_at <= NOW()
            ORDER BY stream_seq ASC
            LIMIT $4
            FOR UPDATE SKIP LOCKED
            "#,
        )
        .bind(EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED)
        .bind(EVENT_REFUND_REQUESTED)
        .bind(EVENT_EMAIL_DELIVERY_REQUESTED)
        .bind(batch_size.max(1))
        .fetch_all(&mut *tx)
        .await?;

        let claimed = lease_claimed_rows(&mut tx, rows, lease_seconds).await?;
        tx.commit().await?;
        Ok(claimed)
    }

    pub async fn mark_dispatched_in_tx(
        conn: &mut PgConnection,
        outbox_event_id: Uuid,
        scrubbed_payload: Option<&serde_json::Value>,
    ) -> Result<(), DbError> {
        if let Some(payload) = scrubbed_payload {
            sqlx::query(
                r#"
                UPDATE outbox_events
                SET dispatch_status = 'DISPATCHED',
                    published_at = NOW(),
                    last_error = NULL,
                    payload_json = $2
                WHERE id = $1
                "#,
            )
            .bind(outbox_event_id)
            .bind(payload)
            .execute(&mut *conn)
            .await?;
        } else {
            sqlx::query(
                r#"
                UPDATE outbox_events
                SET dispatch_status = 'DISPATCHED',
                    published_at = NOW(),
                    last_error = NULL
                WHERE id = $1
                "#,
            )
            .bind(outbox_event_id)
            .execute(&mut *conn)
            .await?;
        }
        Ok(())
    }

    pub async fn mark_dispatched(
        &self,
        outbox_event_id: Uuid,
        scrubbed_payload: Option<&serde_json::Value>,
    ) -> Result<(), DbError> {
        let mut tx = self.pool.begin().await?;
        Self::mark_dispatched_in_tx(&mut tx, outbox_event_id, scrubbed_payload).await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn schedule_retry(
        &self,
        outbox_event_id: Uuid,
        backoff_seconds: i64,
        sanitized_error: &str,
    ) -> Result<(), DbError> {
        let truncated: String = sanitized_error.chars().take(500).collect();
        sqlx::query(
            r#"
            UPDATE outbox_events
            SET dispatch_status = 'PENDING',
                next_attempt_at = NOW() + make_interval(secs => $2),
                last_error = $3
            WHERE id = $1
            "#,
        )
        .bind(outbox_event_id)
        .bind(backoff_seconds.max(1) as f64)
        .bind(truncated)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn mark_dead_letter(
        &self,
        outbox_event_id: Uuid,
        sanitized_error: &str,
        scrubbed_payload: Option<&serde_json::Value>,
    ) -> Result<(), DbError> {
        let truncated: String = sanitized_error.chars().take(500).collect();
        if let Some(payload) = scrubbed_payload {
            sqlx::query(
                r#"
                UPDATE outbox_events
                SET dispatch_status = 'DEAD_LETTER',
                    published_at = NULL,
                    last_error = $2,
                    payload_json = $3
                WHERE id = $1
                "#,
            )
            .bind(outbox_event_id)
            .bind(truncated)
            .bind(payload)
            .execute(&self.pool)
            .await?;
        } else {
            sqlx::query(
                r#"
                UPDATE outbox_events
                SET dispatch_status = 'DEAD_LETTER',
                    published_at = NULL,
                    last_error = $2
                WHERE id = $1
                "#,
            )
            .bind(outbox_event_id)
            .bind(truncated)
            .execute(&self.pool)
            .await?;
        }
        Ok(())
    }

    /// Scrubs any remaining `sealed_envelope` from `EMAIL_DELIVERY_REQUESTED` outbox rows once the
    /// token is expired or the row reached `DISPATCHED` / `DEAD_LETTER`. Never reads keys or decrypts.
    pub async fn scrub_expired_email_envelopes(&self) -> Result<u64, DbError> {
        let res = sqlx::query(
            r#"
            UPDATE outbox_events
            SET payload_json = jsonb_set(
                jsonb_set(payload_json, '{sealed_envelope}', 'null'::jsonb, true),
                '{redacted}',
                'true'::jsonb,
                true
            )
            WHERE event_type = $1
              AND payload_json ? 'sealed_envelope'
              AND payload_json->'sealed_envelope' <> 'null'::jsonb
              AND (
                  dispatch_status IN ('DISPATCHED', 'DEAD_LETTER')
                  OR (
                      NULLIF(payload_json->>'token_expires_at', '') IS NOT NULL
                      AND (payload_json->>'token_expires_at')::timestamptz <= NOW()
                  )
              )
            "#,
        )
        .bind(EVENT_EMAIL_DELIVERY_REQUESTED)
        .execute(&self.pool)
        .await?;

        Ok(res.rows_affected())
    }

    pub async fn purge_expired_dispatched_email_rows(
        &self,
        retention_days: i64,
    ) -> Result<u64, DbError> {
        let days = retention_days.max(1) as f64;
        let res = sqlx::query(
            r#"
            DELETE FROM outbox_events
            WHERE event_type = $1
              AND dispatch_status IN ('DISPATCHED', 'DEAD_LETTER')
              AND created_at <= NOW() - make_interval(days => $2)
            "#,
        )
        .bind(EVENT_EMAIL_DELIVERY_REQUESTED)
        .bind(days)
        .execute(&self.pool)
        .await?;

        Ok(res.rows_affected())
    }
}

async fn lease_claimed_rows(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    rows: Vec<sqlx::postgres::PgRow>,
    lease_seconds: i64,
) -> Result<Vec<ClaimedOutboxEvent>, DbError> {
    if rows.is_empty() {
        return Ok(Vec::new());
    }

    let mut claimed = Vec::with_capacity(rows.len());
    let mut ids = Vec::with_capacity(rows.len());
    for row in rows {
        let id: Uuid = row.try_get("id")?;
        let attempts: i32 = row.try_get("attempts")?;
        ids.push(id);
        claimed.push(ClaimedOutboxEvent {
            id,
            aggregate_type: row.try_get("aggregate_type")?,
            aggregate_id: row.try_get("aggregate_id")?,
            aggregate_version: row.try_get("aggregate_version")?,
            event_type: row.try_get("event_type")?,
            payload_json: row.try_get("payload_json")?,
            stream_seq: row.try_get("stream_seq")?,
            sequenced_at: row.try_get("sequenced_at")?,
            created_at: row.try_get("created_at")?,
            attempts: attempts + 1,
        });
    }

    sqlx::query(
        r#"
        UPDATE outbox_events
        SET dispatch_status = 'PROCESSING',
            attempts = attempts + 1,
            next_attempt_at = NOW() + make_interval(secs => $2)
        WHERE id = ANY($1)
        "#,
    )
    .bind(&ids)
    .bind(lease_seconds.max(1) as f64)
    .execute(&mut **tx)
    .await?;

    Ok(claimed)
}
