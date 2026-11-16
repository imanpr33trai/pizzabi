//! Environment configuration and validation parity (implemented in Phase 5).

use thiserror::Error;

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("missing required configuration: {0}")]
    Missing(&'static str),
}

pub fn crate_name() -> &'static str {
    env!("CARGO_PKG_NAME")
}
