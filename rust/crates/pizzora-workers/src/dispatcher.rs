use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use pizzora_auth::EmailDeliveryProvider;
use pizzora_config::OutboxEncryptionConfig;
use pizzora_db::{
    ClaimedOutboxEvent, EphemeralCacheStore, OutboxRepository,
    EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED, EVENT_REFUND_REQUESTED,
};
use pizzora_payments::RazorpayReconciliationService;
use sqlx::PgPool;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;
use tracing::{debug, error, warn};

use crate::broadcast::{BroadcastDispatchOutcome, BroadcastOutboxDispatcher};
use crate::email_dispatch::{EmailDispatchOutcome, EmailOutboxDispatcher};
use crate::error::WorkerError;
use crate::metrics::{WorkerMetrics, LOOP_DISPATCHER};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct DispatchBatchSummary {
    pub payment_events: usize,
    pub email_events: usize,
    pub broadcast_events: usize,
    pub email_dead_letters: usize,
    pub broadcast_dead_letters: usize,
}

impl DispatchBatchSummary {
    pub fn total_processed(&self) -> usize {
        self.payment_events + self.email_events + self.broadcast_events
    }
}

#[derive(Clone)]
pub struct OutboxDispatcherWorker {
    outbox_repo: OutboxRepository,
    payment_service: Arc<RazorpayReconciliationService>,
    email_dispatcher: EmailOutboxDispatcher,
    broadcast_dispatcher: BroadcastOutboxDispatcher,
    batch_size: i64,
    lease_seconds: i64,
    max_attempts: i32,
    concurrency_limit: usize,
}

