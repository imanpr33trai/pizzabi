use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;

use chrono::{DateTime, SecondsFormat, Utc};
use pizzora_db::{
    ClaimedOutboxEvent, EphemeralCacheStore, NotificationDeliveryStatus, NotificationService,
    OutboxRepository, CATEGORY_ORDER_UPDATE, EVENT_EMAIL_DELIVERY_REQUESTED,
    EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED, EVENT_REFUND_REQUESTED,
};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;
use tracing::{debug, warn};
use uuid::Uuid;

use crate::error::WorkerError;

pub const PUBSUB_CHANNEL: &str = "pizzora:realtime:events";
pub const ROOM_KDS_KITCHEN: &str = "kds:kitchen";
pub const ROOM_OWNER_DASHBOARD: &str = "owner:dashboard";

pub fn user_room(user_id: impl std::fmt::Display) -> String {
    format!("user:{user_id}")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BroadcastDispatchOutcome {
    Dispatched { delivered_live: bool },
    SkippedInternal,
    RetryScheduled,
    DeadLettered,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RealtimeEventDto {
    pub event_id: String,
    pub stream_seq: i64,
    pub event_type: String,
    pub aggregate_type: String,
    pub aggregate_id: String,
    pub aggregate_version: i64,
    pub payload: serde_json::Map<String, serde_json::Value>,
    #[serde(default)]
    pub sequenced_at: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RedisRealtimePubSubMessage {
    #[serde(alias = "wireEvent")]
    pub wire_event: RealtimeEventDto,
    pub rooms: BTreeSet<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingInboxNotification {
    pub user_id: Uuid,
    pub event_id: Uuid,
    pub title: String,
    pub body: String,
    pub category: &'static str,
    pub reference_id: Option<Uuid>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MappedBroadcastEvent {
    pub pubsub_message: RedisRealtimePubSubMessage,
    pub inbox_notifications: Vec<PendingInboxNotification>,
}

pub fn is_internal_worker_command(raw_event_type: &str) -> bool {
    matches!(
        raw_event_type,
        EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED
            | EVENT_REFUND_REQUESTED
            | EVENT_EMAIL_DELIVERY_REQUESTED
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
        let trimmed = target_user_id.trim();
        if !trimmed.is_empty() {
            rooms.insert(user_room(trimmed));
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
            if to_status != Some("EXPIRED") && from_status != Some("PENDING_PAYMENT") {
                rooms.insert(ROOM_KDS_KITCHEN.to_owned());
            } else if to_status == Some("PLACED") {
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

pub fn build_inbox_notifications(
    event_id: Uuid,
    aggregate_id: Uuid,
    wire_type: &str,
    customer_id: Option<Uuid>,
    order_number: &str,
    to_status: Option<&str>,
    payload: &serde_json::Map<String, serde_json::Value>,
) -> Vec<PendingInboxNotification> {
    let Some(customer_uuid) = customer_id else {
        return Vec::new();
    };
    let explicit_msg = payload.get("message").and_then(|v| v.as_str());

    match wire_type {
        "order.created" => vec![PendingInboxNotification {
            user_id: customer_uuid,
            event_id,
            title: "Order Placed".to_owned(),
            body: format!("Order {order_number} has been received by our kitchen."),
            category: CATEGORY_ORDER_UPDATE,
            reference_id: Some(aggregate_id),
        }],
        "kitchen.acknowledged" => vec![PendingInboxNotification {
            user_id: customer_uuid,
            event_id,
            title: "Order Update".to_owned(),
            body: explicit_msg
                .map(str::to_owned)
                .unwrap_or_else(|| format!("Order {order_number} accepted by the kitchen.")),
            category: CATEGORY_ORDER_UPDATE,
            reference_id: Some(aggregate_id),
        }],
        "order.status_changed" => {
            let status_label = match to_status {
                Some("ACCEPTED") => format!("Order {order_number} accepted by the kitchen."),
                Some("PREPARING") => {
                    format!("Order {order_number} is now baking in our wood-fired oven.")
                }
                Some("READY") => format!("Order {order_number} is fresh and ready!"),
                Some("OUT_FOR_DELIVERY") => {
                    format!("Order {order_number} is out for delivery.")
                }
                Some("COMPLETED") => {
                    format!("Order {order_number} is completed. Enjoy your pizza!")
                }
                Some("CANCELLED") => format!("Order {order_number} has been cancelled."),
                Some("EXPIRED") => {
                    format!("Payment window expired for order {order_number}.")
                }
                other => format!(
                    "Order {order_number} status updated to {}.",
                    other.unwrap_or("UPDATED")
                ),
            };
            let title = match to_status {
                Some("CANCELLED") => "Order Cancelled",
                Some("EXPIRED") => "Order Expired",
                Some("READY") => "Order Ready",
                Some("OUT_FOR_DELIVERY") => "Out for Delivery",
                Some("COMPLETED") => "Order Completed",
                _ => "Order Update",
            };
            vec![PendingInboxNotification {
                user_id: customer_uuid,
                event_id,
                title: title.to_owned(),
                body: explicit_msg.map(str::to_owned).unwrap_or(status_label),
                category: CATEGORY_ORDER_UPDATE,
                reference_id: Some(aggregate_id),
            }]
        }
        _ => Vec::new(),
    }
}

pub fn map_broadcast_event(
    event: &ClaimedOutboxEvent,
    stream_seq: i64,
    resolved_customer_id: Option<Uuid>,
) -> Option<MappedBroadcastEvent> {
    if is_internal_worker_command(&event.event_type) {
        return None;
    }

    let mut payload_map = match &event.payload_json {
        serde_json::Value::Object(map) => map.clone(),
        _ => serde_json::Map::new(),
    };

    let customer_id = payload_map
        .get("customer_id")
        .and_then(|v| v.as_str())
        .and_then(|s| Uuid::parse_str(s.trim()).ok())
        .or(resolved_customer_id);

    if let Some(cid) = customer_id {
        payload_map
            .entry("customer_id".to_owned())
            .or_insert_with(|| serde_json::Value::String(cid.to_string()));
    }

    let wire_type = to_wire_event_type(&event.event_type);
    let fallback_order_num: String = event.aggregate_id.to_string().chars().take(8).collect();
    let order_number = payload_map
        .get("order_number")
        .and_then(|v| v.as_str())
        .unwrap_or(&fallback_order_num)
        .to_owned();
    let to_status = payload_map
        .get("to_status")
        .and_then(|v| v.as_str())
        .or_else(|| payload_map.get("status").and_then(|v| v.as_str()))
        .map(str::to_owned);
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
    let inbox_notifications = build_inbox_notifications(
        event.id,
        event.aggregate_id,
        &wire_type,
        customer_id,
        &order_number,
        to_status.as_deref(),
        &payload_map,
    );

    let wire_event = RealtimeEventDto {
        event_id: event.id.to_string(),
        stream_seq,
        event_type: wire_type,
        aggregate_type: event.aggregate_type.clone(),
        aggregate_id: event.aggregate_id.to_string(),
        aggregate_version: event.aggregate_version,
        payload: payload_map,
        sequenced_at: event.sequenced_at.map(format_iso8601),
        created_at: format_iso8601(event.created_at),
    };

    Some(MappedBroadcastEvent {
        pubsub_message: RedisRealtimePubSubMessage { wire_event, rooms },
        inbox_notifications,
    })
}

#[derive(Clone)]
pub struct BroadcastOutboxDispatcher {
    pool: PgPool,
    outbox_repo: OutboxRepository,
    cache: Arc<dyn EphemeralCacheStore>,
    max_attempts: i32,
}

impl BroadcastOutboxDispatcher {
    pub fn new(pool: PgPool, cache: Arc<dyn EphemeralCacheStore>, max_attempts: i32) -> Self {
        let outbox_repo = OutboxRepository::new(pool.clone());
        Self {
            pool,
            outbox_repo,
            cache,
            max_attempts: max_attempts.max(1),
        }
    }

    pub async fn dispatch_event(
        &self,
        event: &ClaimedOutboxEvent,
        order_customer_cache: &mut HashMap<Uuid, Option<Uuid>>,
    ) -> Result<BroadcastDispatchOutcome, WorkerError> {
        if is_internal_worker_command(&event.event_type) {
            self.outbox_repo.mark_dispatched(event.id, None).await?;
            return Ok(BroadcastDispatchOutcome::SkippedInternal);
        }

        let Some(stream_seq) = event.stream_seq else {
            self.outbox_repo
                .schedule_retry(event.id, 2, "Broadcast event missing stream_seq")
                .await?;
            return Ok(BroadcastDispatchOutcome::RetryScheduled);
        };

        let resolved_customer_id = self
            .resolve_order_customer_id(event, order_customer_cache)
            .await?;

        let Some(mapped) = map_broadcast_event(event, stream_seq, resolved_customer_id) else {
            self.outbox_repo.mark_dispatched(event.id, None).await?;
            return Ok(BroadcastDispatchOutcome::SkippedInternal);
        };

        let encoded = match serde_json::to_string(&mapped.pubsub_message) {
            Ok(json) => json,
            Err(err) => {
                let msg = format!("Failed serializing realtime pubsub event: {err}");
                if event.attempts < self.max_attempts {
                    self.outbox_repo.schedule_retry(event.id, 2, &msg).await?;
                    return Ok(BroadcastDispatchOutcome::RetryScheduled);
                }
                self.mark_broadcast_dead_letter(event.id, &mapped.inbox_notifications, &msg)
                    .await?;
                return Ok(BroadcastDispatchOutcome::DeadLettered);
            }
        };

        let delivered_live = self.cache.publish(PUBSUB_CHANNEL, &encoded).await;
        let delivery_status = if delivered_live {
            NotificationDeliveryStatus::SentWs
        } else {
            NotificationDeliveryStatus::Pending
        };

        let mut tx = self.pool.begin().await?;
        for notif in &mapped.inbox_notifications {
            let _ = NotificationService::insert_inbox_notification_if_allowed_in_tx(
                &mut tx,
                notif.user_id,
                notif.event_id,
                &notif.title,
                &notif.body,
                notif.category,
                notif.reference_id,
                delivery_status,
            )
            .await?;
        }
        OutboxRepository::mark_dispatched_in_tx(&mut tx, event.id, None).await?;
        tx.commit().await?;

        debug!(
            outbox_event_id = %event.id,
            stream_seq,
            wire_type = %mapped.pubsub_message.wire_event.event_type,
            delivered_live,
            "Dispatched sequenced broadcast outbox event"
        );

        Ok(BroadcastDispatchOutcome::Dispatched { delivered_live })
    }

    async fn resolve_order_customer_id(
        &self,
        event: &ClaimedOutboxEvent,
        order_customer_cache: &mut HashMap<Uuid, Option<Uuid>>,
    ) -> Result<Option<Uuid>, WorkerError> {
        if let Some(cid) = event
            .payload_json
            .get("customer_id")
            .and_then(|v| v.as_str())
            .and_then(|s| Uuid::parse_str(s.trim()).ok())
        {
            return Ok(Some(cid));
        }
        if event.aggregate_type != "ORDER" {
            return Ok(None);
        }
        if let Some(cached) = order_customer_cache.get(&event.aggregate_id) {
            return Ok(*cached);
        }

        let cid: Option<Uuid> = sqlx::query_scalar("SELECT customer_id FROM orders WHERE id = $1")
            .bind(event.aggregate_id)
            .fetch_optional(&self.pool)
            .await?;
        order_customer_cache.insert(event.aggregate_id, cid);
        Ok(cid)
    }

    async fn mark_broadcast_dead_letter(
        &self,
        outbox_event_id: Uuid,
        notifications: &[PendingInboxNotification],
        error_msg: &str,
    ) -> Result<(), WorkerError> {
        let mut tx = self.pool.begin().await?;
        for notif in notifications {
            let _ = NotificationService::insert_inbox_notification_if_allowed_in_tx(
                &mut tx,
                notif.user_id,
                notif.event_id,
                &notif.title,
                &notif.body,
                notif.category,
                notif.reference_id,
                NotificationDeliveryStatus::Failed,
            )
            .await?;
        }
        tx.commit().await?;
        self.outbox_repo
            .mark_dead_letter(outbox_event_id, error_msg, None)
            .await?;
        warn!(
            outbox_event_id = %outbox_event_id,
            "Moved broadcast outbox event to DEAD_LETTER"
        );
        Ok(())
    }
}

pub fn format_iso8601(dt: DateTime<Utc>) -> String {
    dt.to_rfc3339_opts(SecondsFormat::Millis, true)
}
