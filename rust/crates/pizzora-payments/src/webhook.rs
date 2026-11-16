use pizzora_config::RazorpayConfig;
use pizzora_db::{record_audit_log, AuditContext};
use serde_json::Value as JsonValue;
use tracing::warn;

use crate::error::PaymentError;
use crate::repo::insert_payment_event_idempotent;
use crate::service::PaymentService;
use crate::signatures::verify_webhook_signature;

#[derive(Clone)]
pub struct WebhookService {
    payment_service: PaymentService,
    razorpay_config: RazorpayConfig,
}

impl WebhookService {
    pub fn new(payment_service: PaymentService, razorpay_config: RazorpayConfig) -> Self {
        Self {
            payment_service,
            razorpay_config,
        }
    }

    pub async fn handle_razorpay_webhook(
        &self,
        raw_body: &[u8],
        signature_header: Option<&str>,
        event_id_header: Option<&str>,
        audit: &AuditContext,
    ) -> Result<(), PaymentError> {
        // Verify HMAC-SHA256 on raw request bytes BEFORE any JSON parsing or DB mutations.
        let valid_signature = verify_webhook_signature(
            raw_body,
            signature_header,
            self.razorpay_config.webhook_secret(),
        );

        if !valid_signature {
            let safe_event_id: String = event_id_header
                .unwrap_or("unknown")
                .chars()
                .take(128)
                .collect();
            let has_signature = signature_header.is_some_and(|s| !s.trim().is_empty());

            warn!(
                event_id = %safe_event_id,
                has_signature = has_signature,
                ip = ?audit.ip_address,
                "Blocked Razorpay webhook with invalid HMAC signature"
            );

            let mut system_audit = audit.clone();
            system_audit.actor_role = "SYSTEM".to_owned();
            let mut tx = self.payment_service.pool().begin().await?;
            let after = serde_json::json!({ "has_signature": has_signature });
            record_audit_log(
                &mut tx,
                &system_audit,
                "RAZORPAY_WEBHOOK_INVALID_SIGNATURE",
                "webhook",
                &safe_event_id,
                None,
                Some(&after),
            )
            .await?;
            tx.commit().await?;

            return Err(PaymentError::Unauthorized {
                code: "INVALID_WEBHOOK_SIGNATURE",
                message: "Invalid Razorpay webhook signature.".to_owned(),
            });
        }

        let root: JsonValue = serde_json::from_slice(raw_body).map_err(|_| PaymentError::Validation {
            code: "MALFORMED_WEBHOOK_JSON",
            message: "Invalid JSON payload in Razorpay webhook.".to_owned(),
        })?;

        let event_type = root
            .get("event")
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .ok_or_else(|| PaymentError::Validation {
                code: "MISSING_WEBHOOK_EVENT",
                message: "Webhook payload missing 'event' field.".to_owned(),
            })?;

        let created_at = root.get("created_at").and_then(|v| v.as_i64());
        let payload_obj = root.get("payload").and_then(|v| v.as_object());

        let mut system_audit = audit.clone();
        system_audit.actor_role = "SYSTEM".to_owned();

        match event_type {
            "payment.captured" => {
                let entity = payload_obj
                    .and_then(|p| p.get("payment"))
                    .and_then(|p| p.get("entity"))
                    .and_then(|e| e.as_object())
                    .ok_or_else(|| PaymentError::Validation {
                        code: "MISSING_PAYMENT_ENTITY",
                        message: "payment.captured webhook missing payload.payment.entity."
                            .to_owned(),
                    })?;

                let gateway_payment_id = entity
                    .get("id")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| PaymentError::Validation {
                        code: "MISSING_GATEWAY_PAYMENT_ID",
                        message: "payment.captured missing payment entity id.".to_owned(),
                    })?;
                let gateway_order_id = entity
                    .get("order_id")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| PaymentError::Validation {
                        code: "MISSING_GATEWAY_ORDER_ID",
                        message: "payment.captured missing payment entity order_id.".to_owned(),
                    })?;
                let amount_paise = entity.get("amount").and_then(|v| v.as_i64());
                let event_id = resolve_gateway_event_id(
                    event_id_header,
                    &root,
                    event_type,
                    gateway_payment_id,
                    created_at,
                );

                self.payment_service
                    .process_payment_captured(
                        "WEBHOOK",
                        &event_id,
                        gateway_order_id,
                        gateway_payment_id,
                        amount_paise,
                        None,
                        None,
                        &root,
                        &system_audit,
                    )
                    .await?;
            }

