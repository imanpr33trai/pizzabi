use pizzora_db::DbError;
use thiserror::Error;

#[derive(Debug, Error, Clone, PartialEq, Eq)]
#[error("{code}: {message}")]
pub struct RazorpayGatewayError {
    pub is_transient: bool,
    pub status_code: Option<u16>,
    pub code: String,
    pub message: String,
}

impl RazorpayGatewayError {
    pub fn transient(
        status_code: Option<u16>,
        code: impl Into<String>,
        message: impl Into<String>,
    ) -> Self {
        Self {
            is_transient: true,
            status_code,
            code: code.into(),
            message: message.into(),
        }
    }

    pub fn permanent(
        status_code: Option<u16>,
        code: impl Into<String>,
        message: impl Into<String>,
    ) -> Self {
        Self {
            is_transient: false,
            status_code,
            code: code.into(),
            message: message.into(),
        }
    }
}

#[derive(Debug, Error)]
pub enum PaymentError {
    #[error("{code}: {message}")]
    Validation { code: &'static str, message: String },

    #[error("{code}: {message}")]
    Unauthorized { code: &'static str, message: String },

    #[error("{code}: {message}")]
    Forbidden { code: &'static str, message: String },

    #[error("{code}: {message}")]
    NotFound { code: &'static str, message: String },

    #[error("{code}: {message}")]
    Conflict { code: &'static str, message: String },

    #[error("{code}: {message}")]
    Unprocessable { code: &'static str, message: String },

    #[error("gateway error: {0}")]
    Gateway(#[from] RazorpayGatewayError),

    #[error("database error: {0}")]
    Db(#[from] DbError),

    #[error("sqlx error: {0}")]
    Sqlx(#[from] sqlx::Error),

    #[error("domain error: {0}")]
    Domain(#[from] pizzora_core::DomainError),
}