impl OutboxDispatcherWorker {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        pool: PgPool,
        crypto_config: OutboxEncryptionConfig,
        email_provider: Arc<dyn EmailDeliveryProvider>,
        payment_service: Arc<RazorpayReconciliationService>,
        cache: Arc<dyn EphemeralCacheStore>,
        batch_size: i64,
        lease_seconds: i64,
        max_attempts: i32,
        concurrency_limit: usize,
    ) -> Self {
        let clamped_batch = batch_size.clamp(1, 100);
        let clamped_lease = lease_seconds.clamp(1, 300);
        let clamped_attempts = max_attempts.max(1);
        let clamped_concurrency = concurrency_limit.clamp(1, 16);

        let outbox_repo = OutboxRepository::new(pool.clone());
        let email_dispatcher = EmailOutboxDispatcher::new(
            pool.clone(),
            crypto_config,
            email_provider,
            clamped_attempts,
        );
        let broadcast_dispatcher = BroadcastOutboxDispatcher::new(pool, cache, clamped_attempts);

        Self {
            outbox_repo,
            payment_service,
            email_dispatcher,
            broadcast_dispatcher,
            batch_size: clamped_batch,
            lease_seconds: clamped_lease,
            max_attempts: clamped_attempts,
            concurrency_limit: clamped_concurrency,
        }
    }

    pub async fn run_once(&self) -> Result<DispatchBatchSummary, WorkerError> {
        let mut summary = DispatchBatchSummary {
            payment_events: self.dispatch_payment_lane().await?,
            ..Default::default()
        };

        let (emails, email_dlq) = self.dispatch_email_lane().await?;
        summary.email_events = emails;
        summary.email_dead_letters = email_dlq;

        let (broadcasts, broadcast_dlq) = self.dispatch_broadcast_lane().await?;
        summary.broadcast_events = broadcasts;
        summary.broadcast_dead_letters = broadcast_dlq;

        Ok(summary)
    }

    async fn dispatch_payment_lane(&self) -> Result<usize, WorkerError> {
        let claimed = self
            .outbox_repo
            .claim_payment_events(self.batch_size, self.lease_seconds)
            .await?;
        if claimed.is_empty() {
            return Ok(0);
        }

        let semaphore = Arc::new(Semaphore::new(self.concurrency_limit));
        let mut join_set = JoinSet::new();

        for event in claimed {
            let permit = semaphore
                .clone()
                .acquire_owned()
                .await
                .map_err(|_| WorkerError::Runtime("Payment semaphore closed".to_owned()))?;
            let outbox_repo = self.outbox_repo.clone();
            let payment_service = self.payment_service.clone();
            let max_attempts = self.max_attempts;

            join_set.spawn(async move {
                let _permit = permit;
                let event_id = event.id;
                let event_type = event.event_type.clone();
                let res = dispatch_single_payment_event(
                    &outbox_repo,
                    &payment_service,
                    &event,
                    max_attempts,
                )
                .await;
                (event_id, event_type, res)
            });
        }

        let mut handled = 0usize;
        while let Some(joined) = join_set.join_next().await {
            match joined {
                Ok((_, _, Ok(()))) => {
                    handled += 1;
                }
                Ok((event_id, event_type, Err(err))) => {
                    warn!(
                        outbox_event_id = %event_id,
                        event_type = %event_type,
                        error = %err,
                        "Payment outbox dispatch failed; lease will allow retry"
                    );
                }
                Err(join_err) => {
                    return Err(WorkerError::Runtime(format!(
                        "Payment dispatch task panicked or cancelled: {join_err}"
                    )));
                }
            }
        }

        Ok(handled)
    }

    async fn dispatch_email_lane(&self) -> Result<(usize, usize), WorkerError> {
        let claimed = self
            .outbox_repo
            .claim_email_events(self.batch_size, self.lease_seconds)
            .await?;
        if claimed.is_empty() {
            return Ok((0, 0));
        }

        let semaphore = Arc::new(Semaphore::new(self.concurrency_limit));
        let mut join_set = JoinSet::new();

        for event in claimed {
            let permit = semaphore
                .clone()
                .acquire_owned()
                .await
                .map_err(|_| WorkerError::Runtime("Email semaphore closed".to_owned()))?;
            let dispatcher = self.email_dispatcher.clone();

            join_set.spawn(async move {
                let _permit = permit;
                let event_id = event.id;
                let res = dispatcher.dispatch_event(&event).await;
                (event_id, res)
            });
        }

        let mut handled = 0usize;
        let mut dead_letters = 0usize;
        while let Some(joined) = join_set.join_next().await {
            match joined {
                Ok((_, Ok(outcome))) => {
                    if outcome == EmailDispatchOutcome::Dispatched {
                        handled += 1;
                    } else if outcome == EmailDispatchOutcome::DeadLettered {
                        dead_letters += 1;
                    }
                }
                Ok((event_id, Err(err))) => {
                    warn!(
                        outbox_event_id = %event_id,
                        error = %err,
                        "Email outbox dispatch returned internal error; lease will allow retry"
                    );
                }
                Err(join_err) => {
                    return Err(WorkerError::Runtime(format!(
                        "Email dispatch task panicked or cancelled: {join_err}"
                    )));
                }
            }
        }

        Ok((handled, dead_letters))
    }

    async fn dispatch_broadcast_lane(&self) -> Result<(usize, usize), WorkerError> {
        let claimed = self
            .outbox_repo
            .claim_broadcast_events(self.batch_size, self.lease_seconds)
            .await?;
        if claimed.is_empty() {
            return Ok((0, 0));
        }

        let mut handled = 0usize;
        let mut dead_letters = 0usize;
        let mut order_customer_cache = HashMap::new();

        for event in claimed {
            match self
                .broadcast_dispatcher
                .dispatch_event(&event, &mut order_customer_cache)
                .await
            {
                Ok(BroadcastDispatchOutcome::Dispatched { .. })
                | Ok(BroadcastDispatchOutcome::SkippedInternal) => {
                    handled += 1;
                }
                Ok(BroadcastDispatchOutcome::DeadLettered) => {
                    dead_letters += 1;
                }
                Ok(BroadcastDispatchOutcome::RetryScheduled) => {}
                Err(err) => {
                    warn!(
                        outbox_event_id = %event.id,
                        error = %err,
                        "Broadcast outbox dispatch failed; lease will allow retry"
                    );
                }
            }
        }

        Ok((handled, dead_letters))
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

            let delay_needed = match self.run_once().await {
                Ok(summary) => {
                    consecutive_failures = 0;
                    metrics.record_loop_success(LOOP_DISPATCHER);
                    if summary.payment_events > 0 {
                        metrics.inc_payment_dispatched(summary.payment_events as u64);
                    }
                    if summary.email_events > 0 {
                        metrics.inc_email_dispatched(summary.email_events as u64);
                    }
                    if summary.broadcast_events > 0 {
                        metrics.inc_broadcast_dispatched(summary.broadcast_events as u64);
                    }
                    if summary.email_dead_letters > 0 {
                        metrics.inc_email_dead_letter(summary.email_dead_letters as u64);
                    }
                    if summary.broadcast_dead_letters > 0 {
                        metrics.inc_broadcast_dead_letter(summary.broadcast_dead_letters as u64);
                    }
                    if summary.total_processed() > 0 {
                        debug!(
                            payment = summary.payment_events,
                            email = summary.email_events,
                            broadcast = summary.broadcast_events,
                            "OutboxDispatcherWorker processed batch"
                        );
                        false
                    } else {
                        true
                    }
                }
                Err(err) => {
                    consecutive_failures = consecutive_failures.saturating_add(1);
                    metrics.record_loop_error(LOOP_DISPATCHER, consecutive_failures >= 3);
                    error!(
                        consecutive_failures,
                        error = %err,
                        "OutboxDispatcherWorker loop iteration failed"
                    );
                    true
                }
            };

            if delay_needed {
                tokio::select! {
                    _ = shutdown.cancelled() => break,
                    _ = tokio::time::sleep(poll_interval) => {}
                }
            }
        }
        metrics.set_loop_healthy(LOOP_DISPATCHER, false);
    }
}

async fn dispatch_single_payment_event(
    outbox_repo: &OutboxRepository,
    payment_service: &RazorpayReconciliationService,
    event: &ClaimedOutboxEvent,
    max_attempts: i32,
) -> Result<(), WorkerError> {
    match event.event_type.as_str() {
        EVENT_PAYMENT_GATEWAY_ORDER_REQUESTED => {
            payment_service
                .dispatch_gateway_order_outbox_event(outbox_repo, event, max_attempts)
                .await?;
        }
        EVENT_REFUND_REQUESTED => {
            payment_service
                .dispatch_refund_outbox_event(outbox_repo, event, max_attempts)
                .await?;
        }
        other => {
            outbox_repo
                .mark_dead_letter(
                    event.id,
                    &format!("Unsupported payment lane event_type '{other}'"),
                    None,
                )
                .await?;
        }
    }
    Ok(())
}
