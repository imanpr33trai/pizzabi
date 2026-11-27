use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

use pizzora_core::cart_validation::CatalogProductToppingRef;
use pizzora_core::{
    CartItemInput, CartSyncAction, CartSyncPlanRequest, CartSyncPlanner, CartSyncTrigger,
    CartValidation, CatalogCheeseRef, CatalogCrustRef, CatalogHalfAndHalfConfigRef,
    CatalogProductRef, CatalogRefLookup, CatalogSauceRef, CatalogStandardToppingRef,
    CatalogToppingRef, CatalogVariantRef, CouponCalculationSpec, CouponEvaluator, DiscountType,
    DomainError, FulfillmentType, HalfAndHalfBreakdown, HalfAndHalfInput, Money,
    OrderCausalTracker, OrderStateMachine, OrderStatus, PaymentAttemptStatus, PaymentStateMachine,
    PaymentStatus, PriceBreakdown, PricedLineInput, PricedLineInputExtended, PricingEngine,
    RealtimeEventInput, SessionIdentity, SizeCode, StandardToppingSelection, StorageKeyValidator,
    UserRole, MAX_HALF_AND_HALF_DISTINCT_TOPPINGS, MAX_HALF_AND_HALF_TOPPINGS_PER_HALF,
    MAX_ITEM_QUANTITY,
};
use uuid::Uuid;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum UniffiDomainError {
    #[error("{detail}")]
    Domain { detail: String },
}

impl From<DomainError> for UniffiDomainError {
    fn from(err: DomainError) -> Self {
        Self::Domain {
            detail: err.to_string(),
        }
    }
}

/// Parse a Kotlin-supplied UUID string without ever panicking across the FFI boundary.
fn parse_ffi_uuid(raw: &str, label: &str) -> Result<Uuid, UniffiDomainError> {
    Uuid::parse_str(raw.trim()).map_err(|err| UniffiDomainError::Domain {
        detail: format!("Invalid {label} UUID: {raw} ({err})"),
    })
}

