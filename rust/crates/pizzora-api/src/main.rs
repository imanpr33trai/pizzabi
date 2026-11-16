use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use pizzora_api::router::build_router;
use pizzora_api::services::realtime::spawn_redis_pubsub_subscriber;
use pizzora_api::state::AppState;
use pizzora_auth::{BootstrapOwner, PasswordHasher};
use pizzora_config::AppConfig;
use pizzora_db::{connect_pg_pool, run_locked_handoff, verify_catalog_invariants, RedisStore};
use tokio_util::sync::CancellationToken;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("pizzora-api fatal error: {err}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let args: Vec<String> = std::env::args().collect();
    let migrate_only = args.iter().any(|a| a == "--migrate-only");
    let bootstrap_owner = args.iter().any(|a| a == "--bootstrap-owner");

    let config = AppConfig::from_env()?;
    init_tracing(config.is_production());

    let pool = connect_pg_pool(&config.database).await?;

    if migrate_only {
        let outcome = run_locked_handoff(&pool).await?;
        info!(
            mode = ?outcome.mode,
            applied_sqlx_migrations = outcome.applied_sqlx_migrations,
            "Pre-deploy migration step completed"
        );
        pool.close().await;
        return Ok(());
    }

    if config.database.run_migrations_on_startup {
        let outcome = run_locked_handoff(&pool).await?;
        info!(
            mode = ?outcome.mode,
            applied_sqlx_migrations = outcome.applied_sqlx_migrations,
            "Startup database migrations finished"
        );
    } else {
        verify_catalog_invariants(&pool).await?;
    }

    let password_hasher = PasswordHasher::default();
    if bootstrap_owner {
        let email = std::env::var("OWNER_EMAIL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .ok_or("OWNER_EMAIL env variable is required for --bootstrap-owner")?;
        let password = std::env::var("OWNER_PASSWORD")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .ok_or("OWNER_PASSWORD env variable is required for --bootstrap-owner")?;
        let full_name = std::env::var("OWNER_NAME")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "PIZZORA Owner".to_owned());
        let phone = std::env::var("OWNER_PHONE")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "9999999999".to_owned());
        let employee_code = std::env::var("OWNER_EMPLOYEE_CODE")
            .ok()
            .filter(|s| !s.trim().is_empty());

        BootstrapOwner::bootstrap(
            &pool,
            &password_hasher,
            &email,
            &password,
            &full_name,
            &phone,
            employee_code.as_deref(),
        )
        .await?;
        pool.close().await;
        return Ok(());
    }

    if let (Ok(email), Ok(password)) = (
        std::env::var("BOOTSTRAP_OWNER_EMAIL"),
        std::env::var("BOOTSTRAP_OWNER_PASSWORD"),
    ) {
        if !email.trim().is_empty() && !password.trim().is_empty() {
            let full_name = std::env::var("BOOTSTRAP_OWNER_NAME")
                .ok()
                .filter(|s| !s.trim().is_empty())
                .unwrap_or_else(|| "PIZZORA Owner".to_owned());
            let phone = std::env::var("BOOTSTRAP_OWNER_PHONE")
                .ok()
                .filter(|s| !s.trim().is_empty())
                .unwrap_or_else(|| "9999999999".to_owned());
            BootstrapOwner::bootstrap(
                &pool,
                &password_hasher,
                &email,
                &password,
                &full_name,
                &phone,
                None,
            )
            .await?;
        }
    }

    let redis_store = Arc::new(RedisStore::new(&config.redis.url, config.redis.timeout_ms));
    if let Err(err) = redis_store.connect_now().await {
        if config.redis.required {
            error!(error = %err, "Redis is required (REDIS_REQUIRED=true) and unavailable");
            return Err(Box::new(err));
        }
        warn!(error = %err, "Redis unavailable on startup; continuing with DB/in-memory fallbacks");
    }

    let bind_addr: SocketAddr = format!("0.0.0.0:{}", config.http_port).parse()?;
    let shutdown_timeout = Duration::from_secs(config.shutdown_timeout_secs);
    let environment = config.environment.clone();
    let http_port = config.http_port;
    let s3_enabled = config.storage.enabled;
    let run_migrations = config.database.run_migrations_on_startup;
    let redis_config = config.redis.clone();

    let state = AppState::build(
        config,
        pool.clone(),
        redis_store,
        Some(password_hasher),
        None,
        None,
    )
    .map_err(|err| format!("Failed building AppState: {}", err.message))?;

    let shutdown = CancellationToken::new();
    spawn_redis_pubsub_subscriber(redis_config, state.realtime_hub.clone(), shutdown.clone());

    let app = build_router(state);
    let listener = tokio::net::TcpListener::bind(bind_addr).await?;

    info!(
        environment = %environment,
        port = http_port,
        run_migrations_on_startup = run_migrations,
        s3_enabled = s3_enabled,
        "PIZZORA standalone Axum API server listening"
    );

    let shutdown_signal_token = shutdown.clone();
    let serve_result = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(async move {
        wait_for_shutdown_signal().await;
        info!("Received shutdown signal; draining HTTP/WebSocket connections");
        shutdown_signal_token.cancel();
    })
    .await;

    shutdown.cancel();
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
