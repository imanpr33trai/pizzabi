use std::collections::{BTreeSet, HashMap};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use chrono::{DateTime, Utc};
use pizzora_auth::PizzoraPrincipal;
use pizzora_config::RedisConfig;
use pizzora_core::UserRole;
use pizzora_db::new_uuid_v4;
use serde::{Deserialize, Serialize};
use sqlx::{PgConnection, PgPool, Row as _};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
use tracing::{debug, warn};
use uuid::Uuid;

use crate::dto::{EventsReplayResponse, RealtimeEventDto, WsEventsFrame};
use crate::error::{format_iso8601, now_iso8601, ApiError};

pub const PUBSUB_CHANNEL: &str = "pizzora:realtime:events";
pub const ROOM_KDS_KITCHEN: &str = "kds:kitchen";
pub const ROOM_OWNER_DASHBOARD: &str = "owner:dashboard";

pub fn user_room(user_id: impl std::fmt::Display) -> String {
    format!("user:{user_id}")
}

pub fn is_internal_worker_command(raw_event_type: &str) -> bool {
    matches!(
        raw_event_type,
        "PAYMENT_GATEWAY_ORDER_REQUESTED" | "REFUND_REQUESTED" | "EMAIL_DELIVERY_REQUESTED"
    )
}

pub fn to_wire_event_type(raw_event_type: &str) -> String {
    match raw_event_type {
        "PAYMENT_GATEWAY_READY" => "payment.gateway_ready".to_owned(),
        "ORDER_PLACED" => "order.created".to_owned(),
        "ORDER_STATUS_CHANGED" | "ORDER_CANCELLED" | "ORDER_EXPIRED" => {
            "order.status_changed".to_owned()
        }
        "ORDER_PRIORITY_CHANGED" => "order.priority_changed".to_owned(),
        "KITCHEN_ACKNOWLEDGED" => "kitchen.acknowledged".to_owned(),
        "INVENTORY_LOW_STOCK" => "inventory.low_stock".to_owned(),
        "OWNER_DASHBOARD_UPDATED" => "owner.dashboard_updated".to_owned(),
        "NOTIFICATION_CREATED" => "notification.created".to_owned(),
        other => other.to_ascii_lowercase(),
    }
}

pub fn authorized_rooms(principal: &PizzoraPrincipal) -> BTreeSet<String> {
    let mut rooms = BTreeSet::new();
    rooms.insert(user_room(principal.user_id));
    match principal.role {
        UserRole::Customer => {}
        UserRole::KitchenStaff => {
            rooms.insert(ROOM_KDS_KITCHEN.to_owned());
        }
        UserRole::Owner => {
            rooms.insert(ROOM_KDS_KITCHEN.to_owned());
            rooms.insert(ROOM_OWNER_DASHBOARD.to_owned());
        }
    }
    rooms
}

