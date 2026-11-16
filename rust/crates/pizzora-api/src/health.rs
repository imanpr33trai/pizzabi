use std::collections::BTreeMap;
use std::time::Duration;

use axum::http::StatusCode;
use pizzora_db::{probe_postgres, DependencyHealthStatus};

use crate::dto::{HealthLivenessResponse, HealthReadinessResponse, ReadinessState};
use crate::state::AppState;

pub fn liveness() -> HealthLivenessResponse {
    HealthLivenessResponse {
        status: "ok".to_owned(),
        service: "pizzora-api".to_owned(),
    }
}

pub async fn evaluate_readiness(state: &AppState) -> (StatusCode, HealthReadinessResponse) {
    let pg_fut = probe_postgres(&state.pool);
    let redis_fut = probe_redis(state);
    let storage_fut = probe_storage(state);

    let (pg_status, redis_status, storage_status) = tokio::join!(pg_fut, redis_fut, storage_fut);

    let mut dependencies = BTreeMap::new();
    dependencies.insert("postgres".to_owned(), pg_status);
    dependencies.insert("redis".to_owned(), redis_status);

    let mut required_down = pg_status == DependencyHealthStatus::Down;
    let mut optional_down = false;

    if state.config.redis.required {
        if redis_status == DependencyHealthStatus::Down {
            required_down = true;
        }
    } else if redis_status == DependencyHealthStatus::Down {
        optional_down = true;
    }

    if let Some(minio_status) = storage_status {
        dependencies.insert("minio".to_owned(), minio_status);
        if minio_status == DependencyHealthStatus::Down {
            optional_down = true;
        }
    }

    let state_enum = if required_down {
        ReadinessState::Unhealthy
    } else if optional_down {
        ReadinessState::Degraded
    } else {
        ReadinessState::Healthy
    };

    let http_status = if state_enum == ReadinessState::Unhealthy {
        StatusCode::SERVICE_UNAVAILABLE
    } else {
        StatusCode::OK
    };

    (
        http_status,
        HealthReadinessResponse {
            status: state_enum,
            dependencies,
            service: "pizzora-api".to_owned(),
        },
    )
}

async fn probe_redis(state: &AppState) -> DependencyHealthStatus {
    let Some(client) = state.redis_store.client() else {
        return DependencyHealthStatus::Down;
    };
    let timeout = Duration::from_millis(state.config.redis.timeout_ms.clamp(200, 2_000));
    let res = tokio::time::timeout(timeout, async {
        let mut conn = client.get_multiplexed_async_connection().await?;
        let pong: String = redis::cmd("PING").query_async(&mut conn).await?;
        Ok::<String, redis::RedisError>(pong)
    })
    .await;

    match res {
        Ok(Ok(pong)) if pong.eq_ignore_ascii_case("PONG") => DependencyHealthStatus::Up,
        Ok(Ok(_)) => DependencyHealthStatus::Up,
        _ => DependencyHealthStatus::Down,
    }
}

async fn probe_storage(state: &AppState) -> Option<DependencyHealthStatus> {
    if !state.config.storage.enabled {
        return None;
    }
    let host = state.config.storage.host.clone();
    let port = state.config.storage.port;
    let addr = format!("{host}:{port}");
    let status = match tokio::time::timeout(
        Duration::from_millis(1_000),
        tokio::net::TcpStream::connect(&addr),
    )
    .await
    {
        Ok(Ok(_)) => DependencyHealthStatus::Up,
        _ => DependencyHealthStatus::Down,
    };
    Some(status)
}
