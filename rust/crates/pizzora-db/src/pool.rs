use std::str::FromStr;
use std::time::Duration;

use pizzora_config::DatabaseConfig;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions, PgSslMode};
use sqlx::PgPool;

use crate::error::DbError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DependencyHealthStatus {
    Up,
    Down,
}

pub fn build_pg_connect_options(config: &DatabaseConfig) -> Result<PgConnectOptions, DbError> {
    let mut opts = PgConnectOptions::new()
        .host(&config.url.host)
        .port(config.url.port)
        .database(&config.url.database)
        .username(&config.url.username)
        .password(config.url.password());

    if let Some(ssl_mode) = &config.url.ssl_mode {
        let parsed = PgSslMode::from_str(ssl_mode).map_err(|_| DbError::Validation {
            code: "INVALID_DATABASE_SSLMODE",
            message: format!("Unsupported PostgreSQL sslmode '{ssl_mode}'"),
        })?;
        opts = opts.ssl_mode(parsed);
    }

    if let Some((_, app_name)) = config
        .url
        .query_params
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case("application_name"))
    {
        opts = opts.application_name(app_name);
    }

    Ok(opts)
}

pub fn create_pg_pool_options(config: &DatabaseConfig) -> PgPoolOptions {
    PgPoolOptions::new()
        .max_connections(config.max_pool_size)
        .min_connections(config.min_connections)
        .acquire_timeout(Duration::from_millis(config.connect_timeout_ms))
}

pub async fn connect_pg_pool(config: &DatabaseConfig) -> Result<PgPool, DbError> {
    let connect_opts = build_pg_connect_options(config)?;
    let pool = create_pg_pool_options(config)
        .connect_with(connect_opts)
        .await?;
    Ok(pool)
}

pub fn connect_pg_pool_lazy(config: &DatabaseConfig) -> Result<PgPool, DbError> {
    let connect_opts = build_pg_connect_options(config)?;
    Ok(create_pg_pool_options(config).connect_lazy_with(connect_opts))
}

pub async fn probe_postgres(pool: &PgPool) -> DependencyHealthStatus {
    let check = tokio::time::timeout(Duration::from_secs(2), async {
        sqlx::query_scalar::<_, i32>("SELECT 1")
            .fetch_one(pool)
            .await
    })
    .await;

    match check {
        Ok(Ok(1)) => DependencyHealthStatus::Up,
        _ => DependencyHealthStatus::Down,
    }
}
