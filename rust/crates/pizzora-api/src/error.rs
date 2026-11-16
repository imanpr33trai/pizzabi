use std::collections::BTreeMap;

use axum::http::{header, HeaderName, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use chrono::{SecondsFormat, Utc};
use pizzora_auth::AuthError;
use pizzora_config::ConfigError;
use pizzora_core::DomainError;
use pizzora_db::{new_uuid_v4, DbError};
use pizzora_payments::PaymentError;
use serde::{Deserialize, Serialize};
use tracing::error;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ApiErrorDetail {
    pub code: String,
    pub message: String,
    #[serde(default)]
    pub details: BTreeMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ApiErrorEnvelope {
    pub error: ApiErrorDetail,
    pub correlation_id: String,
    pub timestamp: String,
}

#[derive(Debug, Clone)]
pub struct ApiError {
    pub status: StatusCode,
    pub code: String,
    pub message: String,
    pub details: BTreeMap<String, String>,
    pub retry_after_secs: Option<u64>,
    pub correlation_id: Option<String>,
}

impl ApiError {
    pub fn new(status: StatusCode, code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            status,
            code: code.into(),
            message: message.into(),
            details: BTreeMap::new(),
            retry_after_secs: None,
            correlation_id: None,
        }
    }

    pub fn with_details(mut self, details: BTreeMap<String, String>) -> Self {
        self.details = details;
        self
    }

    pub fn with_detail(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.details.insert(key.into(), value.into());
        self
    }

    pub fn with_retry_after(mut self, secs: u64) -> Self {
        self.retry_after_secs = Some(secs);
        self
    }

    pub fn with_correlation_id(mut self, correlation_id: impl Into<String>) -> Self {
        self.correlation_id = Some(correlation_id.into());
        self
    }

    pub fn validation(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, code, message)
    }

    pub fn unauthorized(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(StatusCode::UNAUTHORIZED, code, message)
    }

    pub fn forbidden(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(StatusCode::FORBIDDEN, code, message)
    }

    pub fn not_found(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(StatusCode::NOT_FOUND, code, message)
    }

    pub fn conflict(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(StatusCode::CONFLICT, code, message)
    }

    pub fn unprocessable(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, code, message)
    }

    pub fn rate_limited(message: impl Into<String>, retry_after_secs: u64) -> Self {
        Self::new(StatusCode::TOO_MANY_REQUESTS, "RATE_LIMITED", message)
            .with_retry_after(retry_after_secs)
    }

    pub fn service_unavailable(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(StatusCode::SERVICE_UNAVAILABLE, code, message)
    }

    pub fn internal() -> Self {
        Self::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected server error occurred.",
        )
    }

    pub fn into_response_with_correlation(self, correlation_id: &str) -> Response {
        let envelope = ApiErrorEnvelope {
            error: ApiErrorDetail {
                code: self.code,
                message: self.message,
                details: self.details,
            },
            correlation_id: correlation_id.to_owned(),
            timestamp: Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true),
        };

        let mut resp = (self.status, Json(envelope)).into_response();
        if let Ok(cid_val) = HeaderValue::from_str(correlation_id) {
            resp.headers_mut()
                .insert(HeaderName::from_static("x-request-id"), cid_val);
        }
        if let Some(secs) = self.retry_after_secs {
            if let Ok(val) = HeaderValue::from_str(&secs.to_string()) {
                resp.headers_mut().insert(header::RETRY_AFTER, val);
            }
        }
        resp
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let resolved_cid = self
            .correlation_id
            .clone()
            .or_else(crate::middleware::current_request_correlation_id)
            .unwrap_or_else(|| new_uuid_v4().to_string());
        self.into_response_with_correlation(&resolved_cid)
    }
}

impl From<DomainError> for ApiError {
    fn from(value: DomainError) -> Self {
        match value {
            DomainError::Validation { code, message } => Self::validation(code, message),
            DomainError::Unprocessable { code, message } => Self::unprocessable(code, message),
            DomainError::Conflict { code, message } => Self::conflict(code, message),
            DomainError::InvalidObjectKey(message) => {
                Self::validation("INVALID_OBJECT_KEY", message)
            }
            other => Self::validation("VALIDATION_ERROR", other.to_string()),
        }
    }
}

impl From<DbError> for ApiError {
    fn from(value: DbError) -> Self {
        match value {
            DbError::Validation { code, message } => Self::validation(code, message),
            DbError::Unauthorized { code, message } => Self::unauthorized(code, message),
            DbError::Forbidden { code, message } => Self::forbidden(code, message),
            DbError::NotFound { code, message } => Self::not_found(code, message),
            DbError::Conflict { code, message } => Self::conflict(code, message),
            DbError::Unprocessable { code, message } => Self::unprocessable(code, message),
            DbError::Domain(domain) => Self::from(domain),
            other => {
                error!(error = %other, "Unhandled database layer error");
                Self::internal()
            }
        }
    }
}

impl From<AuthError> for ApiError {
    fn from(value: AuthError) -> Self {
        match value {
            AuthError::Validation { code, message } => Self::validation(code, message),
            AuthError::Unauthorized { code, message } => Self::unauthorized(code, message),
            AuthError::Forbidden { code, message } => Self::forbidden(code, message),
            AuthError::Conflict { code, message } => Self::conflict(code, message),
            AuthError::NotFound { code, message } => Self::not_found(code, message),
            AuthError::Db(db) => Self::from(db),
            other => {
                error!(error = %other, "Unhandled auth layer error");
                Self::internal()
            }
        }
    }
}

impl From<PaymentError> for ApiError {
    fn from(value: PaymentError) -> Self {
        match value {
            PaymentError::Validation { code, message } => Self::validation(code, message),
            PaymentError::Unauthorized { code, message } => Self::unauthorized(code, message),
            PaymentError::Forbidden { code, message } => Self::forbidden(code, message),
            PaymentError::NotFound { code, message } => Self::not_found(code, message),
            PaymentError::Conflict { code, message } => Self::conflict(code, message),
            PaymentError::Unprocessable { code, message } => Self::unprocessable(code, message),
            PaymentError::Db(db) => Self::from(db),
            PaymentError::Domain(domain) => Self::from(domain),
            PaymentError::Gateway(gw) => {
                error!(gateway_code = %gw.code, "Payment gateway failure");
                Self::new(
                    StatusCode::BAD_GATEWAY,
                    "GATEWAY_ERROR",
                    "Payment gateway communication failed.",
                )
            }
            other => {
                error!(error = %other, "Unhandled payment layer error");
                Self::internal()
            }
        }
    }
}

impl From<ConfigError> for ApiError {
    fn from(value: ConfigError) -> Self {
        error!(error = %value, "Configuration error surfaced during request");
        Self::internal()
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(value: sqlx::Error) -> Self {
        error!(error = %value, "Unhandled SQLx error");
        Self::internal()
    }
}

pub fn now_iso8601() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
}

pub fn format_iso8601(dt: chrono::DateTime<Utc>) -> String {
    dt.to_rfc3339_opts(SecondsFormat::Millis, true)
}
