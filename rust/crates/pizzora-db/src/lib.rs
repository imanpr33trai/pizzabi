//! Shared persistence, Redis, advisory-locked Flyway->SQLx migrator, and transactional services (Phase 5).

pub fn crate_name() -> &'static str {
    let _ = pizzora_config::crate_name();
    env!("CARGO_PKG_NAME")
}
