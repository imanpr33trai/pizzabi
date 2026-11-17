use pizzora_db::{record_audit_log, AuditContext};
use tracing::warn;
use uuid::Uuid;

use crate::dto::{VerifyRazorpayPaymentRequest, VerifyRazorpayPaymentResponse};
use crate::error::PaymentError;
use crate::service::{parse_uuid, PaymentService};
use crate::signatures::verify_checkout_signature;

impl PaymentService {
    pub async fn verify_client_payment(
        &self,
        customer_id: Uuid,
        request: &VerifyRazorpayPaymentRequest,
        audit: &AuditContext,
    ) -> Result<VerifyRazorpayPaymentResponse, PaymentError> {
        let order_id = parse_uuid(&request.order_id, "order_id")?;
        let gateway_order_id = request.razorpay_order_id.trim();
        let gateway_payment_id = request.razorpay_payment_id.trim();
        let signature = request.razorpay_signature.trim();

        if gateway_order_id.is_empty() || gateway_payment_id.is_empty() || signature.is_empty() {
            return Err(PaymentError::Validation {
                code: "INVALID_VERIFY_PAYLOAD",
                message:
                    "razorpay_order_id, razorpay_payment_id, and razorpay_signature are required."
                        .to_owned(),
            });
        }

        // Verify HMAC-SHA256 signature strictly OUTSIDE any database transaction.
        let sig_valid = verify_checkout_signature(
            gateway_order_id,
            gateway_payment_id,
            signature,
            self.razorpay_config.key_secret(),
        );

        if !sig_valid {
            warn!(
                order_id = %order_id,
                customer_id = %customer_id,
                gateway_order_id = %gateway_order_id.chars().take(64).collect::<String>(),
                ip = ?audit.ip_address,
                "Blocked client Razorpay verify with invalid signature"
            );
            let mut tx = self.pool.begin().await?;
            let after = serde_json::json!({
                "gateway_order_id": gateway_order_id.chars().take(128).collect::<String>(),
                "gateway_payment_id": gateway_payment_id.chars().take(128).collect::<String>(),
            });
            record_audit_log(
                &mut tx,
                audit,
                "PAYMENT_SIGNATURE_INVALID",
                "order",
                &order_id.to_string(),
                None,
                Some(&after),
            )
            .await?;
            tx.commit().await?;

            return Err(PaymentError::Unauthorized {
                code: "INVALID_PAYMENT_SIGNATURE",
                message: "Razorpay checkout signature verification failed.".to_owned(),
            });
        }

        let payload_json = serde_json::json!({
            "order_id": order_id.to_string(),
            "razorpay_order_id": gateway_order_id,
            "razorpay_payment_id": gateway_payment_id,
        });

        self.process_payment_captured(
            "CLIENT_VERIFY",
            &format!("client_verify:{gateway_payment_id}"),
            gateway_order_id,
            gateway_payment_id,
            None,
            Some(order_id),
            Some(customer_id),
            &payload_json,
            audit,
        )
        .await
    }
}
