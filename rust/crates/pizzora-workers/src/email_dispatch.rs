use std::sync::Arc;

use pizzora_auth::{
    sanitize_error_for_logs, scrub_outbox_email_payload, unseal_outbox_email_payload,
    EmailDeliveryProvider, OutboundEmail, OutboxEmailPayload,
};
use pizzora_config::OutboxEncryptionConfig;
use pizzora_db::{
    record_audit_log, AuditContext, ClaimedOutboxEvent, OutboxRepository,
};
use sqlx::PgPool;
use tracing::{info, warn};
use uuid::Uuid;

use crate::error::WorkerError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EmailDispatchOutcome {
    Dispatched,
    RetryScheduled,
    DeadLettered,
}

#[derive(Clone)]
pub struct EmailOutboxDispatcher {
    pool: PgPool,
    outbox_repo: OutboxRepository,
    crypto_config: OutboxEncryptionConfig,
    email_provider: Arc<dyn EmailDeliveryProvider>,
    max_attempts: i32,
}

impl EmailOutboxDispatcher {
    pub fn new(
        pool: PgPool,
        crypto_config: OutboxEncryptionConfig,
        email_provider: Arc<dyn EmailDeliveryProvider>,
        max_attempts: i32,
    ) -> Self {
        let outbox_repo = OutboxRepository::new(pool.clone());
        Self {
            pool,
            outbox_repo,
            crypto_config,
            email_provider,
            max_attempts: max_attempts.max(1),
        }
    }

