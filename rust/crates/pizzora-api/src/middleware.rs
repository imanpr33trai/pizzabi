use std::net::SocketAddr;
use std::time::Instant;

use axum::body::{to_bytes, Body};
use axum::extract::{ConnectInfo, MatchedPath, Request, State};
use axum::http::{header, HeaderName, HeaderValue, Method, StatusCode};
use axum::middleware::Next;
use axum::response::Response;
use pizzora_db::new_uuid_v4;
use tracing::{info, warn};

use crate::error::ApiError;
use crate::rate_limit::RateLimitDecision;
use crate::state::AppState;

#[derive(Debug, Clone)]
pub struct CorrelationId(pub String);

#[derive(Debug, Clone)]
pub struct ClientIp(pub String);

pub async fn correlation_id_middleware(mut req: Request, next: Next) -> Response {
    let incoming = req
        .headers()
        .get("x-request-id")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|v| is_valid_request_id(v))
        .map(str::to_owned);

    let cid = incoming.unwrap_or_else(|| new_uuid_v4().to_string());
    let client_ip = extract_client_ip(&req);

    req.extensions_mut().insert(CorrelationId(cid.clone()));
    req.extensions_mut().insert(ClientIp(client_ip));

    let mut resp = next.run(req).await;
    if let Ok(val) = HeaderValue::from_str(&cid) {
        resp.headers_mut()
            .insert(HeaderName::from_static("x-request-id"), val);
    }
    resp
}

pub async fn security_headers_middleware(
    State(state): State<AppState>,
    req: Request,
    next: Next,
) -> Response {
    let is_prod = state.config.is_production();
    let mut resp = next.run(req).await;
    let headers = resp.headers_mut();

    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("no-store"),
    );
    headers.insert(header::PRAGMA, HeaderValue::from_static("no-cache"));
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static("default-src 'none'; frame-ancestors 'none'"),
    );
    headers.insert(
        HeaderName::from_static("permissions-policy"),
        HeaderValue::from_static("geolocation=(), microphone=(), camera=()"),
    );
    headers.insert(
        HeaderName::from_static("cross-origin-resource-policy"),
        HeaderValue::from_static("same-origin"),
    );

    if is_prod {
        headers.insert(
            header::STRICT_TRANSPORT_SECURITY,
            HeaderValue::from_static("max-age=31536000; includeSubDomains"),
        );
    }

    resp
}

pub async fn body_size_limit_middleware(
    State(state): State<AppState>,
    req: Request,
    next: Next,
) -> Response {
    let cid = correlation_id_from_req(&req);
    let is_ws_upgrade = req
        .headers()
        .get(header::UPGRADE)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.eq_ignore_ascii_case("websocket"));

    if is_ws_upgrade || matches!(*req.method(), Method::GET | Method::HEAD | Method::OPTIONS) {
        return next.run(req).await;
    }

    let path = req.uri().path();
    let max_bytes = if path.starts_with("/api/v1/webhooks/") {
        state.config.security.max_webhook_body_bytes
    } else {
        state.config.security.max_json_body_bytes
    };

    if let Some(raw_len) = req.headers().get(header::CONTENT_LENGTH) {
        let parsed = raw_len
            .to_str()
            .ok()
            .and_then(|s| s.trim().parse::<u64>().ok());
        match parsed {
            None => {
                return ApiError::validation(
                    "INVALID_CONTENT_LENGTH",
                    "Content-Length header is invalid.",
                )
                .into_response_with_correlation(&cid);
            }
            Some(len) if len > max_bytes => {
                return ApiError::new(
                    StatusCode::PAYLOAD_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE",
                    format!("Request payload exceeds maximum limit of {max_bytes} bytes."),
                )
                .with_detail("max_bytes", max_bytes.to_string())
                .into_response_with_correlation(&cid);
            }
            Some(_) => {}
        }
    }

    let (parts, body) = req.into_parts();
    match to_bytes(body, max_bytes as usize).await {
        Ok(bytes) => {
            let rebuilt = Request::from_parts(parts, Body::from(bytes));
            next.run(rebuilt).await
        }
        Err(_) => ApiError::new(
            StatusCode::PAYLOAD_TOO_LARGE,
            "PAYLOAD_TOO_LARGE",
            format!("Request payload exceeds maximum limit of {max_bytes} bytes."),
        )
        .with_detail("max_bytes", max_bytes.to_string())
        .into_response_with_correlation(&cid),
    }
}