pub fn resolve_target_rooms(
    wire_type: &str,
    customer_id: Option<Uuid>,
    from_status: Option<&str>,
    payload: &serde_json::Map<String, serde_json::Value>,
) -> BTreeSet<String> {
    let mut rooms = BTreeSet::new();
    if let Some(cid) = customer_id {
        rooms.insert(user_room(cid));
    }
    if let Some(target_user_id) = payload.get("user_id").and_then(|v| v.as_str()) {
        if !target_user_id.trim().is_empty() {
            rooms.insert(user_room(target_user_id.trim()));
        }
    }

    match wire_type {
        "payment.gateway_ready" => {
            rooms.insert(ROOM_OWNER_DASHBOARD.to_owned());
        }
        "order.created" | "kitchen.acknowledged" | "order.priority_changed" => {
            rooms.insert(ROOM_KDS_KITCHEN.to_owned());
            rooms.insert(ROOM_OWNER_DASHBOARD.to_owned());
        }
        "order.status_changed" => {
            rooms.insert(ROOM_OWNER_DASHBOARD.to_owned());
            let to_status = payload.get("to_status").and_then(|v| v.as_str());
            if (to_status != Some("EXPIRED") && from_status != Some("PENDING_PAYMENT"))
                || to_status == Some("PLACED")
            {
                rooms.insert(ROOM_KDS_KITCHEN.to_owned());
            }
        }
        "inventory.low_stock" | "owner.dashboard_updated" => {
            rooms.insert(ROOM_OWNER_DASHBOARD.to_owned());
        }
        _ => {}
    }

    rooms
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RedisRealtimePubSubMessage {
    #[serde(alias = "wireEvent")]
    pub wire_event: RealtimeEventDto,
    pub rooms: BTreeSet<String>,
}

struct RegisteredSession {
    user_id: Uuid,
    authorized_rooms: BTreeSet<String>,
    outbound: mpsc::Sender<WsEventsFrame>,
    last_sent_seq: Arc<AtomicI64>,
}

pub struct RegisteredSessionHandle {
    pub session_id: Uuid,
    pub outbound_rx: mpsc::Receiver<WsEventsFrame>,
    pub last_sent_seq: Arc<AtomicI64>,
}

pub struct RealtimeHub {
    sessions: Mutex<HashMap<Uuid, RegisteredSession>>,
}

impl Default for RealtimeHub {
    fn default() -> Self {
        Self::new()
    }
}

impl RealtimeHub {
    pub fn new() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
        }
    }

    pub fn register_session(
        &self,
        principal: &PizzoraPrincipal,
        initial_sent_seq: i64,
    ) -> RegisteredSessionHandle {
        let session_id = new_uuid_v4();
        let (outbound_tx, outbound_rx) = mpsc::channel(128);
        let last_sent_seq = Arc::new(AtomicI64::new(initial_sent_seq));

        let session = RegisteredSession {
            user_id: principal.user_id,
            authorized_rooms: authorized_rooms(principal),
            outbound: outbound_tx,
            last_sent_seq: last_sent_seq.clone(),
        };

        if let Ok(mut guard) = self.sessions.lock() {
            guard.insert(session_id, session);
        }

        RegisteredSessionHandle {
            session_id,
            outbound_rx,
            last_sent_seq,
        }
    }

    pub fn unregister_session(&self, session_id: Uuid) {
        if let Ok(mut guard) = self.sessions.lock() {
            guard.remove(&session_id);
        }
    }

    pub fn connected_session_count(&self) -> usize {
        self.sessions.lock().map(|g| g.len()).unwrap_or(0)
    }

    pub fn ws_session_counts(&self) -> (usize, usize) {
        let Ok(guard) = self.sessions.lock() else {
            return (0, 0);
        };
        let total = guard.len();
        let unique_users = guard
            .values()
            .map(|s| s.user_id)
            .collect::<std::collections::HashSet<Uuid>>()
            .len();
        (total, unique_users)
    }

    pub fn fan_out_local(&self, wire_event: RealtimeEventDto, rooms: &BTreeSet<String>) -> bool {
        let Ok(guard) = self.sessions.lock() else {
            return false;
        };
        let mut delivered = false;
        let now_str = now_iso8601();

        for session in guard.values() {
            if session
                .authorized_rooms
                .intersection(rooms)
                .next()
                .is_none()
            {
                continue;
            }
            let prev = session.last_sent_seq.load(Ordering::Acquire);
            if wire_event.stream_seq <= prev {
                continue;
            }

            let frame = WsEventsFrame {
                frame_type: "EVENTS".to_owned(),
                events: vec![wire_event.clone()],
                server_high_water_seq: wire_event.stream_seq,
                server_now: now_str.clone(),
                resumed_from_seq: None,
            };

            if session.outbound.try_send(frame).is_ok() {
                session
                    .last_sent_seq
                    .fetch_max(wire_event.stream_seq, Ordering::AcqRel);
                delivered = true;
            }
        }

        delivered
    }

    pub fn dispatch_serialized(&self, raw_payload: &str) -> bool {
        if let Ok(envelope) = serde_json::from_str::<RedisRealtimePubSubMessage>(raw_payload) {
            return self.fan_out_local(envelope.wire_event, &envelope.rooms);
        }

        if let Ok(mut event) = serde_json::from_str::<RealtimeEventDto>(raw_payload) {
            if is_internal_worker_command(&event.event_type) {
                return false;
            }
            if !event.event_type.contains('.') {
                event.event_type = to_wire_event_type(&event.event_type);
            }
            let customer_id = event
                .payload
                .get("customer_id")
                .and_then(|v| v.as_str())
                .and_then(|s| Uuid::parse_str(s.trim()).ok());
            let from_status = event.payload.get("from_status").and_then(|v| v.as_str());
            let rooms =
                resolve_target_rooms(&event.event_type, customer_id, from_status, &event.payload);
            return self.fan_out_local(event, &rooms);
        }

        false
    }
}

