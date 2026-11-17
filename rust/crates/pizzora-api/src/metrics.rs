use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Instant;

use sqlx::PgPool;

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct MetricKey {
    method: String,
    route: String,
    status: u16,
}

#[derive(Debug)]
pub struct ApiMetrics {
    started_at: Instant,
    in_flight: AtomicI64,
    total_requests: AtomicU64,
    total_5xx: AtomicU64,
    route_counts: Mutex<BTreeMap<MetricKey, u64>>,
    route_duration_secs: Mutex<BTreeMap<(String, String), f64>>,
}

impl Default for ApiMetrics {
    fn default() -> Self {
        Self::new()
    }
}

impl ApiMetrics {
    pub fn new() -> Self {
        Self {
            started_at: Instant::now(),
            in_flight: AtomicI64::new(0),
            total_requests: AtomicU64::new(0),
            total_5xx: AtomicU64::new(0),
            route_counts: Mutex::new(BTreeMap::new()),
            route_duration_secs: Mutex::new(BTreeMap::new()),
        }
    }

    pub fn inc_in_flight(&self) {
        self.in_flight.fetch_add(1, Ordering::Relaxed);
    }

    pub fn dec_in_flight(&self) {
        self.in_flight.fetch_sub(1, Ordering::Relaxed);
    }

    pub fn record_request(&self, method: &str, route: &str, status: u16, duration_secs: f64) {
        self.total_requests.fetch_add(1, Ordering::Relaxed);
        if status >= 500 {
            self.total_5xx.fetch_add(1, Ordering::Relaxed);
        }

        if let Ok(mut map) = self.route_counts.lock() {
            let key = MetricKey {
                method: method.to_owned(),
                route: route.to_owned(),
                status,
            };
            *map.entry(key).or_insert(0) += 1;
        }
        if let Ok(mut dur) = self.route_duration_secs.lock() {
            let key = (method.to_owned(), route.to_owned());
            *dur.entry(key).or_insert(0.0) += duration_secs;
        }
    }

