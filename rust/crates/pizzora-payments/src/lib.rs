//! Canonical receipts, idempotent/conflict-safe payment retries, Razorpay gateway, and webhooks (Phase 6).

pub fn crate_name() -> &'static str {
    let _ = pizzora_db::crate_name();
    env!("CARGO_PKG_NAME")
}
