use std::sync::Mutex;

use pizzora_core::{
    CartSyncAction, CartSyncPlanRequest, CartSyncPlanner, CartSyncTrigger, CouponCalculationSpec,
    CouponEvaluator, DiscountType, FulfillmentType, Money, OrderCausalTracker, OrderStateMachine,
    OrderStatus, PaymentAttemptStatus, PaymentStateMachine, PaymentStatus, PriceBreakdown,
    PricedLineInput, PricingEngine, RealtimeEventInput, SessionIdentity, StorageKeyValidator,
    UserRole,
};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum UniffiDomainError {
    #[error("{detail}")]
    Domain { detail: String },
}

impl From<pizzora_core::DomainError> for UniffiDomainError {
    fn from(err: pizzora_core::DomainError) -> Self {
        Self::Domain {
            detail: err.to_string(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiDiscountType {
    PercentBps,
    FlatPaise,
}

impl From<FfiDiscountType> for DiscountType {
    fn from(value: FfiDiscountType) -> Self {
        match value {
            FfiDiscountType::PercentBps => Self::PercentBps,
            FfiDiscountType::FlatPaise => Self::FlatPaise,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiUserRole {
    Customer,
    KitchenStaff,
    Owner,
}

impl From<FfiUserRole> for UserRole {
    fn from(value: FfiUserRole) -> Self {
        match value {
            FfiUserRole::Customer => Self::Customer,
            FfiUserRole::KitchenStaff => Self::KitchenStaff,
            FfiUserRole::Owner => Self::Owner,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiOrderStatus {
    PendingPayment,
    Placed,
    Accepted,
    Preparing,
    Ready,
    OutForDelivery,
    Completed,
    Cancelled,
    Expired,
}

impl From<FfiOrderStatus> for OrderStatus {
    fn from(value: FfiOrderStatus) -> Self {
        match value {
            FfiOrderStatus::PendingPayment => Self::PendingPayment,
            FfiOrderStatus::Placed => Self::Placed,
            FfiOrderStatus::Accepted => Self::Accepted,
            FfiOrderStatus::Preparing => Self::Preparing,
            FfiOrderStatus::Ready => Self::Ready,
            FfiOrderStatus::OutForDelivery => Self::OutForDelivery,
            FfiOrderStatus::Completed => Self::Completed,
            FfiOrderStatus::Cancelled => Self::Cancelled,
            FfiOrderStatus::Expired => Self::Expired,
        }
    }
}

impl From<OrderStatus> for FfiOrderStatus {
    fn from(value: OrderStatus) -> Self {
        match value {
            OrderStatus::PendingPayment => Self::PendingPayment,
            OrderStatus::Placed => Self::Placed,
            OrderStatus::Accepted => Self::Accepted,
            OrderStatus::Preparing => Self::Preparing,
            OrderStatus::Ready => Self::Ready,
            OrderStatus::OutForDelivery => Self::OutForDelivery,
            OrderStatus::Completed => Self::Completed,
            OrderStatus::Cancelled => Self::Cancelled,
            OrderStatus::Expired => Self::Expired,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiFulfillmentType {
    Delivery,
    Pickup,
}

impl From<FfiFulfillmentType> for FulfillmentType {
    fn from(value: FfiFulfillmentType) -> Self {
        match value {
            FfiFulfillmentType::Delivery => Self::Delivery,
            FfiFulfillmentType::Pickup => Self::Pickup,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiPaymentStatus {
    Unpaid,
    CodPending,
    CodCollected,
    Captured,
    Expired,
    Cancelled,
    RefundPending,
    PartiallyRefunded,
    Refunded,
}

impl From<FfiPaymentStatus> for PaymentStatus {
    fn from(value: FfiPaymentStatus) -> Self {
        match value {
            FfiPaymentStatus::Unpaid => Self::Unpaid,
            FfiPaymentStatus::CodPending => Self::CodPending,
            FfiPaymentStatus::CodCollected => Self::CodCollected,
            FfiPaymentStatus::Captured => Self::Captured,
            FfiPaymentStatus::Expired => Self::Expired,
            FfiPaymentStatus::Cancelled => Self::Cancelled,
            FfiPaymentStatus::RefundPending => Self::RefundPending,
            FfiPaymentStatus::PartiallyRefunded => Self::PartiallyRefunded,
            FfiPaymentStatus::Refunded => Self::Refunded,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiPaymentAttemptStatus {
    Initiating,
    AwaitingCustomerPayment,
    Authorized,
    Captured,
    Failed,
    Expired,
    Cancelled,
}

impl From<FfiPaymentAttemptStatus> for PaymentAttemptStatus {
    fn from(value: FfiPaymentAttemptStatus) -> Self {
        match value {
            FfiPaymentAttemptStatus::Initiating => Self::Initiating,
            FfiPaymentAttemptStatus::AwaitingCustomerPayment => Self::AwaitingCustomerPayment,
            FfiPaymentAttemptStatus::Authorized => Self::Authorized,
            FfiPaymentAttemptStatus::Captured => Self::Captured,
            FfiPaymentAttemptStatus::Failed => Self::Failed,
            FfiPaymentAttemptStatus::Expired => Self::Expired,
            FfiPaymentAttemptStatus::Cancelled => Self::Cancelled,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiCartSyncTrigger {
    PassiveBootstrap,
    ExplicitMutation,
    ExplicitClearOrRemove,
    PeriodicValidate,
}

impl From<FfiCartSyncTrigger> for CartSyncTrigger {
    fn from(value: FfiCartSyncTrigger) -> Self {
        match value {
            FfiCartSyncTrigger::PassiveBootstrap => Self::PassiveBootstrap,
            FfiCartSyncTrigger::ExplicitMutation => Self::ExplicitMutation,
            FfiCartSyncTrigger::ExplicitClearOrRemove => Self::ExplicitClearOrRemove,
            FfiCartSyncTrigger::PeriodicValidate => Self::PeriodicValidate,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiCartSyncAction {
    DiscardStaleSession,
    FetchServerCart,
    ReplaceServerCartEmpty,
    ReplaceServerCartWithValidateFallback,
    ValidateCartOnly,
    NoOpEmptyLocal,
}

impl From<CartSyncAction> for FfiCartSyncAction {
    fn from(value: CartSyncAction) -> Self {
        match value {
            CartSyncAction::DiscardStaleSession => Self::DiscardStaleSession,
            CartSyncAction::FetchServerCart => Self::FetchServerCart,
            CartSyncAction::ReplaceServerCartEmpty => Self::ReplaceServerCartEmpty,
            CartSyncAction::ReplaceServerCartWithValidateFallback => {
                Self::ReplaceServerCartWithValidateFallback
            }
            CartSyncAction::ValidateCartOnly => Self::ValidateCartOnly,
            CartSyncAction::NoOpEmptyLocal => Self::NoOpEmptyLocal,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum FfiCausalDecision {
    IgnoreDuplicate,
    IgnoreStale,
    ApplyOrderEvent { event_id: String },
    TriggerResync { order_id: String, event_id: String },
    ApplyNonOrderEvent { event_id: String },
}

impl From<pizzora_core::CausalDecision> for FfiCausalDecision {
    fn from(value: pizzora_core::CausalDecision) -> Self {
        match value {
            pizzora_core::CausalDecision::IgnoreDuplicate => Self::IgnoreDuplicate,
            pizzora_core::CausalDecision::IgnoreStale => Self::IgnoreStale,
            pizzora_core::CausalDecision::ApplyOrderEvent { event_id } => {
                Self::ApplyOrderEvent { event_id }
            }
            pizzora_core::CausalDecision::TriggerResync { order_id, event_id } => {
                Self::TriggerResync { order_id, event_id }
            }
            pizzora_core::CausalDecision::ApplyNonOrderEvent { event_id } => {
                Self::ApplyNonOrderEvent { event_id }
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiPricedLineInput {
    pub unit_price_paise: i64,
    pub quantity: i32,
    pub is_taxable: bool,
}

impl From<FfiPricedLineInput> for PricedLineInput {
    fn from(value: FfiPricedLineInput) -> Self {
        Self {
            unit_price_paise: value.unit_price_paise,
            quantity: value.quantity,
            is_taxable: value.is_taxable,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiCouponCalculationSpec {
    pub discount_type: FfiDiscountType,
    pub discount_value: i64,
    pub max_discount_paise: Option<i64>,
    pub min_order_paise: i64,
    pub is_active: bool,
}

impl From<FfiCouponCalculationSpec> for CouponCalculationSpec {
    fn from(value: FfiCouponCalculationSpec) -> Self {
        Self {
            discount_type: value.discount_type.into(),
            discount_value: value.discount_value,
            max_discount_paise: value.max_discount_paise,
            min_order_paise: value.min_order_paise,
            is_active: value.is_active,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiPriceBreakdown {
    pub subtotal_paise: i64,
    pub taxable_subtotal_paise: i64,
    pub discount_paise: i64,
    pub taxable_amount_paise: i64,
    pub delivery_fee_paise: i64,
    pub gst_bps: i32,
    pub tax_paise: i64,
    pub cgst_paise: i64,
    pub sgst_paise: i64,
    pub total_paise: i64,
}

impl From<PriceBreakdown> for FfiPriceBreakdown {
    fn from(value: PriceBreakdown) -> Self {
        Self {
            subtotal_paise: value.subtotal_paise,
            taxable_subtotal_paise: value.taxable_subtotal_paise,
            discount_paise: value.discount_paise,
            taxable_amount_paise: value.taxable_amount_paise,
            delivery_fee_paise: value.delivery_fee_paise,
            gst_bps: value.gst_bps,
            tax_paise: value.tax_paise,
            cgst_paise: value.cgst_paise,
            sgst_paise: value.sgst_paise,
            total_paise: value.total_paise,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiRealtimeEventInput {
    pub event_id: String,
    pub stream_seq: i64,
    pub event_type: String,
    pub aggregate_type: String,
    pub aggregate_id: String,
    pub aggregate_version: i64,
    pub order_id: Option<String>,
}

impl From<FfiRealtimeEventInput> for RealtimeEventInput {
    fn from(value: FfiRealtimeEventInput) -> Self {
        Self {
            event_id: value.event_id,
            stream_seq: value.stream_seq,
            event_type: value.event_type,
            aggregate_type: value.aggregate_type,
            aggregate_id: value.aggregate_id,
            aggregate_version: value.aggregate_version,
            order_id: value.order_id,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiSessionIdentity {
    pub epoch: i64,
    pub user_id: String,
}

impl From<FfiSessionIdentity> for SessionIdentity {
    fn from(value: FfiSessionIdentity) -> Self {
        Self {
            epoch: value.epoch,
            user_id: value.user_id,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiCartSyncPlanRequest {
    pub active_session: Option<FfiSessionIdentity>,
    pub captured_session: FfiSessionIdentity,
    pub trigger: FfiCartSyncTrigger,
    pub local_item_count: i32,
    pub persist_to_server: bool,
    pub allow_empty_replace: bool,
}

#[derive(uniffi::Object)]
pub struct FfiOrderCausalTracker {
    inner: Mutex<OrderCausalTracker>,
}

#[uniffi::export]
impl FfiOrderCausalTracker {
    #[uniffi::constructor]
    pub fn new(max_tracked_event_ids: i32) -> Self {
        let capacity = usize::try_from(max_tracked_event_ids.max(1)).unwrap_or(1);
        Self {
            inner: Mutex::new(OrderCausalTracker::new(capacity)),
        }
    }

    pub fn last_seen_stream_seq(&self) -> i64 {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .last_seen_stream_seq()
    }

    pub fn advance_cursor(&self, server_high_water_seq: i64) {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .advance_cursor(server_high_water_seq);
    }

    pub fn record_snapshot(&self, order_id: String, version: i64) {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .record_snapshot(&order_id, version);
    }

    pub fn local_version_for(&self, order_id: String) -> Option<i64> {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .local_version_for(&order_id)
    }

    pub fn evaluate(&self, event: FfiRealtimeEventInput) -> FfiCausalDecision {
        let core_event: RealtimeEventInput = event.into();
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .evaluate(&core_event)
            .into()
    }

    pub fn clear(&self) {
        self.inner.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }
}

#[uniffi::export]
pub fn pizzora_core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[uniffi::export]
pub fn money_bps_of(amount_paise: i64, bps: i32) -> Result<i64, UniffiDomainError> {
    Money::bps_of(amount_paise, bps).map_err(Into::into)
}

#[uniffi::export]
pub fn money_format_inr(paise: i64) -> String {
    Money::format_inr(paise)
}

#[uniffi::export]
pub fn pricing_calculate_line_total_paise(
    unit_price_paise: i64,
    quantity: i32,
) -> Result<i64, UniffiDomainError> {
    PricingEngine::calculate_line_total_paise(unit_price_paise, quantity).map_err(Into::into)
}

#[uniffi::export]
pub fn pricing_calculate_discount_paise(
    subtotal_paise: i64,
    coupon: Option<FfiCouponCalculationSpec>,
) -> Result<i64, UniffiDomainError> {
    let core_coupon = coupon.map(CouponCalculationSpec::from);
    PricingEngine::calculate_discount_paise(subtotal_paise, core_coupon.as_ref())
        .map_err(Into::into)
}

#[uniffi::export]
pub fn pricing_calculate_quote(
    lines: Vec<FfiPricedLineInput>,
    gst_bps: i32,
    delivery_fee_paise: i64,
    coupon: Option<FfiCouponCalculationSpec>,
) -> Result<FfiPriceBreakdown, UniffiDomainError> {
    let core_lines: Vec<PricedLineInput> = lines.into_iter().map(Into::into).collect();
    let core_coupon = coupon.map(CouponCalculationSpec::from);
    PricingEngine::calculate_quote(
        &core_lines,
        gst_bps,
        delivery_fee_paise,
        core_coupon.as_ref(),
    )
    .map(Into::into)
    .map_err(Into::into)
}

#[uniffi::export]
pub fn coupon_normalize_code(raw: String) -> Result<String, UniffiDomainError> {
    CouponEvaluator::normalize_code(&raw).map_err(Into::into)
}

#[uniffi::export]
pub fn order_can_customer_cancel(status: FfiOrderStatus) -> bool {
    OrderStateMachine::can_customer_cancel(status.into())
}

#[uniffi::export]
pub fn order_can_owner_cancel(status: FfiOrderStatus) -> bool {
    OrderStateMachine::can_owner_cancel(status.into())
}

#[uniffi::export]
pub fn order_can_cancel(status: FfiOrderStatus, actor_role: FfiUserRole) -> bool {
    OrderStateMachine::can_cancel(status.into(), actor_role.into())
}

#[uniffi::export]
pub fn order_can_transition(
    from: FfiOrderStatus,
    to: FfiOrderStatus,
    fulfillment_type: FfiFulfillmentType,
    actor_role: Option<FfiUserRole>,
) -> bool {
    OrderStateMachine::can_transition(
        from.into(),
        to.into(),
        fulfillment_type.into(),
        actor_role.map(Into::into),
    )
}

#[uniffi::export]
pub fn order_next_kds_status(
    status: FfiOrderStatus,
    fulfillment_type: FfiFulfillmentType,
) -> Option<FfiOrderStatus> {
    OrderStateMachine::next_kds_status(status.into(), fulfillment_type.into()).map(Into::into)
}

#[uniffi::export]
pub fn payment_can_transition_payment(from: FfiPaymentStatus, to: FfiPaymentStatus) -> bool {
    PaymentStateMachine::can_transition_payment(from.into(), to.into())
}

#[uniffi::export]
pub fn payment_can_transition_attempt(
    from: FfiPaymentAttemptStatus,
    to: FfiPaymentAttemptStatus,
) -> bool {
    PaymentStateMachine::can_transition_attempt(from.into(), to.into())
}

#[uniffi::export]
pub fn cart_plan_sync(
    request: FfiCartSyncPlanRequest,
) -> Result<FfiCartSyncAction, UniffiDomainError> {
    let local_item_count =
        usize::try_from(request.local_item_count).map_err(|_| UniffiDomainError::Domain {
            detail: "local_item_count must be non-negative".to_owned(),
        })?;
    let core_req = CartSyncPlanRequest {
        active_session: request.active_session.map(Into::into),
        captured_session: request.captured_session.into(),
        trigger: request.trigger.into(),
        local_item_count,
        persist_to_server: request.persist_to_server,
        allow_empty_replace: request.allow_empty_replace,
    };
    Ok(CartSyncPlanner::plan(&core_req).into())
}

#[uniffi::export]
pub fn cart_fallback_on_replace_http_error(status_code: i32) -> Option<FfiCartSyncAction> {
    u16::try_from(status_code)
        .ok()
        .and_then(CartSyncPlanner::fallback_action_on_replace_http_error)
        .map(Into::into)
}

#[uniffi::export]
pub fn storage_validate_object_key(raw_key: String) -> Result<String, UniffiDomainError> {
    StorageKeyValidator::validate_object_key(&raw_key)
        .map(ToOwned::to_owned)
        .map_err(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn thin_uniffi_adapter_delegates_to_pizzora_core() {
        assert_eq!(money_format_inr(29_900), "₹299.00");
        assert_eq!(money_bps_of(10_005, 500).unwrap(), 500);

        let tracker = FfiOrderCausalTracker::new(100);
        tracker.record_snapshot("ord-1".to_owned(), 1);
        let decision = tracker.evaluate(FfiRealtimeEventInput {
            event_id: "evt-2".to_owned(),
            stream_seq: 11,
            event_type: "order.status_changed".to_owned(),
            aggregate_type: "ORDER".to_owned(),
            aggregate_id: "ord-1".to_owned(),
            aggregate_version: 2,
            order_id: Some("ord-1".to_owned()),
        });
        assert_eq!(
            decision,
            FfiCausalDecision::ApplyOrderEvent {
                event_id: "evt-2".to_owned()
            }
        );
        assert_eq!(tracker.local_version_for("ord-1".to_owned()), Some(2));
        assert_eq!(tracker.last_seen_stream_seq(), 11);

        let action = cart_plan_sync(FfiCartSyncPlanRequest {
            active_session: Some(FfiSessionIdentity {
                epoch: 2,
                user_id: "user-b".to_owned(),
            }),
            captured_session: FfiSessionIdentity {
                epoch: 1,
                user_id: "user-a".to_owned(),
            },
            trigger: FfiCartSyncTrigger::ExplicitMutation,
            local_item_count: 2,
            persist_to_server: true,
            allow_empty_replace: false,
        })
        .unwrap();
        assert_eq!(action, FfiCartSyncAction::DiscardStaleSession);
        assert_eq!(
            storage_validate_object_key("products/1/pizza.jpg".to_owned()).unwrap(),
            "products/1/pizza.jpg"
        );
        assert!(storage_validate_object_key("../etc/passwd".to_owned()).is_err());
    }
}
