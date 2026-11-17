use std::collections::BTreeMap;
use std::fmt;

use pizzora_core::{OrderStatus, RefundStatus};
use pizzora_db::{OrderDetailDto, PaymentSummaryDto};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RefundDto {
    pub id: String,
    pub payment_id: String,
    #[serde(default)]
    pub payment_attempt_id: Option<String>,
    pub order_id: String,
    pub idempotency_key: String,
    pub amount_paise: i64,
    pub status: RefundStatus,
    #[serde(default)]
    pub gateway_refund_id: Option<String>,
    pub reason: String,
    #[serde(default)]
    pub initiated_by: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OrderPaymentStatusResponse {
    pub order_id: String,
    pub order_number: String,
    pub order_status: OrderStatus,
    pub payment: PaymentSummaryDto,
    #[serde(default)]
    pub razorpay_key_id: Option<String>,
    #[serde(default)]
    pub can_retry: bool,
    #[serde(default)]
    pub expires_at: Option<String>,
    #[serde(default)]
    pub refunds: Vec<RefundDto>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct RetryPaymentRequest {
    #[serde(default)]
    pub reason: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FailPaymentAttemptRequest {
    #[serde(default)]
    pub attempt_id: Option<String>,
    #[serde(default)]
    pub gateway_order_id: Option<String>,
    #[serde(default)]
    pub failure_code: Option<String>,
    #[serde(default)]
    pub failure_reason: Option<String>,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VerifyRazorpayPaymentRequest {
    pub order_id: String,
    pub razorpay_order_id: String,
    pub razorpay_payment_id: String,
    pub razorpay_signature: String,
}

impl fmt::Debug for VerifyRazorpayPaymentRequest {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("VerifyRazorpayPaymentRequest")
            .field("order_id", &self.order_id)
            .field("razorpay_order_id", &self.razorpay_order_id)
            .field("razorpay_payment_id", &self.razorpay_payment_id)
            .field("razorpay_signature", &"[REDACTED]")
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VerifyRazorpayPaymentResponse {
    pub order: OrderDetailDto,
    #[serde(default)]
    pub already_captured: bool,
    #[serde(default)]
    pub late_capture_refund_triggered: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OwnerRefundRequest {
    #[serde(default)]
    pub amount_paise: Option<i64>,
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GatewayOrderResult {
    pub gateway_order_id: String,
    pub receipt: String,
    pub amount_paise: i64,
    pub currency: String,
    pub status: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GatewayPaymentResult {
    pub gateway_payment_id: String,
    pub gateway_order_id: String,
    pub amount_paise: i64,
    pub currency: String,
    pub status: String,
    #[serde(default)]
    pub error_code: Option<String>,
    #[serde(default)]
    pub error_description: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GatewayRefundResult {
    pub gateway_refund_id: String,
    pub gateway_payment_id: String,
    pub amount_paise: i64,
    pub status: String,
    #[serde(default)]
    pub notes: BTreeMap<String, String>,
}
