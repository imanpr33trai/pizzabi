//! Canonical receipts, idempotent/conflict-safe payment retries, Razorpay gateway, and webhooks (Phase 6).

mod capture;
pub mod dto;
pub mod error;
mod failed_webhook;
pub mod gateway;
pub mod http_gateway;
pub mod in_memory_gateway;
mod outbox_dispatch;
pub mod receipts;
pub mod reconciliation;
mod refund_ops;
pub mod repo;
mod retry_and_verify;
pub mod service;
pub mod signatures;
mod status_map;
mod verify_client;
pub mod webhook;

pub use dto::{
    FailPaymentAttemptRequest, GatewayOrderResult, GatewayPaymentResult, GatewayRefundResult,
    OrderPaymentStatusResponse, OwnerRefundRequest, RefundDto, RetryPaymentRequest,
    VerifyRazorpayPaymentRequest, VerifyRazorpayPaymentResponse,
};
pub use error::{PaymentError, RazorpayGatewayError};
pub use gateway::{
    create_default_razorpay_gateway, BoxFuture, HttpRazorpayClient, InMemoryRazorpayGateway,
    RazorpayGateway, SandboxRazorpayClient, UnconfiguredRazorpayGateway,
};
pub use receipts::{
    compute_retry_fingerprint, format_razorpay_receipt, EXACT_RAZORPAY_RECEIPT_LEN,
    MAX_RAZORPAY_RECEIPT_LEN, RECEIPT_HEX_LEN, RECEIPT_PREFIX,
};
pub use reconciliation::RazorpayReconciliationService;
pub use repo::{
    insert_payment_event_idempotent, mark_refund_processed_and_update_payment, notify_owners,
    resolve_gateway_payment_id,
};
pub use service::PaymentService;
pub use signatures::{
    compute_hmac_sha256_hex, verify_checkout_signature, verify_webhook_signature,
};
pub use status_map::parse_refund_status;
pub use webhook::WebhookService;

pub fn crate_name() -> &'static str {
    let _ = pizzora_db::crate_name();
    env!("CARGO_PKG_NAME")
}

#[cfg(test)]
mod tests {
    use std::collections::{BTreeMap, HashMap};
    use std::path::PathBuf;

    use pizzora_config::{AppConfig, RazorpayConfig};
    use pizzora_core::PaymentMethod;
    use pizzora_db::new_uuid_v4;
    use uuid::Uuid;

    use super::*;

    #[derive(serde::Deserialize)]
    struct ReceiptVectorFile {
        max_length: usize,
        exact_length: usize,
        vectors: Vec<ReceiptVector>,
    }

    #[derive(serde::Deserialize)]
    struct ReceiptVector {
        order_id: String,
        key_or_attempt_id: String,
        expected_receipt: String,
    }

