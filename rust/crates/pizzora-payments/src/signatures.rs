use hmac::{Hmac, Mac};
use sha2::Sha256;
use subtle::ConstantTimeEq as _;

type HmacSha256 = Hmac<Sha256>;

pub fn compute_hmac_sha256_hex(payload: &[u8], secret: &str) -> String {
    let mut mac = HmacSha256::new_from_slice(secret.as_bytes())
        .expect("HMAC-SHA256 accepts keys of any length");
    mac.update(payload);
    hex::encode(mac.finalize().into_bytes())
}

pub fn verify_checkout_signature(
    gateway_order_id: &str,
    gateway_payment_id: &str,
    signature: &str,
    key_secret: &str,
) -> bool {
    if gateway_order_id.trim().is_empty()
        || gateway_payment_id.trim().is_empty()
        || signature.trim().is_empty()
        || key_secret.trim().is_empty()
    {
        return false;
    }

    let payload = format!("{gateway_order_id}|{gateway_payment_id}");
    let expected_hex = compute_hmac_sha256_hex(payload.as_bytes(), key_secret);
    let provided_hex = signature.trim().to_ascii_lowercase();
    constant_time_equals_ascii(&expected_hex, &provided_hex)
}

pub fn verify_webhook_signature(
    raw_body: &[u8],
    signature_header: Option<&str>,
    webhook_secret: &str,
) -> bool {
    let Some(raw_sig) = signature_header else {
        return false;
    };
    if raw_sig.trim().is_empty() || webhook_secret.trim().is_empty() {
        return false;
    }

    let expected_hex = compute_hmac_sha256_hex(raw_body, webhook_secret);
    let provided_hex = raw_sig.trim().to_ascii_lowercase();
    constant_time_equals_ascii(&expected_hex, &provided_hex)
}

fn constant_time_equals_ascii(expected: &str, actual: &str) -> bool {
    let exp_bytes = expected.as_bytes();
    let act_bytes = actual.as_bytes();
    if exp_bytes.len() != act_bytes.len() {
        return false;
    }
    bool::from(exp_bytes.ct_eq(act_bytes))
}
