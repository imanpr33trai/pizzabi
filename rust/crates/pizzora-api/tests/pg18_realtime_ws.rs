mod common;

use std::collections::HashMap;
use std::time::Duration;

use pizzora_api::dto::{
    CartItemInputDto, CheckoutRequest, CheckoutResponse, EventsReplayResponse,
    KdsTicketActionResponse, RealtimeEventDto, WsEventsFrame,
};
use pizzora_auth::WsTicketResponse;
use pizzora_core::{FulfillmentType, PaymentMethod, ScheduleType};
use pizzora_db::{new_uuid_v4, AddressDto, UpsertAddressRequest};
use reqwest::StatusCode;
use serde_json::json;
use sqlx::{PgPool, Row as _};
use uuid::Uuid;

use common::{seed_full_storefront_via_owner_api, setup_pool, TestServer, TestWsClient, WsFrame};

fn unique_pincode(seed: &Uuid) -> String {
    let bytes = seed.as_bytes();
    let tail = 10_000 + ((u16::from(bytes[0]) << 8 | u16::from(bytes[1])) % 89_000);
    format!("5{tail:05}")
}

async fn sequence_unsequenced_outbox(pool: &PgPool) -> i64 {
    let mut tx = pool.begin().await.expect("begin sequence tx");
    let cursor_last: i64 =
        sqlx::query_scalar("SELECT last_seq FROM event_stream_cursor WHERE id = 1 FOR UPDATE")
            .fetch_one(&mut *tx)
            .await
            .expect("lock event_stream_cursor");
    let max_existing: i64 =
        sqlx::query_scalar("SELECT COALESCE(MAX(stream_seq), 0) FROM outbox_events")
            .fetch_one(&mut *tx)
            .await
            .expect("max stream_seq");
    let mut next_seq = cursor_last.max(max_existing);

    let rows = sqlx::query(
        r#"
        SELECT id
        FROM outbox_events
        WHERE stream_seq IS NULL
        ORDER BY created_at ASC, id ASC
        FOR UPDATE
        "#,
    )
    .fetch_all(&mut *tx)
    .await
    .expect("select unsequenced outbox");

    for row in rows {
        next_seq += 1;
        let id: Uuid = row.try_get("id").unwrap();
        sqlx::query("UPDATE outbox_events SET stream_seq = $1, sequenced_at = NOW() WHERE id = $2")
            .bind(next_seq)
            .bind(id)
            .execute(&mut *tx)
            .await
            .expect("assign stream_seq");
    }

    sqlx::query("UPDATE event_stream_cursor SET last_seq = $1 WHERE id = 1")
        .bind(next_seq)
        .execute(&mut *tx)
        .await
        .expect("update event_stream_cursor");

    tx.commit().await.expect("commit sequence tx");
    next_seq
}

async fn next_ws_events_frame(ws: &mut TestWsClient) -> WsEventsFrame {
    tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            let text = ws.next_text_frame().await.expect("ws text frame");
            let frame: WsEventsFrame = serde_json::from_str(&text).expect("parse WsEventsFrame");
            if frame.frame_type == "EVENTS" {
                return frame;
            }
        }
    })
    .await
    .expect("timed out waiting for EVENTS ws frame")
}

async fn assert_only_heartbeats(ws: &mut TestWsClient, window: Duration) {
    let mut hb_count = 0usize;
    let res = tokio::time::timeout(window, async {
        loop {
            let text = ws.next_text_frame().await.expect("ws text frame");
            let frame: WsEventsFrame = serde_json::from_str(&text).expect("parse WsEventsFrame");
            assert_eq!(
                frame.frame_type, "HEARTBEAT",
                "unexpected leaked event frame: {frame:?}"
            );
            hb_count += 1;
        }
    })
    .await;
    assert!(res.is_err(), "heartbeat assertion loop exited unexpectedly");
    assert!(hb_count >= 1, "expected at least 1 periodic heartbeat frame");
}