    fn golden_dir() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("..")
            .join("..")
            .join("tools")
            .join("golden-vectors")
    }

    #[test]
    fn format_razorpay_receipt_matches_golden_vectors_and_constraints() {
        let raw = std::fs::read_to_string(golden_dir().join("razorpay_receipts.json"))
            .expect("tools/golden-vectors/razorpay_receipts.json must exist");
        let parsed: ReceiptVectorFile =
            serde_json::from_str(&raw).expect("valid razorpay_receipts.json");
        assert!(!parsed.vectors.is_empty());

        for vector in parsed.vectors {
            let order_id = Uuid::parse_str(&vector.order_id).expect("valid vector UUID");
            let actual =
                format_razorpay_receipt(order_id, &vector.key_or_attempt_id).expect("receipt");
            assert_eq!(actual, vector.expected_receipt);
            assert_eq!(actual.len(), parsed.exact_length);
            assert!(actual.len() <= parsed.max_length);
            assert!(actual.starts_with(RECEIPT_PREFIX));
            assert!(actual[RECEIPT_PREFIX.len()..]
                .bytes()
                .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')));
        }
    }

    #[test]
    fn format_razorpay_receipt_rejects_blank_input() {
        let oid = new_uuid_v4();
        let err = format_razorpay_receipt(oid, "   \t  ").expect_err("blank input must fail");
        assert!(matches!(
            err,
            PaymentError::Validation {
                code: "INVALID_RECEIPT_INPUT",
                ..
            }
        ));
    }

    #[test]
    fn retry_fingerprint_is_deterministic_and_sensitive_to_parameters() {
        let order_id = new_uuid_v4();
        let payment_id = new_uuid_v4();

        let fp_base = compute_retry_fingerprint(
            order_id,
            payment_id,
            PaymentMethod::Razorpay,
            45_000,
            Some("  UPI retry  "),
        );
        let fp_trimmed = compute_retry_fingerprint(
            order_id,
            payment_id,
            PaymentMethod::Razorpay,
            45_000,
            Some("UPI retry"),
        );
        let fp_different_reason = compute_retry_fingerprint(
            order_id,
            payment_id,
            PaymentMethod::Razorpay,
            45_000,
            Some("Card retry"),
        );
        let fp_different_amount = compute_retry_fingerprint(
            order_id,
            payment_id,
            PaymentMethod::Razorpay,
            46_000,
            Some("UPI retry"),
        );

        assert_eq!(fp_base, fp_trimmed);
        assert_ne!(fp_base, fp_different_reason);
        assert_ne!(fp_base, fp_different_amount);
        assert_eq!(fp_base.len(), 64);
    }

    #[test]
    fn hmac_verifiers_validate_checkout_and_exact_raw_webhook_bytes() {
        let key_secret = "rzp_secret_unit_test_value";
        let wh_secret = "rzp_webhook_unit_test_value";

        let checkout_sig = compute_hmac_sha256_hex(b"order_abc|pay_xyz", key_secret);
        assert!(verify_checkout_signature(
            "order_abc",
            "pay_xyz",
            &checkout_sig,
            key_secret
        ));
        assert!(verify_checkout_signature(
            "order_abc",
            "pay_xyz",
            &format!("  {}  ", checkout_sig.to_ascii_uppercase()),
            key_secret
        ));
        assert!(!verify_checkout_signature(
            "order_abc",
            "pay_tampered",
            &checkout_sig,
            key_secret
        ));
        assert!(!verify_checkout_signature(
            "order_abc",
            "pay_xyz",
            &checkout_sig,
            ""
        ));

        let raw_body =
            br#"{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_1"}}}}"#;
        let wh_sig = compute_hmac_sha256_hex(raw_body, wh_secret);
        assert!(verify_webhook_signature(raw_body, Some(&wh_sig), wh_secret));

        let mut modified_body = raw_body.to_vec();
        modified_body.push(b' ');
        assert!(!verify_webhook_signature(
            &modified_body,
            Some(&wh_sig),
            wh_secret
        ));
        assert!(!verify_webhook_signature(raw_body, None, wh_secret));
        assert!(!verify_webhook_signature(raw_body, Some(&wh_sig), " "));
    }

    #[test]
    fn http_client_and_verify_request_redact_secrets_in_debug() {
        let cfg = RazorpayConfig::new(
            "rzp_test_public_key",
            "super_secret_key_never_logged",
            "super_secret_webhook_never_logged",
            "https://api.razorpay.com/v1",
        );
        let client = HttpRazorpayClient::new(&cfg).expect("client builds");
        let client_dbg = format!("{client:?}");
        assert!(client_dbg.contains("rzp_test_public_key"));
        assert!(!client_dbg.contains("super_secret_key_never_logged"));

        let req = VerifyRazorpayPaymentRequest {
            order_id: new_uuid_v4().to_string(),
            razorpay_order_id: "order_123".to_owned(),
            razorpay_payment_id: "pay_123".to_owned(),
            razorpay_signature: "deadbeef_secret_sig_999".to_owned(),
        };
        let req_dbg = format!("{req:?}");
        assert!(req_dbg.contains("[REDACTED]"));
        assert!(!req_dbg.contains("deadbeef_secret_sig_999"));
    }

    #[tokio::test]
    async fn gateway_factory_and_in_memory_double_support_idempotency_and_fault_injection() {
        let dev_cfg = AppConfig::from_env_map(&HashMap::new()).expect("dev config");
        let gw = create_default_razorpay_gateway(&dev_cfg).expect("sandbox gateway in dev");
        assert_eq!(gw.public_key_id(), "rzp_test_sandbox");

        let sbx_order = gw
            .create_order(50_000, "INR", "rcpt_0123456789abcdef", BTreeMap::new())
            .await
            .expect("sandbox order");
        assert!(sbx_order.gateway_order_id.starts_with("order_sbx_"));

        let mem = InMemoryRazorpayGateway::new("rzp_test_mem");
        let ord1 = mem
            .create_order(45_000, "INR", "rcpt_same", BTreeMap::new())
            .await
            .expect("order 1");
        let ord2 = mem
            .create_order(45_000, "INR", "rcpt_same", BTreeMap::new())
            .await
            .expect("dedup order");
        assert_eq!(ord1.gateway_order_id, ord2.gateway_order_id);
        assert_eq!(mem.created_orders().len(), 1);

        mem.set_fail_next_refunds(1, true);
        let transient_err = mem
            .create_refund("pay_1", 45_000, "idemp_1", BTreeMap::new())
            .await
            .expect_err("first call fails transiently");
        assert!(transient_err.is_transient);
        assert_eq!(transient_err.status_code, Some(503));

        mem.set_simulate_post_gateway_crash_on_create_refund(true);
        let crash_err = mem
            .create_refund("pay_1", 45_000, "idemp_1", BTreeMap::new())
            .await
            .expect_err("post-gateway crash returns timeout");
        assert_eq!(crash_err.code, "SIMULATED_RESPONSE_TIMEOUT");

        let fetched = mem
            .fetch_refunds_for_payment("pay_1")
            .await
            .expect("fetch refunds");
        assert_eq!(fetched.len(), 1);
        assert_eq!(
            fetched[0].notes.get("idempotency_key").map(String::as_str),
            Some("idemp_1")
        );
    }
}