            "payment.failed" => {
                let Some(entity) = payload_obj
                    .and_then(|p| p.get("payment"))
                    .and_then(|p| p.get("entity"))
                    .and_then(|e| e.as_object())
                else {
                    return Ok(());
                };
                let gateway_payment_id = entity.get("id").and_then(|v| v.as_str());
                let Some(gateway_order_id) = entity.get("order_id").and_then(|v| v.as_str()) else {
                    return Ok(());
                };
                let failure_code = entity.get("error_code").and_then(|v| v.as_str());
                let failure_reason = entity
                    .get("error_description")
                    .and_then(|v| v.as_str())
                    .or_else(|| entity.get("error_reason").and_then(|v| v.as_str()));

                let event_id = resolve_gateway_event_id(
                    event_id_header,
                    &root,
                    event_type,
                    gateway_payment_id.unwrap_or(gateway_order_id),
                    created_at,
                );

                self.payment_service
                    .process_payment_failed_webhook(
                        &event_id,
                        gateway_order_id,
                        gateway_payment_id,
                        failure_code,
                        failure_reason,
                        &root,
                        &system_audit,
                    )
                    .await?;
            }

            "refund.processed" | "refund.failed" => {
                let entity = payload_obj
                    .and_then(|p| p.get("refund"))
                    .and_then(|r| r.get("entity"))
                    .and_then(|e| e.as_object())
                    .ok_or_else(|| PaymentError::Validation {
                        code: "MISSING_REFUND_ENTITY",
                        message: format!("{event_type} webhook missing payload.refund.entity."),
                    })?;

                let gateway_refund_id = entity
                    .get("id")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| PaymentError::Validation {
                        code: "MISSING_GATEWAY_REFUND_ID",
                        message: format!("{event_type} missing refund entity id."),
                    })?;

                let idemp_note = entity
                    .get("notes")
                    .and_then(|n| n.as_object())
                    .and_then(|n| n.get("idempotency_key"))
                    .and_then(|v| v.as_str());

                let event_id = resolve_gateway_event_id(
                    event_id_header,
                    &root,
                    event_type,
                    gateway_refund_id,
                    created_at,
                );

                self.payment_service
                    .process_refund_webhook(
                        &event_id,
                        gateway_refund_id,
                        idemp_note,
                        event_type == "refund.processed",
                        &root,
                        &system_audit,
                    )
                    .await?;
            }

            _ => {
                let event_id = resolve_gateway_event_id(
                    event_id_header,
                    &root,
                    event_type,
                    "generic",
                    created_at,
                );
                let mut tx = self.payment_service.pool().begin().await?;
                insert_payment_event_idempotent(
                    &mut tx,
                    None,
                    None,
                    "WEBHOOK",
                    &event_id,
                    event_type,
                    &root,
                    true,
                )
                .await?;
                tx.commit().await?;
            }
        }

        Ok(())
    }
}

fn resolve_gateway_event_id(
    event_id_header: Option<&str>,
    root: &JsonValue,
    event_type: &str,
    entity_id: &str,
    created_at: Option<i64>,
) -> String {
    if let Some(h) = event_id_header.map(str::trim).filter(|s| !s.is_empty()) {
        return h.to_owned();
    }
    if let Some(body_id) = root
        .get("id")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        return body_id.to_owned();
    }
    format!(
        "webhook:{event_type}:{entity_id}:{}",
        created_at.unwrap_or(0)
    )
}