fn parse_size_code(raw: &str, variant_id: &str) -> Result<SizeCode, UniffiDomainError> {
    match raw.trim().to_ascii_uppercase().as_str() {
        "REGULAR" => Ok(SizeCode::Regular),
        "MEDIUM" => Ok(SizeCode::Medium),
        "LARGE" => Ok(SizeCode::Large),
        other => Err(UniffiDomainError::Domain {
            detail: format!(
                "Invalid size code '{other}' for variant {variant_id}; expected REGULAR|MEDIUM|LARGE"
            ),
        }),
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

// ============================================================================
// Phase 8: New FFI Records per contract §7
// ============================================================================

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiCheeseOption {
    pub id: String,
    pub product_id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_active: bool,
    pub sort_order: i32,
}

impl From<CatalogCheeseRef> for FfiCheeseOption {
    fn from(c: CatalogCheeseRef) -> Self {
        Self {
            id: c.id.to_string(),
            product_id: c.product_id.to_string(),
            name: c.name,
            extra_price_paise: c.extra_price_paise,
            is_active: c.is_active,
            sort_order: 0, // Not available in core type
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiSauceOption {
    pub id: String,
    pub product_id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_active: bool,
    pub sort_order: i32,
}

impl From<CatalogSauceRef> for FfiSauceOption {
    fn from(s: CatalogSauceRef) -> Self {
        Self {
            id: s.id.to_string(),
            product_id: s.product_id.to_string(),
            name: s.name,
            extra_price_paise: s.extra_price_paise,
            is_active: s.is_active,
            sort_order: 0, // Not available in core type
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiStandardTopping {
    pub id: String,
    pub product_id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_default: bool,
    pub is_active: bool,
    pub sort_order: i32,
}

impl From<CatalogStandardToppingRef> for FfiStandardTopping {
    fn from(s: CatalogStandardToppingRef) -> Self {
        Self {
            id: s.id.to_string(),
            product_id: s.product_id.to_string(),
            name: s.name,
            is_veg: s.is_veg,
            extra_price_paise: s.extra_price_paise,
            is_default: s.is_default,
            is_active: s.is_active,
            sort_order: 0, // Not available in core type
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiHalfAndHalfConfig {
    pub id: String,
    pub product_id: String,
    pub name: String,
    pub left_topping_ids: Vec<String>,
    pub right_topping_ids: Vec<String>,
    pub half_and_half_surcharge_paise: i64,
    pub is_active: bool,
    pub sort_order: i32,
}

impl From<CatalogHalfAndHalfConfigRef> for FfiHalfAndHalfConfig {
    fn from(h: CatalogHalfAndHalfConfigRef) -> Self {
        Self {
            id: h.id.to_string(),
            product_id: h.product_id.to_string(),
            name: h.name,
            left_topping_ids: h.left_topping_ids.iter().map(|u| u.to_string()).collect(),
            right_topping_ids: h.right_topping_ids.iter().map(|u| u.to_string()).collect(),
            half_and_half_surcharge_paise: h.half_and_half_surcharge_paise,
            is_active: h.is_active,
            sort_order: 0, // Not available in core type
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiHalfAndHalfBreakdown {
    pub left_half_base_paise: i64,
    pub right_half_base_paise: i64,
    pub average_paise: i64,
    pub surcharge_paise: i64,
    pub cheese_extra_paise: i64,
    pub sauce_extra_paise: i64,
    pub standard_toppings_extra_paise: i64,
    pub unit_price_paise: i64,
}

impl From<HalfAndHalfBreakdown> for FfiHalfAndHalfBreakdown {
    fn from(b: HalfAndHalfBreakdown) -> Self {
        Self {
            left_half_base_paise: b.left_half_base_paise,
            right_half_base_paise: b.right_half_base_paise,
            average_paise: b.average_paise,
            surcharge_paise: b.surcharge_paise,
            cheese_extra_paise: b.cheese_extra_paise,
            sauce_extra_paise: b.sauce_extra_paise,
            standard_toppings_extra_paise: b.standard_toppings_extra_paise,
            unit_price_paise: b.unit_price_paise,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiStandardToppingSelection {
    pub id: String,
    pub extra_price_paise: i64,
    pub is_default: bool,
    pub is_removed: bool,
}

impl TryFrom<FfiStandardToppingSelection> for StandardToppingSelection {
    type Error = UniffiDomainError;

    fn try_from(s: FfiStandardToppingSelection) -> Result<Self, Self::Error> {
        Ok(Self {
            id: parse_ffi_uuid(&s.id, "standard topping selection")?,
            extra_price_paise: s.extra_price_paise,
            is_default: s.is_default,
            is_removed: s.is_removed,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiPricedLineInputExtended {
    pub variant_price_paise: i64,
    pub crust_extra_paise: i64,
    pub cheese_extra_paise: i64,
    pub sauce_extra_paise: i64,
    pub standard_toppings: Vec<FfiStandardToppingSelection>,
    pub extra_topping_extras: Vec<i64>,
    pub half_and_half_left_topping_extras: Option<Vec<i64>>,
    pub half_and_half_right_topping_extras: Option<Vec<i64>>,
    pub half_and_half_surcharge_paise: Option<i64>,
    pub quantity: i32,
}

impl TryFrom<FfiPricedLineInputExtended> for PricedLineInputExtended {
    type Error = UniffiDomainError;

    fn try_from(f: FfiPricedLineInputExtended) -> Result<Self, Self::Error> {
        let half_and_half = if f.half_and_half_left_topping_extras.is_some()
            || f.half_and_half_right_topping_extras.is_some()
            || f.half_and_half_surcharge_paise.is_some()
        {
            Some(HalfAndHalfInput {
                left_topping_extras: f.half_and_half_left_topping_extras.unwrap_or_default(),
                right_topping_extras: f.half_and_half_right_topping_extras.unwrap_or_default(),
                surcharge_paise: f.half_and_half_surcharge_paise.unwrap_or(0),
            })
        } else {
            None
        };

        Ok(Self {
            variant_price_paise: f.variant_price_paise,
            crust_extra_paise: f.crust_extra_paise,
            cheese_extra_paise: f.cheese_extra_paise,
            sauce_extra_paise: f.sauce_extra_paise,
            standard_toppings: f
                .standard_toppings
                .into_iter()
                .map(StandardToppingSelection::try_from)
                .collect::<Result<Vec<_>, _>>()?,
            extra_topping_extras: f.extra_topping_extras,
            half_and_half,
            quantity: f.quantity,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiCartItemInputExtended {
    pub id: Option<String>,
    pub product_id: String,
    pub variant_id: String,
    pub crust_id: String,
    pub topping_ids: Vec<String>,
    pub quantity: i32,
    pub cheese_id: Option<String>,
    pub sauce_id: Option<String>,
    pub standard_topping_ids: Vec<String>,
    pub half_and_half_config_id: Option<String>,
    pub half_and_half_left_topping_ids: Vec<String>,
    pub half_and_half_right_topping_ids: Vec<String>,
}

impl From<FfiCartItemInputExtended> for CartItemInput {
    fn from(f: FfiCartItemInputExtended) -> Self {
        Self {
            id: f.id,
            product_id: f.product_id,
            variant_id: f.variant_id,
            crust_id: f.crust_id,
            topping_ids: f.topping_ids,
            quantity: f.quantity,
            cheese_id: f.cheese_id,
            sauce_id: f.sauce_id,
            standard_topping_ids: f.standard_topping_ids,
            half_and_half_config_id: f.half_and_half_config_id,
            half_and_half_left_topping_ids: f.half_and_half_left_topping_ids,
            half_and_half_right_topping_ids: f.half_and_half_right_topping_ids,
        }
    }
}

/// Platform-provided catalog snapshot. All IDs are strings at the UniFFI boundary and are
/// parsed defensively inside Rust; malformed IDs become `UniffiDomainError::Domain`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiModifierAvailability {
    // Product-scoped modifier arrays (the platform supplies these from the menu response).
    pub cheeses: Vec<FfiCheeseOption>,
    pub sauces: Vec<FfiSauceOption>,
    pub standard_toppings: Vec<FfiStandardTopping>,
    pub half_and_half_configs: Vec<FfiHalfAndHalfConfig>,

    // Generic catalog data needed to reconstruct a complete core CatalogRefLookup.
    pub product_ids: Vec<String>,
    pub product_names: HashMap<String, String>,
    pub product_is_veg: HashMap<String, bool>,
    pub product_is_taxable: HashMap<String, bool>,
    pub product_image_urls: HashMap<String, String>,
    pub variant_product_ids: HashMap<String, String>,
    pub variant_size_codes: HashMap<String, String>,
    pub variant_prices_paise: HashMap<String, i64>,
    pub active_variant_ids: Vec<String>,
    pub active_crust_ids: Vec<String>,
    pub crust_names: HashMap<String, String>,
    pub crust_extra_paise: HashMap<String, i64>,
    pub active_topping_ids: Vec<String>,
    pub topping_names: HashMap<String, String>,
    pub topping_is_veg: HashMap<String, bool>,
    pub topping_extra_paise: HashMap<String, i64>,
    /// Topping IDs available per product (the `product_toppings` join table).
    pub product_toppings: HashMap<String, Vec<String>>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiReorderItem {
    pub order_item_id: String,
    pub product_id: String,
    pub product_name: String,
    pub variant_id: Option<String>,
    pub crust_id: Option<String>,
    pub cheese_id: Option<String>,
    pub sauce_id: Option<String>,
    pub topping_ids: Vec<String>,
    pub standard_topping_ids: Vec<String>,
    pub standard_topping_removed_ids: Vec<String>,
    pub half_and_half_config_id: Option<String>,
    pub half_and_half_left_topping_ids: Vec<String>,
    pub half_and_half_right_topping_ids: Vec<String>,
    pub quantity: i32,
    pub is_available: bool,
    pub issues: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiReorderUnavailable {
    pub order_item_id: String,
    pub product_name: String,
    pub modifier_type: String,
    pub modifier_id: String,
    pub modifier_name: String,
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiReorderValidation {
    pub order_id: String,
    pub can_reorder: bool,
    pub items: Vec<FfiReorderItem>,
    pub unavailable: Vec<FfiReorderUnavailable>,
    pub warnings: Vec<String>,
}

// ============================================================================
// Existing FFI Records (Phase 3/4/6/7)
// ============================================================================

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

// ============================================================================
// Exported Functions
// ============================================================================

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

// --- Pricing Exports ---

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

// Phase 8: New pricing exports per contract §7

#[uniffi::export]
pub fn pricing_calculate_unit_price_paise(
    input: FfiPricedLineInputExtended,
) -> Result<i64, UniffiDomainError> {
    let core_input: PricedLineInputExtended = input.try_into()?;
    let std_extra =
        PricingEngine::calculate_standard_toppings_extra_paise(&core_input.standard_toppings)?;
    PricingEngine::calculate_unit_price_paise(
        core_input.variant_price_paise,
        core_input.crust_extra_paise,
        core_input.cheese_extra_paise,
        core_input.sauce_extra_paise,
        std_extra,
        &core_input.extra_topping_extras,
        core_input.half_and_half.as_ref(),
    )
    .map_err(Into::into)
}

#[uniffi::export]
pub fn pricing_calculate_standard_toppings_extra_paise(
    selections: Vec<FfiStandardToppingSelection>,
) -> Result<i64, UniffiDomainError> {
    let core_selections = selections
        .into_iter()
        .map(StandardToppingSelection::try_from)
        .collect::<Result<Vec<_>, _>>()?;
    PricingEngine::calculate_standard_toppings_extra_paise(&core_selections).map_err(Into::into)
}

/// Validate half-and-half limits with the core constants and return the exact paise breakdown
/// produced by the core pricing engine. No pricing arithmetic is duplicated here.
#[uniffi::export]
pub fn pricing_validate_half_and_half(
    input: FfiPricedLineInputExtended,
) -> Result<FfiHalfAndHalfBreakdown, UniffiDomainError> {
    let core_input: PricedLineInputExtended = input.try_into()?;
    let half_and_half = core_input.half_and_half.as_ref().ok_or_else(|| {
        UniffiDomainError::Domain {
            detail: "HALF_AND_HALF_MISSING_INPUT: Half-and-half topping extras or surcharge are required."
                .to_owned(),
        }
    })?;

    if half_and_half.left_topping_extras.len() > MAX_HALF_AND_HALF_TOPPINGS_PER_HALF {
        return Err(DomainError::HalfAndHalfToppingLimitExceeded {
            code: "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
            message: format!(
                "Half-and-half left side cannot have more than {} toppings.",
                MAX_HALF_AND_HALF_TOPPINGS_PER_HALF
            ),
        }
        .into());
    }
    if half_and_half.right_topping_extras.len() > MAX_HALF_AND_HALF_TOPPINGS_PER_HALF {
        return Err(DomainError::HalfAndHalfToppingLimitExceeded {
            code: "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
            message: format!(
                "Half-and-half right side cannot have more than {} toppings.",
                MAX_HALF_AND_HALF_TOPPINGS_PER_HALF
            ),
        }
        .into());
    }

    let mut distinct: HashSet<&i64> = HashSet::new();
    distinct.extend(half_and_half.left_topping_extras.iter());
    distinct.extend(half_and_half.right_topping_extras.iter());
    if distinct.len() > MAX_HALF_AND_HALF_DISTINCT_TOPPINGS {
        return Err(DomainError::HalfAndHalfToppingLimitExceeded {
            code: "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
            message: format!(
                "Half-and-half cannot have more than {} distinct toppings total.",
                MAX_HALF_AND_HALF_DISTINCT_TOPPINGS
            ),
        }
        .into());
    }

    let left_set: HashSet<_> = half_and_half.left_topping_extras.iter().collect();
    let right_set: HashSet<_> = half_and_half.right_topping_extras.iter().collect();
    if !left_set.is_disjoint(&right_set) {
        return Err(DomainError::HalfAndHalfLeftRightOverlap {
            code: "HALF_AND_HALF_LEFT_RIGHT_OVERLAP",
            message: "Half-and-half left and right sides cannot share toppings.".to_owned(),
        }
        .into());
    }

    let std_extra =
        PricingEngine::calculate_standard_toppings_extra_paise(&core_input.standard_toppings)?;
    let (_, breakdown) = PricingEngine::calculate_unit_price_with_breakdown(
        core_input.variant_price_paise,
        core_input.crust_extra_paise,
        core_input.cheese_extra_paise,
        core_input.sauce_extra_paise,
        std_extra,
        &core_input.extra_topping_extras,
        Some(half_and_half),
    )?;
    breakdown
        .map(Into::into)
        .ok_or_else(|| UniffiDomainError::Domain {
            detail:
                "HALF_AND_HALF_BREAKDOWN_MISSING: Core engine returned no half-and-half breakdown."
                    .to_owned(),
        })
}

#[uniffi::export]
pub fn cart_validate_with_modifiers(
    items: Vec<FfiCartItemInputExtended>,
    catalog: FfiModifierAvailability,
) -> Result<Vec<FfiEvaluatedCartItem>, UniffiDomainError> {
    // Convert FFI items to core CartItemInput
    let core_items: Vec<CartItemInput> = items.into_iter().map(Into::into).collect();

    // Build a complete CatalogRefLookup from the platform-provided catalog snapshot.
    let lookup = build_catalog_lookup(&catalog)?;

    // Parse and validate inputs
    let parsed = CartValidation::validate_and_parse_inputs(&core_items)
        .map_err(|e| UniffiDomainError::from(e))?;

    // Merge duplicate configurations
    let merged = CartValidation::merge_duplicate_configurations(parsed);

    // Ensure catalog FK references (includes all Phase 8 modifier validations)
    CartValidation::ensure_catalog_fk_references(&merged, &lookup)
        .map_err(|e| UniffiDomainError::from(e))?;

    // Evaluate cart
    let evaluated =
        CartValidation::evaluate_cart(&merged, &lookup).map_err(|e| UniffiDomainError::from(e))?;

    // Convert to FFI format
    Ok(evaluated
        .items
        .into_iter()
        .map(FfiEvaluatedCartItem::from)
        .collect())
}

// Helper FFI types for cart validation response
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiEvaluatedToppingView {
    pub topping_id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_available: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiEvaluatedCheeseView {
    pub cheese_id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_available: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiEvaluatedSauceView {
    pub sauce_id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_available: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiEvaluatedStandardToppingView {
    pub standard_topping_id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_default: bool,
    pub is_removed: bool,
    pub is_available: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiEvaluatedHalfAndHalfView {
    pub config_id: String,
    pub config_name: String,
    pub left_topping_ids: Vec<String>,
    pub right_topping_ids: Vec<String>,
    pub surcharge_paise: i64,
    pub left_half_base_paise: i64,
    pub right_half_base_paise: i64,
    pub average_paise: i64,
    pub is_available: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiEvaluatedCartItem {
    pub item_id: String,
    pub product_id: String,
    pub product_name: String,
    pub is_veg: bool,
    pub is_taxable: bool,
    pub image_url: Option<String>,
    pub variant_id: String,
    pub size_code: Option<String>, // FFI enum not exported, use String
    pub base_price_paise: i64,
    pub crust_id: String,
    pub crust_name: String,
    pub crust_extra_price_paise: i64,
    pub toppings: Vec<FfiEvaluatedToppingView>,
    pub cheese: Option<FfiEvaluatedCheeseView>,
    pub sauce: Option<FfiEvaluatedSauceView>,
    pub standard_toppings: Vec<FfiEvaluatedStandardToppingView>,
    pub half_and_half: Option<FfiEvaluatedHalfAndHalfView>,
    pub quantity: i32,
    pub unit_price_paise: i64,
    pub line_total_paise: i64,
    pub is_available: bool,
    pub issues: Vec<FfiCartValidationIssue>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiCartValidationIssue {
    pub code: String,
    pub message: String,
}

impl From<pizzora_core::CartValidationIssue> for FfiCartValidationIssue {
    fn from(i: pizzora_core::CartValidationIssue) -> Self {
        Self {
            code: format!("{:?}", i.code),
            message: i.message,
        }
    }
}

impl From<pizzora_core::EvaluatedCartItem> for FfiEvaluatedCartItem {
    fn from(e: pizzora_core::EvaluatedCartItem) -> Self {
        Self {
            item_id: e.item_id,
            product_id: e.product_id,
            product_name: e.product_name,
            is_veg: e.is_veg,
            is_taxable: e.is_taxable,
            image_url: e.image_url,
            variant_id: e.variant_id,
            size_code: e.size_code.map(|s| format!("{:?}", s)),
            base_price_paise: e.base_price_paise,
            crust_id: e.crust_id,
            crust_name: e.crust_name,
            crust_extra_price_paise: e.crust_extra_price_paise,
            toppings: e.toppings.into_iter().map(Into::into).collect(),
            cheese: e.cheese.map(Into::into),
            sauce: e.sauce.map(Into::into),
            standard_toppings: e.standard_toppings.into_iter().map(Into::into).collect(),
            half_and_half: e.half_and_half.map(Into::into),
            quantity: e.quantity,
            unit_price_paise: e.unit_price_paise,
            line_total_paise: e.line_total_paise,
            is_available: e.is_available,
            issues: e.issues.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<pizzora_core::EvaluatedToppingView> for FfiEvaluatedToppingView {
    fn from(v: pizzora_core::EvaluatedToppingView) -> Self {
        Self {
            topping_id: v.topping_id,
            name: v.name,
            is_veg: v.is_veg,
            extra_price_paise: v.extra_price_paise,
            is_available: v.is_available,
        }
    }
}

impl From<pizzora_core::EvaluatedCheeseView> for FfiEvaluatedCheeseView {
    fn from(v: pizzora_core::EvaluatedCheeseView) -> Self {
        Self {
            cheese_id: v.cheese_id,
            name: v.name,
            extra_price_paise: v.extra_price_paise,
            is_available: v.is_available,
        }
    }
}

impl From<pizzora_core::EvaluatedSauceView> for FfiEvaluatedSauceView {
    fn from(v: pizzora_core::EvaluatedSauceView) -> Self {
        Self {
            sauce_id: v.sauce_id,
            name: v.name,
            extra_price_paise: v.extra_price_paise,
            is_available: v.is_available,
        }
    }
}

impl From<pizzora_core::EvaluatedStandardToppingView> for FfiEvaluatedStandardToppingView {
    fn from(v: pizzora_core::EvaluatedStandardToppingView) -> Self {
        Self {
            standard_topping_id: v.standard_topping_id,
            name: v.name,
            is_veg: v.is_veg,
            extra_price_paise: v.extra_price_paise,
            is_default: v.is_default,
            is_removed: v.is_removed,
            is_available: v.is_available,
        }
    }
}

impl From<pizzora_core::EvaluatedHalfAndHalfView> for FfiEvaluatedHalfAndHalfView {
    fn from(v: pizzora_core::EvaluatedHalfAndHalfView) -> Self {
        Self {
            config_id: v.config_id,
            config_name: v.config_name,
            left_topping_ids: v.left_topping_ids,
            right_topping_ids: v.right_topping_ids,
            surcharge_paise: v.surcharge_paise,
            left_half_base_paise: v.left_half_base_paise,
            right_half_base_paise: v.right_half_base_paise,
            average_paise: v.average_paise,
            is_available: v.is_available,
        }
    }
}

fn lookup_name(map: &HashMap<String, String>, key: &str) -> String {
    map.get(key)
        .filter(|value| !value.is_empty())
        .cloned()
        .unwrap_or_else(|| key.to_owned())
}

/// Populate every map used by core cart validation from the platform-provided snapshot.
fn build_catalog_lookup(
    catalog: &FfiModifierAvailability,
) -> Result<CatalogRefLookup, UniffiDomainError> {
    let mut lookup = CatalogRefLookup::default();

    for product_id_str in &catalog.product_ids {
        let id = parse_ffi_uuid(product_id_str, "product")?;
        lookup.products.insert(
            id,
            CatalogProductRef {
                id,
                name: lookup_name(&catalog.product_names, product_id_str),
                is_veg: catalog
                    .product_is_veg
                    .get(product_id_str)
                    .copied()
                    .unwrap_or(true),
                is_taxable: catalog
                    .product_is_taxable
                    .get(product_id_str)
                    .copied()
                    .unwrap_or(true),
                is_effective_active: true,
                image_url: catalog
                    .product_image_urls
                    .get(product_id_str)
                    .filter(|url| !url.is_empty())
                    .cloned(),
            },
        );
    }

    for (variant_id_str, product_id_str) in &catalog.variant_product_ids {
        let id = parse_ffi_uuid(variant_id_str, "variant")?;
        let product_id = parse_ffi_uuid(product_id_str, "product for variant")?;
        let size_code = parse_size_code(
            catalog
                .variant_size_codes
                .get(variant_id_str)
                .map_or("REGULAR", String::as_str),
            variant_id_str,
        )?;
        lookup.variants.insert(
            id,
            CatalogVariantRef {
                id,
                product_id,
                size_code,
                price_paise: catalog
                    .variant_prices_paise
                    .get(variant_id_str)
                    .copied()
                    .unwrap_or(0),
                is_active: catalog
                    .active_variant_ids
                    .iter()
                    .any(|raw| raw.trim() == *variant_id_str),
            },
        );
    }

    for crust_id_str in &catalog.active_crust_ids {
        let id = parse_ffi_uuid(crust_id_str, "crust")?;
        lookup.crusts.insert(
            id,
            CatalogCrustRef {
                id,
                name: lookup_name(&catalog.crust_names, crust_id_str),
                extra_price_paise: catalog
                    .crust_extra_paise
                    .get(crust_id_str)
                    .copied()
                    .unwrap_or(0),
                is_active: true,
            },
        );
    }

    for topping_id_str in &catalog.active_topping_ids {
        let id = parse_ffi_uuid(topping_id_str, "topping")?;
        lookup.toppings.insert(
            id,
            CatalogToppingRef {
                id,
                name: lookup_name(&catalog.topping_names, topping_id_str),
                is_veg: catalog
                    .topping_is_veg
                    .get(topping_id_str)
                    .copied()
                    .unwrap_or(true),
                extra_price_paise: catalog
                    .topping_extra_paise
                    .get(topping_id_str)
                    .copied()
                    .unwrap_or(0),
                is_active: true,
            },
        );
    }

    for cheese in &catalog.cheeses {
        let id = parse_ffi_uuid(&cheese.id, "cheese")?;
        let product_id = parse_ffi_uuid(&cheese.product_id, "product for cheese")?;
        lookup.cheeses.insert(
            id,
            CatalogCheeseRef {
                id,
                product_id,
                name: cheese.name.clone(),
                extra_price_paise: cheese.extra_price_paise,
                is_active: cheese.is_active,
            },
        );
    }

    for sauce in &catalog.sauces {
        let id = parse_ffi_uuid(&sauce.id, "sauce")?;
        let product_id = parse_ffi_uuid(&sauce.product_id, "product for sauce")?;
        lookup.sauces.insert(
            id,
            CatalogSauceRef {
                id,
                product_id,
                name: sauce.name.clone(),
                extra_price_paise: sauce.extra_price_paise,
                is_active: sauce.is_active,
            },
        );
    }

    for standard_topping in &catalog.standard_toppings {
        let id = parse_ffi_uuid(&standard_topping.id, "standard topping")?;
        let product_id =
            parse_ffi_uuid(&standard_topping.product_id, "product for standard topping")?;
        lookup.standard_toppings.insert(
            id,
            CatalogStandardToppingRef {
                id,
                product_id,
                name: standard_topping.name.clone(),
                is_veg: standard_topping.is_veg,
                extra_price_paise: standard_topping.extra_price_paise,
                is_default: standard_topping.is_default,
                is_active: standard_topping.is_active,
            },
        );
    }

    for config in &catalog.half_and_half_configs {
        let id = parse_ffi_uuid(&config.id, "half-and-half config")?;
        let product_id = parse_ffi_uuid(&config.product_id, "product for half-and-half config")?;
        let left_topping_ids = config
            .left_topping_ids
            .iter()
            .map(|raw| parse_ffi_uuid(raw, "left half-and-half topping"))
            .collect::<Result<Vec<_>, _>>()?;
        let right_topping_ids = config
            .right_topping_ids
            .iter()
            .map(|raw| parse_ffi_uuid(raw, "right half-and-half topping"))
            .collect::<Result<Vec<_>, _>>()?;
        lookup.half_and_half_configs.insert(
            id,
            CatalogHalfAndHalfConfigRef {
                id,
                product_id,
                name: config.name.clone(),
                left_topping_ids,
                right_topping_ids,
                half_and_half_surcharge_paise: config.half_and_half_surcharge_paise,
                is_active: config.is_active,
            },
        );
    }

    for (product_id_str, topping_ids) in &catalog.product_toppings {
        let product_id = parse_ffi_uuid(product_id_str, "product for product_toppings")?;
        let refs = topping_ids
            .iter()
            .map(|topping_id_str| {
                Ok(CatalogProductToppingRef {
                    product_id,
                    topping_id: parse_ffi_uuid(topping_id_str, "topping in product_toppings")?,
                })
            })
            .collect::<Result<Vec<_>, UniffiDomainError>>()?;
        lookup.product_toppings.insert(product_id, refs);
    }

    Ok(lookup)
}

/// Filter a platform catalog snapshot down to a single active product. No ID is substituted.
#[uniffi::export]
pub fn catalog_check_modifier_availability(
    product_id: String,
    catalog: FfiModifierAvailability,
) -> Result<FfiModifierAvailability, UniffiDomainError> {
    let product_uuid = parse_ffi_uuid(&product_id, "product")?;
    let product_key = product_uuid.to_string();
    let mut filtered = catalog;

    filtered.product_ids.retain(|id| id.trim() == product_key);
    filtered
        .product_names
        .retain(|id, _| id.trim() == product_key);
    filtered
        .product_is_veg
        .retain(|id, _| id.trim() == product_key);
    filtered
        .product_is_taxable
        .retain(|id, _| id.trim() == product_key);
    filtered
        .product_image_urls
        .retain(|id, _| id.trim() == product_key);

    filtered
        .cheeses
        .retain(|c| c.is_active && c.product_id.trim() == product_key);
    filtered
        .sauces
        .retain(|s| s.is_active && s.product_id.trim() == product_key);
    filtered
        .standard_toppings
        .retain(|st| st.is_active && st.product_id.trim() == product_key);
    filtered
        .half_and_half_configs
        .retain(|config| config.is_active && config.product_id.trim() == product_key);

    filtered
        .variant_product_ids
        .retain(|_, owning_product| owning_product.trim() == product_key);
    let variant_keys: HashSet<String> = filtered
        .variant_product_ids
        .keys()
        .map(|id| id.trim().to_owned())
        .collect();
    filtered
        .variant_size_codes
        .retain(|id, _| variant_keys.contains(id.trim()));
    filtered
        .variant_prices_paise
        .retain(|id, _| variant_keys.contains(id.trim()));
    filtered
        .active_variant_ids
        .retain(|id| variant_keys.contains(id.trim()));

    // product_toppings is the authority for product-scoped extra toppings (U8-02).
    filtered
        .product_toppings
        .retain(|id, _| id.trim() == product_key);
    let product_topping_keys: HashSet<String> = filtered
        .product_toppings
        .values()
        .flatten()
        .map(|id| id.trim().to_owned())
        .collect();
    filtered
        .active_topping_ids
        .retain(|id| product_topping_keys.contains(id.trim()));
    filtered
        .topping_names
        .retain(|id, _| product_topping_keys.contains(id.trim()));
    filtered
        .topping_is_veg
        .retain(|id, _| product_topping_keys.contains(id.trim()));
    filtered
        .topping_extra_paise
        .retain(|id, _| product_topping_keys.contains(id.trim()));

    // Crust options are global in the current schema; only their active subset is kept.
    let active_crust_keys: HashSet<String> = filtered
        .active_crust_ids
        .iter()
        .map(|id| id.trim().to_owned())
        .collect();
    filtered
        .crust_names
        .retain(|id, _| active_crust_keys.contains(id.trim()));
    filtered
        .crust_extra_paise
        .retain(|id, _| active_crust_keys.contains(id.trim()));

    Ok(filtered)
}

fn push_unavailable(
    unavailable: &mut Vec<FfiReorderUnavailable>,
    issues: &mut Vec<String>,
    item: &FfiReorderItem,
    modifier_type: &str,
    modifier_id: &str,
    modifier_name: &str,
    reason: &str,
) {
    issues.push(reason.to_owned());
    unavailable.push(FfiReorderUnavailable {
        order_item_id: item.order_item_id.clone(),
        product_name: item.product_name.clone(),
        modifier_type: modifier_type.to_owned(),
        modifier_id: modifier_id.to_owned(),
        modifier_name: modifier_name.to_owned(),
        reason: reason.to_owned(),
    });
}

fn push_warning(warnings: &mut Vec<String>, message: String) {
    if !warnings.contains(&message) {
        warnings.push(message);
    }
}

/// Read-only reorder reconciliation: validate historical item IDs against the live catalog,
/// report unavailable modifiers, and never substitute or mutate an item.
#[uniffi::export]
pub fn reorder_validate_items(
    order_id: String,
    items: Vec<FfiReorderItem>,
    catalog: FfiModifierAvailability,
) -> Result<FfiReorderValidation, UniffiDomainError> {
    let order_uuid = parse_ffi_uuid(&order_id, "order")?;
    let lookup = build_catalog_lookup(&catalog)?;
    let product_toppings_by_product = |product_id: Uuid| -> HashSet<Uuid> {
        lookup
            .product_toppings
            .get(&product_id)
            .map(|refs| refs.iter().map(|reference| reference.topping_id).collect())
            .unwrap_or_default()
    };

    let mut validated_items = Vec::with_capacity(items.len());
    let mut unavailable = Vec::new();
    let mut warnings = Vec::new();

    for item in items {
        let _order_item_uuid = parse_ffi_uuid(&item.order_item_id, "order item")?;
        let product_uuid = parse_ffi_uuid(&item.product_id, "product")?;
        let variant_uuid = item
            .variant_id
            .as_deref()
            .filter(|raw| !raw.trim().is_empty())
            .map(|raw| parse_ffi_uuid(raw, "variant"))
            .transpose()?;
        let crust_uuid = item
            .crust_id
            .as_deref()
            .filter(|raw| !raw.trim().is_empty())
            .map(|raw| parse_ffi_uuid(raw, "crust"))
            .transpose()?;
        let cheese_uuid = item
            .cheese_id
            .as_deref()
            .filter(|raw| !raw.trim().is_empty())
            .map(|raw| parse_ffi_uuid(raw, "cheese"))
            .transpose()?;
        let sauce_uuid = item
            .sauce_id
            .as_deref()
            .filter(|raw| !raw.trim().is_empty())
            .map(|raw| parse_ffi_uuid(raw, "sauce"))
            .transpose()?;
        let config_uuid = item
            .half_and_half_config_id
            .as_deref()
            .filter(|raw| !raw.trim().is_empty())
            .map(|raw| parse_ffi_uuid(raw, "half-and-half config"))
            .transpose()?;
        let topping_uuids = item
            .topping_ids
            .iter()
            .map(|raw| parse_ffi_uuid(raw, "topping"))
            .collect::<Result<Vec<_>, _>>()?;
        let standard_topping_uuids = item
            .standard_topping_ids
            .iter()
            .map(|raw| parse_ffi_uuid(raw, "standard topping"))
            .collect::<Result<Vec<_>, _>>()?;
        let removed_topping_uuids = item
            .standard_topping_removed_ids
            .iter()
            .map(|raw| parse_ffi_uuid(raw, "removed standard topping"))
            .collect::<Result<Vec<_>, _>>()?;
        let left_half_uuids = item
            .half_and_half_left_topping_ids
            .iter()
            .map(|raw| parse_ffi_uuid(raw, "left half-and-half topping"))
            .collect::<Result<Vec<_>, _>>()?;
        let right_half_uuids = item
            .half_and_half_right_topping_ids
            .iter()
            .map(|raw| parse_ffi_uuid(raw, "right half-and-half topping"))
            .collect::<Result<Vec<_>, _>>()?;

        let mut issues = Vec::new();
        let allowed_toppings = product_toppings_by_product(product_uuid);

        if !lookup
            .products
            .get(&product_uuid)
            .is_some_and(|p| p.is_effective_active)
        {
            push_unavailable(
                &mut unavailable,
                &mut issues,
                &item,
                "PRODUCT",
                &item.product_id,
                &item.product_name,
                "PRODUCT_UNAVAILABLE",
            );
        }

        if let Some(variant_id) = variant_uuid {
            let raw = item.variant_id.as_deref().unwrap_or_default();
            let name = lookup
                .variants
                .get(&variant_id)
                .map_or("Unknown", |variant| match variant.size_code {
                    SizeCode::Regular => "REGULAR",
                    SizeCode::Medium => "MEDIUM",
                    SizeCode::Large => "LARGE",
                });
            if !lookup
                .variants
                .get(&variant_id)
                .is_some_and(|variant| variant.product_id == product_uuid && variant.is_active)
            {
                push_unavailable(
                    &mut unavailable,
                    &mut issues,
                    &item,
                    "VARIANT",
                    raw,
                    name,
                    "VARIANT_UNAVAILABLE",
                );
            }
        } else {
            push_warning(
                &mut warnings,
                format!(
                    "Historical order: variant for order item {} could not be reconstructed.",
                    item.order_item_id
                ),
            );
            push_unavailable(
                &mut unavailable,
                &mut issues,
                &item,
                "VARIANT",
                "",
                "Unknown",
                "VARIANT_UNAVAILABLE",
            );
        }

        if let Some(crust_id) = crust_uuid {
            let raw = item.crust_id.as_deref().unwrap_or_default();
            let name = lookup
                .crusts
                .get(&crust_id)
                .map_or("Unknown", |crust| crust.name.as_str());
            if !lookup
                .crusts
                .get(&crust_id)
                .is_some_and(|crust| crust.is_active)
            {
                push_unavailable(
                    &mut unavailable,
                    &mut issues,
                    &item,
                    "CRUST",
                    raw,
                    name,
                    "CRUST_UNAVAILABLE",
                );
            }
        } else {
            push_warning(
                &mut warnings,
                format!(
                    "Historical order: crust for order item {} could not be reconstructed.",
                    item.order_item_id
                ),
            );
            push_unavailable(
                &mut unavailable,
                &mut issues,
                &item,
                "CRUST",
                "",
                "Unknown",
                "CRUST_UNAVAILABLE",
            );
        }

        if let Some(cheese_id) = cheese_uuid {
            let raw = item.cheese_id.as_deref().unwrap_or_default();
            let name = lookup
                .cheeses
                .get(&cheese_id)
                .map_or("Unknown", |cheese| cheese.name.as_str());
            if !lookup
                .cheeses
                .get(&cheese_id)
                .is_some_and(|cheese| cheese.product_id == product_uuid && cheese.is_active)
            {
                push_unavailable(
                    &mut unavailable,
                    &mut issues,
                    &item,
                    "CHEESE",
                    raw,
                    name,
                    "CHEESE_UNAVAILABLE",
                );
            }
        }

        if let Some(sauce_id) = sauce_uuid {
            let raw = item.sauce_id.as_deref().unwrap_or_default();
            let name = lookup
                .sauces
                .get(&sauce_id)
                .map_or("Unknown", |sauce| sauce.name.as_str());
            if !lookup
                .sauces
                .get(&sauce_id)
                .is_some_and(|sauce| sauce.product_id == product_uuid && sauce.is_active)
            {
                push_unavailable(
                    &mut unavailable,
                    &mut issues,
                    &item,
                    "SAUCE",
                    raw,
                    name,
                    "SAUCE_UNAVAILABLE",
                );
            }
        }

        for (raw, topping_id) in item.topping_ids.iter().zip(topping_uuids) {
            let name = lookup
                .toppings
                .get(&topping_id)
                .map_or("Unknown", |topping| topping.name.as_str());
            let is_available = lookup
                .toppings
                .get(&topping_id)
                .is_some_and(|t| t.is_active)
                && allowed_toppings.contains(&topping_id);
            if !is_available {
                push_unavailable(
                    &mut unavailable,
                    &mut issues,
                    &item,
                    "TOPPING",
                    raw,
                    name,
                    "TOPPING_UNAVAILABLE",
                );
            }
        }

        for (raw, standard_id) in item.standard_topping_ids.iter().zip(standard_topping_uuids) {
            let name = lookup
                .standard_toppings
                .get(&standard_id)
                .map_or("Unknown", |standard| standard.name.as_str());
            if !lookup
                .standard_toppings
                .get(&standard_id)
                .is_some_and(|standard| standard.product_id == product_uuid && standard.is_active)
            {
                push_unavailable(
                    &mut unavailable,
                    &mut issues,
                    &item,
                    "STANDARD_TOPPING",
                    raw,
                    name,
                    "STANDARD_TOPPING_UNAVAILABLE",
                );
            }
        }

        for (raw, removed_id) in item
            .standard_topping_removed_ids
            .iter()
            .zip(removed_topping_uuids)
        {
            if !lookup.standard_toppings.contains_key(&removed_id) {
                push_warning(
                    &mut warnings,
                    format!(
                        "Historical order: standard topping removal {raw} could not be reconstructed."
                    ),
                );
            }
        }

        if let Some(config_id) = config_uuid {
            let raw = item.half_and_half_config_id.as_deref().unwrap_or_default();
            let name = lookup
                .half_and_half_configs
                .get(&config_id)
                .map_or("Unknown", |config| config.name.as_str());
            if !lookup
                .half_and_half_configs
                .get(&config_id)
                .is_some_and(|config| config.product_id == product_uuid && config.is_active)
            {
                push_unavailable(
                    &mut unavailable,
                    &mut issues,
                    &item,
                    "HALF_AND_HALF_CONFIG",
                    raw,
                    name,
                    "HALF_AND_HALF_CONFIG_UNAVAILABLE",
                );
            }
        } else {
            push_warning(
                &mut warnings,
                format!(
                    "Historical order: half-and-half config for order item {} could not be reconstructed.",
                    item.order_item_id
                ),
            );
        }

        for (raw, topping_id) in item
            .half_and_half_left_topping_ids
            .iter()
            .zip(left_half_uuids.iter().copied())
        {
            let name = lookup
                .toppings
                .get(&topping_id)
                .map_or("Unknown", |topping| topping.name.as_str());
            if !allowed_toppings.contains(&topping_id)
                || !lookup
                    .toppings
                    .get(&topping_id)
                    .is_some_and(|t| t.is_active)
            {
                push_unavailable(
                    &mut unavailable,
                    &mut issues,
                    &item,
                    "TOPPING",
                    raw,
                    name,
                    "TOPPING_UNAVAILABLE",
                );
            }
        }
        for (raw, topping_id) in item
            .half_and_half_right_topping_ids
            .iter()
            .zip(right_half_uuids.iter().copied())
        {
            let name = lookup
                .toppings
                .get(&topping_id)
                .map_or("Unknown", |topping| topping.name.as_str());
            if !allowed_toppings.contains(&topping_id)
                || !lookup
                    .toppings
                    .get(&topping_id)
                    .is_some_and(|t| t.is_active)
            {
                push_unavailable(
                    &mut unavailable,
                    &mut issues,
                    &item,
                    "TOPPING",
                    raw,
                    name,
                    "TOPPING_UNAVAILABLE",
                );
            }
        }

        if left_half_uuids.len() > MAX_HALF_AND_HALF_TOPPINGS_PER_HALF
            || right_half_uuids.len() > MAX_HALF_AND_HALF_TOPPINGS_PER_HALF
        {
            push_unavailable(
                &mut unavailable,
                &mut issues,
                &item,
                "HALF_AND_HALF",
                "",
                "Unknown",
                "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
            );
        }
        let distinct_half_toppings: HashSet<Uuid> = left_half_uuids
            .iter()
            .chain(right_half_uuids.iter())
            .copied()
            .collect();
        if distinct_half_toppings.len() > MAX_HALF_AND_HALF_DISTINCT_TOPPINGS {
            push_unavailable(
                &mut unavailable,
                &mut issues,
                &item,
                "HALF_AND_HALF",
                "",
                "Unknown",
                "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
            );
        }
        let left_set: HashSet<Uuid> = left_half_uuids.into_iter().collect();
        let right_set: HashSet<Uuid> = right_half_uuids.into_iter().collect();
        if !left_set.is_disjoint(&right_set) {
            push_unavailable(
                &mut unavailable,
                &mut issues,
                &item,
                "HALF_AND_HALF",
                "",
                "Unknown",
                "HALF_AND_HALF_LEFT_RIGHT_OVERLAP",
            );
        }

        if !(1..=MAX_ITEM_QUANTITY).contains(&item.quantity) {
            push_unavailable(
                &mut unavailable,
                &mut issues,
                &item,
                "ITEM",
                &item.order_item_id,
                &item.product_name,
                "INVALID_QUANTITY",
            );
        }

        let is_available = issues.is_empty();
        let mut validated_item = item;
        validated_item.is_available = is_available;
        validated_item.issues = issues;
        validated_items.push(validated_item);
    }

    let valid_items: Vec<FfiReorderItem> = validated_items
        .iter()
        .filter(|item| item.is_available)
        .cloned()
        .collect();

    Ok(FfiReorderValidation {
        order_id: order_uuid.to_string(),
        can_reorder: !valid_items.is_empty(),
        items: validated_items,
        unavailable,
        warnings,
    })
}

// --- Coupon Export ---

#[uniffi::export]
pub fn coupon_normalize_code(raw: String) -> Result<String, UniffiDomainError> {
    CouponEvaluator::normalize_code(&raw).map_err(Into::into)
}

// --- Order State Machine Exports ---

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

// --- Payment State Machine Exports ---

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

// --- Cart Sync Exports ---

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

// --- Storage Export ---

#[uniffi::export]
pub fn storage_validate_object_key(raw_key: String) -> Result<String, UniffiDomainError> {
    StorageKeyValidator::validate_object_key(&raw_key)
        .map(ToOwned::to_owned)
        .map_err(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;
    use std::fs;
    use std::path::Path;

    #[derive(Debug, Deserialize)]
    struct GoldenVectorFile {
        vectors: Vec<GoldenVector>,
    }

    #[derive(Debug, Deserialize)]
    struct GoldenVector {
        id: String,
        scenario: String,
        inputs: GoldenVectorInputs,
        #[serde(default)]
        expected_unit_price_paise: Option<i64>,
        #[serde(default)]
        expected_line_total_paise: Option<i64>,
        #[serde(default)]
        expected_quote: Option<ExpectedQuote>,
        #[serde(default)]
        expected_error: Option<String>,
    }

    #[derive(Debug, Deserialize)]
    struct GoldenVectorInputs {
        variant_price_paise: Option<i64>,
        crust_extra_paise: Option<i64>,
        cheese_extra_paise: Option<i64>,
        sauce_extra_paise: Option<i64>,
        standard_toppings: Option<Vec<GoldenStandardTopping>>,
        extra_topping_extras: Option<Vec<i64>>,
        half_and_half: Option<GoldenHalfAndHalf>,
        quantity: Option<i32>,
        lines: Option<Vec<GoldenLine>>,
        gst_bps: Option<i32>,
        delivery_fee_paise: Option<i64>,
        coupon: Option<GoldenCoupon>,
    }

    #[derive(Debug, Deserialize)]
    struct GoldenStandardTopping {
        id: String,
        extra_price_paise: i64,
        is_default: bool,
        is_removed: bool,
    }

    #[derive(Debug, Deserialize)]
    struct GoldenHalfAndHalf {
        left_topping_extras: Vec<i64>,
        right_topping_extras: Vec<i64>,
        surcharge_paise: i64,
    }

    #[derive(Debug, Deserialize)]
    struct GoldenLine {
        unit_price_paise: i64,
        quantity: i32,
        is_taxable: bool,
    }

    #[derive(Debug, Deserialize)]
    struct GoldenCoupon {
        discount_type: String,
        discount_value: i64,
        max_discount_paise: Option<i64>,
        min_order_paise: i64,
        is_active: bool,
    }

    #[derive(Debug, Deserialize)]
    struct ExpectedQuote {
        subtotal_paise: i64,
        taxable_subtotal_paise: i64,
        discount_paise: i64,
        taxable_amount_paise: i64,
        delivery_fee_paise: i64,
        gst_bps: i32,
        tax_paise: i64,
        cgst_paise: i64,
        sgst_paise: i64,
        total_paise: i64,
    }

    fn load_vectors() -> Vec<GoldenVector> {
        let manifest_dir = env!("CARGO_MANIFEST_DIR");
        let path = Path::new(manifest_dir)
            .join("..")
            .join("..")
            .join("..")
            .join("tools")
            .join("golden-vectors")
            .join("pizzora_phase8_modifier_vectors.json");
        let content = fs::read_to_string(&path).expect("Failed to read golden vectors file");
        let file: GoldenVectorFile =
            serde_json::from_str(&content).expect("Failed to parse golden vectors JSON");
        file.vectors
    }

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

    #[test]
    fn uniffi_golden_vectors_all_pass() {
        let vectors = load_vectors();
        assert_eq!(vectors.len(), 33, "Expected exactly 33 golden vectors");

        for vector in &vectors {
            println!("Testing vector: {} - {}", vector.id, vector.scenario);

            if vector.id.starts_with("U-") {
                test_unit_pricing_vector(vector);
            } else if vector.id.starts_with("Q-") {
                test_quote_vector(vector);
            } else {
                panic!("Unknown vector type: {}", vector.id);
            }
        }
    }

    fn test_unit_pricing_vector(vector: &GoldenVector) {
        let inputs = &vector.inputs;

        // Build standard topping selections
        let standard_toppings: Vec<FfiStandardToppingSelection> = inputs
            .standard_toppings
            .as_ref()
            .unwrap_or(&vec![])
            .iter()
            .map(|st| FfiStandardToppingSelection {
                id: st.id.clone(),
                extra_price_paise: st.extra_price_paise,
                is_default: st.is_default,
                is_removed: st.is_removed,
            })
            .collect();

        // Build FfiPricedLineInputExtended
        let ffi_input = FfiPricedLineInputExtended {
            variant_price_paise: inputs.variant_price_paise.unwrap_or(0),
            crust_extra_paise: inputs.crust_extra_paise.unwrap_or(0),
            cheese_extra_paise: inputs.cheese_extra_paise.unwrap_or(0),
            sauce_extra_paise: inputs.sauce_extra_paise.unwrap_or(0),
            standard_toppings,
            extra_topping_extras: inputs.extra_topping_extras.clone().unwrap_or_default(),
            half_and_half_left_topping_extras: inputs
                .half_and_half
                .as_ref()
                .map(|h| h.left_topping_extras.clone()),
            half_and_half_right_topping_extras: inputs
                .half_and_half
                .as_ref()
                .map(|h| h.right_topping_extras.clone()),
            half_and_half_surcharge_paise: inputs.half_and_half.as_ref().map(|h| h.surcharge_paise),
            quantity: inputs.quantity.unwrap_or(1),
        };

        let quantity = inputs.quantity.unwrap_or(1);

        if let Some(expected_error) = &vector.expected_error {
            let unit_price_result = pricing_calculate_unit_price_paise(ffi_input.clone());
            if unit_price_result.is_err() {
                let err = unit_price_result.unwrap_err();
                assert!(
                    format!("{err}").contains("arithmetic overflow")
                        || format!("{err}").contains("ArithmeticOverflow"),
                    "Vector {}: expected ArithmeticOverflow error, got: {}",
                    vector.id,
                    err
                );
                return;
            }
            let unit_price = unit_price_result.unwrap();
            let line_total_result = pricing_calculate_line_total_paise(unit_price, quantity);
            assert!(
                line_total_result.is_err(),
                "Vector {}: expected error '{}' but line total calculation succeeded",
                vector.id,
                expected_error
            );
            let err = line_total_result.unwrap_err();
            assert!(
                format!("{err}").contains("arithmetic overflow")
                    || format!("{err}").contains("ArithmeticOverflow"),
                "Vector {}: expected ArithmeticOverflow error, got: {}",
                vector.id,
                err
            );
            return;
        }

        let unit_price = pricing_calculate_unit_price_paise(ffi_input).expect(&format!(
            "Vector {}: pricing_calculate_unit_price_paise failed",
            vector.id
        ));

        assert_eq!(
            unit_price,
            vector
                .expected_unit_price_paise
                .expect("expected_unit_price_paise missing"),
            "Vector {}: unit price mismatch",
            vector.id
        );

        if quantity != 1 || vector.expected_line_total_paise.is_some() {
            let line_total =
                pricing_calculate_line_total_paise(unit_price, quantity).expect(&format!(
                    "Vector {}: pricing_calculate_line_total_paise failed",
                    vector.id
                ));
            if let Some(expected_line_total) = vector.expected_line_total_paise {
                assert_eq!(
                    line_total, expected_line_total,
                    "Vector {}: line total mismatch",
                    vector.id
                );
            }
        }
    }

    fn test_quote_vector(vector: &GoldenVector) {
        let inputs = &vector.inputs;
        let lines = inputs.lines.as_ref().expect("Q vectors must have lines");
        let gst_bps = inputs.gst_bps.expect("Q vectors must have gst_bps");
        let delivery_fee_paise = inputs
            .delivery_fee_paise
            .expect("Q vectors must have delivery_fee_paise");

        let priced_lines: Vec<FfiPricedLineInput> = lines
            .iter()
            .map(|l| FfiPricedLineInput {
                unit_price_paise: l.unit_price_paise,
                quantity: l.quantity,
                is_taxable: l.is_taxable,
            })
            .collect();

        let coupon_spec = inputs.coupon.as_ref().map(|c| FfiCouponCalculationSpec {
            discount_type: match c.discount_type.as_str() {
                "PercentBps" => FfiDiscountType::PercentBps,
                "FlatPaise" => FfiDiscountType::FlatPaise,
                other => panic!("Unknown discount type: {}", other),
            },
            discount_value: c.discount_value,
            max_discount_paise: c.max_discount_paise,
            min_order_paise: c.min_order_paise,
            is_active: c.is_active,
        });

        let breakdown =
            pricing_calculate_quote(priced_lines, gst_bps, delivery_fee_paise, coupon_spec).expect(
                &format!("Vector {}: pricing_calculate_quote failed", vector.id),
            );

        let expected = vector
            .expected_quote
            .as_ref()
            .expect("Q vectors must have expected_quote");

        assert_eq!(
            breakdown.subtotal_paise, expected.subtotal_paise,
            "Vector {}: subtotal mismatch",
            vector.id
        );
        assert_eq!(
            breakdown.taxable_subtotal_paise, expected.taxable_subtotal_paise,
            "Vector {}: taxable_subtotal mismatch",
            vector.id
        );
        assert_eq!(
            breakdown.discount_paise, expected.discount_paise,
            "Vector {}: discount mismatch",
            vector.id
        );
        assert_eq!(
            breakdown.taxable_amount_paise, expected.taxable_amount_paise,
            "Vector {}: taxable_amount mismatch",
            vector.id
        );
        assert_eq!(
            breakdown.delivery_fee_paise, expected.delivery_fee_paise,
            "Vector {}: delivery_fee mismatch",
            vector.id
        );
        assert_eq!(
            breakdown.gst_bps, expected.gst_bps,
            "Vector {}: gst_bps mismatch",
            vector.id
        );
        assert_eq!(
            breakdown.tax_paise, expected.tax_paise,
            "Vector {}: tax mismatch",
            vector.id
        );
        assert_eq!(
            breakdown.cgst_paise, expected.cgst_paise,
            "Vector {}: cgst mismatch",
            vector.id
        );
        assert_eq!(
            breakdown.sgst_paise, expected.sgst_paise,
            "Vector {}: sgst mismatch",
            vector.id
        );
        assert_eq!(
            breakdown.total_paise, expected.total_paise,
            "Vector {}: total mismatch",
            vector.id
        );
    }

    #[test]
    fn pricing_calculate_standard_toppings_extra_paise_parity() {
        // Test U-06: non-default standard 1000
        let selections = vec![FfiStandardToppingSelection {
            id: "00000000-0000-0000-0000-000000000101".to_owned(),
            extra_price_paise: 1000,
            is_default: false,
            is_removed: false,
        }];
        let extra = pricing_calculate_standard_toppings_extra_paise(selections).unwrap();
        assert_eq!(extra, 1000);

        // Test U-08: default kept (extra ignored)
        let selections = vec![FfiStandardToppingSelection {
            id: "00000000-0000-0000-0000-000000000101".to_owned(),
            extra_price_paise: 1000,
            is_default: true,
            is_removed: false,
        }];
        let extra = pricing_calculate_standard_toppings_extra_paise(selections).unwrap();
        assert_eq!(extra, 0);

        // Test U-09: default removed (zero credit)
        let selections = vec![FfiStandardToppingSelection {
            id: "00000000-0000-0000-0000-000000000101".to_owned(),
            extra_price_paise: 1000,
            is_default: true,
            is_removed: true,
        }];
        let extra = pricing_calculate_standard_toppings_extra_paise(selections).unwrap();
        assert_eq!(extra, 0);

        // Test U-10: default kept + non-default 1000
        let selections = vec![
            FfiStandardToppingSelection {
                id: "00000000-0000-0000-0000-000000000101".to_owned(),
                extra_price_paise: 1000,
                is_default: true,
                is_removed: false,
            },
            FfiStandardToppingSelection {
                id: "00000000-0000-0000-0000-000000000102".to_owned(),
                extra_price_paise: 1000,
                is_default: false,
                is_removed: false,
            },
        ];
        let extra = pricing_calculate_standard_toppings_extra_paise(selections).unwrap();
        assert_eq!(extra, 1000);

        // Test U-11: default removed + non-default 1000
        let selections = vec![
            FfiStandardToppingSelection {
                id: "00000000-0000-0000-0000-000000000101".to_owned(),
                extra_price_paise: 1000,
                is_default: true,
                is_removed: true,
            },
            FfiStandardToppingSelection {
                id: "00000000-0000-0000-0000-000000000102".to_owned(),
                extra_price_paise: 1000,
                is_default: false,
                is_removed: false,
            },
        ];
        let extra = pricing_calculate_standard_toppings_extra_paise(selections).unwrap();
        assert_eq!(extra, 1000);
    }

    #[test]
    fn pricing_validate_half_and_half_limits() {
        let half_input =
            |left: Vec<i64>, right: Vec<i64>, surcharge: i64| FfiPricedLineInputExtended {
                variant_price_paise: 10_000,
                crust_extra_paise: 100,
                cheese_extra_paise: 0,
                sauce_extra_paise: 0,
                standard_toppings: vec![],
                extra_topping_extras: vec![],
                half_and_half_left_topping_extras: Some(left),
                half_and_half_right_topping_extras: Some(right),
                half_and_half_surcharge_paise: Some(surcharge),
                quantity: 1,
            };

        // Test per-half limit from the core constant.
        let left = (0..MAX_HALF_AND_HALF_TOPPINGS_PER_HALF as i64 + 1).collect::<Vec<_>>();
        let result = pricing_validate_half_and_half(half_input(left, vec![], 0));
        assert!(result.is_err());
        assert!(format!("{}", result.unwrap_err()).contains("HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED"));

        // Test distinct-total limit from the core constant.
        let half_size = MAX_HALF_AND_HALF_DISTINCT_TOPPINGS as i64 / 2 + 1;
        let left = (0..half_size).collect::<Vec<_>>();
        let right = (half_size..half_size * 2).collect::<Vec<_>>();
        let result = pricing_validate_half_and_half(half_input(left, right, 0));
        assert!(result.is_err());
        assert!(format!("{}", result.unwrap_err()).contains("HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED"));

        // Test no left/right overlap.
        let result = pricing_validate_half_and_half(half_input(vec![1, 2], vec![2, 3], 0));
        assert!(result.is_err());
        assert!(format!("{}", result.unwrap_err()).contains("HALF_AND_HALF_LEFT_RIGHT_OVERLAP"));

        // Valid case.
        let result = pricing_validate_half_and_half(half_input(vec![1, 2], vec![3, 4], 1000));
        assert!(result.is_ok());
        let breakdown = result.unwrap();
        assert_eq!(breakdown.surcharge_paise, 1000);
    }

    #[test]
    fn error_mapping_phase8_variants() {
        // Verify that all new DomainError variants map through UniffiDomainError
        // The generic From impl uses Display, so all variants are covered.
        // This test confirms the Display output contains the expected codes.
        use pizzora_core::error::DomainError;

        let errors = vec![
            DomainError::CheeseUnavailable {
                code: "CHEESE_UNAVAILABLE",
                message: "test".to_owned(),
            },
            DomainError::SauceUnavailable {
                code: "SAUCE_UNAVAILABLE",
                message: "test".to_owned(),
            },
            DomainError::StandardToppingUnavailable {
                code: "STANDARD_TOPPING_UNAVAILABLE",
                message: "test".to_owned(),
            },
            DomainError::HalfAndHalfUnavailable {
                code: "HALF_AND_HALF_UNAVAILABLE",
                message: "test".to_owned(),
            },
            DomainError::HalfAndHalfToppingLimitExceeded {
                code: "HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED",
                message: "test".to_owned(),
            },
            DomainError::HalfAndHalfLeftRightOverlap {
                code: "HALF_AND_HALF_LEFT_RIGHT_OVERLAP",
                message: "test".to_owned(),
            },
            DomainError::StandardToppingNotInProduct {
                code: "STANDARD_TOPPING_NOT_IN_PRODUCT",
                message: "test".to_owned(),
            },
            DomainError::CheeseNotInProduct {
                code: "CHEESE_NOT_IN_PRODUCT",
                message: "test".to_owned(),
            },
            DomainError::SauceNotInProduct {
                code: "SAUCE_NOT_IN_PRODUCT",
                message: "test".to_owned(),
            },
            DomainError::HalfAndHalfConfigNotInProduct {
                code: "HALF_AND_HALF_CONFIG_NOT_IN_PRODUCT",
                message: "test".to_owned(),
            },
        ];

        for err in errors {
            let uniffi_err: UniffiDomainError = err.into();
            let detail = format!("{uniffi_err}");
            assert!(
                detail.contains("UNAVAILABLE")
                    || detail.contains("LIMIT")
                    || detail.contains("OVERLAP")
                    || detail.contains("NOT_IN_PRODUCT"),
                "Error mapping should preserve variant info: {detail}"
            );
        }
    }

    const PRODUCT: &str = "11111111-1111-1111-1111-111111111111";
    const FOREIGN_PRODUCT: &str = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    const VARIANT: &str = "22222222-2222-2222-2222-222222222222";
    const CRUST: &str = "33333333-3333-3333-3333-333333333333";
    const TOPPING_1: &str = "44444444-4444-4444-4444-444444444444";
    const TOPPING_2: &str = "55555555-5555-5555-5555-555555555555";
    const CHEESE: &str = "66666666-6666-6666-6666-666666666666";
    const FOREIGN_CHEESE: &str = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    const SAUCE: &str = "77777777-7777-7777-7777-777777777777";
    const STANDARD_TOPPING: &str = "88888888-8888-8888-8888-888888888888";
    const HALF_CONFIG: &str = "99999999-9999-9999-9999-999999999999";
    const ORDER: &str = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    const ORDER_ITEM: &str = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    const ORDER_ITEM_2: &str = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";

    fn test_catalog() -> FfiModifierAvailability {
        let mut product_toppings = HashMap::new();
        product_toppings.insert(
            PRODUCT.to_owned(),
            vec![TOPPING_1.to_owned(), TOPPING_2.to_owned()],
        );

        FfiModifierAvailability {
            cheeses: vec![FfiCheeseOption {
                id: CHEESE.to_owned(),
                product_id: PRODUCT.to_owned(),
                name: "Mozzarella".to_owned(),
                extra_price_paise: 2_000,
                is_active: true,
                sort_order: 0,
            }],
            sauces: vec![FfiSauceOption {
                id: SAUCE.to_owned(),
                product_id: PRODUCT.to_owned(),
                name: "Tomato".to_owned(),
                extra_price_paise: 1_500,
                is_active: true,
                sort_order: 0,
            }],
            standard_toppings: vec![FfiStandardTopping {
                id: STANDARD_TOPPING.to_owned(),
                product_id: PRODUCT.to_owned(),
                name: "Extra cheese".to_owned(),
                is_veg: true,
                extra_price_paise: 1_000,
                is_default: false,
                is_active: true,
                sort_order: 0,
            }],
            half_and_half_configs: vec![FfiHalfAndHalfConfig {
                id: HALF_CONFIG.to_owned(),
                product_id: PRODUCT.to_owned(),
                name: "Two halves".to_owned(),
                left_topping_ids: vec![TOPPING_1.to_owned()],
                right_topping_ids: vec![TOPPING_2.to_owned()],
                half_and_half_surcharge_paise: 1_000,
                is_active: true,
                sort_order: 0,
            }],
            product_ids: vec![PRODUCT.to_owned(), FOREIGN_PRODUCT.to_owned()],
            product_names: HashMap::from([
                (PRODUCT.to_owned(), "Margherita".to_owned()),
                (FOREIGN_PRODUCT.to_owned(), "Foreign".to_owned()),
            ]),
            product_is_veg: HashMap::from([(PRODUCT.to_owned(), true)]),
            product_is_taxable: HashMap::from([(PRODUCT.to_owned(), true)]),
            product_image_urls: HashMap::new(),
            variant_product_ids: HashMap::from([(VARIANT.to_owned(), PRODUCT.to_owned())]),
            variant_size_codes: HashMap::from([(VARIANT.to_owned(), "REGULAR".to_owned())]),
            variant_prices_paise: HashMap::from([(VARIANT.to_owned(), 10_000)]),
            active_variant_ids: vec![VARIANT.to_owned()],
            active_crust_ids: vec![CRUST.to_owned()],
            crust_names: HashMap::from([(CRUST.to_owned(), "Classic".to_owned())]),
            crust_extra_paise: HashMap::from([(CRUST.to_owned(), 100)]),
            active_topping_ids: vec![TOPPING_1.to_owned(), TOPPING_2.to_owned()],
            topping_names: HashMap::from([
                (TOPPING_1.to_owned(), "Onion".to_owned()),
                (TOPPING_2.to_owned(), "Tomato".to_owned()),
            ]),
            topping_is_veg: HashMap::from([
                (TOPPING_1.to_owned(), true),
                (TOPPING_2.to_owned(), true),
            ]),
            topping_extra_paise: HashMap::from([
                (TOPPING_1.to_owned(), 500),
                (TOPPING_2.to_owned(), 700),
            ]),
            product_toppings,
        }
    }

    fn valid_cart_item() -> FfiCartItemInputExtended {
        FfiCartItemInputExtended {
            id: Some("line-1".to_owned()),
            product_id: PRODUCT.to_owned(),
            variant_id: VARIANT.to_owned(),
            crust_id: CRUST.to_owned(),
            topping_ids: vec![TOPPING_1.to_owned()],
            quantity: 2,
            cheese_id: Some(CHEESE.to_owned()),
            sauce_id: Some(SAUCE.to_owned()),
            standard_topping_ids: vec![STANDARD_TOPPING.to_owned()],
            half_and_half_config_id: None,
            half_and_half_left_topping_ids: vec![],
            half_and_half_right_topping_ids: vec![],
        }
    }

    fn reorder_item(order_item_id: &str, cheese_id: Option<&str>) -> FfiReorderItem {
        FfiReorderItem {
            order_item_id: order_item_id.to_owned(),
            product_id: PRODUCT.to_owned(),
            product_name: "Margherita".to_owned(),
            variant_id: Some(VARIANT.to_owned()),
            crust_id: Some(CRUST.to_owned()),
            cheese_id: cheese_id.map(ToOwned::to_owned),
            sauce_id: None,
            topping_ids: vec![TOPPING_1.to_owned()],
            standard_topping_ids: vec![STANDARD_TOPPING.to_owned()],
            standard_topping_removed_ids: vec![],
            half_and_half_config_id: None,
            half_and_half_left_topping_ids: vec![],
            half_and_half_right_topping_ids: vec![],
            quantity: 1,
            is_available: false,
            issues: vec![],
        }
    }

    #[test]
    fn cart_validate_with_modifiers_accepts_valid_catalog_cart() {
        let evaluated = cart_validate_with_modifiers(vec![valid_cart_item()], test_catalog())
            .expect("valid catalog and cart must validate");
        assert_eq!(evaluated.len(), 1);
        assert!(evaluated[0].is_available);
        assert!(evaluated[0].issues.is_empty());
        assert_eq!(evaluated[0].unit_price_paise, 15_100);
        assert_eq!(evaluated[0].line_total_paise, 30_200);
    }

    #[test]
    fn cart_validate_with_modifiers_rejects_inactive_foreign_and_over_limit() {
        let mut inactive = test_catalog();
        inactive.cheeses[0].is_active = false;
        let err = cart_validate_with_modifiers(vec![valid_cart_item()], inactive).unwrap_err();
        assert!(format!("{err}").contains("CHEESE_UNAVAILABLE"), "{err}");

        let mut foreign = test_catalog();
        foreign.cheeses[0].product_id = FOREIGN_PRODUCT.to_owned();
        let err = cart_validate_with_modifiers(vec![valid_cart_item()], foreign).unwrap_err();
        assert!(format!("{err}").contains("CHEESE_NOT_IN_PRODUCT"), "{err}");

        let mut over_limit_catalog = test_catalog();
        let extra_toppings = (0..MAX_HALF_AND_HALF_TOPPINGS_PER_HALF + 1)
            .map(|index| format!("00000000-0000-0000-0000-{index:012x}"))
            .collect::<Vec<_>>();
        over_limit_catalog
            .product_toppings
            .get_mut(PRODUCT)
            .expect("product topping entry")
            .extend(extra_toppings.iter().cloned());
        over_limit_catalog
            .active_topping_ids
            .extend(extra_toppings.iter().cloned());

        let mut over_limit_item = valid_cart_item();
        over_limit_item.topping_ids.clear();
        over_limit_item.cheese_id = None;
        over_limit_item.sauce_id = None;
        over_limit_item.standard_topping_ids.clear();
        over_limit_item.half_and_half_config_id = Some(HALF_CONFIG.to_owned());
        over_limit_item.half_and_half_left_topping_ids = extra_toppings;
        let err =
            cart_validate_with_modifiers(vec![over_limit_item], over_limit_catalog).unwrap_err();
        assert!(
            format!("{err}").contains("HALF_AND_HALF_TOPPING_LIMIT_EXCEEDED"),
            "{err}"
        );
    }

    #[test]
    fn catalog_check_modifier_availability_filters_product_and_active_modifiers() {
        let filtered =
            catalog_check_modifier_availability(PRODUCT.to_owned(), test_catalog()).unwrap();
        assert_eq!(filtered.product_ids, vec![PRODUCT]);
        assert_eq!(filtered.cheeses.len(), 1);
        assert_eq!(filtered.sauces.len(), 1);
        assert_eq!(filtered.standard_toppings.len(), 1);
        assert_eq!(filtered.half_and_half_configs.len(), 1);
        assert_eq!(filtered.variant_product_ids.len(), 1);
        assert!(filtered.active_variant_ids.contains(&VARIANT.to_owned()));
        assert_eq!(filtered.active_topping_ids.len(), 2);
        assert_eq!(filtered.product_toppings.len(), 1);
        assert_eq!(
            filtered.product_toppings.get(PRODUCT).map(Vec::len),
            Some(2)
        );

        let mut inactive = test_catalog();
        inactive.cheeses[0].is_active = false;
        let filtered = catalog_check_modifier_availability(PRODUCT.to_owned(), inactive).unwrap();
        assert!(filtered.cheeses.is_empty());

        let foreign =
            catalog_check_modifier_availability(FOREIGN_PRODUCT.to_owned(), test_catalog())
                .unwrap();
        assert!(foreign.cheeses.is_empty());
        assert!(foreign.sauces.is_empty());
        assert!(foreign.standard_toppings.is_empty());
        assert!(foreign.half_and_half_configs.is_empty());
        assert!(foreign.product_toppings.is_empty());
        assert!(foreign.active_variant_ids.is_empty());
    }

    #[test]
    fn reorder_validate_items_reports_unavailable_without_substitution() {
        let available = reorder_item(ORDER_ITEM, Some(CHEESE));
        let unavailable = reorder_item(ORDER_ITEM_2, Some(FOREIGN_CHEESE));

        let validation = reorder_validate_items(
            ORDER.to_owned(),
            vec![available, unavailable],
            test_catalog(),
        )
        .unwrap();

        assert_eq!(validation.order_id, ORDER);
        assert!(validation.can_reorder);
        assert_eq!(validation.items.len(), 2);
        assert!(validation.items[0].is_available);
        assert!(validation.items[0].issues.is_empty());
        assert!(!validation.items[1].is_available);
        assert!(validation.items[1]
            .issues
            .contains(&"CHEESE_UNAVAILABLE".to_owned()));
        assert_eq!(validation.unavailable.len(), 1);
        assert_eq!(validation.unavailable[0].order_item_id, ORDER_ITEM_2);
        assert_eq!(validation.unavailable[0].modifier_type, "CHEESE");
        assert_eq!(validation.unavailable[0].modifier_id, FOREIGN_CHEESE);
        assert_eq!(validation.unavailable[0].reason, "CHEESE_UNAVAILABLE");
        // Historical IDs are preserved verbatim; no substitution is performed.
        assert_eq!(
            validation.items[1].cheese_id.as_deref(),
            Some(FOREIGN_CHEESE)
        );
        assert_eq!(validation.items[0].variant_id.as_deref(), Some(VARIANT));
        assert_eq!(validation.warnings.len(), 2);
        assert!(validation
            .warnings
            .iter()
            .all(|warning| warning.contains("half-and-half config")));

        let all_unavailable = reorder_validate_items(
            ORDER.to_owned(),
            vec![reorder_item(ORDER_ITEM_2, Some(FOREIGN_CHEESE))],
            test_catalog(),
        )
        .unwrap();
        assert!(!all_unavailable.can_reorder);
        assert!(!all_unavailable.items[0].is_available);
    }

    #[test]
    fn pricing_validate_half_and_half_matches_core_breakdown() {
        let input = FfiPricedLineInputExtended {
            variant_price_paise: 10_000,
            crust_extra_paise: 100,
            cheese_extra_paise: 2_000,
            sauce_extra_paise: 1_500,
            standard_toppings: vec![FfiStandardToppingSelection {
                id: STANDARD_TOPPING.to_owned(),
                extra_price_paise: 700,
                is_default: false,
                is_removed: false,
            }],
            extra_topping_extras: vec![],
            half_and_half_left_topping_extras: Some(vec![500]),
            half_and_half_right_topping_extras: Some(vec![700]),
            half_and_half_surcharge_paise: Some(1_000),
            quantity: 1,
        };

        let actual = pricing_validate_half_and_half(input.clone()).unwrap();

        let core_input: PricedLineInputExtended = input.try_into().unwrap();
        let std_extra =
            PricingEngine::calculate_standard_toppings_extra_paise(&core_input.standard_toppings)
                .unwrap();
        let (_, core_breakdown) = PricingEngine::calculate_unit_price_with_breakdown(
            core_input.variant_price_paise,
            core_input.crust_extra_paise,
            core_input.cheese_extra_paise,
            core_input.sauce_extra_paise,
            std_extra,
            &core_input.extra_topping_extras,
            core_input.half_and_half.as_ref(),
        )
        .unwrap();
        let expected: FfiHalfAndHalfBreakdown = core_breakdown.unwrap().into();

        assert_eq!(actual, expected);
        assert_eq!(actual.left_half_base_paise, 10_600);
        assert_eq!(actual.right_half_base_paise, 10_800);
        assert_eq!(actual.average_paise, 10_700);
        assert_eq!(actual.surcharge_paise, 1_000);
        assert_eq!(actual.cheese_extra_paise, 2_000);
        assert_eq!(actual.sauce_extra_paise, 1_500);
        assert_eq!(actual.standard_toppings_extra_paise, 700);
        assert_eq!(actual.unit_price_paise, 15_900);
    }

    #[test]
    fn malformed_uuids_return_errors_without_panicking() {
        let bad_selection = FfiStandardToppingSelection {
            id: "not-a-uuid".to_owned(),
            extra_price_paise: 1_000,
            is_default: false,
            is_removed: false,
        };
        assert!(
            pricing_calculate_standard_toppings_extra_paise(vec![bad_selection.clone()]).is_err()
        );

        let mut bad_pricing_input = FfiPricedLineInputExtended {
            variant_price_paise: 10_000,
            crust_extra_paise: 0,
            cheese_extra_paise: 0,
            sauce_extra_paise: 0,
            standard_toppings: vec![bad_selection],
            extra_topping_extras: vec![],
            half_and_half_left_topping_extras: None,
            half_and_half_right_topping_extras: None,
            half_and_half_surcharge_paise: None,
            quantity: 1,
        };
        assert!(pricing_calculate_unit_price_paise(bad_pricing_input.clone()).is_err());
        bad_pricing_input.standard_toppings.clear();
        bad_pricing_input.half_and_half_surcharge_paise = Some(0);
        assert!(pricing_validate_half_and_half(bad_pricing_input).is_ok());

        assert!(
            catalog_check_modifier_availability("not-a-uuid".to_owned(), test_catalog()).is_err()
        );
        assert!(reorder_validate_items("not-a-uuid".to_owned(), vec![], test_catalog()).is_err());
        assert!(reorder_validate_items(
            ORDER.to_owned(),
            vec![reorder_item("not-a-uuid", Some(CHEESE))],
            test_catalog()
        )
        .is_err());

        let mut bad_catalog = test_catalog();
        bad_catalog.cheeses[0].id = "not-a-uuid".to_owned();
        assert!(cart_validate_with_modifiers(vec![valid_cart_item()], bad_catalog).is_err());
    }
}
