use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

use uuid::Uuid;

use crate::dto::{GatewayOrderResult, GatewayPaymentResult, GatewayRefundResult};
use crate::error::RazorpayGatewayError;
use crate::gateway::{BoxFuture, RazorpayGateway};

/// Deterministic in-memory gateway test double mirroring Kotlin `FakeRazorpayGateway`.
/// Supports idempotent receipt/refund deduplication and transient/permanent fault injection.
#[derive(Debug)]
pub struct InMemoryRazorpayGateway {
    public_key_id: String,
    order_seq: AtomicU64,
    refund_seq: AtomicU64,
    state: Mutex<InMemoryGatewayState>,
    fail_order_remaining: AtomicU64,
    fail_order_transient: AtomicBool,
    fail_refund_remaining: AtomicU64,
    fail_refund_transient: AtomicBool,
    simulate_post_gateway_crash_on_create_refund: AtomicBool,
}

#[derive(Debug, Default)]
struct InMemoryGatewayState {
    created_orders: Vec<GatewayOrderResult>,
    created_refunds: Vec<GatewayRefundResult>,
    refunds_by_payment: HashMap<String, Vec<GatewayRefundResult>>,
    payments_by_order: HashMap<String, Vec<GatewayPaymentResult>>,
}

impl Default for InMemoryRazorpayGateway {
    fn default() -> Self {
        Self::new("rzp_test_in_memory")
    }
}

impl InMemoryRazorpayGateway {
    pub fn new(public_key_id: impl Into<String>) -> Self {
        Self {
            public_key_id: public_key_id.into(),
            order_seq: AtomicU64::new(1),
            refund_seq: AtomicU64::new(1),
            state: Mutex::new(InMemoryGatewayState::default()),
            fail_order_remaining: AtomicU64::new(0),
            fail_order_transient: AtomicBool::new(false),
            fail_refund_remaining: AtomicU64::new(0),
            fail_refund_transient: AtomicBool::new(false),
            simulate_post_gateway_crash_on_create_refund: AtomicBool::new(false),
        }
    }

    pub fn set_fail_next_orders(&self, count: u64, transient: bool) {
        self.fail_order_transient.store(transient, Ordering::SeqCst);
        self.fail_order_remaining.store(count, Ordering::SeqCst);
    }

    pub fn set_fail_next_refunds(&self, count: u64, transient: bool) {
        self.fail_refund_transient
            .store(transient, Ordering::SeqCst);
        self.fail_refund_remaining.store(count, Ordering::SeqCst);
    }

    pub fn set_simulate_post_gateway_crash_on_create_refund(&self, enabled: bool) {
        self.simulate_post_gateway_crash_on_create_refund
            .store(enabled, Ordering::SeqCst);
    }

    pub fn insert_mock_payment_for_order(
        &self,
        gateway_order_id: impl Into<String>,
        payment: GatewayPaymentResult,
    ) {
        let mut guard = self.state.lock().expect("gateway state lock");
        guard
            .payments_by_order
            .entry(gateway_order_id.into())
            .or_default()
            .push(payment);
    }

    pub fn insert_mock_refund_for_payment(
        &self,
        gateway_payment_id: impl Into<String>,
        refund: GatewayRefundResult,
    ) {
        let mut guard = self.state.lock().expect("gateway state lock");
        guard
            .refunds_by_payment
            .entry(gateway_payment_id.into())
            .or_default()
            .push(refund);
    }

    pub fn created_orders(&self) -> Vec<GatewayOrderResult> {
        self.state
            .lock()
            .expect("gateway state lock")
            .created_orders
            .clone()
    }

    pub fn created_refunds(&self) -> Vec<GatewayRefundResult> {
        self.state
            .lock()
            .expect("gateway state lock")
            .created_refunds
            .clone()
    }
}