pub async fn rate_limit_middleware(
    State(state): State<AppState>,
    req: Request,
    next: Next,
) -> Response {
    if !state.rate_limiter.is_enabled() {
        return next.run(req).await;
    }

    let path = req.uri().path().to_owned();
    let client_ip = req
        .extensions()
        .get::<ClientIp>()
        .map(|ip| ip.0.clone())
        .unwrap_or_else(|| "unknown".to_owned());

    let Some(quota) = state.rate_limiter.classify_route_group(&path) else {
        return next.run(req).await;
    };
    let key = format!("group:{}:{client_ip}", quota.group);
    match state.rate_limiter.check(&key, quota.max_per_minute, 60).await {
        RateLimitDecision::Allowed => next.run(req).await,
        RateLimitDecision::Rejected { retry_after_secs } => {
            let cid = correlation_id_from_req(&req);
            warn!(
                route_group = quota.group,
                path = %path,
                client_ip = %client_ip,
                correlation_id = %cid,
                "Rate limited request by route group"
            );
            ApiError::rate_limited(
                format!("Too many requests for '{}'. Please try again later.", quota.group),
                retry_after_secs,
            )
            .with_detail("group", quota.group)
            .with_detail("retry_after_seconds", retry_after_secs.to_string())
            .into_response_with_correlation(&cid)
        }
    }
}

pub async fn metrics_and_logging_middleware(
    State(state): State<AppState>,
    req: Request,
    next: Next,
) -> Response {
    let started = Instant::now();
    state.metrics.inc_in_flight();

    let method = req.method().to_string();
    let matched_route = req
        .extensions()
        .get::<MatchedPath>()
        .map(|m| m.as_str().to_owned())
        .unwrap_or_else(|| req.uri().path().to_owned());
    let cid = correlation_id_from_req(&req);

    let resp = next.run(req).await;
    let status = resp.status().as_u16();
    let elapsed_secs = started.elapsed().as_secs_f64();

    state.metrics.dec_in_flight();
    state
        .metrics
        .record_request(&method, &matched_route, status, elapsed_secs);

    if matched_route != "/api/v1/health/live" {
        info!(
            correlation_id = %cid,
            method = %method,
            route = %matched_route,
            status = status,
            duration_ms = (elapsed_secs * 1000.0) as u64,
            "HTTP request completed"
        );
    }

    resp
}

pub fn correlation_id_from_req(req: &Request) -> String {
    req.extensions()
        .get::<CorrelationId>()
        .map(|c| c.0.clone())
        .unwrap_or_else(|| new_uuid_v4().to_string())
}

pub fn extract_client_ip(req: &Request) -> String {
    if let Some(fwd) = req
        .headers()
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
    {
        if let Some(first) = fwd.split(',').next().map(str::trim).filter(|s| !s.is_empty()) {
            return first.to_owned();
        }
    }
    if let Some(real_ip) = req
        .headers()
        .get("x-real-ip")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        return real_ip.to_owned();
    }
    if let Some(ConnectInfo(addr)) = req.extensions().get::<ConnectInfo<SocketAddr>>() {
        return addr.ip().to_string();
    }
    "unknown".to_owned()
}

pub fn is_valid_request_id(candidate: &str) -> bool {
    let bytes = candidate.as_bytes();
    if !(8..=64).contains(&bytes.len()) {
        return false;
    }
    bytes
        .iter()
        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
}
