use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use sqlx::{PgPool, Row as _};

pub const LOOP_SEQUENCER: &str = "sequencer_loop";
pub const LOOP_DISPATCHER: &str = "dispatcher_loop";
pub const LOOP_EXPIRY_SWEEP: &str = "expiry_sweep";
pub const LOOP_RECONCILER: &str = "reconciler_loop";

pub const ALL_WORKER_LOOPS: [&str; 4] = [
    LOOP_SEQUENCER,
    LOOP_DISPATCHER,
    LOOP_EXPIRY_SWEEP,
    LOOP_RECONCILER,
];

#[derive(Debug)]
struct LoopCounters {
    healthy: AtomicBool,
    cycles_total: AtomicU64,
    errors_total: AtomicU64,
    last_beat_epoch_secs: AtomicI64,
}

impl Default for LoopCounters {
    fn default() -> Self {
        Self {
            healthy: AtomicBool::new(true),
            cycles_total: AtomicU64::new(0),
            errors_total: AtomicU64::new(0),
            last_beat_epoch_secs: AtomicI64::new(0),
        }
    }
}

#[derive(Debug)]
pub struct WorkerMetrics {
    started_at: Instant,
    loops: BTreeMap<&'static str, LoopCounters>,
    dispatched_payment: AtomicU64,
    dispatched_refund: AtomicU64,
    dispatched_email: AtomicU64,
    dispatched_broadcast: AtomicU64,
    dead_letter_email: AtomicU64,
    dead_letter_broadcast: AtomicU64,
    orders_expired: AtomicU64,
    email_envelopes_scrubbed: AtomicU64,
    email_rows_purged: AtomicU64,
    reconciler_actions: AtomicU64,
}

impl Default for WorkerMetrics {
    fn default() -> Self {
        Self::new()
    }
}

impl WorkerMetrics {
    pub fn new() -> Self {
        let mut loops = BTreeMap::new();
        for name in ALL_WORKER_LOOPS {
            loops.insert(name, LoopCounters::default());
        }
        Self {
            started_at: Instant::now(),
            loops,
            dispatched_payment: AtomicU64::new(0),
            dispatched_refund: AtomicU64::new(0),
            dispatched_email: AtomicU64::new(0),
            dispatched_broadcast: AtomicU64::new(0),
            dead_letter_email: AtomicU64::new(0),
            dead_letter_broadcast: AtomicU64::new(0),
            orders_expired: AtomicU64::new(0),
            email_envelopes_scrubbed: AtomicU64::new(0),
            email_rows_purged: AtomicU64::new(0),
            reconciler_actions: AtomicU64::new(0),
        }
    }

    pub fn set_loop_healthy(&self, loop_name: &str, healthy: bool) {
        if let Some(entry) = self.loops.get(loop_name) {
            entry.healthy.store(healthy, Ordering::SeqCst);
        }
    }

    pub fn record_loop_success(&self, loop_name: &str) {
        if let Some(entry) = self.loops.get(loop_name) {
            entry.healthy.store(true, Ordering::SeqCst);
            entry.cycles_total.fetch_add(1, Ordering::Relaxed);
            entry
                .last_beat_epoch_secs
                .store(current_epoch_secs(), Ordering::Relaxed);
        }
    }

    pub fn record_loop_error(&self, loop_name: &str, mark_unhealthy: bool) {
        if let Some(entry) = self.loops.get(loop_name) {
            entry.errors_total.fetch_add(1, Ordering::Relaxed);
            if mark_unhealthy {
                entry.healthy.store(false, Ordering::SeqCst);
            }
        }
    }

    pub fn loop_health_snapshot(&self) -> BTreeMap<String, bool> {
        self.loops
            .iter()
            .map(|(&name, counters)| (name.to_owned(), counters.healthy.load(Ordering::SeqCst)))
            .collect()
    }

    pub fn inc_payment_dispatched(&self, count: u64) {
        self.dispatched_payment.fetch_add(count, Ordering::Relaxed);
    }

    pub fn inc_refund_dispatched(&self, count: u64) {
        self.dispatched_refund.fetch_add(count, Ordering::Relaxed);
    }

    pub fn inc_email_dispatched(&self, count: u64) {
        self.dispatched_email.fetch_add(count, Ordering::Relaxed);
    }

    pub fn inc_broadcast_dispatched(&self, count: u64) {
        self.dispatched_broadcast
            .fetch_add(count, Ordering::Relaxed);
    }

    pub fn inc_email_dead_letter(&self, count: u64) {
        self.dead_letter_email.fetch_add(count, Ordering::Relaxed);
    }

    pub fn inc_broadcast_dead_letter(&self, count: u64) {
        self.dead_letter_broadcast
            .fetch_add(count, Ordering::Relaxed);
    }

