use axum::extract::rejection::JsonRejection;
use axum::extract::{FromRequest, FromRequestParts, Request};
use axum::http::header;
use axum::http::request::Parts;
use axum::Json;
use pizzora_auth::PizzoraPrincipal;
use pizzora_db::{user_role_as_str, AuditContext};
use serde::de::DeserializeOwned;
use uuid::Uuid;

use crate::error::ApiError;
use crate::middleware::{ClientIp, CorrelationId};
use crate::state::AppState;

#[derive(Debug, Clone)]
pub struct RequestMeta {
    pub correlation_id: String,
    pub client_ip: String,
}

impl FromRequestParts<AppState> for RequestMeta {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        _state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let correlation_id = parts
            .extensions
            .get::<CorrelationId>()
            .map(|c| c.0.clone())
            .unwrap_or_else(|| pizzora_db::new_uuid_v4().to_string());
        let client_ip = parts
            .extensions
            .get::<ClientIp>()
            .map(|ip| ip.0.clone())
            .unwrap_or_else(|| "unknown".to_owned());
        Ok(Self {
            correlation_id,
            client_ip,
        })
    }
}

#[derive(Debug, Clone)]
pub struct AuthPrincipal(pub PizzoraPrincipal);

impl FromRequestParts<AppState> for AuthPrincipal {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let raw_auth = parts
            .headers
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| {
                ApiError::unauthorized("UNAUTHORIZED", "Missing Bearer access token.")
            })?;

        let token = raw_auth
            .strip_prefix("Bearer ")
            .or_else(|| raw_auth.strip_prefix("bearer "))
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .ok_or_else(|| {
                ApiError::unauthorized("UNAUTHORIZED", "Invalid Authorization header format.")
            })?;

        let verified = state
            .auth_service
            .jwt_provider()
            .verify_access_token(token)
            .ok_or_else(|| {
                ApiError::unauthorized("INVALID_TOKEN", "Access token is invalid or expired.")
            })?;

        let active = state
            .authz_service
            .is_session_active(verified.user_id, verified.session_id)
            .await
            .map_err(ApiError::from)?;
        if !active {
            return Err(ApiError::unauthorized(
                "SESSION_REVOKED",
                "Session is no longer active.",
            ));
        }

        Ok(Self(PizzoraPrincipal {
            user_id: verified.user_id,
            session_id: verified.session_id,
            role: verified.role,
        }))
    }
}

pub struct ValidatedJson<T>(pub T);

impl<T> FromRequest<AppState> for ValidatedJson<T>
where
    T: DeserializeOwned,
{
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &AppState) -> Result<Self, Self::Rejection> {
        match Json::<T>::from_request(req, state).await {
            Ok(Json(val)) => Ok(Self(val)),
            Err(JsonRejection::JsonDataError(_)) | Err(JsonRejection::JsonSyntaxError(_)) => Err(
                ApiError::validation("MALFORMED_REQUEST", "Invalid JSON payload."),
            ),
            Err(JsonRejection::MissingJsonContentType(_)) => Err(ApiError::validation(
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json.",
            )),
            Err(_) => Err(ApiError::validation(
                "MALFORMED_REQUEST",
                "Unable to read JSON request body.",
            )),
        }
    }
}

pub struct OptionalJson<T>(pub Option<T>);

impl<T> FromRequest<AppState> for OptionalJson<T>
where
    T: DeserializeOwned,
{
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &AppState) -> Result<Self, Self::Rejection> {
        let content_type = req
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        let bytes = axum::body::Bytes::from_request(req, state)
            .await
            .map_err(|_| {
                ApiError::validation("MALFORMED_REQUEST", "Unable to read JSON request body.")
            })?;

        if bytes.iter().all(|b| b.is_ascii_whitespace()) {
            return Ok(Self(None));
        }

        if let Some(ct) = content_type.as_deref() {
            let mime = ct.split(';').next().unwrap_or("").trim();
            if !mime.eq_ignore_ascii_case("application/json") && !mime.ends_with("+json") {
                return Err(ApiError::validation(
                    "UNSUPPORTED_MEDIA_TYPE",
                    "Content-Type must be application/json.",
                ));
            }
        }

        let parsed = serde_json::from_slice::<T>(&bytes)
            .map_err(|_| ApiError::validation("MALFORMED_REQUEST", "Invalid JSON payload."))?;
        Ok(Self(Some(parsed)))
    }
}

