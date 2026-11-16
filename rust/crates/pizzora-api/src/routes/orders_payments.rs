use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use pizzora_auth::MessageResponse;
use pizzora_db::{OrderDetailDto, OrderListResponse};
use pizzora_payments::{
    FailPaymentAttemptRequest, OrderPaymentStatusResponse, OwnerRefundRequest, RefundDto,
    RetryPaymentRequest, VerifyRazorpayPaymentRequest, VerifyRazorpayPaymentResponse,
};
use serde::Deserialize;

use crate::dto::{CancelOrderRequest, TransitionOrderStatusRequest};
use crate::error::ApiError;
use crate::extractors::{
    build_audit_context, header_str, parse_uuid_param, require_permission, AuthPrincipal,
    OptionalJson, RequestMeta, ValidatedJson,
};
use crate::services::kitchen::parse_order_status;
use crate::state::AppState;

#[derive(Debug, Deserialize, Default)]
pub struct CustomerOrdersQuery {
    pub limit: Option<i64>,
}

#[derive(Debug, Deserialize, Default)]
pub struct OwnerOrdersQuery {
    pub status: Option<String>,
    pub limit: Option<i64>,
}

pub async fn list_my_orders(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<CustomerOrdersQuery>,
) -> Result<Json<OrderListResponse>, ApiError> {
    require_permission(&state, &principal, "orders:read_own").await?;
    let limit = query.limit.unwrap_or(50);
    let resp = state
        .order_service
        .list_customer_orders(principal.user_id, limit)
        .await?;
    Ok(Json(resp))
}

pub async fn get_my_order(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Path(id): Path<String>,
) -> Result<Json<OrderDetailDto>, ApiError> {
    require_permission(&state, &principal, "orders:read_own").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let detail = state
        .order_service
        .get_order_detail(order_id, principal.user_id, principal.role)
        .await?;
    Ok(Json(detail))
}

pub async fn cancel_my_order(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    OptionalJson(body): OptionalJson<CancelOrderRequest>,
) -> Result<Json<OrderDetailDto>, ApiError> {
    require_permission(&state, &principal, "orders:cancel_own").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let reason = body.and_then(|req| req.reason);
    let updated = state
        .order_service
        .cancel_order(
            order_id,
            principal.user_id,
            principal.role,
            reason.as_deref(),
            &audit,
        )
        .await?;
    Ok(Json(updated))
}

pub async fn get_order_payment(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Path(id): Path<String>,
) -> Result<Json<OrderPaymentStatusResponse>, ApiError> {
    require_permission(&state, &principal, "orders:read_own").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let resp = state
        .payment_service
        .get_order_payment_status(order_id, principal.user_id, principal.role)
        .await?;
    Ok(Json(resp))
}

pub async fn retry_order_payment(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    headers: HeaderMap,
    Path(id): Path<String>,
    OptionalJson(body): OptionalJson<RetryPaymentRequest>,
) -> Result<Json<OrderPaymentStatusResponse>, ApiError> {
    require_permission(&state, &principal, "payments:retry_own").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let idempotency_key = header_str(&headers, "idempotency-key");
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .payment_service
        .retry_payment(
            order_id,
            principal.user_id,
            idempotency_key.as_deref(),
            body.as_ref(),
            &audit,
        )
        .await?;
    Ok(Json(resp))
}

pub async fn fail_order_payment(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    OptionalJson(body): OptionalJson<FailPaymentAttemptRequest>,
) -> Result<Json<OrderPaymentStatusResponse>, ApiError> {
    require_permission(&state, &principal, "payments:retry_own").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let payload = body.unwrap_or_default();
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .payment_service
        .report_attempt_failure(order_id, principal.user_id, &payload, &audit)
        .await?;
    Ok(Json(resp))
}

pub async fn verify_razorpay_payment(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<VerifyRazorpayPaymentRequest>,
) -> Result<Json<VerifyRazorpayPaymentResponse>, ApiError> {
    require_permission(&state, &principal, "payments:retry_own").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .payment_service
        .verify_client_payment(principal.user_id, &body, &audit)
        .await?;
    Ok(Json(resp))
}

pub async fn razorpay_webhook(
    State(state): State<AppState>,
    meta: RequestMeta,
    headers: HeaderMap,
    raw_body: Bytes,
) -> Result<Json<MessageResponse>, ApiError> {
    let signature = header_str(&headers, "x-razorpay-signature");
    let event_id = header_str(&headers, "x-razorpay-event-id");
    let audit = build_audit_context(None, &meta);
    state
        .webhook_service
        .handle_razorpay_webhook(&raw_body, signature.as_deref(), event_id.as_deref(), &audit)
        .await?;
    Ok(Json(MessageResponse {
        message: "Webhook processed.".to_owned(),
    }))
}

pub async fn list_owner_orders(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<OwnerOrdersQuery>,
) -> Result<Json<OrderListResponse>, ApiError> {
    require_permission(&state, &principal, "owner:orders_manage").await?;
    let status_filter = match query
        .status
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(raw) => Some(parse_order_status(raw)?),
        None => None,
    };
    let resp = state
        .owner_service
        .list_orders(status_filter, query.limit.unwrap_or(50))
        .await?;
    Ok(Json(resp))
}

pub async fn transition_owner_order_status(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<TransitionOrderStatusRequest>,
) -> Result<Json<OrderDetailDto>, ApiError> {
    require_permission(&state, &principal, "owner:orders_manage").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .order_service
        .transition_order_status(
            order_id,
            body.to_status,
            principal.user_id,
            principal.role,
            body.reason.as_deref(),
            &audit,
            "ORDER_STATUS_CHANGED",
        )
        .await?;
    Ok(Json(updated))
}

pub async fn initiate_owner_refund(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    headers: HeaderMap,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<OwnerRefundRequest>,
) -> Result<(StatusCode, Json<RefundDto>), ApiError> {
    require_permission(&state, &principal, "owner:refund_execute").await?;
    let order_id = parse_uuid_param(&id, "order_id")?;
    let idempotency_key = header_str(&headers, "idempotency-key");
    let audit = build_audit_context(Some(&principal), &meta);
    let refund = state
        .payment_service
        .initiate_owner_refund(
            order_id,
            principal.user_id,
            idempotency_key.as_deref(),
            &body,
            &audit,
        )
        .await?;
    Ok((StatusCode::ACCEPTED, Json(refund)))
}
