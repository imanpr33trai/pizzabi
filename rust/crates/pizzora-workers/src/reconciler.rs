use std::sync::Arc;
use std::time::Duration;

use pizzora_payments::RazorpayReconciliationService;
use tokio_util::sync::CancellationToken;
use tracing::{debug, error, info};

use crate::error::WorkerError;
use crate::metrics::{WorkerMetrics, LOOP_RECONCILER};

#[derive(Clone)]
pub struct RazorpayReconciliationWorker {
    service: Arc<RazorpayReconciliationService>,
}

impl RazorpayReconciliationWorker {
    pub fn new(service: Arc<RazorpayReconciliationService>) -> Self {
        Self { service }
    }

    pub async fn run_once(&self) -> Result<usize, WorkerError> {
        Ok(self.service.run_once().await?)
    }

    pub async fn run_loop(
        &self,
        poll_interval: Duration,
        metrics: Arc<WorkerMetrics>,
        shutdown: CancellationToken,
    ) {
        let mut consecutive_failures: u32 = 0;
        loop {
            if shutdown.is_cancelled() {
                break;
            }

            match self.run_once().await {
                Ok(actions) => {
                    consecutive_failures = 0;
                    metrics.record_loop_success(LOOP_RECONCILER);
                    if actions > 0 {
                        metrics.inc_reconciler_actions(actions as u64);
                        info!(actions, "RazorpayReconciliationWorker reconciled records");
                    } else {
                        debug!("RazorpayReconciliationWorker found no stale attempts or failed refunds");
                    }
                }
                Err(err) => {
                    consecutive_failures = consecutive_failures.saturating_add(1);
                    metrics.record_loop_error(LOOP_RECONCILER, consecutive_failures >= 3);
                    error!(
                        consecutive_failures,
                        error = %err,
                        "RazorpayReconciliationWorker loop iteration failed"
                    );
                }
            }

            tokio::select! {
                _ = shutdown.cancelled() => break,
                _ = tokio::time::sleep(poll_interval) => {}
            }
        }
        metrics.set_loop_healthy(LOOP_RECONCILER, false);
    }
}
