#![recursion_limit = "256"]

//! Standalone Axum 0.8 HTTP + WebSocket API server runtime (`pizzora-api`).
//! Strictly process-independent: zero worker loop or `pizzora-workers` coupling.

pub mod dto;
pub mod error;
pub mod extractors;
pub mod health;
pub mod metrics;
pub mod middleware;
pub mod openapi;
pub mod rate_limit;
pub mod router;
pub mod routes;
pub mod services;
pub mod state;
pub mod storage;

pub use router::build_router;
pub use state::AppState;

pub fn crate_name() -> &'static str {
    env!("CARGO_PKG_NAME")
}

#[cfg(test)]
mod unit_tests;
