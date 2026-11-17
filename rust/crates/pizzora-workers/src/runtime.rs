use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use pizzora_auth::{EmailDeliveryProvider, LettreSmtpEmailDeliveryProvider};
use pizzora_config::AppConfig;
use pizzora_db::EphemeralCacheStore;
use pizzora_payments::{
    create_default_razorpay_gateway, PaymentService, RazorpayGateway, RazorpayReconciliationService,
};
use sqlx::PgPool;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use tracing::{info, warn};

use crate::dispatcher::{DispatchBatchSummary, OutboxDispatcherWorker};
use crate::error::WorkerError;
use crate::expiry_sweep::{ExpirySweepSummary, ExpirySweepWorker};
use crate::http::{build_worker_router, WorkerHttpState};
use crate::metrics::WorkerMetrics;
use crate::reconciler::RazorpayReconciliationWorker;
use crate::sequencer::EventSequencerWorker;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct WorkerCycleReport {
    pub sequenced_events: usize,
    pub dispatched: DispatchBatchSummary,
    pub expiry: ExpirySweepSummary,
    pub reconciled_actions: usize,
}

#[derive(Clone)]
pub struct WorkerRuntime {
    pub config: AppConfig,
    pub pool: PgPool,
    pub metrics: Arc<WorkerMetrics>,
    pub sequencer: EventSequencerWorker,
    pub dispatcher: OutboxDispatcherWorker,
    pub expiry_sweep: ExpirySweepWorker,
    pub reconciler: RazorpayReconciliationWorker,
    pub shutdown: CancellationToken,
}

impl WorkerRuntime {
    pub fn with_default_providers(
        config: AppConfig,
        pool: PgPool,
        cache: Arc<dyn EphemeralCacheStore>,
    ) -> Result<Self, WorkerError> {
        let gateway = create_default_razorpay_gateway(&config).map_err(|err| {
            WorkerError::Runtime(format!("Failed initializing Razorpay gateway: {err}"))
        })?;
        let email_provider: Arc<dyn EmailDeliveryProvider> =
            Arc::new(LettreSmtpEmailDeliveryProvider::new(config.smtp.clone()));
        Ok(Self::from_components(
            config,
            pool,
            cache,
            gateway,
            email_provider,
            None,
        ))
    }

    pub fn from_components(
        config: AppConfig,
        pool: PgPool,
        cache: Arc<dyn EphemeralCacheStore>,
        gateway: Arc<dyn RazorpayGateway>,
        email_provider: Arc<dyn EmailDeliveryProvider>,
        shutdown: Option<CancellationToken>,
    ) -> Self {
        let metrics = Arc::new(WorkerMetrics::new());
        let batch_size = config.worker.batch_size as i64;
        let payment_service = PaymentService::new(pool.clone(), config.razorpay.clone());
        let reconciler_service = Arc::new(RazorpayReconciliationService::new(
            pool.clone(),
            gateway,
            payment_service,
            batch_size,
        ));

        let sequencer = EventSequencerWorker::new(pool.clone(), batch_size);
        let dispatcher = OutboxDispatcherWorker::new(
            pool.clone(),
            config.outbox_encryption.clone(),
            email_provider,
            reconciler_service.clone(),
            cache,
            batch_size,
            config.worker.lease_seconds,
            config.worker.max_attempts,
            4,
        );
        let expiry_sweep = ExpirySweepWorker::new(
            pool.clone(),
            batch_size,
            config.worker.outbox_retention_days,
        );
        let reconciler = RazorpayReconciliationWorker::new(reconciler_service);

        Self {
            config,
            pool,
            metrics,
            sequencer,
            dispatcher,
            expiry_sweep,
            reconciler,
            shutdown: shutdown.unwrap_or_default(),
        }
    }

    pub fn build_http_router(&self) -> Router {
        build_worker_router(WorkerHttpState::with_pg_pool(
            self.pool.clone(),
            self.metrics.clone(),
        ))
    }

    pub async fn run_all_once(&self) -> Result<WorkerCycleReport, WorkerError> {
        let expiry = self.expiry_sweep.run_once().await?;
        let sequenced_events = self.sequencer.run_once().await?;
        let dispatched = self.dispatcher.run_once().await?;
        let reconciled_actions = self.reconciler.run_once().await?;
        Ok(WorkerCycleReport {
            sequenced_events,
            dispatched,
            expiry,
            reconciled_actions,
        })
    }

    pub fn spawn_background_loops(&self) -> Vec<JoinHandle<()>> {
        let sequencer = self.sequencer.clone();
        let sequencer_poll = Duration::from_millis(self.config.worker.sequencer_poll_ms);
        let sequencer_metrics = self.metrics.clone();
        let sequencer_shutdown = self.shutdown.clone();
        let sequencer_handle = tokio::spawn(async move {
            sequencer
                .run_loop(sequencer_poll, sequencer_metrics, sequencer_shutdown)
                .await;
        });

        let dispatcher = self.dispatcher.clone();
        let dispatcher_poll = Duration::from_millis(self.config.worker.dispatcher_poll_ms);
        let dispatcher_metrics = self.metrics.clone();
        let dispatcher_shutdown = self.shutdown.clone();
        let dispatcher_handle = tokio::spawn(async move {
            dispatcher
                .run_loop(dispatcher_poll, dispatcher_metrics, dispatcher_shutdown)
                .await;
        });

        let expiry = self.expiry_sweep.clone();
        let expiry_poll = Duration::from_millis(self.config.worker.expiry_sweep_ms);
        let expiry_metrics = self.metrics.clone();
        let expiry_shutdown = self.shutdown.clone();
        let expiry_handle = tokio::spawn(async move {
            expiry
                .run_loop(expiry_poll, expiry_metrics, expiry_shutdown)
                .await;
        });

        let reconciler = self.reconciler.clone();
        let reconciler_poll = Duration::from_millis(self.config.worker.reconciler_poll_ms);
        let reconciler_metrics = self.metrics.clone();
        let reconciler_shutdown = self.shutdown.clone();
        let reconciler_handle = tokio::spawn(async move {
            reconciler
                .run_loop(reconciler_poll, reconciler_metrics, reconciler_shutdown)
                .await;
        });

        info!(
            sequencer_poll_ms = self.config.worker.sequencer_poll_ms,
            dispatcher_poll_ms = self.config.worker.dispatcher_poll_ms,
            expiry_sweep_ms = self.config.worker.expiry_sweep_ms,
            reconciler_poll_ms = self.config.worker.reconciler_poll_ms,
            "Spawned 4 supervised background worker loops"
        );

        vec![
            sequencer_handle,
            dispatcher_handle,
            expiry_handle,
            reconciler_handle,
        ]
    }

    pub async fn shutdown_and_drain(&self, handles: Vec<JoinHandle<()>>) {
        self.shutdown.cancel();
        let drain_timeout = Duration::from_secs(self.config.shutdown_timeout_secs.max(1));
        let drain_result = tokio::time::timeout(drain_timeout, async move {
            for handle in handles {
                let _ = handle.await;
            }
        })
        .await;

        match drain_result {
            Ok(()) => {
                info!("All background worker loops stopped gracefully");
            }
            Err(_) => {
                warn!(
                    timeout_secs = drain_timeout.as_secs(),
                    "Timed out waiting for background worker loops to drain cleanly"
                );
            }
        }
    }
}
