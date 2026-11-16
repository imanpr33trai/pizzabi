use std::sync::atomic::Ordering;

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{FromRequestParts, Query, Request, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Deserialize;

use crate::dto::{EventsReplayResponse, WsEventsFrame};
use crate::error::{now_iso8601, ApiError};
use crate::extractors::{authenticate_ws_parts, AuthPrincipal};
use crate::state::AppState;

#[derive(Debug, Deserialize, Default)]
pub struct ReplayQuery {
    pub since_seq: Option<i64>,
    pub limit: Option<i64>,
}

#[derive(Debug, Deserialize, Default)]
pub struct WsEventsQuery {
    pub ticket: Option<String>,
    pub since_seq: Option<i64>,
}

pub async fn replay_events(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<ReplayQuery>,
) -> Result<Json<EventsReplayResponse>, ApiError> {
    let since_seq = query.since_seq.unwrap_or(0);
    let limit = query.limit.unwrap_or(100);
    let resp = state
        .realtime_event_service
        .replay_events(&principal, since_seq, limit)
        .await?;
    Ok(Json(resp))
}

pub async fn ws_events(
    State(state): State<AppState>,
    Query(query): Query<WsEventsQuery>,
    req: Request,
) -> Response {
    let (mut parts, _body) = req.into_parts();
    let principal =
        match authenticate_ws_parts(&parts, &state, query.ticket.as_deref()).await {
            Ok(p) => p,
            Err(err) => {
                if let Some(cid) = parts.extensions.get::<crate::middleware::CorrelationId>() {
                    return err.into_response_with_correlation(&cid.0);
                }
                return err.into_response();
            }
        };

    let ws_upgrade = match WebSocketUpgrade::from_request_parts(&mut parts, &state).await {
        Ok(ws) => ws,
        Err(_) => {
            return (
                StatusCode::UPGRADE_REQUIRED,
                "WebSocket upgrade headers are required.",
            )
                .into_response();
        }
    };

    let max_frame_bytes = usize::try_from(state.config.security.max_ws_frame_bytes)
        .unwrap_or(65_536);
    let since_seq = query.since_seq;

    ws_upgrade.on_upgrade(move |socket| {
        handle_ws_connection(socket, state, principal, since_seq, max_frame_bytes)
    })
}

async fn handle_ws_connection(
    mut socket: WebSocket,
    state: AppState,
    principal: pizzora_auth::PizzoraPrincipal,
    since_seq_param: Option<i64>,
    max_frame_bytes: usize,
) {
    let mut cursor_seq = 0i64;
    if let Some(since_seq) = since_seq_param.filter(|v| *v >= 0) {
        let mut page_since = since_seq;
        let mut sent_any_replay_frame = false;
        for page_idx in 0..10 {
            let Ok(replay) = state
                .realtime_event_service
                .replay_events(&principal, page_since, 100)
                .await
            else {
                let _ = socket.send(Message::Close(None)).await;
                return;
            };
            cursor_seq = cursor_seq.max(replay.server_high_water_seq);
            if !replay.events.is_empty() || !sent_any_replay_frame {
                let frame = WsEventsFrame {
                    frame_type: if replay.events.is_empty() {
                        "HEARTBEAT".to_owned()
                    } else {
                        "EVENTS".to_owned()
                    },
                    events: replay.events,
                    server_high_water_seq: replay.server_high_water_seq,
                    server_now: replay.server_now,
                    resumed_from_seq: (page_idx == 0).then_some(since_seq),
                };
                if let Ok(encoded) = serde_json::to_string(&frame) {
                    if socket.send(Message::Text(encoded.into())).await.is_err() {
                        return;
                    }
                    sent_any_replay_frame = true;
                }
            }
            page_since = replay.server_high_water_seq;
            if !replay.has_more {
                break;
            }
        }
    } else {
        cursor_seq = state
            .realtime_event_service
            .current_high_water_seq()
            .await
            .unwrap_or(0);
        let initial = WsEventsFrame {
            frame_type: "HEARTBEAT".to_owned(),
            events: Vec::new(),
            server_high_water_seq: cursor_seq,
            server_now: now_iso8601(),
            resumed_from_seq: None,
        };
        if let Ok(encoded) = serde_json::to_string(&initial) {
            if socket.send(Message::Text(encoded.into())).await.is_err() {
                return;
            }
        }
    }

    let mut handle = state.realtime_hub.register_session(&principal, cursor_seq);
    let mut heartbeat_interval = tokio::time::interval(state.ws_heartbeat_interval);
    heartbeat_interval.tick().await;

    loop {
        tokio::select! {
            maybe_frame = handle.outbound_rx.recv() => {
                match maybe_frame {
                    Some(frame) => {
                        if let Ok(encoded) = serde_json::to_string(&frame) {
                            if socket.send(Message::Text(encoded.into())).await.is_err() {
                                break;
                            }
                        }
                    }
                    None => break,
                }
            }
            _ = heartbeat_interval.tick() => {
                let db_hw = state
                    .realtime_event_service
                    .current_high_water_seq()
                    .await
                    .unwrap_or(0);
                let seq = handle.last_sent_seq.load(Ordering::Acquire).max(db_hw);
                let hb = WsEventsFrame {
                    frame_type: "HEARTBEAT".to_owned(),
                    events: Vec::new(),
                    server_high_water_seq: seq,
                    server_now: now_iso8601(),
                    resumed_from_seq: None,
                };
                if let Ok(encoded) = serde_json::to_string(&hb) {
                    if socket.send(Message::Text(encoded.into())).await.is_err() {
                        break;
                    }
                }
            }
            incoming = socket.recv() => {
                match incoming {
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(Message::Text(text))) => {
                        if text.len() > max_frame_bytes {
                            let _ = socket.send(Message::Close(None)).await;
                            break;
                        }
                    }
                    Some(Ok(Message::Binary(bin))) => {
                        if bin.len() > max_frame_bytes {
                            let _ = socket.send(Message::Close(None)).await;
                            break;
                        }
                    }
                    Some(Ok(Message::Ping(payload))) => {
                        if socket.send(Message::Pong(payload)).await.is_err() {
                            break;
                        }
                    }
                    Some(Ok(Message::Pong(_))) => {}
                }
            }
        }
    }

    state.realtime_hub.unregister_session(handle.session_id);
}
