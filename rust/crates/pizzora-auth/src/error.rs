use std::fmt;

use pizzora_db::DbError;
use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EmailDeliveryError {
    pub is_transient: bool,
    pub code: String,
    pub sanitized_message: String,
}

impl fmt::Display for EmailDeliveryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.code, self.sanitized_message)
    }
}

impl std::error::Error for EmailDeliveryError {}

#[derive(Debug, Error)]
pub enum AuthError {
    #[error("{code}: {message}")]
    Validation { code: &'static str, message: String },

    #[error("{code}: {message}")]
    Unauthorized { code: &'static str, message: String },

    #[error("{code}: {message}")]
    Forbidden { code: &'static str, message: String },

    #[error("{code}: {message}")]
    Conflict { code: &'static str, message: String },

    #[error("{code}: {message}")]
    NotFound { code: &'static str, message: String },

    #[error("cryptographic failure: {0}")]
    Crypto(String),

    #[error("email delivery failure: {0}")]
    Email(#[from] EmailDeliveryError),

    #[error("configuration error: {0}")]
    Config(String),

    #[error("database error: {0}")]
    Db(DbError),

    #[error("sqlx error: {0}")]
    Sqlx(#[from] sqlx::Error),
}

impl From<DbError> for AuthError {
    fn from(value: DbError) -> Self {
        match value {
            DbError::Validation { code, message } => Self::Validation { code, message },
            DbError::Unauthorized { code, message } => Self::Unauthorized { code, message },
            DbError::Forbidden { code, message } => Self::Forbidden { code, message },
            DbError::Conflict { code, message } => Self::Conflict { code, message },
            DbError::NotFound { code, message } => Self::NotFound { code, message },
            other => Self::Db(other),
        }
    }
}
