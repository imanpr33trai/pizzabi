//! Canonical Razorpay receipt and retry-fingerprint generator.
//!
//! Formula (byte-identical with Kotlin `RazorpayReceiptFormatter`):
//!   `rcpt_` + lowercase hex SHA-256("pizzora:attempt:" + lowercase_uuid(order_id) + ":" + trimmed_key_or_attempt_id)[0..32]
//! Total length is always 37 ASCII characters (<= Razorpay's 40-char limit).

use pizzora_core::PaymentMethod;
use sha2::{Digest as _, Sha256};
use uuid::Uuid;

use crate::error::PaymentError;

pub const RECEIPT_PREFIX: &str = "rcpt_";
pub const RECEIPT_HEX_LEN: usize = 32;
pub const MAX_RAZORPAY_RECEIPT_LEN: usize = 40;
pub const EXACT_RAZORPAY_RECEIPT_LEN: usize = 37;

pub fn format_razorpay_receipt(
    order_id: Uuid,
    key_or_attempt_id: &str,
) -> Result<String, PaymentError> {
    let trimmed = key_or_attempt_id.trim();
    if trimmed.is_empty() {
        return Err(PaymentError::Validation {
            code: "INVALID_RECEIPT_INPUT",
            message: "key_or_attempt_id must not be blank".to_owned(),
        });
    }

    let order_lower = order_id.hyphenated().to_string().to_ascii_lowercase();
    let canonical_input = format!("pizzora:attempt:{order_lower}:{trimmed}");
    let digest = Sha256::digest(canonical_input.as_bytes());
    let full_hex = hex::encode(digest);
    let receipt = format!("{RECEIPT_PREFIX}{}", &full_hex[..RECEIPT_HEX_LEN]);
    debug_assert_eq!(receipt.len(), EXACT_RAZORPAY_RECEIPT_LEN);
    debug_assert!(receipt.len() <= MAX_RAZORPAY_RECEIPT_LEN);
    Ok(receipt)
}

pub fn compute_retry_fingerprint(
    order_id: Uuid,
    payment_id: Uuid,
    method: PaymentMethod,
    amount_paise: i64,
    reason: Option<&str>,
) -> String {
    let normalized_reason = reason.map(str::trim).unwrap_or("");
    let order_lower = order_id.hyphenated().to_string().to_ascii_lowercase();
    let payment_lower = payment_id.hyphenated().to_string().to_ascii_lowercase();
    let method_str = match method {
        PaymentMethod::Razorpay => "RAZORPAY",
        PaymentMethod::Cod => "COD",
    };
    let canonical =
        format!("{order_lower}|{payment_lower}|{method_str}|{amount_paise}|{normalized_reason}");
    hex::encode(Sha256::digest(canonical.as_bytes()))
}
