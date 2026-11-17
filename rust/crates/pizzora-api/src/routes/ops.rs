use axum::extract::State;
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;

use crate::dto::HealthReadinessResponse;
use crate::health::{evaluate_readiness, liveness};
use crate::openapi::build_openapi_document;
use crate::state::AppState;

pub async fn health_live() -> Json<HealthReadinessResponse> {
    Json(liveness())
}

pub async fn health_ready(
    State(state): State<AppState>,
) -> (StatusCode, Json<HealthReadinessResponse>) {
    let (status, body) = evaluate_readiness(&state).await;
    (status, Json(body))
}

pub async fn openapi_json() -> Json<serde_json::Value> {
    Json(build_openapi_document("/"))
}

pub async fn metrics_scrape(State(state): State<AppState>) -> Response {
    let (ws_sessions, ws_users) = state.realtime_hub.ws_session_counts();
    let payload = state
        .metrics
        .render_prometheus(&state.pool, ws_sessions, ws_users)
        .await;
    let mut resp = (StatusCode::OK, payload).into_response();
    resp.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; version=0.0.4; charset=utf-8"),
    );
    resp
}
