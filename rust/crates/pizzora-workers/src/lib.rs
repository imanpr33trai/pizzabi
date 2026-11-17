//! Standalone worker runtime (:8081) for sequencing, outbox dispatch,
//! expiry sweep, and Razorpay reconciliation (Phase 8).

pub mod broadcast;
pub mod dispatcher;
pub mod email_dispatch;
pub mod error;
pub mod expiry_sweep;
pub mod http;
pub mod metrics;
pub mod reconciler;
pub mod runtime;
pub mod sequencer;

pub use broadcast::{
    build_inbox_notifications, is_internal_worker_command, map_broadcast_event,
    resolve_target_rooms, to_wire_event_type, BroadcastDispatchOutcome, BroadcastOutboxDispatcher,
    MappedBroadcastEvent, PendingInboxNotification, RealtimeEventDto, RedisRealtimePubSubMessage,
    PUBSUB_CHANNEL, ROOM_KDS_KITCHEN, ROOM_OWNER_DASHBOARD,
};
pub use dispatcher::{DispatchBatchSummary, OutboxDispatcherWorker};
pub use email_dispatch::{
    compute_exponential_backoff_seconds, EmailDispatchOutcome, EmailOutboxDispatcher,
};
pub use error::WorkerError;
pub use expiry_sweep::{ExpirySweepSummary, ExpirySweepWorker};
pub use http::{
    build_worker_router, BoxFuture, PostgresProbe, WorkerDependencyHealth, WorkerHealthResponse,
    WorkerHttpState, WorkerReadinessState,
};
pub use metrics::{
    WorkerMetrics, ALL_WORKER_LOOPS, LOOP_DISPATCHER, LOOP_EXPIRY_SWEEP, LOOP_RECONCILER,
    LOOP_SEQUENCER,
};
pub use reconciler::RazorpayReconciliationWorker;
pub use runtime::{WorkerCycleReport, WorkerRuntime};
pub use sequencer::{EventSequencerWorker, EVENT_SEQUENCER_LOCK_KEY};

pub fn crate_name() -> &'static str {
    let _ = pizzora_auth::crate_name();
    let _ = pizzora_payments::crate_name();
    env!("CARGO_PKG_NAME")
}

#[cfg(test)]
mod unit_tests {
    use std::sync::Arc;

    use axum::body::to_bytes;
    use axum::http::{Request, StatusCode};
    use chrono::Utc;
    use pizzora_db::{
        new_uuid_v4, ClaimedOutboxEvent, DependencyHealthStatus, EVENT_EMAIL_DELIVERY_REQUESTED,
        EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED, EVENT_REFUND_REQUESTED,
    };
    use tower::ServiceExt as _;

    use super::*;

    struct FixedPgProbe(DependencyHealthStatus);

