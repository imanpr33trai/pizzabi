use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum DomainError {
    #[error("{0} must be non-negative")]
    NegativeAmount(&'static str),

    #[error("quantity must be between 1 and {max}, got {actual}")]
    QuantityOutOfRange { actual: i32, max: i32 },

    #[error("quantity must be positive, got {0}")]
    NonPositiveQuantity(i32),

    #[error("{field} must be between {min} and {max}, got {value}")]
    RangeViolation {
        field: &'static str,
        value: i64,
        min: i64,
        max: i64,
    },

    #[error("{0} must be positive")]
    NonPositiveValue(&'static str),

    #[error("arithmetic overflow in {0}")]
    ArithmeticOverflow(&'static str),

    #[error("{code}: {message}")]
    Validation { code: &'static str, message: String },

    #[error("{code}: {message}")]
    Unprocessable { code: &'static str, message: String },

    #[error("{code}: {message}")]
    Conflict { code: &'static str, message: String },

    #[error("invalid storage object key: {0}")]
    InvalidObjectKey(String),
}
