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

    // Phase 8 modifier validation errors
    #[error("{code}: {message}")]
    CheeseUnavailable { code: &'static str, message: String },

    #[error("{code}: {message}")]
    SauceUnavailable { code: &'static str, message: String },

    #[error("{code}: {message}")]
    StandardToppingUnavailable { code: &'static str, message: String },

    #[error("{code}: {message}")]
    HalfAndHalfUnavailable { code: &'static str, message: String },

    #[error("{code}: {message}")]
    HalfAndHalfToppingLimitExceeded { code: &'static str, message: String },

    #[error("{code}: {message}")]
    HalfAndHalfLeftRightOverlap { code: &'static str, message: String },

    #[error("{code}: {message}")]
    StandardToppingNotInProduct { code: &'static str, message: String },

    #[error("{code}: {message}")]
    CheeseNotInProduct { code: &'static str, message: String },

    #[error("{code}: {message}")]
    SauceNotInProduct { code: &'static str, message: String },

    #[error("{code}: {message}")]
    HalfAndHalfConfigNotInProduct { code: &'static str, message: String },
}