    pub async fn render_prometheus(
        &self,
        pool: &PgPool,
        ws_active_sessions: usize,
        ws_unique_users: usize,
    ) -> String {
        let mut out = String::with_capacity(2048);

        let uptime_secs = self.started_at.elapsed().as_secs();
        let _ = writeln!(
            out,
            "# HELP pizzora_process_uptime_seconds Process uptime in seconds\n\
             # TYPE pizzora_process_uptime_seconds gauge\n\
             pizzora_process_uptime_seconds {uptime_secs}"
        );

        let in_flight = self.in_flight.load(Ordering::Relaxed);
        let _ = writeln!(
            out,
            "# HELP pizzora_http_requests_in_flight In-flight HTTP requests\n\
             # TYPE pizzora_http_requests_in_flight gauge\n\
             pizzora_http_requests_in_flight {in_flight}"
        );

        let total_req = self.total_requests.load(Ordering::Relaxed);
        let _ = writeln!(
            out,
            "# HELP pizzora_http_requests_total Total HTTP requests handled\n\
             # TYPE pizzora_http_requests_total counter\n\
             pizzora_http_requests_total {total_req}"
        );

        let total_5xx = self.total_5xx.load(Ordering::Relaxed);
        let _ = writeln!(
            out,
            "# HELP pizzora_http_errors_5xx_total Total HTTP 5xx responses\n\
             # TYPE pizzora_http_errors_5xx_total counter\n\
             pizzora_http_errors_5xx_total {total_5xx}"
        );

        let _ = writeln!(
            out,
            "# HELP pizzora_http_route_requests_total HTTP requests by method, route, and status\n\
             # TYPE pizzora_http_route_requests_total counter"
        );
        if let Ok(map) = self.route_counts.lock() {
            for (k, count) in map.iter() {
                let method = escape_label(&k.method);
                let route = escape_label(&k.route);
                let _ = writeln!(
                    out,
                    "pizzora_http_route_requests_total{{method=\"{method}\",route=\"{route}\",status=\"{}\"}} {count}",
                    k.status
                );
            }
        }

        let _ = writeln!(
            out,
            "# HELP pizzora_http_request_duration_seconds_sum Cumulative HTTP request duration in seconds\n\
             # TYPE pizzora_http_request_duration_seconds_sum counter"
        );
        if let Ok(dur) = self.route_duration_secs.lock() {
            for ((method, route), sum_secs) in dur.iter() {
                let m = escape_label(method);
                let r = escape_label(route);
                let _ = writeln!(
                    out,
                    "pizzora_http_request_duration_seconds_sum{{method=\"{m}\",route=\"{r}\"}} {sum_secs:.6}"
                );
            }
        }

        let _ = writeln!(
            out,
            "# HELP pizzora_ws_active_sessions Active WebSocket connections\n\
             # TYPE pizzora_ws_active_sessions gauge\n\
             pizzora_ws_active_sessions {ws_active_sessions}"
        );
        let _ = writeln!(
            out,
            "# HELP pizzora_ws_unique_users Connected unique authenticated WebSocket users\n\
             # TYPE pizzora_ws_unique_users gauge\n\
             pizzora_ws_unique_users {ws_unique_users}"
        );

        let pool_size = pool.size();
        let pool_idle = pool.num_idle();
        let _ = writeln!(
            out,
            "# HELP pizzora_db_pool_connections Database pool connections\n\
             # TYPE pizzora_db_pool_connections gauge\n\
             pizzora_db_pool_connections{{state=\"total\"}} {pool_size}\n\
             pizzora_db_pool_connections{{state=\"idle\"}} {pool_idle}"
        );

        let metrics_row = sqlx::query(
            r#"
            SELECT
              (SELECT COUNT(*) FROM outbox_events WHERE dispatch_status = 'PENDING')::bigint AS outbox_pending,
              (SELECT COUNT(*) FROM outbox_events WHERE dispatch_status = 'DEAD_LETTER')::bigint AS outbox_dead_letter,
              (SELECT COUNT(*) FROM outbox_events WHERE dispatch_status IN ('PENDING', 'PROCESSING') AND stream_seq IS NULL)::bigint AS outbox_unsequenced,
              (SELECT last_seq FROM event_stream_cursor WHERE id = 1)::bigint AS high_water_seq,
              (SELECT COUNT(*) FROM refunds WHERE status IN ('PENDING', 'PROCESSING'))::bigint AS refunds_pending
            "#,
        )
        .fetch_optional(pool)
        .await;

        if let Ok(Some(row)) = metrics_row {
            use sqlx::Row as _;
            let pending: i64 = row.try_get("outbox_pending").unwrap_or(0);
            let dead: i64 = row.try_get("outbox_dead_letter").unwrap_or(0);
            let unseq: i64 = row.try_get("outbox_unsequenced").unwrap_or(0);
            let high_water: i64 = row.try_get("high_water_seq").unwrap_or(0);
            let refunds_pending: i64 = row.try_get("refunds_pending").unwrap_or(0);

            let _ = writeln!(
                out,
                "# HELP pizzora_outbox_pending_total Pending outbox events\n\
                 # TYPE pizzora_outbox_pending_total gauge\n\
                 pizzora_outbox_pending_total {pending}\n\
                 # HELP pizzora_outbox_dead_letter_total Dead-lettered outbox events\n\
                 # TYPE pizzora_outbox_dead_letter_total gauge\n\
                 pizzora_outbox_dead_letter_total {dead}\n\
                 # HELP pizzora_outbox_unsequenced_total Unsequenced outbox events awaiting sequencer\n\
                 # TYPE pizzora_outbox_unsequenced_total gauge\n\
                 pizzora_outbox_unsequenced_total {unseq}\n\
                 # HELP pizzora_realtime_high_water_seq Monotonic global event stream high-water sequence\n\
                 # TYPE pizzora_realtime_high_water_seq gauge\n\
                 pizzora_realtime_high_water_seq {high_water}\n\
                 # HELP pizzora_refunds_pending_total Pending or processing refunds\n\
                 # TYPE pizzora_refunds_pending_total gauge\n\
                 pizzora_refunds_pending_total {refunds_pending}"
            );
        }

        out
    }
}

fn escape_label(raw: &str) -> String {
    raw.replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
}