    pub fn inc_orders_expired(&self, count: u64) {
        self.orders_expired.fetch_add(count, Ordering::Relaxed);
    }

    pub fn inc_envelopes_scrubbed(&self, count: u64) {
        self.email_envelopes_scrubbed
            .fetch_add(count, Ordering::Relaxed);
    }

    pub fn inc_email_rows_purged(&self, count: u64) {
        self.email_rows_purged.fetch_add(count, Ordering::Relaxed);
    }

    pub fn inc_reconciler_actions(&self, count: u64) {
        self.reconciler_actions.fetch_add(count, Ordering::Relaxed);
    }

    pub async fn render_prometheus(&self, pool: Option<&PgPool>) -> String {
        let mut out = String::with_capacity(2560);
        let uptime = self.started_at.elapsed().as_secs_f64();

        let _ = writeln!(
            out,
            "# HELP pizzora_worker_uptime_seconds Worker process uptime in seconds\n\
             # TYPE pizzora_worker_uptime_seconds gauge\n\
             pizzora_worker_uptime_seconds {uptime:.3}"
        );

        let _ = writeln!(
            out,
            "# HELP pizzora_worker_loop_cycles_total Total successful worker loop cycles\n\
             # TYPE pizzora_worker_loop_cycles_total counter"
        );
        for (&loop_name, counters) in &self.loops {
            let val = counters.cycles_total.load(Ordering::Relaxed);
            let _ = writeln!(
                out,
                "pizzora_worker_loop_cycles_total{{loop=\"{loop_name}\"}} {val}"
            );
        }

        let _ = writeln!(
            out,
            "# HELP pizzora_worker_loop_errors_total Total worker loop errors\n\
             # TYPE pizzora_worker_loop_errors_total counter"
        );
        for (&loop_name, counters) in &self.loops {
            let val = counters.errors_total.load(Ordering::Relaxed);
            let _ = writeln!(
                out,
                "pizzora_worker_loop_errors_total{{loop=\"{loop_name}\"}} {val}"
            );
        }

        let _ = writeln!(
            out,
            "# HELP pizzora_worker_last_heartbeat_epoch_seconds Unix timestamp of last loop heartbeat\n\
             # TYPE pizzora_worker_last_heartbeat_epoch_seconds gauge"
        );
        for (&loop_name, counters) in &self.loops {
            let val = counters.last_beat_epoch_secs.load(Ordering::Relaxed);
            let _ = writeln!(
                out,
                "pizzora_worker_last_heartbeat_epoch_seconds{{loop=\"{loop_name}\"}} {val}"
            );
        }

        let _ = writeln!(
            out,
            "# HELP pizzora_worker_dispatched_total Total outbox jobs handled by worker lane\n\
             # TYPE pizzora_worker_dispatched_total counter\n\
             pizzora_worker_dispatched_total{{lane=\"payment\"}} {}\n\
             pizzora_worker_dispatched_total{{lane=\"refund\"}} {}\n\
             pizzora_worker_dispatched_total{{lane=\"email\"}} {}\n\
             pizzora_worker_dispatched_total{{lane=\"broadcast\"}} {}",
            self.dispatched_payment.load(Ordering::Relaxed),
            self.dispatched_refund.load(Ordering::Relaxed),
            self.dispatched_email.load(Ordering::Relaxed),
            self.dispatched_broadcast.load(Ordering::Relaxed),
        );

        let _ = writeln!(
            out,
            "# HELP pizzora_worker_dead_letter_marked_total Total events moved to DEAD_LETTER by worker\n\
             # TYPE pizzora_worker_dead_letter_marked_total counter\n\
             pizzora_worker_dead_letter_marked_total{{lane=\"email\"}} {}\n\
             pizzora_worker_dead_letter_marked_total{{lane=\"broadcast\"}} {}",
            self.dead_letter_email.load(Ordering::Relaxed),
            self.dead_letter_broadcast.load(Ordering::Relaxed),
        );

        let _ = writeln!(
            out,
            "# HELP pizzora_worker_orders_expired_total Orders expired by ExpirySweepWorker\n\
             # TYPE pizzora_worker_orders_expired_total counter\n\
             pizzora_worker_orders_expired_total {}\n\
             # HELP pizzora_worker_email_envelopes_scrubbed_total Expired email sealed envelopes scrubbed\n\
             # TYPE pizzora_worker_email_envelopes_scrubbed_total counter\n\
             pizzora_worker_email_envelopes_scrubbed_total {}\n\
             # HELP pizzora_worker_email_rows_purged_total Historical email outbox rows purged\n\
             # TYPE pizzora_worker_email_rows_purged_total counter\n\
             pizzora_worker_email_rows_purged_total {}\n\
             # HELP pizzora_worker_reconciler_actions_total Razorpay reconciliation updates applied\n\
             # TYPE pizzora_worker_reconciler_actions_total counter\n\
             pizzora_worker_reconciler_actions_total {}",
            self.orders_expired.load(Ordering::Relaxed),
            self.email_envelopes_scrubbed.load(Ordering::Relaxed),
            self.email_rows_purged.load(Ordering::Relaxed),
            self.reconciler_actions.load(Ordering::Relaxed),
        );

        if let Some(pg_pool) = pool {
            let pool_size = pg_pool.size();
            let pool_idle = pg_pool.num_idle();
            let _ = writeln!(
                out,
                "# HELP pizzora_db_pool_connections Database pool connections\n\
                 # TYPE pizzora_db_pool_connections gauge\n\
                 pizzora_db_pool_connections{{state=\"total\"}} {pool_size}\n\
                 pizzora_db_pool_connections{{state=\"idle\"}} {pool_idle}"
            );

            let snapshot = tokio::time::timeout(
                Duration::from_secs(2),
                sqlx::query(
                    r#"
                    SELECT
                      (SELECT COUNT(*) FROM outbox_events WHERE dispatch_status = 'PENDING')::bigint AS outbox_pending,
                      (SELECT COUNT(*) FROM outbox_events WHERE stream_seq IS NOT NULL AND dispatch_status IN ('PENDING', 'PROCESSING'))::bigint AS pending_dispatch,
                      (SELECT COUNT(*) FROM outbox_events WHERE dispatch_status = 'DEAD_LETTER')::bigint AS outbox_dead_letter,
                      (SELECT COUNT(*) FROM outbox_events WHERE dispatch_status IN ('PENDING', 'PROCESSING') AND stream_seq IS NULL)::bigint AS outbox_unsequenced,
                      COALESCE((SELECT last_seq FROM event_stream_cursor WHERE id = 1)::bigint, 0) AS high_water_seq,
                      (SELECT COUNT(*) FROM refunds WHERE status IN ('PENDING', 'PROCESSING'))::bigint AS refunds_pending,
                      (SELECT COUNT(*) FROM refunds WHERE status = 'FAILED')::bigint AS refunds_failed
                    "#,
                )
                .fetch_optional(pg_pool),
            )
            .await;

            if let Ok(Ok(Some(row))) = snapshot {
                let pending: i64 = row.try_get("outbox_pending").unwrap_or(0);
                let pending_dispatch: i64 = row.try_get("pending_dispatch").unwrap_or(0);
                let dead: i64 = row.try_get("outbox_dead_letter").unwrap_or(0);
                let unseq: i64 = row.try_get("outbox_unsequenced").unwrap_or(0);
                let high_water: i64 = row.try_get("high_water_seq").unwrap_or(0);
                let refunds_pending: i64 = row.try_get("refunds_pending").unwrap_or(0);
                let refunds_failed: i64 = row.try_get("refunds_failed").unwrap_or(0);

                let _ = writeln!(
                    out,
                    "# HELP pizzora_outbox_pending_total Pending outbox events\n\
                     # TYPE pizzora_outbox_pending_total gauge\n\
                     pizzora_outbox_pending_total {pending}\n\
                     # HELP pizzora_outbox_pending_dispatch_total Sequenced outbox rows awaiting dispatch\n\
                     # TYPE pizzora_outbox_pending_dispatch_total gauge\n\
                     pizzora_outbox_pending_dispatch_total {pending_dispatch}\n\
                     # HELP pizzora_outbox_dead_letter_total Dead-lettered outbox events\n\
                     # TYPE pizzora_outbox_dead_letter_total gauge\n\
                     pizzora_outbox_dead_letter_total {dead}\n\
                     # HELP pizzora_outbox_unsequenced_total Unsequenced outbox events awaiting sequencer\n\
                     # TYPE pizzora_outbox_unsequenced_total gauge\n\
                     pizzora_outbox_unsequenced_total {unseq}\n\
                     # HELP pizzora_realtime_high_water_seq Monotonic global event stream high-water sequence\n\
                     # TYPE pizzora_realtime_high_water_seq gauge\n\
                     pizzora_realtime_high_water_seq {high_water}\n\
                     # HELP pizzora_stream_last_seq Latest committed global event stream sequence\n\
                     # TYPE pizzora_stream_last_seq gauge\n\
                     pizzora_stream_last_seq {high_water}\n\
                     # HELP pizzora_refunds_pending_total Pending or processing refunds\n\
                     # TYPE pizzora_refunds_pending_total gauge\n\
                     pizzora_refunds_pending_total {refunds_pending}\n\
                     # HELP pizzora_refunds_failed_total Failed refund rows needing reconciliation\n\
                     # TYPE pizzora_refunds_failed_total gauge\n\
                     pizzora_refunds_failed_total {refunds_failed}"
                );
            }
        }

        out
    }
}

fn current_epoch_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
