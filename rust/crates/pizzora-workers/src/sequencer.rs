use std::sync::Arc;
use std::time::Duration;

use sqlx::{PgPool, Row as _};
use tokio_util::sync::CancellationToken;
use tracing::{debug, error};
use uuid::Uuid;

use crate::error::WorkerError;
use crate::metrics::{WorkerMetrics, LOOP_SEQUENCER};

pub const EVENT_SEQUENCER_LOCK_KEY: i64 = 748_291_041;

#[derive(Clone)]
pub struct EventSequencerWorker {
    pool: PgPool,
    batch_size: i64,
}

impl EventSequencerWorker {
    pub fn new(pool: PgPool, batch_size: i64) -> Self {
        Self {
            pool,
            batch_size: batch_size.clamp(1, 500),
        }
    }

    pub async fn run_once(&self) -> Result<usize, WorkerError> {
        let mut tx = self.pool.begin().await?;

        sqlx::query("SELECT pg_advisory_xact_lock($1)")
            .bind(EVENT_SEQUENCER_LOCK_KEY)
            .execute(&mut *tx)
            .await?;

        sqlx::query(
            "INSERT INTO event_stream_cursor (id, last_seq) VALUES (1, 0) ON CONFLICT (id) DO NOTHING",
        )
        .execute(&mut *tx)
        .await?;

        let current_cursor: i64 =
            sqlx::query_scalar("SELECT last_seq FROM event_stream_cursor WHERE id = 1 FOR UPDATE")
                .fetch_one(&mut *tx)
                .await?;

        let rows = sqlx::query(
            r#"
            SELECT id
            FROM outbox_events
            WHERE stream_seq IS NULL
            ORDER BY created_at ASC, id ASC
            LIMIT $1
            FOR UPDATE
            "#,
        )
        .bind(self.batch_size)
        .fetch_all(&mut *tx)
        .await?;

        if rows.is_empty() {
            tx.commit().await?;
            return Ok(0);
        }

        let mut ids = Vec::with_capacity(rows.len());
        let mut seqs = Vec::with_capacity(rows.len());
        let mut next_seq = current_cursor;
        for row in rows {
            let id: Uuid = row.try_get("id")?;
            next_seq += 1;
            ids.push(id);
            seqs.push(next_seq);
        }

        sqlx::query(
            r#"
            UPDATE outbox_events AS o
            SET stream_seq = v.seq,
                sequenced_at = NOW()
            FROM UNNEST($1::uuid[], $2::bigint[]) AS v(id, seq)
            WHERE o.id = v.id
              AND o.stream_seq IS NULL
            "#,
        )
        .bind(&ids)
        .bind(&seqs)
        .execute(&mut *tx)
        .await?;

        sqlx::query("UPDATE event_stream_cursor SET last_seq = $1 WHERE id = 1")
            .bind(next_seq)
            .execute(&mut *tx)
            .await?;

        tx.commit().await?;
        Ok(ids.len())
    }

    pub async fn run_loop(
        &self,
        poll_interval: Duration,
        metrics: Arc<WorkerMetrics>,
        shutdown: CancellationToken,
    ) {
        let mut consecutive_failures: u32 = 0;
        loop {
            if shutdown.is_cancelled() {
                break;
            }

            let delay_needed = match self.run_once().await {
                Ok(sequenced) => {
                    consecutive_failures = 0;
                    metrics.record_loop_success(LOOP_SEQUENCER);
                    if sequenced > 0 {
                        debug!(sequenced, "EventSequencerWorker assigned stream_seq batch");
                        false
                    } else {
                        true
                    }
                }
                Err(err) => {
                    consecutive_failures = consecutive_failures.saturating_add(1);
                    metrics.record_loop_error(LOOP_SEQUENCER, consecutive_failures >= 3);
                    error!(
                        consecutive_failures,
                        error = %err,
                        "EventSequencerWorker loop iteration failed"
                    );
                    true
                }
            };

            if delay_needed {
                tokio::select! {
                    _ = shutdown.cancelled() => break,
                    _ = tokio::time::sleep(poll_interval) => {}
                }
            }
        }
        metrics.set_loop_healthy(LOOP_SEQUENCER, false);
    }
}
