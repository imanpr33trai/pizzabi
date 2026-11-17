use axum::extract::{Path, Query, State};
use axum::Json;
use pizzora_core::OrderStatus;
use serde::Deserialize;

use crate::dto::{
    KdsActionRequest, KdsTicketActionResponse, KdsTicketsResponse, UpdateOrderPriorityRequest,
};
use crate::error::ApiError;
use crate::extractors::{
    build_audit_context, parse_uuid_param, require_permission, AuthPrincipal, OptionalJson,
    RequestMeta, ValidatedJson,
};
use crate::services::kitchen::parse_order_status;
use crate::state::AppState;

#[derive(Debug, Deserialize, Default)]
pub struct KdsTicketsQuery {
    pub include_completed: Option<bool>,
    pub status: Option<String>,
    pub limit: Option<i64>,
}

pub async fn list_tickets(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<KdsTicketsQuery>,
) -> Result<Json<KdsTicketsResponse>, ApiError> {
    require_permission(&state, &principal, "kds:read").await?;
    let include_completed = query.include_completed.unwrap_or(false);
    let status_filter = match query
        .status
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(raw) => Some(parse_order_status(raw)?),
        None => None,
    };
    let limit = query.limit.unwrap_or(100);
    let resp = state
        .kitchen_service
        .list_tickets(include_completed, status_filter, limit)
        .await?;
    Ok(Json(resp))
}

pub async fn ack_order(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    OptionalJson(body): OptionalJson<KdsActionRequest>,
) -> Result<Json<KdsTicketActionResponse>, ApiError> {
    require_permission(&state, &principal, "kds:update_status").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .kitchen_service
        .transition_ticket(
            order_id,
            OrderStatus::Accepted,
            principal.user_id,
            principal.role,
            body.as_ref().and_then(|b| b.reason.as_deref()),
            &audit,
            "KITCHEN_ACKNOWLEDGED",
        )
        .await?;
    Ok(Json(resp))
}

pub async fn start_prep(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    OptionalJson(body): OptionalJson<KdsActionRequest>,
) -> Result<Json<KdsTicketActionResponse>, ApiError> {
    require_permission(&state, &principal, "kds:update_status").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .kitchen_service
        .transition_ticket(
            order_id,
            OrderStatus::Preparing,
            principal.user_id,
            principal.role,
            body.as_ref().and_then(|b| b.reason.as_deref()),
            &audit,
            "ORDER_STATUS_CHANGED",
        )
        .await?;
    Ok(Json(resp))
}

pub async fn mark_ready(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    OptionalJson(body): OptionalJson<KdsActionRequest>,
) -> Result<Json<KdsTicketActionResponse>, ApiError> {
    require_permission(&state, &principal, "kds:update_status").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .kitchen_service
        .transition_ticket(
            order_id,
            OrderStatus::Ready,
            principal.user_id,
            principal.role,
            body.as_ref().and_then(|b| b.reason.as_deref()),
            &audit,
            "ORDER_STATUS_CHANGED",
        )
        .await?;
    Ok(Json(resp))
}

pub async fn dispatch_order(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    OptionalJson(body): OptionalJson<KdsActionRequest>,
) -> Result<Json<KdsTicketActionResponse>, ApiError> {
    require_permission(&state, &principal, "kds:update_status").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .kitchen_service
        .transition_ticket(
            order_id,
            OrderStatus::OutForDelivery,
            principal.user_id,
            principal.role,
            body.as_ref().and_then(|b| b.reason.as_deref()),
            &audit,
            "ORDER_STATUS_CHANGED",
        )
        .await?;
    Ok(Json(resp))
}

pub async fn complete_order(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    OptionalJson(body): OptionalJson<KdsActionRequest>,
) -> Result<Json<KdsTicketActionResponse>, ApiError> {
    require_permission(&state, &principal, "kds:update_status").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .kitchen_service
        .transition_ticket(
            order_id,
            OrderStatus::Completed,
            principal.user_id,
            principal.role,
            body.as_ref().and_then(|b| b.reason.as_deref()),
            &audit,
            "ORDER_STATUS_CHANGED",
        )
        .await?;
    Ok(Json(resp))
}

pub async fn update_priority(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateOrderPriorityRequest>,
) -> Result<Json<KdsTicketActionResponse>, ApiError> {
    require_permission(&state, &principal, "kds:prioritize").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .kitchen_service
        .update_priority(
            order_id,
            body.is_priority,
            principal.user_id,
            body.reason.as_deref(),
            &audit,
        )
        .await?;
    Ok(Json(resp))
}