#[derive(Clone)]
pub struct RealtimeEventService {
    pool: PgPool,
}

impl RealtimeEventService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn current_high_water_seq(&self) -> Result<i64, ApiError> {
        let seq: Option<i64> =
            sqlx::query_scalar("SELECT last_seq FROM event_stream_cursor WHERE id = 1")
                .fetch_optional(&self.pool)
                .await?;
        Ok(seq.unwrap_or(0))
    }

    pub async fn replay_events(
        &self,
        principal: &PizzoraPrincipal,
        since_seq: i64,
        limit: i64,
    ) -> Result<EventsReplayResponse, ApiError> {
        let safe_since = since_seq.max(0);
        let clamped_limit = limit.clamp(1, 200) as usize;
        let caller_rooms = authorized_rooms(principal);

        let mut tx = self.pool.begin().await?;
        let global_high_water: i64 =
            sqlx::query_scalar("SELECT last_seq FROM event_stream_cursor WHERE id = 1")
                .fetch_optional(&mut *tx)
                .await?
                .unwrap_or(0);

        if safe_since >= global_high_water {
            tx.commit().await?;
            return Ok(EventsReplayResponse {
                events: Vec::new(),
                server_high_water_seq: global_high_water,
                has_more: false,
                server_now: now_iso8601(),
            });
        }

        let mut matched: Vec<RealtimeEventDto> = Vec::new();
        let mut customer_cache: HashMap<Uuid, Option<Uuid>> = HashMap::new();
        let mut cursor_seq = safe_since;
        let mut scanned_rows = 0usize;
        let max_scan_rows = 1_000usize;
        let chunk_limit = 100i64;

        while cursor_seq < global_high_water
            && matched.len() < clamped_limit
            && scanned_rows < max_scan_rows
        {
            let rows = sqlx::query(
                r#"
                SELECT id, aggregate_type, aggregate_id, aggregate_version, event_type,
                       payload_json, stream_seq, sequenced_at, created_at
                FROM outbox_events
                WHERE stream_seq IS NOT NULL
                  AND stream_seq > $1
                  AND stream_seq <= $2
                ORDER BY stream_seq ASC
                LIMIT $3
                "#,
            )
            .bind(cursor_seq)
            .bind(global_high_water)
            .bind(chunk_limit)
            .fetch_all(&mut *tx)
            .await?;

            if rows.is_empty() {
                cursor_seq = global_high_water;
                break;
            }

            for row in rows {
                let id: Uuid = row.try_get("id")?;
                let aggregate_type: String = row.try_get("aggregate_type")?;
                let aggregate_id: Uuid = row.try_get("aggregate_id")?;
                let aggregate_version: i64 = row.try_get("aggregate_version")?;
                let raw_event_type: String = row.try_get("event_type")?;
                let payload_val: serde_json::Value = row.try_get("payload_json")?;
                let stream_seq: i64 = row.try_get("stream_seq")?;
                let sequenced_at: Option<DateTime<Utc>> = row.try_get("sequenced_at")?;
                let created_at: DateTime<Utc> = row.try_get("created_at")?;

                cursor_seq = stream_seq;
                scanned_rows += 1;

                if is_internal_worker_command(&raw_event_type) {
                    continue;
                }

                let mut payload_map = match payload_val {
                    serde_json::Value::Object(map) => map,
                    _ => serde_json::Map::new(),
                };

                let mut customer_id = payload_map
                    .get("customer_id")
                    .and_then(|v| v.as_str())
                    .and_then(|s| Uuid::parse_str(s.trim()).ok());
                if customer_id.is_none() && aggregate_type == "ORDER" {
                    if let Some(cached) = customer_cache.get(&aggregate_id) {
                        customer_id = *cached;
                    } else {
                        let looked_up = lookup_order_customer_id(&mut tx, aggregate_id).await?;
                        customer_cache.insert(aggregate_id, looked_up);
                        customer_id = looked_up;
                    }
                }
                if let Some(cid) = customer_id {
                    payload_map
                        .entry("customer_id".to_owned())
                        .or_insert_with(|| serde_json::Value::String(cid.to_string()));
                }

                let wire_type = to_wire_event_type(&raw_event_type);
                let from_status = payload_map
                    .get("from_status")
                    .and_then(|v| v.as_str())
                    .map(str::to_owned);
                let rooms = resolve_target_rooms(
                    &wire_type,
                    customer_id,
                    from_status.as_deref(),
                    &payload_map,
                );

                if caller_rooms.intersection(&rooms).next().is_some() {
                    matched.push(RealtimeEventDto {
                        event_id: id.to_string(),
                        stream_seq,
                        event_type: wire_type,
                        aggregate_type,
                        aggregate_id: aggregate_id.to_string(),
                        aggregate_version,
                        payload: payload_map,
                        sequenced_at: sequenced_at.map(format_iso8601),
                        created_at: format_iso8601(created_at),
                    });
                    if matched.len() >= clamped_limit {
                        break;
                    }
                }
            }
        }

        tx.commit().await?;

        let has_more = cursor_seq < global_high_water;
        let high_water_out = if has_more {
            cursor_seq
        } else {
            global_high_water
        };

        Ok(EventsReplayResponse {
            events: matched,
            server_high_water_seq: high_water_out,
            has_more,
            server_now: now_iso8601(),
        })
    }
}

