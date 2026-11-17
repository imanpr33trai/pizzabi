use std::collections::BTreeMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use axum::extract::State;
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use pizzora_db::{probe_postgres, DependencyHealthStatus};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;

use crate::metrics::WorkerMetrics;

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkerReadinessState {
    Healthy,
    Unhealthy,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkerDependencyHealth {
    Up,
    Down,
}

impl From<DependencyHealthStatus> for WorkerDependencyHealth {
    fn from(value: DependencyHealthStatus) -> Self {
        match value {
            DependencyHealthStatus::Up => Self::Up,
            DependencyHealthStatus::Down => Self::Down,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkerHealthResponse {
    pub status: WorkerReadinessState,
    pub dependencies: BTreeMap<String, WorkerDependencyHealth>,
    pub service: String,
}

pub trait PostgresProbe: Send + Sync {
    fn check<'a>(&'a self) -> BoxFuture<'a, DependencyHealthStatus>;
}

struct LivePgProbe {
    pool: PgPool,
}

impl PostgresProbe for LivePgProbe {
    fn check<'a>(&'a self) -> BoxFuture<'a, DependencyHealthStatus> {
        Box::pin(async move { probe_postgres(&self.pool).await })
    }
}

#[derive(Clone)]
pub struct WorkerHttpState {
    pub pool: Option<PgPool>,
    pub pg_probe: Option<Arc<dyn PostgresProbe>>,
    pub metrics: Arc<WorkerMetrics>,
}

impl WorkerHttpState {
    pub fn with_pg_pool(pool: PgPool, metrics: Arc<WorkerMetrics>) -> Self {
        let probe = Arc::new(LivePgProbe { pool: pool.clone() });
        Self {
            pool: Some(pool),
            pg_probe: Some(probe),
            metrics,
        }
    }

    pub fn for_tests(
        pg_probe: Option<Arc<dyn PostgresProbe>>,
        metrics: Arc<WorkerMetrics>,
    ) -> Self {
        Self {
            pool: None,
            pg_probe,
            metrics,
        }
    }
}

pub fn build_worker_router(state: WorkerHttpState) -> Router {
    Router::new()
        .route("/health/worker", get(health_worker))
        .route("/metrics", get(metrics_worker))
        .with_state(state)
}

async fn health_worker(
    State(state): State<WorkerHttpState>,
) -> (StatusCode, Json<WorkerHealthResponse>) {
    let mut dependencies = BTreeMap::new();
    for (name, healthy) in state.metrics.loop_health_snapshot() {
        dependencies.insert(
            name,
            if healthy {
                WorkerDependencyHealth::Up
            } else {
                WorkerDependencyHealth::Down
            },
        );
    }

    if let Some(probe) = &state.pg_probe {
        let pg_status: WorkerDependencyHealth = probe.check().await.into();
        dependencies.insert("postgres".to_owned(), pg_status);
    }

    let all_up = dependencies
        .values()
        .all(|&dep| dep == WorkerDependencyHealth::Up);

    let http_status = if all_up {
        StatusCode::OK
    } else {
        StatusCode::SERVICE_UNAVAILABLE
    };
    let status = if all_up {
        WorkerReadinessState::Healthy
    } else {
        WorkerReadinessState::Unhealthy
    };

    (
        http_status,
        Json(WorkerHealthResponse {
            status,
            dependencies,
            service: "pizzora-worker".to_owned(),
        }),
    )
}

async fn metrics_worker(State(state): State<WorkerHttpState>) -> Response {
    let payload = state.metrics.render_prometheus(state.pool.as_ref()).await;
    let mut resp = (StatusCode::OK, payload).into_response();
    resp.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; version=0.0.4; charset=utf-8"),
    );
    resp
}
