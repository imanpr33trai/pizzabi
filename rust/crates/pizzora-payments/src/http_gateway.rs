use std::collections::BTreeMap;
use std::fmt;
use std::time::Duration;

use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use base64::Engine as _;
use pizzora_config::RazorpayConfig;
use serde_json::{json, Map as JsonMap, Value as JsonValue};
use zeroize::{Zeroize as _, Zeroizing};

use crate::dto::{GatewayOrderResult, GatewayPaymentResult, GatewayRefundResult};
use crate::error::RazorpayGatewayError;
use crate::gateway::{BoxFuture, RazorpayGateway};

#[derive(Clone)]
pub struct HttpRazorpayClient {
    key_id: String,
    auth_header: Zeroizing<String>,
    base_url: String,
    client: reqwest::Client,
}

impl HttpRazorpayClient {
    pub fn new(config: &RazorpayConfig) -> Result<Self, RazorpayGatewayError> {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|err| {
                RazorpayGatewayError::permanent(
                    None,
                    "RAZORPAY_CLIENT_INIT",
                    format!("Failed to initialize Razorpay HTTP client: {err}"),
                )
            })?;
        Ok(Self::with_client(config, client))
    }

    pub fn with_client(config: &RazorpayConfig, client: reqwest::Client) -> Self {
        let raw_creds = Zeroizing::new(format!("{}:{}", config.key_id, config.key_secret()));
        let encoded = Zeroizing::new(BASE64_STANDARD.encode(raw_creds.as_bytes()));
        let auth_header = Zeroizing::new(format!("Basic {}", encoded.as_str()));
        Self {
            key_id: config.key_id.clone(),
            auth_header,
            base_url: config.base_url.trim_end_matches('/').to_owned(),
            client,
        }
    }

    async fn execute_json(
        &self,
        method: reqwest::Method,
        path: &str,
        body: Option<JsonValue>,
        extra_headers: &[(&str, &str)],
    ) -> Result<JsonValue, RazorpayGatewayError> {
        let url = format!("{}{path}", self.base_url);
        let mut req = self
            .client
            .request(method, &url)
            .header(reqwest::header::AUTHORIZATION, self.auth_header.as_str())
            .header(reqwest::header::ACCEPT, "application/json");

        for (k, v) in extra_headers {
            req = req.header(*k, *v);
        }

        if let Some(payload) = body {
            req = req
                .header(reqwest::header::CONTENT_TYPE, "application/json")
                .json(&payload);
        }

        let response = req.send().await.map_err(|err| {
            let safe_err = err.without_url();
            RazorpayGatewayError::transient(
                safe_err.status().map(|s| s.as_u16()),
                "RAZORPAY_NETWORK_ERROR",
                format!("Razorpay network call failed: {safe_err}"),
            )
        })?;

        let status = response.status().as_u16();
        let raw_text = response.text().await.unwrap_or_default();

        if !(200..=299).contains(&status) {
            let is_transient = status == 429 || status >= 500;
            let error_desc = serde_json::from_str::<JsonValue>(&raw_text)
                .ok()
                .and_then(|v| {
                    v.get("error")?
                        .get("description")?
                        .as_str()
                        .map(str::to_owned)
                })
                .unwrap_or_else(|| raw_text.chars().take(250).collect());

            return Err(RazorpayGatewayError {
                is_transient,
                status_code: Some(status),
                code: format!("RAZORPAY_HTTP_{status}"),
                message: format!("Razorpay API returned HTTP {status}: {error_desc}"),
            });
        }

        serde_json::from_str::<JsonValue>(&raw_text).map_err(|err| {
            RazorpayGatewayError::transient(
                Some(status),
                "RAZORPAY_INVALID_JSON",
                format!("Invalid JSON returned by Razorpay API: {err}"),
            )
        })
    }
}

impl fmt::Debug for HttpRazorpayClient {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("HttpRazorpayClient")
            .field("key_id", &self.key_id)
            .field("auth_header", &"[REDACTED]")
            .field("base_url", &self.base_url)
            .finish()
    }
}