    pub async fn dispatch_event(
        &self,
        event: &ClaimedOutboxEvent,
    ) -> Result<EmailDispatchOutcome, WorkerError> {
        let parsed_payload: OutboxEmailPayload = match serde_json::from_value(
            event.payload_json.clone(),
        ) {
            Ok(payload) => payload,
            Err(_) => {
                let scrubbed = best_effort_scrub_malformed_payload(&event.payload_json);
                let safe_error = "Malformed EMAIL_DELIVERY_REQUESTED JSON payload";
                self.mark_dead_letter_with_audit(
                    event,
                    None,
                    None,
                    None,
                    "EMAIL_PAYLOAD_MALFORMED",
                    safe_error,
                    Some(&scrubbed),
                )
                .await?;
                warn!(
                    outbox_event_id = %event.id,
                    attempts = event.attempts,
                    error_code = "EMAIL_PAYLOAD_MALFORMED",
                    "Moved malformed email outbox event to DEAD_LETTER"
                );
                return Ok(EmailDispatchOutcome::DeadLettered);
            }
        };

        let envelope_kid = parsed_payload
            .sealed_envelope
            .as_ref()
            .map(|e| e.kid.clone());

        let secrets = match unseal_outbox_email_payload(
            &self.crypto_config,
            event.id,
            &event.event_type,
            &parsed_payload,
        ) {
            Ok(unsealed) => unsealed,
            Err(err) => {
                let scrubbed = scrub_outbox_email_payload(&parsed_payload);
                let scrubbed_json = serde_json::to_value(&scrubbed)?;
                let safe_error = sanitize_error_for_logs(
                    &format!("UNSEAL_FAILED: {err}"),
                    &[],
                );
                self.mark_dead_letter_with_audit(
                    event,
                    Some(&format!("{:?}", parsed_payload.template)),
                    Some(&parsed_payload.token_record_id),
                    envelope_kid.as_deref(),
                    "EMAIL_UNSEAL_FAILED",
                    &safe_error,
                    Some(&scrubbed_json),
                )
                .await?;
                warn!(
                    outbox_event_id = %event.id,
                    template = ?parsed_payload.template,
                    token_record_id = %parsed_payload.token_record_id,
                    kid = ?envelope_kid,
                    attempts = event.attempts,
                    error_code = "EMAIL_UNSEAL_FAILED",
                    "Moved undecryptable email outbox event to DEAD_LETTER with scrubbed envelope"
                );
                return Ok(EmailDispatchOutcome::DeadLettered);
            }
        };

        let send_outcome = {
            let outbound = OutboundEmail {
                outbox_event_id: event.id,
                template: parsed_payload.template,
                to_email: secrets.to_email.clone(),
                full_name: secrets.full_name.clone(),
                raw_token: secrets.raw_token.clone(),
            };
            let result = self.email_provider.send_outbox_email(&outbound).await;
            let sensitive_values = [
                secrets.raw_token.as_str(),
                secrets.to_email.as_str(),
                secrets.full_name.as_str(),
            ];
            result.map_err(|err| {
                let safe_code = sanitize_code(&err.code);
                let safe_msg = sanitize_error_for_logs(
                    &format!("{safe_code}: {}", err.sanitized_message),
                    &sensitive_values,
                );
                (err.is_transient, safe_code, safe_msg)
            })
        };
        // Explicit drop guarantees Zeroizing buffers are cleared before any DB/audit I/O.
        drop(secrets);

        match send_outcome {
            Ok(()) => {
                let scrubbed = scrub_outbox_email_payload(&parsed_payload);
                let scrubbed_json = serde_json::to_value(&scrubbed)?;
                self.outbox_repo
                    .mark_dispatched(event.id, Some(&scrubbed_json))
                    .await?;
                info!(
                    outbox_event_id = %event.id,
                    template = ?parsed_payload.template,
                    token_record_id = %parsed_payload.token_record_id,
                    kid = ?envelope_kid,
                    attempts = event.attempts,
                    "Dispatched email outbox event and scrubbed envelope"
                );
                Ok(EmailDispatchOutcome::Dispatched)
            }
            Err((is_transient, safe_code, safe_error)) => {
                if is_transient && event.attempts < self.max_attempts {
                    let backoff_secs = compute_exponential_backoff_seconds(event.attempts);
                    self.outbox_repo
                        .schedule_retry(event.id, backoff_secs, &safe_error)
                        .await?;
                    warn!(
                        outbox_event_id = %event.id,
                        template = ?parsed_payload.template,
                        token_record_id = %parsed_payload.token_record_id,
                        kid = ?envelope_kid,
                        attempts = event.attempts,
                        backoff_secs,
                        error_code = %safe_code,
                        "Scheduled transient retry for email outbox event"
                    );
                    Ok(EmailDispatchOutcome::RetryScheduled)
                } else {
                    let scrubbed = scrub_outbox_email_payload(&parsed_payload);
                    let scrubbed_json = serde_json::to_value(&scrubbed)?;
                    self.mark_dead_letter_with_audit(
                        event,
                        Some(&format!("{:?}", parsed_payload.template)),
                        Some(&parsed_payload.token_record_id),
                        envelope_kid.as_deref(),
                        &safe_code,
                        &safe_error,
                        Some(&scrubbed_json),
                    )
                    .await?;
                    warn!(
                        outbox_event_id = %event.id,
                        template = ?parsed_payload.template,
                        token_record_id = %parsed_payload.token_record_id,
                        kid = ?envelope_kid,
                        attempts = event.attempts,
                        error_code = %safe_code,
                        "Moved failed email outbox event to DEAD_LETTER with scrubbed envelope"
                    );
                    Ok(EmailDispatchOutcome::DeadLettered)
                }
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    async fn mark_dead_letter_with_audit(
        &self,
        event: &ClaimedOutboxEvent,
        template: Option<&str>,
        token_record_id: Option<&str>,
        kid: Option<&str>,
        error_code: &str,
        sanitized_error: &str,
        scrubbed_payload: Option<&serde_json::Value>,
    ) -> Result<(), WorkerError> {
        let truncated_error: String = sanitized_error.chars().take(500).collect();
        let mut tx = self.pool.begin().await?;

        if let Some(payload) = scrubbed_payload {
            sqlx::query(
                r#"
                UPDATE outbox_events
                SET dispatch_status = 'DEAD_LETTER',
                    published_at = NULL,
                    last_error = $2,
                    payload_json = $3
                WHERE id = $1
                "#,
            )
            .bind(event.id)
            .bind(&truncated_error)
            .bind(payload)
            .execute(&mut *tx)
            .await?;
        } else {
            sqlx::query(
                r#"
                UPDATE outbox_events
                SET dispatch_status = 'DEAD_LETTER',
                    published_at = NULL,
                    last_error = $2
                WHERE id = $1
                "#,
            )
            .bind(event.id)
            .bind(&truncated_error)
            .execute(&mut *tx)
            .await?;
        }

        let audit = AuditContext {
            actor_user_id: None,
            actor_role: "SYSTEM".to_owned(),
            correlation_id: event.id.to_string(),
            ip_address: None,
        };
        let after_json = serde_json::json!({
            "outbox_event_id": event.id.to_string(),
            "template": template,
            "token_record_id": token_record_id,
            "kid": kid,
            "attempts": event.attempts,
            "error_code": error_code,
            "error": truncated_error,
        });
        record_audit_log(
            &mut tx,
            &audit,
            "EMAIL_DELIVERY_DEAD_LETTER",
            "outbox_event",
            &event.id.to_string(),
            None,
            Some(&after_json),
        )
        .await?;

        tx.commit().await?;
        Ok(())
    }
}

pub fn compute_exponential_backoff_seconds(attempts: i32) -> i64 {
    let shift = (attempts - 1).clamp(0, 8) as u32;
    (2_i64 << shift).min(300)
}

fn sanitize_code(raw: &str) -> String {
    let clean: String = raw
        .trim()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
        .take(64)
        .collect();
    if clean.is_empty() {
        "EMAIL_DELIVERY_ERROR".to_owned()
    } else {
        clean
    }
}

fn best_effort_scrub_malformed_payload(raw: &serde_json::Value) -> serde_json::Value {
    match raw {
        serde_json::Value::Object(map) => {
            let mut scrubbed = map.clone();
            if scrubbed.contains_key("sealed_envelope") {
                scrubbed.insert("sealed_envelope".to_owned(), serde_json::Value::Null);
            }
            scrubbed.remove("raw_token");
            scrubbed.remove("to_email");
            scrubbed.remove("full_name");
            scrubbed.insert("redacted".to_owned(), serde_json::Value::Bool(true));
            serde_json::Value::Object(scrubbed)
        }
        _ => serde_json::json!({
            "sealed_envelope": serde_json::Value::Null,
            "redacted": true,
            "malformed": true,
        }),
    }
}

pub fn parse_uuid_opt(raw: &str) -> Option<Uuid> {
    Uuid::parse_str(raw.trim()).ok()
}