    impl PostgresProbe for FixedPgProbe {
        fn check<'a>(&'a self) -> BoxFuture<'a, DependencyHealthStatus> {
            Box::pin(async move { self.0 })
        }
    }

    #[test]
    fn exponential_backoff_is_bounded_and_monotonic() {
        assert_eq!(compute_exponential_backoff_seconds(0), 2);
        assert_eq!(compute_exponential_backoff_seconds(1), 2);
        assert_eq!(compute_exponential_backoff_seconds(2), 4);
        assert_eq!(compute_exponential_backoff_seconds(3), 8);
        assert_eq!(compute_exponential_backoff_seconds(5), 32);
        assert_eq!(compute_exponential_backoff_seconds(8), 256);
        assert_eq!(compute_exponential_backoff_seconds(9), 300);
        assert_eq!(compute_exponential_backoff_seconds(25), 300);
    }

    #[test]
    fn internal_worker_commands_are_filtered_from_realtime_broadcasts() {
        for raw in [
            EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED,
            EVENT_REFUND_REQUESTED,
            EVENT_EMAIL_DELIVERY_REQUESTED,
        ] {
            assert!(is_internal_worker_command(raw));
            let event = ClaimedOutboxEvent {
                id: new_uuid_v4(),
                aggregate_type: "ORDER".to_owned(),
                aggregate_id: new_uuid_v4(),
                aggregate_version: 1,
                event_type: raw.to_owned(),
                payload_json: serde_json::json!({}),
                stream_seq: Some(42),
                sequenced_at: Some(Utc::now()),
                created_at: Utc::now(),
                attempts: 1,
            };
            assert!(map_broadcast_event(&event, 42, None).is_none());
        }
    }

    #[test]
    fn maps_order_placed_and_expired_events_to_expected_rooms_and_inbox() {
        let customer_id = new_uuid_v4();
        let order_id = new_uuid_v4();

        let placed_event = ClaimedOutboxEvent {
            id: new_uuid_v4(),
            aggregate_type: "ORDER".to_owned(),
            aggregate_id: order_id,
            aggregate_version: 1,
            event_type: "ORDER_PLACED".to_owned(),
            payload_json: serde_json::json!({
                "order_number": "PZ-1001"
            }),
            stream_seq: Some(11),
            sequenced_at: Some(Utc::now()),
            created_at: Utc::now(),
            attempts: 1,
        };
        let mapped = map_broadcast_event(&placed_event, 11, Some(customer_id))
            .expect("ORDER_PLACED should map");
        assert_eq!(mapped.pubsub_message.wire_event.event_type, "order.created");
        assert_eq!(mapped.pubsub_message.wire_event.stream_seq, 11);
        assert!(mapped
            .pubsub_message
            .rooms
            .contains(&format!("user:{customer_id}")));
        assert!(mapped.pubsub_message.rooms.contains(ROOM_KDS_KITCHEN));
        assert!(mapped.pubsub_message.rooms.contains(ROOM_OWNER_DASHBOARD));
        assert_eq!(mapped.inbox_notifications.len(), 1);
        assert_eq!(mapped.inbox_notifications[0].title, "Order Placed");

        let expired_event = ClaimedOutboxEvent {
            id: new_uuid_v4(),
            aggregate_type: "ORDER".to_owned(),
            aggregate_id: order_id,
            aggregate_version: 2,
            event_type: "ORDER_EXPIRED".to_owned(),
            payload_json: serde_json::json!({
                "order_number": "PZ-1001",
                "customer_id": customer_id.to_string(),
                "from_status": "PENDING_PAYMENT",
                "to_status": "EXPIRED"
            }),
            stream_seq: Some(12),
            sequenced_at: Some(Utc::now()),
            created_at: Utc::now(),
            attempts: 1,
        };
        let mapped_exp =
            map_broadcast_event(&expired_event, 12, None).expect("ORDER_EXPIRED should map");
        assert_eq!(
            mapped_exp.pubsub_message.wire_event.event_type,
            "order.status_changed"
        );
        assert!(mapped_exp
            .pubsub_message
            .rooms
            .contains(&format!("user:{customer_id}")));
        assert!(mapped_exp
            .pubsub_message
            .rooms
            .contains(ROOM_OWNER_DASHBOARD));
        assert!(
            !mapped_exp.pubsub_message.rooms.contains(ROOM_KDS_KITCHEN),
            "Expired unpaid orders must not clutter KDS room"
        );
        assert_eq!(mapped_exp.inbox_notifications.len(), 1);
        assert_eq!(mapped_exp.inbox_notifications[0].title, "Order Expired");
    }

    #[tokio::test]
    async fn worker_health_and_metrics_endpoints_reflect_loop_and_pg_state() {
        let metrics = Arc::new(WorkerMetrics::new());
        metrics.record_loop_success(LOOP_SEQUENCER);
        metrics.inc_email_dispatched(2);

        let healthy_app = build_worker_router(WorkerHttpState::for_tests(
            Some(Arc::new(FixedPgProbe(DependencyHealthStatus::Up))),
            metrics.clone(),
        ));

        let resp = healthy_app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/health/worker")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let body_bytes = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        let health: WorkerHealthResponse = serde_json::from_slice(&body_bytes).unwrap();
        assert_eq!(health.status, WorkerReadinessState::Healthy);
        assert_eq!(health.service, "pizzora-worker");
        assert_eq!(
            health.dependencies.get("postgres"),
            Some(&WorkerDependencyHealth::Up)
        );
        assert_eq!(
            health.dependencies.get(LOOP_SEQUENCER),
            Some(&WorkerDependencyHealth::Up)
        );

        let metrics_resp = healthy_app
            .oneshot(
                Request::builder()
                    .uri("/metrics")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(metrics_resp.status(), StatusCode::OK);
        let content_type = metrics_resp
            .headers()
            .get(axum::http::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default()
            .to_owned();
        assert!(content_type.contains("text/plain"));
        let metrics_body = String::from_utf8(
            to_bytes(metrics_resp.into_body(), usize::MAX)
                .await
                .unwrap()
                .to_vec(),
        )
        .unwrap();
        assert!(metrics_body.contains("pizzora_worker_uptime_seconds"));
        assert!(metrics_body.contains("pizzora_worker_dispatched_total{lane=\"email\"} 2"));

        // Mark a loop unhealthy -> expect 503 UNHEALTHY
        metrics.record_loop_error(LOOP_DISPATCHER, true);
        let unhealthy_app = build_worker_router(WorkerHttpState::for_tests(
            Some(Arc::new(FixedPgProbe(DependencyHealthStatus::Up))),
            metrics.clone(),
        ));
        let unhealthy_resp = unhealthy_app
            .oneshot(
                Request::builder()
                    .uri("/health/worker")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(unhealthy_resp.status(), StatusCode::SERVICE_UNAVAILABLE);
        let unhealthy_body: WorkerHealthResponse = serde_json::from_slice(
            &to_bytes(unhealthy_resp.into_body(), usize::MAX)
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(unhealthy_body.status, WorkerReadinessState::Unhealthy);
        assert_eq!(
            unhealthy_body.dependencies.get(LOOP_DISPATCHER),
            Some(&WorkerDependencyHealth::Down)
        );

        // Recover loop health, simulate PG outage -> expect 503 UNHEALTHY
        metrics.record_loop_success(LOOP_DISPATCHER);
        let pg_down_app = build_worker_router(WorkerHttpState::for_tests(
            Some(Arc::new(FixedPgProbe(DependencyHealthStatus::Down))),
            metrics,
        ));
        let pg_down_resp = pg_down_app
            .oneshot(
                Request::builder()
                    .uri("/health/worker")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(pg_down_resp.status(), StatusCode::SERVICE_UNAVAILABLE);
    }
}