impl Drop for HttpRazorpayClient {
    fn drop(&mut self) {
        self.auth_header.zeroize();
    }
}

impl RazorpayGateway for HttpRazorpayClient {
    fn public_key_id(&self) -> &str {
        &self.key_id
    }

    fn create_order<'a>(
        &'a self,
        amount_paise: i64,
        currency: &'a str,
        receipt: &'a str,
        notes: BTreeMap<String, String>,
    ) -> BoxFuture<'a, Result<GatewayOrderResult, RazorpayGatewayError>> {
        Box::pin(async move {
            let mut body = json!({
                "amount": amount_paise,
                "currency": currency,
                "receipt": receipt,
            });
            if !notes.is_empty() {
                let mut map = JsonMap::new();
                for (k, v) in notes {
                    map.insert(k, JsonValue::String(v));
                }
                if let Some(obj) = body.as_object_mut() {
                    obj.insert("notes".to_owned(), JsonValue::Object(map));
                }
            }

            let resp = self
                .execute_json(reqwest::Method::POST, "/orders", Some(body), &[])
                .await?;
            parse_order_object(&resp, receipt, amount_paise, currency)
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
            validate_gateway_path_segment(gateway_payment_id, "gateway_payment_id")?;
            notes.insert("idempotency_key".to_owned(), idempotency_key.to_owned());

            let mut notes_map = JsonMap::new();
            for (k, v) in notes {
                notes_map.insert(k, JsonValue::String(v));
            }
            let body = json!({
                "amount": amount_paise,
                "notes": JsonValue::Object(notes_map),
            });

            let path = format!("/payments/{gateway_payment_id}/refund");
            let headers = [("X-Razorpay-Idempotency-Key", idempotency_key)];
            let resp = self
                .execute_json(reqwest::Method::POST, &path, Some(body), &headers)
                .await?;
            parse_refund_object(&resp, gateway_payment_id, amount_paise)
        })
    }

    fn fetch_payments_for_order<'a>(
        &'a self,
        gateway_order_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayPaymentResult>, RazorpayGatewayError>> {
        Box::pin(async move {
            validate_gateway_path_segment(gateway_order_id, "gateway_order_id")?;
            let path = format!("/orders/{gateway_order_id}/payments");
            let resp = self
                .execute_json(reqwest::Method::GET, &path, None, &[])
                .await?;

            let Some(items) = resp.get("items").and_then(|v| v.as_array()) else {
                return Ok(Vec::new());
            };
            Ok(parse_payment_items(items, gateway_order_id))
        })
    }

    fn fetch_refunds_for_payment<'a>(
        &'a self,
        gateway_payment_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayRefundResult>, RazorpayGatewayError>> {
        Box::pin(async move {
            validate_gateway_path_segment(gateway_payment_id, "gateway_payment_id")?;
            let path = format!("/payments/{gateway_payment_id}/refunds");
            let resp = self
                .execute_json(reqwest::Method::GET, &path, None, &[])
                .await?;

            let Some(items) = resp.get("items").and_then(|v| v.as_array()) else {
                return Ok(Vec::new());
            };

            let mut out = Vec::with_capacity(items.len());
            for item in items {
                out.push(parse_refund_object(item, gateway_payment_id, 0)?);
            }
            Ok(out)
        })
    }
}

fn validate_gateway_path_segment(
    value: &str,
    field: &'static str,
) -> Result<(), RazorpayGatewayError> {
    if value.trim().is_empty()
        || value.contains(['/', '?', '#', '\\'])
        || value.chars().any(char::is_whitespace)
    {
        return Err(RazorpayGatewayError::permanent(
            Some(400),
            "INVALID_GATEWAY_ID",
            format!("Invalid {field} path segment"),
        ));
    }
    Ok(())
}

