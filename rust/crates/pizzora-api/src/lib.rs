//! Standalone API runtime (:8080) implemented in Phase 7.

pub fn crate_name() -> &'static str {
    let _ = pizzora_auth::crate_name();
    let _ = pizzora_payments::crate_name();
    env!("CARGO_PKG_NAME")
}