impl RazorpayGateway for InMemoryRazorpayGateway {
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
            if self
                .fail_order_remaining
                .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |cur| cur.checked_sub(1))
                .is_ok()
            {
                let transient = self.fail_order_transient.load(Ordering::SeqCst);
                return Err(RazorpayGatewayError {
                    is_transient: transient,
                    status_code: Some(if transient { 503 } else { 400 }),
                    code: "GATEWAY_ORDER_ERROR".to_owned(),
                    message: "Simulated Razorpay order creation failure".to_owned(),
                });
            }

            let mut guard = self.state.lock().expect("gateway state lock");
            if let Some(existing) = guard
                .created_orders
                .iter()
                .find(|o| o.receipt == receipt)
                .cloned()
            {
                return Ok(existing);
            }

            let seq = self.order_seq.fetch_add(1, Ordering::SeqCst);
            let short = &Uuid::new_v4().simple().to_string()[..6];
            let result = GatewayOrderResult {
                gateway_order_id: format!("order_gw_{seq}_{short}"),
                receipt: receipt.to_owned(),
                amount_paise,
                currency: currency.to_owned(),
                status: "created".to_owned(),
            };
            guard.created_orders.push(result.clone());
            Ok(result)
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
            if self
                .fail_refund_remaining
                .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |cur| cur.checked_sub(1))
                .is_ok()
            {
                let transient = self.fail_refund_transient.load(Ordering::SeqCst);
                return Err(RazorpayGatewayError {
                    is_transient: transient,
                    status_code: Some(if transient { 503 } else { 400 }),
                    code: "GATEWAY_REFUND_ERROR".to_owned(),
                    message: "Simulated Razorpay refund failure".to_owned(),
                });
            }

            let refund = {
                let mut guard = self.state.lock().expect("gateway state lock");
                let existing = guard
                    .refunds_by_payment
                    .get(gateway_payment_id)
                    .and_then(|list| {
                        list.iter()
                            .find(|r| {
                                r.notes.get("idempotency_key").map(String::as_str)
                                    == Some(idempotency_key)
                            })
                            .cloned()
                    });

                if let Some(found) = existing {
                    found
                } else {
                    let seq = self.refund_seq.fetch_add(1, Ordering::SeqCst);
                    let short = &Uuid::new_v4().simple().to_string()[..6];
                    notes.insert("idempotency_key".to_owned(), idempotency_key.to_owned());
                    let created = GatewayRefundResult {
                        gateway_refund_id: format!("rfnd_gw_{seq}_{short}"),
                        gateway_payment_id: gateway_payment_id.to_owned(),
                        amount_paise,
                        status: "processed".to_owned(),
                        notes,
                    };
                    guard.created_refunds.push(created.clone());
                    guard
                        .refunds_by_payment
                        .entry(gateway_payment_id.to_owned())
                        .or_default()
                        .push(created.clone());
                    created
                }
            };

            if self
                .simulate_post_gateway_crash_on_create_refund
                .swap(false, Ordering::SeqCst)
            {
                return Err(RazorpayGatewayError::permanent(
                    Some(504),
                    "SIMULATED_RESPONSE_TIMEOUT",
                    "Simulated crash after gateway created refund",
                ));
            }

            Ok(refund)
        })
    }

    fn fetch_payments_for_order<'a>(
        &'a self,
        gateway_order_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayPaymentResult>, RazorpayGatewayError>> {
        Box::pin(async move {
            let guard = self.state.lock().expect("gateway state lock");
            Ok(guard
                .payments_by_order
                .get(gateway_order_id)
                .cloned()
                .unwrap_or_default())
        })
    }

    fn fetch_refunds_for_payment<'a>(
        &'a self,
        gateway_payment_id: &'a str,
    ) -> BoxFuture<'a, Result<Vec<GatewayRefundResult>, RazorpayGatewayError>> {
        Box::pin(async move {
            let guard = self.state.lock().expect("gateway state lock");
            Ok(guard
                .refunds_by_payment
                .get(gateway_payment_id)
                .cloned()
                .unwrap_or_default())
        })
    }
}
