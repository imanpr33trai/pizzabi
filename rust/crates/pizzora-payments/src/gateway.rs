use std::collections::BTreeMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use pizzora_config::AppConfig;
use sha2::{Digest as _, Sha256};

use crate::dto::{GatewayOrderResult, GatewayPaymentResult, GatewayRefundResult};
use crate::error::RazorpayGatewayError;
pub use crate::http_gateway::HttpRazorpayClient;
pub use crate::in_memory_gateway::InMemoryRazorpayGateway;

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

pub trait RazorpayGateway: Send + Sync {
    fn public_key_id(&self) -> &str;

    fn create_order<'a>(
        &'a self,
        amount_paise: i64,
        currency: &'a str,
        receipt: &'a str,
        notes: BTreeMap<String, String>,
    ) -> BoxFuture<'a, Result<GatewayOrderResult, RazorpayGatewayError>>;

    fn create_refund<'a>(
        &'a self,
        gateway_payment_id: &'a str,
        amount_paise: i64,
        idempotency_key: &'a str,
        notes: BTreeMap<String, String>,
    ) -> BoxFuture<'a, Result<GatewayRefundResult, RazorpayGatewayError>>;

    fn fetch_payments_for_order<'a>(
        &'a self,
        gateway_order_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayPaymentResult>, RazorpayGatewayError>>;

    fn fetch_refunds_for_payment<'a>(
        &'a self,
        gateway_payment_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayRefundResult>, RazorpayGatewayError>>;
}

pub fn create_default_razorpay_gateway(
    config: &AppConfig,
) -> Result<Arc<dyn RazorpayGateway>, RazorpayGatewayError> {
    let rzp = &config.razorpay;
    if !rzp.key_id.trim().is_empty() && !rzp.key_secret().trim().is_empty() {
        Ok(Arc::new(HttpRazorpayClient::new(rzp)?))
    } else if config.is_production() {
        Ok(Arc::new(UnconfiguredRazorpayGateway))
    } else {
        let key_id = if rzp.key_id.trim().is_empty() {
            "rzp_test_sandbox"
        } else {
            rzp.key_id.trim()
        };
        Ok(Arc::new(SandboxRazorpayClient::new(key_id)))
    }
}

#[derive(Debug, Default, Clone, Copy)]
pub struct UnconfiguredRazorpayGateway;

impl RazorpayGateway for UnconfiguredRazorpayGateway {
    fn public_key_id(&self) -> &str {
        ""
    }

    fn create_order<'a>(
        &'a self,
        _amount_paise: i64,
        _currency: &'a str,
        _receipt: &'a str,
        _notes: BTreeMap<String, String>,
    ) -> BoxFuture<'a, Result<GatewayOrderResult, RazorpayGatewayError>> {
        Box::pin(async {
            Err(RazorpayGatewayError::permanent(
                Some(500),
                "RAZORPAY_NOT_CONFIGURED",
                "RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET must be configured in production.",
            ))
        })
    }

    fn create_refund<'a>(
        &'a self,
        _gateway_payment_id: &'a str,
        _amount_paise: i64,
        _idempotency_key: &'a str,
        _notes: BTreeMap<String, String>,
    ) -> BoxFuture<'a, Result<GatewayRefundResult, RazorpayGatewayError>> {
        Box::pin(async {
            Err(RazorpayGatewayError::permanent(
                Some(500),
                "RAZORPAY_NOT_CONFIGURED",
                "RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET must be configured in production.",
            ))
        })
    }

    fn fetch_payments_for_order<'a>(
        &'a self,
        _gateway_order_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayPaymentResult>, RazorpayGatewayError>> {
        Box::pin(async { Ok(Vec::new()) })
    }

    fn fetch_refunds_for_payment<'a>(
        &'a self,
        _gateway_payment_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayRefundResult>, RazorpayGatewayError>> {
        Box::pin(async { Ok(Vec::new()) })
    }
}

#[derive(Debug, Clone)]
pub struct SandboxRazorpayClient {
    public_key_id: String,
}

impl Default for SandboxRazorpayClient {
    fn default() -> Self {
        Self::new("rzp_test_sandbox")
    }
}

impl SandboxRazorpayClient {
    pub fn new(public_key_id: impl Into<String>) -> Self {
        Self {
            public_key_id: public_key_id.into(),
        }
    }
}

impl RazorpayGateway for SandboxRazorpayClient {
    fn public_key_id(&self) -> &str {
        &self.public_key_id
    }

    fn create_order<'a>(
        &'a self,
        amount_paise: i64,
        currency: &'a str,
        receipt: &'a str,
        _notes: BTreeMap<String, String>,
    ) -> BoxFuture<'a, Result<GatewayOrderResult, RazorpayGatewayError>> {
        Box::pin(async move {
            let suffix: String = receipt.replace('-', "").chars().take(14).collect();
            Ok(GatewayOrderResult {
                gateway_order_id: format!("order_sbx_{suffix}"),
                receipt: receipt.to_owned(),
                amount_paise,
                currency: currency.to_owned(),
                status: "created".to_owned(),
            })
        })
    }

    fn create_refund<'a>(
        &'a self,
        gateway_payment_id: &'a str,
        amount_paise: i64,
        idempotency_key: &'a str,
        mut notes: BTreeMap<String, String>,
    ) -> BoxFuture<'a, Result<GatewayRefundResult, RazorpayGatewayError>> {
        Box::pin(async move {
            let digest = Sha256::digest(idempotency_key.as_bytes());
            let hex_str = hex::encode(digest);
            let suffix = &hex_str[..14];
            notes.insert("idempotency_key".to_owned(), idempotency_key.to_owned());
            Ok(GatewayRefundResult {
                gateway_refund_id: format!("rfnd_sbx_{suffix}"),
                gateway_payment_id: gateway_payment_id.to_owned(),
                amount_paise,
                status: "processed".to_owned(),
                notes,
            })
        })
    }

    fn fetch_payments_for_order<'a>(
        &'a self,
        _gateway_order_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayPaymentResult>, RazorpayGatewayError>> {
        Box::pin(async { Ok(Vec::new()) })
    }

    fn fetch_refunds_for_payment<'a>(
        &'a self,
        _gateway_payment_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayRefundResult>, RazorpayGatewayError>> {
        Box::pin(async { Ok(Vec::new()) })
    }
}
