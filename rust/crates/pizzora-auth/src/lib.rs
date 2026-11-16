//! Identity, RBAC, AEAD outbox token envelope crypto, and SMTP delivery (Phase 5).

pub fn crate_name() -> &'static str {
    let _ = pizzora_db::crate_name();
    env!("CARGO_PKG_NAME")
}