pub async fn authenticate_ws_parts(
    parts: &Parts,
    state: &AppState,
    ticket: Option<&str>,
) -> Result<PizzoraPrincipal, ApiError> {
    if let Some(raw_ticket) = ticket.map(str::trim).filter(|s| !s.is_empty()) {
        let verified = state
            .ws_ticket_service
            .consume_ticket(raw_ticket)
            .await
            .map_err(ApiError::from)?
            .ok_or_else(|| {
                ApiError::unauthorized(
                    "INVALID_WS_TICKET",
                    "WebSocket ticket is invalid or expired.",
                )
            })?;
        let active = state
            .authz_service
            .is_session_active(verified.user_id, verified.session_id)
            .await
            .map_err(ApiError::from)?;
        if !active {
            return Err(ApiError::unauthorized(
                "SESSION_REVOKED",
                "Session is no longer active.",
            ));
        }
        return Ok(PizzoraPrincipal {
            user_id: verified.user_id,
            session_id: verified.session_id,
            role: verified.role,
        });
    }

    let raw_auth = parts
        .headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| {
            ApiError::unauthorized(
                "UNAUTHORIZED",
                "Missing WebSocket ticket or Bearer access token.",
            )
        })?;

    let token = raw_auth
        .strip_prefix("Bearer ")
        .or_else(|| raw_auth.strip_prefix("bearer "))
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            ApiError::unauthorized("UNAUTHORIZED", "Invalid Authorization header format.")
        })?;

    let verified = state
        .auth_service
        .jwt_provider()
        .verify_access_token(token)
        .ok_or_else(|| {
            ApiError::unauthorized("INVALID_TOKEN", "Access token is invalid or expired.")
        })?;

    let active = state
        .authz_service
        .is_session_active(verified.user_id, verified.session_id)
        .await
        .map_err(ApiError::from)?;
    if !active {
        return Err(ApiError::unauthorized(
            "SESSION_REVOKED",
            "Session is no longer active.",
        ));
    }

    Ok(PizzoraPrincipal {
        user_id: verified.user_id,
        session_id: verified.session_id,
        role: verified.role,
    })
}

pub async fn require_permission(
    state: &AppState,
    principal: &PizzoraPrincipal,
    permission: &str,
) -> Result<(), ApiError> {
    state
        .authz_service
        .require_permission(principal.role, permission)
        .await
        .map_err(ApiError::from)
}

pub fn build_audit_context(
    principal: Option<&PizzoraPrincipal>,
    meta: &RequestMeta,
) -> AuditContext {
    let (actor_user_id, actor_role) = match principal {
        Some(p) => (Some(p.user_id), user_role_as_str(p.role)),
        None => (None, "SYSTEM"),
    };
    AuditContext {
        actor_user_id,
        actor_role: actor_role.to_owned(),
        correlation_id: meta.correlation_id.clone(),
        ip_address: (!meta.client_ip.is_empty() && meta.client_ip != "unknown")
            .then(|| meta.client_ip.clone()),
    }
}

pub fn parse_uuid_param(raw: &str, field: &str) -> Result<Uuid, ApiError> {
    Uuid::parse_str(raw.trim())
        .map_err(|_| ApiError::validation("INVALID_UUID", format!("Invalid {field} UUID: {raw}")))
}

pub fn header_str(headers: &axum::http::HeaderMap, name: &str) -> Option<String> {
    headers
        .get(name)
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
}