async fn wait_for_session_count(srv: &TestServer, expected: usize) {
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            if srv.state.realtime_hub.connected_session_count() == expected {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap_or_else(|_| {
        panic!(
            "timed out waiting for connected_session_count == {expected} (actual {})",
            srv.state.realtime_hub.connected_session_count()
        )
    });
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_ws_ticket_bearer_auth_single_use_and_revocation() {
    let pool = setup_pool().await;
    let srv = TestServer::spawn_default(pool).await;
    let tag = &new_uuid_v4().simple().to_string()[..8];

    let (_, _, cust_tokens) = srv.register_and_verify_customer(tag).await;

    // 1. Missing auth or bogus ticket rejected with 401
    let no_auth = TestWsClient::connect(srv.addr, "/ws/v1/events", None)
        .await
        .expect_err("unauthenticated ws connect must fail");
    assert!(no_auth.starts_with("HTTP/1.1 401 "), "got: {no_auth}");

    let bad_ticket = TestWsClient::connect(srv.addr, "/ws/v1/events?ticket=bogus-ticket", None)
        .await
        .expect_err("bogus ticket must fail");
    assert!(
        bad_ticket.starts_with("HTTP/1.1 401 "),
        "got: {bad_ticket}"
    );

    // 2. Single-use ws-ticket connect succeeds, second use rejected with 401
    let ticket_resp: WsTicketResponse = srv
        .client
        .post(srv.url("/api/v1/auth/ws-ticket"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let ws_path = format!("/ws/v1/events?ticket={}", ticket_resp.ticket);
    let mut ws1 = TestWsClient::connect(srv.addr, &ws_path, None)
        .await
        .expect("single-use ticket ws connect");
    let init1_raw = ws1.next_text_frame().await.unwrap();
    let init1: WsEventsFrame = serde_json::from_str(&init1_raw).unwrap();
    assert_eq!(init1.frame_type, "HEARTBEAT");
    assert_eq!(init1.resumed_from_seq, None);
    let _ = ws1.send_close().await;

    let replay_ticket = TestWsClient::connect(srv.addr, &ws_path, None)
        .await
        .expect_err("replayed single-use ticket must be rejected");
    assert!(
        replay_ticket.starts_with("HTTP/1.1 401 "),
        "got: {replay_ticket}"
    );

    // 3. Bearer JWT auth works while session is active
    let mut ws_bearer =
        TestWsClient::connect(srv.addr, "/ws/v1/events", Some(&cust_tokens.access_token))
            .await
            .expect("bearer ws connect");
    let init2_raw = ws_bearer.next_text_frame().await.unwrap();
    let init2: WsEventsFrame = serde_json::from_str(&init2_raw).unwrap();
    assert_eq!(init2.frame_type, "HEARTBEAT");
    let _ = ws_bearer.send_close().await;

    // 4. Ticket minted before logout + Bearer token are both rejected after logout
    let pre_logout_ticket: WsTicketResponse = srv
        .client
        .post(srv.url("/api/v1/auth/ws-ticket"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    let logout_resp = srv
        .client
        .post(srv.url("/api/v1/auth/logout"))
        .bearer_auth(&cust_tokens.access_token)
        .send()
        .await
        .unwrap();
    assert_eq!(logout_resp.status(), StatusCode::OK);

    let revoked_bearer =
        TestWsClient::connect(srv.addr, "/ws/v1/events", Some(&cust_tokens.access_token))
            .await
            .expect_err("revoked bearer ws connect must fail");
    assert!(
        revoked_bearer.starts_with("HTTP/1.1 401 "),
        "got: {revoked_bearer}"
    );

    let revoked_ticket = TestWsClient::connect(
        srv.addr,
        &format!("/ws/v1/events?ticket={}", pre_logout_ticket.ticket),
        None,
    )
    .await
    .expect_err("pre-logout ticket must fail after session revocation");
    assert!(
        revoked_ticket.starts_with("HTTP/1.1 401 "),
        "got: {revoked_ticket}"
    );
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_events_replay_and_ws_resume_with_room_isolation() {
    let pool = setup_pool().await;
    let srv = TestServer::spawn_default(pool).await;
    let uid = new_uuid_v4();
    let tag = &uid.simple().to_string()[..8];
    let pincode = unique_pincode(&uid);

    let (_, owner_tokens) = srv.bootstrap_and_login_owner(tag).await;
    let (_, kds_tokens) = srv
        .create_and_login_kitchen_staff(&owner_tokens.access_token, tag)
        .await;
    let (cust_a_id, _, cust_a_tokens) = srv.register_and_verify_customer(&format!("ca-{tag}")).await;
    let (_, _, cust_b_tokens) = srv.register_and_verify_customer(&format!("cb-{tag}")).await;
    let seeded =
        seed_full_storefront_via_owner_api(&srv, &owner_tokens.access_token, tag, &pincode).await;

    let addr_a: AddressDto = srv
        .client
        .post(srv.url("/api/v1/me/addresses"))
        .bearer_auth(&cust_a_tokens.access_token)
        .json(&UpsertAddressRequest {
            label: "Home".to_owned(),
            line1: "Indiranagar 12th Main".to_owned(),
            line2: None,
            landmark: None,
            pincode: pincode.clone(),
            city: "Bengaluru".to_owned(),
            is_default: true,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    // Flush any prior unsequenced rows so our baseline marks the exact start of Customer A's flow
    let baseline_seq = sequence_unsequenced_outbox(&srv.pool).await;

    let item = CartItemInputDto {
        id: None,
        product_id: seeded.product.id.clone(),
        variant_id: seeded.variant_id.clone(),
        crust_id: seeded.crust.id.clone(),
        topping_ids: vec![seeded.topping.id.clone()],
        quantity: 1,
        client_unit_price_paise: None,
    };

    // COD checkout -> ORDER_PLACED outbox event
    let cod_resp: CheckoutResponse = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(&cust_a_tokens.access_token)
        .header("Idempotency-Key", format!("ws-cod-{tag}"))
        .json(&CheckoutRequest {
            items: vec![item.clone()],
            fulfillment_type: FulfillmentType::Delivery,
            schedule_type: ScheduleType::Asap,
            scheduled_slot_id: None,
            address_id: Some(addr_a.id.clone()),
            payment_method: PaymentMethod::Cod,
            coupon_code: None,
            clear_cart: false,
        })
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let cod_order_id = cod_resp.order.id.clone();

    // KDS ack -> ORDER_STATUS_CHANGED outbox event
    let ack_resp: KdsTicketActionResponse = srv
        .client
        .post(srv.url(&format!("/api/v1/kds/orders/{cod_order_id}/ack")))
        .bearer_auth(&kds_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(ack_resp.ticket.id, cod_order_id);

    // Razorpay checkout -> inserts internal PAYMENT_GATEWAY_ORDER_REQUESTED (must NEVER replay)
    let rzp_resp = srv
        .client
        .post(srv.url("/api/v1/checkout"))
        .bearer_auth(&cust_a_tokens.access_token)
        .header("Idempotency-Key", format!("ws-rzp-{tag}"))
        .json(&CheckoutRequest {
            items: vec![item],
            fulfillment_type: FulfillmentType::Delivery,
            schedule_type: ScheduleType::Asap,
            scheduled_slot_id: None,
            address_id: Some(addr_a.id),
            payment_method: PaymentMethod::Razorpay,
            coupon_code: None,
            clear_cart: false,
        })
        .send()
        .await
        .unwrap();
    assert_eq!(rzp_resp.status(), StatusCode::CREATED);

    // Insert a private NOTIFICATION_CREATED outbox event targeted exclusively at Customer A
    let notif_event_id = new_uuid_v4();
    sqlx::query(
        r#"
        INSERT INTO outbox_events (
            id, aggregate_type, aggregate_id, aggregate_version, event_type,
            payload_json, stream_seq, sequenced_at, dispatch_status, published_at
        ) VALUES ($1, 'NOTIFICATION', $2, 1, 'NOTIFICATION_CREATED', $3, NULL, NULL, 'PENDING', NULL)
        "#,
    )
    .bind(notif_event_id)
    .bind(notif_event_id)
    .bind(json!({
        "user_id": cust_a_id.to_string(),
        "title": "Order Acknowledged",
        "order_id": cod_order_id,
    }))
    .execute(&srv.pool)
    .await
    .unwrap();

    let high_water_after = sequence_unsequenced_outbox(&srv.pool).await;
    assert!(high_water_after > baseline_seq);

    // 1. HTTP replay checks across Customer A, Customer B, KDS, and Owner
    let replay_a: EventsReplayResponse = srv
        .client
        .get(srv.url(&format!(
            "/api/v1/events/replay?since_seq={baseline_seq}&limit=100"
        )))
        .bearer_auth(&cust_a_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(replay_a.server_high_water_seq, high_water_after);
    assert!(replay_a
        .events
        .iter()
        .any(|e| e.aggregate_id == cod_order_id && e.event_type == "order.created"));
    assert!(replay_a
        .events
        .iter()
        .any(|e| e.aggregate_id == cod_order_id && e.event_type == "order.status_changed"));
    assert!(replay_a
        .events
        .iter()
        .any(|e| e.event_id == notif_event_id.to_string()
            && e.event_type == "notification.created"));
    assert!(!replay_a.events.iter().any(|e| {
        e.event_type.contains("payment_gateway_order_requested")
            || e.event_type.contains("email_delivery_requested")
    }));

    let replay_b: EventsReplayResponse = srv
        .client
        .get(srv.url(&format!(
            "/api/v1/events/replay?since_seq={baseline_seq}&limit=100"
        )))
        .bearer_auth(&cust_b_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(
        replay_b.events.is_empty(),
        "Customer B must not see Customer A events: {:?}",
        replay_b.events
    );

    let replay_kds: EventsReplayResponse = srv
        .client
        .get(srv.url(&format!(
            "/api/v1/events/replay?since_seq={baseline_seq}&limit=100"
        )))
        .bearer_auth(&kds_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(replay_kds
        .events
        .iter()
        .any(|e| e.aggregate_id == cod_order_id && e.event_type == "order.created"));
    assert!(!replay_kds
        .events
        .iter()
        .any(|e| e.event_id == notif_event_id.to_string()));

    let replay_owner: EventsReplayResponse = srv
        .client
        .get(srv.url(&format!(
            "/api/v1/events/replay?since_seq={baseline_seq}&limit=100"
        )))
        .bearer_auth(&owner_tokens.access_token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(replay_owner
        .events
        .iter()
        .any(|e| e.aggregate_id == cod_order_id && e.event_type == "order.status_changed"));
    assert!(!replay_owner
        .events
        .iter()
        .any(|e| e.event_id == notif_event_id.to_string()));

    // 2. WebSocket resume (?since_seq=baseline_seq) pushes EVENTS for Customer A and empty HEARTBEAT for Customer B
    let mut ws_a = TestWsClient::connect(
        srv.addr,
        &format!("/ws/v1/events?since_seq={baseline_seq}"),
        Some(&cust_a_tokens.access_token),
    )
    .await
    .unwrap();
    let frame_a: WsEventsFrame =
        serde_json::from_str(&ws_a.next_text_frame().await.unwrap()).unwrap();
    assert_eq!(frame_a.frame_type, "EVENTS");
    assert_eq!(frame_a.resumed_from_seq, Some(baseline_seq));
    assert!(frame_a
        .events
        .iter()
        .any(|e| e.aggregate_id == cod_order_id && e.event_type == "order.created"));
    let _ = ws_a.send_close().await;

    let mut ws_b = TestWsClient::connect(
        srv.addr,
        &format!("/ws/v1/events?since_seq={baseline_seq}"),
        Some(&cust_b_tokens.access_token),
    )
    .await
    .unwrap();
    let frame_b: WsEventsFrame =
        serde_json::from_str(&ws_b.next_text_frame().await.unwrap()).unwrap();
    assert_eq!(frame_b.frame_type, "HEARTBEAT");
    assert!(frame_b.events.is_empty());
    assert_eq!(frame_b.resumed_from_seq, Some(baseline_seq));
    let _ = ws_b.send_close().await;
}

#[tokio::test]
#[ignore = "Requires live PostgreSQL 18.6 at DATABASE_URL"]
async fn pg18_ws_live_hub_fanout_heartbeat_ping_pong_and_oversized_frame() {
    let pool = setup_pool().await;
    let env_overrides = HashMap::from([("MAX_WS_FRAME_BYTES".to_owned(), "128".to_owned())]);
    let srv =
        TestServer::spawn_with_options(pool, env_overrides, false, Some(Duration::from_millis(50)))
            .await;
    let tag = &new_uuid_v4().simple().to_string()[..8];

    let (_, owner_tokens) = srv.bootstrap_and_login_owner(tag).await;
    let (_, kds_tokens) = srv
        .create_and_login_kitchen_staff(&owner_tokens.access_token, tag)
        .await;
    let (cust_a_id, _, cust_a_tokens) = srv.register_and_verify_customer(&format!("la-{tag}")).await;
    let (_, _, cust_b_tokens) = srv.register_and_verify_customer(&format!("lb-{tag}")).await;

    let baseline_sessions = srv.state.realtime_hub.connected_session_count();

    let mut ws_a =
        TestWsClient::connect(srv.addr, "/ws/v1/events", Some(&cust_a_tokens.access_token))
            .await
            .unwrap();
    let mut ws_b =
        TestWsClient::connect(srv.addr, "/ws/v1/events", Some(&cust_b_tokens.access_token))
            .await
            .unwrap();
    let mut ws_kds =
        TestWsClient::connect(srv.addr, "/ws/v1/events", Some(&kds_tokens.access_token))
            .await
            .unwrap();
    let mut ws_owner =
        TestWsClient::connect(srv.addr, "/ws/v1/events", Some(&owner_tokens.access_token))
            .await
            .unwrap();

    // Consume initial HEARTBEAT frames and wait for server registration
    for ws in [&mut ws_a, &mut ws_b, &mut ws_kds, &mut ws_owner] {
        let init: WsEventsFrame = serde_json::from_str(&ws.next_text_frame().await.unwrap()).unwrap();
        assert_eq!(init.frame_type, "HEARTBEAT");
    }
    wait_for_session_count(&srv, baseline_sessions + 4).await;

    let hw = srv
        .state
        .realtime_event_service
        .current_high_water_seq()
        .await
        .unwrap();
    let live_seq = hw + 100;
    let order_id = new_uuid_v4();

    let mut payload = serde_json::Map::new();
    payload.insert(
        "customer_id".to_owned(),
        serde_json::Value::String(cust_a_id.to_string()),
    );
    payload.insert(
        "order_number".to_owned(),
        serde_json::Value::String(format!("PIZ-WS-{tag}")),
    );
    let live_dto = RealtimeEventDto {
        event_id: new_uuid_v4().to_string(),
        stream_seq: live_seq,
        event_type: "ORDER_PLACED".to_owned(),
        aggregate_type: "ORDER".to_owned(),
        aggregate_id: order_id.to_string(),
        aggregate_version: 1,
        payload,
        sequenced_at: Some(chrono::Utc::now().to_rfc3339()),
        created_at: chrono::Utc::now().to_rfc3339(),
    };

    assert!(srv
        .state
        .realtime_hub
        .dispatch_serialized(&serde_json::to_string(&live_dto).unwrap()));

    let fa = next_ws_events_frame(&mut ws_a).await;
    assert_eq!(fa.events[0].event_type, "order.created");
    assert_eq!(fa.events[0].stream_seq, live_seq);

    let fk = next_ws_events_frame(&mut ws_kds).await;
    assert_eq!(fk.events[0].aggregate_id, order_id.to_string());

    let fo = next_ws_events_frame(&mut ws_owner).await;
    assert_eq!(fo.events[0].aggregate_id, order_id.to_string());

    // Customer B receives heartbeats (proving 50ms heartbeat loop works) and zero leaked events
    assert_only_heartbeats(&mut ws_b, Duration::from_millis(220)).await;

    // Duplicate/stale stream_seq and internal worker command are dropped
    assert!(!srv
        .state
        .realtime_hub
        .dispatch_serialized(&serde_json::to_string(&live_dto).unwrap()));

    let internal_dto = RealtimeEventDto {
        event_id: new_uuid_v4().to_string(),
        stream_seq: live_seq + 1,
        event_type: "PAYMENT_GATEWAY_ORDER_REQUESTED".to_owned(),
        aggregate_type: "ORDER".to_owned(),
        aggregate_id: order_id.to_string(),
        aggregate_version: 2,
        payload: serde_json::Map::new(),
        sequenced_at: None,
        created_at: chrono::Utc::now().to_rfc3339(),
    };
    assert!(!srv
        .state
        .realtime_hub
        .dispatch_serialized(&serde_json::to_string(&internal_dto).unwrap()));

    // Ping/Pong verification on Customer A socket
    ws_a.send_ping(b"pizzora-ping").await.unwrap();
    let got_pong = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            match ws_a.read_frame().await.expect("ws_a read frame") {
                WsFrame::Pong(data) => return data,
                WsFrame::Ping(p) => {
                    let _ = p;
                }
                WsFrame::Text(_) | WsFrame::Binary(_) => {}
                WsFrame::Close(c) => panic!("unexpected close before pong: {c:?}"),
            }
        }
    })
    .await
    .expect("timed out waiting for pong");
    assert_eq!(got_pong, b"pizzora-ping");

    // Oversized frame (> 128 bytes) triggers clean server-side close and unregisters session
    ws_a.send_text(&"A".repeat(256)).await.unwrap();
    let closed_cleanly = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            match ws_a.read_frame().await {
                Ok(WsFrame::Close(_)) => return true,
                Ok(_) => {}
                Err(_) => return true,
            }
        }
    })
    .await
    .expect("timed out waiting for server close on oversized frame");
    assert!(closed_cleanly);

    wait_for_session_count(&srv, baseline_sessions + 3).await;

    let _ = ws_b.send_close().await;
    let _ = ws_kds.send_close().await;
    let _ = ws_owner.send_close().await;
}