async fn lookup_order_customer_id(
    conn: &mut PgConnection,
    order_id: Uuid,
) -> Result<Option<Uuid>, ApiError> {
    let cid: Option<Uuid> = sqlx::query_scalar("SELECT customer_id FROM orders WHERE id = $1")
        .bind(order_id)
        .fetch_optional(&mut *conn)
        .await?;
    Ok(cid)
}

pub fn spawn_redis_pubsub_subscriber(
    redis_config: RedisConfig,
    hub: Arc<RealtimeHub>,
    shutdown: CancellationToken,
) {
    let url = redis_config.url().trim().to_owned();
    if url.is_empty() {
        return;
    }

    tokio::task::spawn_blocking(move || {
        while !shutdown.is_cancelled() {
            let client = match redis::Client::open(url.as_str()) {
                Ok(c) => c,
                Err(err) => {
                    warn!(error = %err, "Failed creating Redis PubSub client");
                    std::thread::sleep(Duration::from_secs(2));
                    continue;
                }
            };

            let mut conn = match client.get_connection() {
                Ok(c) => c,
                Err(err) => {
                    debug!(error = %err, "Redis PubSub connection unavailable; retrying");
                    std::thread::sleep(Duration::from_secs(2));
                    continue;
                }
            };

            let _ = conn.set_read_timeout(Some(Duration::from_secs(1)));
            let mut pubsub = conn.as_pubsub();
            if let Err(err) = pubsub.subscribe(PUBSUB_CHANNEL) {
                warn!(error = %err, "Failed subscribing to Redis PubSub channel");
                std::thread::sleep(Duration::from_secs(2));
                continue;
            }

            while !shutdown.is_cancelled() {
                match pubsub.get_message() {
                    Ok(msg) => {
                        if let Ok(payload_str) = msg.get_payload::<String>() {
                            hub.dispatch_serialized(&payload_str);
                        }
                    }
                    Err(err) if err.is_timeout() => continue,
                    Err(err) => {
                        debug!(error = %err, "Redis PubSub stream interrupted; reconnecting");
                        break;
                    }
                }
            }
        }
    });
}
