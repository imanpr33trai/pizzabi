use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use pizzora_config::AppConfig;
use pizzora_db::{
    connect_pg_pool, run_locked_handoff, verify_catalog_invariants, EphemeralCacheStore, RedisStore,
};
use pizzora_workers::WorkerRuntime;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("pizzora-workers fatal error: {err}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let args: Vec<String> = std::env::args().collect();
    let migrate_only = args.iter().any(|a| a == "--migrate-only");

    let config = AppConfig::from_env()?;
    init_tracing(config.is_production());

    let pool = connect_pg_pool(&config.database).await?;

    if migrate_only {
        let outcome = run_locked_handoff(&pool).await?;
        info!(
            mode = ?outcome.mode,
            backend_pid = outcome.backend_pid,
            applied_or_verified_versions = ?outcome.applied_or_verified_versions,
            "Pre-deploy migration step completed from worker binary"
        );
        pool.close().await;
        return Ok(());
    }

    if config.database.run_migrations_on_startup {
        let outcome = run_locked_handoff(&pool).await?;
        info!(
            mode = ?outcome.mode,
            backend_pid = outcome.backend_pid,
            applied_or_verified_versions = ?outcome.applied_or_verified_versions,
            "Startup database migrations finished"
        );
    } else {
        let mut conn = pool.acquire().await?;
        verify_catalog_invariants(&mut conn).await?;
    }

    let redis_store = Arc::new(RedisStore::new(config.redis.url(), config.redis.timeout_ms));
    if let Err(err) = redis_store.connect_now().await {
        if config.redis.required {
            error!(error = %err, "Redis is required (REDIS_REQUIRED=true) and unavailable");
            return Err(Box::new(err));
        }
        warn!(error = %err, "Redis unavailable on startup; continuing with PostgreSQL/in-memory fallbacks");
    }

    let bind_addr: SocketAddr = format!("0.0.0.0:{}", config.worker.health_port).parse()?;
    let environment = config.environment.clone();
    let health_port = config.worker.health_port;
    let shutdown_timeout = Duration::from_secs(config.shutdown_timeout_secs);
    let cache: Arc<dyn EphemeralCacheStore> = redis_store;

    let runtime = WorkerRuntime::with_default_providers(config, pool.clone(), cache)?;
    let loop_handles = runtime.spawn_background_loops();
    let app = runtime.build_http_router();
    let listener = tokio::net::TcpListener::bind(bind_addr).await?;

    info!(
        environment = %environment,
        health_port,
        "PIZZORA standalone Rust worker health server listening"
    );

    let shutdown_token = runtime.shutdown.clone();
    let serve_result = axum::serve(listener, app)
        .with_graceful_shutdown(async move {
            wait_for_shutdown_signal().await;
            info!("Received shutdown signal; stopping worker loops and HTTP health server");
            shutdown_token.cancel();
        })
        .await;

    runtime.shutdown_and_drain(loop_handles).await;
    let _ = tokio::time::timeout(shutdown_timeout, pool.close()).await;
    serve_result?;
    Ok(())
}

fn init_tracing(is_production: bool) {
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("info,sqlx::query=warn,tower_http=info"));
    if is_production {
        let _ = tracing_subscriber::fmt()
            .with_env_filter(filter)
            .json()
            .try_init();
    } else {
        let _ = tracing_subscriber::fmt()
            .with_env_filter(filter)
            .compact()
            .try_init();
    }
}

async fn wait_for_shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        let mut sigterm = signal(SignalKind::terminate()).ok();
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = async {
                if let Some(ref mut term) = sigterm {
                    term.recv().await;
                } else {
                    std::future::pending::<()>().await;
                }
            } => {}
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}
