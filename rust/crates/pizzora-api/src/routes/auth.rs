use axum::extract::State;
use axum::http::StatusCode;
use axum::Json;
use pizzora_auth::{
    AuthTokensResponse, ForgotPasswordRequest, LoginRequest, MessageResponse, RefreshTokenRequest,
    RegisterRequest, ResendVerificationRequest, ResetPasswordRequest, VerifyEmailRequest,
    WsTicketResponse,
};

use crate::error::ApiError;
use crate::extractors::{AuthPrincipal, RequestMeta, ValidatedJson};
use crate::rate_limit::RateLimitDecision;
use crate::state::AppState;

async fn enforce_ip_rate_limit(
    state: &AppState,
    key: &str,
    max_requests: u32,
    window_secs: u64,
    group_label: &str,
) -> Result<(), ApiError> {
    match state
        .rate_limiter
        .check(key, max_requests, window_secs)
        .await
    {
        RateLimitDecision::Allowed => Ok(()),
        RateLimitDecision::Rejected { retry_after_secs } => Err(ApiError::rate_limited(
            format!("Too many requests for '{group_label}'. Please try again later."),
            retry_after_secs,
        )
        .with_detail("group", group_label)
        .with_detail("retry_after_seconds", retry_after_secs.to_string())),
    }
}

pub async fn register(
    State(state): State<AppState>,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<RegisterRequest>,
) -> Result<(StatusCode, Json<MessageResponse>), ApiError> {
    enforce_ip_rate_limit(
        &state,
        &format!("auth:register:{}", meta.client_ip),
        15,
        60,
        "auth_register",
    )
    .await?;

    let resp = state.auth_service.register(&body).await?;
    Ok((StatusCode::CREATED, Json(resp)))
}

pub async fn verify_email(
    State(state): State<AppState>,
    ValidatedJson(body): ValidatedJson<VerifyEmailRequest>,
) -> Result<Json<MessageResponse>, ApiError> {
    let resp = state.auth_service.verify_email(&body).await?;
    Ok(Json(resp))
}

pub async fn resend_verification(
    State(state): State<AppState>,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<ResendVerificationRequest>,
) -> Result<(StatusCode, Json<MessageResponse>), ApiError> {
    enforce_ip_rate_limit(
        &state,
        &format!("auth:resend:{}", meta.client_ip),
        10,
        60,
        "auth_resend",
    )
    .await?;

    let resp = state.auth_service.resend_verification(&body).await?;
    Ok((StatusCode::ACCEPTED, Json(resp)))
}

pub async fn login(
    State(state): State<AppState>,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<LoginRequest>,
) -> Result<Json<AuthTokensResponse>, ApiError> {
    enforce_ip_rate_limit(
        &state,
        &format!("auth:login:{}", meta.client_ip),
        20,
        60,
        "auth_login",
    )
    .await?;

    let ip_opt = (!meta.client_ip.is_empty() && meta.client_ip != "unknown")
        .then_some(meta.client_ip.as_str());
    let resp = state.auth_service.login(&body, ip_opt).await?;
    Ok(Json(resp))
}

pub async fn refresh(
    State(state): State<AppState>,
    ValidatedJson(body): ValidatedJson<RefreshTokenRequest>,
) -> Result<Json<AuthTokensResponse>, ApiError> {
    let resp = state.auth_service.refresh(&body).await?;
    Ok(Json(resp))
}

pub async fn forgot_password(
    State(state): State<AppState>,
    meta: RequestMeta,
    ValidatedJson(body): ValidatedJson<ForgotPasswordRequest>,
) -> Result<(StatusCode, Json<MessageResponse>), ApiError> {
    enforce_ip_rate_limit(
        &state,
        &format!("auth:forgot:{}", meta.client_ip),
        10,
        60,
        "auth_forgot",
    )
    .await?;

    let resp = state.auth_service.forgot_password(&body).await?;
    Ok((StatusCode::ACCEPTED, Json(resp)))
}

pub async fn reset_password(
    State(state): State<AppState>,
    ValidatedJson(body): ValidatedJson<ResetPasswordRequest>,
) -> Result<Json<MessageResponse>, ApiError> {
    let resp = state.auth_service.reset_password(&body).await?;
    Ok(Json(resp))
}

pub async fn logout(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<MessageResponse>, ApiError> {
    let resp = state.auth_service.logout(principal.session_id).await?;
    Ok(Json(resp))
}

pub async fn mint_ws_ticket(
    State(state): State<AppState>,
    AuthPrincipal(principal): AuthPrincipal,
) -> Result<Json<WsTicketResponse>, ApiError> {
    let resp = state.ws_ticket_service.mint_ticket(&principal).await?;
    Ok(Json(resp))
}
