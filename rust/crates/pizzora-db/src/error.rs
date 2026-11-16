use std::borrow::Cow;

use thiserror::Error;
use uuid::{Builder, Uuid};

#[derive(Debug, Error)]
pub enum DbError {
    #[error("FLYWAY_CHECKSUM_MISMATCH: migration version {version} ({script}) expected checksum {expected}, found {actual}")]
    MigrationChecksumMismatch {
        version: String,
        script: String,
        expected: i32,
        actual: i32,
    },

    #[error("FLYWAY_HISTORY_INVALID: {0}")]
    FlywayHistoryInvalid(String),

    #[error("CATALOG_INVARIANT_VIOLATION: {0}")]
    CatalogInvariantViolation(String),

    #[error("UNSAFE_DATABASE_STATE: {0}")]
    UnsafeDatabaseState(String),

    #[error("advisory lock failure: {0}")]
    AdvisoryLock(String),

    #[error("SQLX_CHECKSUM_MISMATCH: baseline migration {version} checksum mismatch")]
    SqlxChecksumMismatch { version: i64 },

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
    Unprocessable {
        code: &'static str,
        message: String,
    },

    #[error("database error: {0}")]
    Sqlx(#[from] sqlx::Error),

    #[error("migration error: {0}")]
    Migrate(#[from] sqlx::migrate::MigrateError),

    #[error("domain error: {0}")]
    Domain(#[from] pizzora_core::DomainError),

    #[error("cache error: {0}")]
    Cache(String),
}

impl DbError {
    pub fn is_unique_violation(err: &sqlx::Error) -> bool {
        err.as_database_error()
            .and_then(|db_err| db_err.code())
            .is_some_and(|code| code == Cow::Borrowed("23505"))
    }
}

pub fn new_uuid_v4() -> Uuid {
    Builder::from_random_bytes(rand::random::<[u8; 16]>()).into_uuid()
}