fn parse_order_object(
    resp: &JsonValue,
    fallback_receipt: &str,
    fallback_amount_paise: i64,
    fallback_currency: &str,
) -> Result<GatewayOrderResult, RazorpayGatewayError> {
    let obj = resp.as_object().ok_or_else(|| {
        RazorpayGatewayError::transient(
            Some(502),
            "MALFORMED_GATEWAY_RESPONSE",
            "Razorpay /orders response was not a JSON object.",
        )
    })?;
    let gateway_order_id = obj
        .get("id")
        .and_then(|v| v.as_str())
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| {
            RazorpayGatewayError::transient(
                Some(502),
                "MALFORMED_GATEWAY_RESPONSE",
                "Razorpay /orders response missing id.",
            )
        })?
        .to_owned();

    Ok(GatewayOrderResult {
        gateway_order_id,
        receipt: obj
            .get("receipt")
            .and_then(|v| v.as_str())
            .unwrap_or(fallback_receipt)
            .to_owned(),
        amount_paise: obj
            .get("amount")
            .and_then(|v| v.as_i64())
            .unwrap_or(fallback_amount_paise),
        currency: obj
            .get("currency")
            .and_then(|v| v.as_str())
            .unwrap_or(fallback_currency)
            .to_owned(),
        status: obj
            .get("status")
            .and_then(|v| v.as_str())
            .unwrap_or("created")
            .to_owned(),
    })
}

fn parse_payment_items(items: &[JsonValue], fallback_order_id: &str) -> Vec<GatewayPaymentResult> {
    let mut out = Vec::with_capacity(items.len());
    for item in items {
        let Some(obj) = item.as_object() else {
            continue;
        };
        let Some(id) = obj.get("id").and_then(|v| v.as_str()) else {
            continue;
        };
        out.push(GatewayPaymentResult {
            gateway_payment_id: id.to_owned(),
            gateway_order_id: obj
                .get("order_id")
                .and_then(|v| v.as_str())
                .unwrap_or(fallback_order_id)
                .to_owned(),
            amount_paise: obj.get("amount").and_then(|v| v.as_i64()).unwrap_or(0),
            currency: obj
                .get("currency")
                .and_then(|v| v.as_str())
                .unwrap_or("INR")
                .to_owned(),
            status: obj
                .get("status")
                .and_then(|v| v.as_str())
                .unwrap_or("unknown")
                .to_owned(),
            error_code: obj
                .get("error_code")
                .and_then(|v| v.as_str())
                .map(str::to_owned),
            error_description: obj
                .get("error_description")
                .and_then(|v| v.as_str())
                .map(str::to_owned),
        });
    }
    out
}

fn parse_refund_object(
    value: &JsonValue,
    fallback_payment_id: &str,
    fallback_amount_paise: i64,
) -> Result<GatewayRefundResult, RazorpayGatewayError> {
    let obj = value.as_object().ok_or_else(|| {
        RazorpayGatewayError::transient(
            Some(502),
            "MALFORMED_GATEWAY_RESPONSE",
            "Razorpay refund response was not a JSON object.",
        )
    })?;
    let gateway_refund_id = obj
        .get("id")
        .and_then(|v| v.as_str())
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| {
            RazorpayGatewayError::transient(
                Some(502),
                "MALFORMED_GATEWAY_RESPONSE",
                "Razorpay refund response missing id.",
            )
        })?
        .to_owned();

    let mut notes = BTreeMap::new();
    if let Some(notes_obj) = obj.get("notes").and_then(|v| v.as_object()) {
        for (k, v) in notes_obj {
            if let Some(s) = v.as_str() {
                notes.insert(k.clone(), s.to_owned());
            }
        }
    }

    Ok(GatewayRefundResult {
        gateway_refund_id,
        gateway_payment_id: obj
            .get("payment_id")
            .and_then(|v| v.as_str())
            .unwrap_or(fallback_payment_id)
            .to_owned(),
        amount_paise: obj
            .get("amount")
            .and_then(|v| v.as_i64())
            .unwrap_or(fallback_amount_paise),
        status: obj
            .get("status")
            .and_then(|v| v.as_str())
            .unwrap_or("processed")
            .to_owned(),
        notes,
    })
}
