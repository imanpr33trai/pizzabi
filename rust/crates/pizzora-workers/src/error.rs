use pizzora_auth::AuthError;
use pizzora_config::ConfigError;
use pizzora_db::DbError;
use pizzora_payments::PaymentError;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum WorkerError {
    #[error("configuration error: {0}")]
    Config(#[from] ConfigError),
    #[error("database error: {0}")]
    Db(#[from] DbError),
    #[error("sqlx error: {0}")]
    Sqlx(#[from] sqlx::Error),
    #[error("auth error: {0}")]
    Auth(#[from] AuthError),
    #[error("payment error: {0}")]
    Payment(#[from] PaymentError),
    #[error("serialization error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("worker i/o error: {0}")]
    Io(#[from] std::io::Error),
    #[error("worker runtime error: {0}")]
    Runtime(String),
}
