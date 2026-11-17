use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use pizzora_db::{AvailableCouponsResponse, CouponDto, CreateCouponRequest, UpdateCouponRequest};
use serde::Deserialize;

use crate::dto::{
    CartResponseDto, CheckoutRequest, CheckoutResponse, PricingQuoteRequest, PricingQuoteResponse,
    ReplaceCartRequest, ValidateCartRequest,
};
use crate::error::ApiError;
use crate::extractors::{
    build_audit_context, header_str, parse_uuid_param, require_permission, AuthPrincipal,
    RequestMeta, ValidatedJson,
};
use crate::state::AppState;

#[derive(Debug, Deserialize, Default)]
pub struct AvailableCouponsQuery {
    pub subtotal_paise: Option<i64>,
}

pub async fn get_cart(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<CartResponseDto>, ApiError> {
    require_permission(&state, &principal, "cart:manage").await?;
    let cart = state.cart_service.get_cart(principal.user_id).await?;
    Ok(Json(cart))
}

pub async fn replace_cart(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    ValidatedJson(body): ValidatedJson<ReplaceCartRequest>,
) -> Result<Json<CartResponseDto>, ApiError> {
    require_permission(&state, &principal, "cart:manage").await?;
    let cart = state
        .cart_service
        .replace_cart(principal.user_id, &body)
        .await?;
    Ok(Json(cart))
}

pub async fn validate_cart(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    ValidatedJson(body): ValidatedJson<ValidateCartRequest>,
) -> Result<Json<CartResponseDto>, ApiError> {
    require_permission(&state, &principal, "cart:manage").await?;
    let cart = state.cart_service.validate_cart(&body).await?;
    Ok(Json(cart))
}

pub async fn list_available_coupons(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    Query(query): Query<AvailableCouponsQuery>,
) -> Result<Json<AvailableCouponsResponse>, ApiError> {
    require_permission(&state, &principal, "cart:manage").await?;
    let resp = state
        .coupon_service
        .list_available_coupons(principal.user_id, query.subtotal_paise)
        .await?;
    Ok(Json(resp))
}

pub async fn quote_pricing(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    ValidatedJson(body): ValidatedJson<PricingQuoteRequest>,
) -> Result<Json<PricingQuoteResponse>, ApiError> {
    require_permission(&state, &principal, "cart:manage").await?;
    let quote = state
        .pricing_service
        .quote_price(principal.user_id, &body)
        .await?;
    Ok(Json(quote))
}

pub async fn checkout(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    headers: HeaderMap,
    ValidatedJson(body): ValidatedJson<CheckoutRequest>,
) -> Result<(StatusCode, Json<CheckoutResponse>), ApiError> {
    require_permission(&state, &principal, "checkout:create").await?;
    let idemp_key = header_str(&headers, "idempotency-key");
    let audit = build_audit_context(Some(&principal), &meta);
    let resp = state
        .checkout_service
        .checkout(principal.user_id, idemp_key.as_deref(), &body, &audit)
        .await?;
    let status = if resp.idempotent_replay {
        StatusCode::OK
    } else {
        StatusCode::CREATED
    };
    Ok((status, Json(resp)))
}

// ---------- Owner Coupons ----------

pub async fn list_owner_coupons(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<Vec<CouponDto>>, ApiError> {
    require_permission(&state, &principal, "owner:coupons_manage").await?;
    let coupons = state.coupon_service.list_all_coupons().await?;
    Ok(Json(coupons))
}

pub async fn create_coupon(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<CreateCouponRequest>,
) -> Result<(StatusCode, Json<CouponDto>), ApiError> {
    require_permission(&state, &principal, "owner:coupons_manage").await?;
    let audit = build_audit_context(Some(&principal), &meta);
    let created = state.coupon_service.create_coupon(&body, &audit).await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub async fn update_coupon(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
    ValidatedJson(body): ValidatedJson<UpdateCouponRequest>,
) -> Result<Json<CouponDto>, ApiError> {
    require_permission(&state, &principal, "owner:coupons_manage").await?;
    let coupon_id = parse_uuid_param(&id, "coupon_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    let updated = state
        .coupon_service
        .update_coupon(coupon_id, &body, &audit)
        .await?;
    Ok(Json(updated))
}

pub async fn delete_coupon(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
    meta: RequestMeta,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    require_permission(&state, &principal, "owner:coupons_manage").await?;
    let coupon_id = parse_uuid_param(&id, "coupon_id")?;
    let audit = build_audit_context(Some(&principal), &meta);
    state
        .coupon_service
        .delete_or_deactivate_coupon(coupon_id, &audit)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
