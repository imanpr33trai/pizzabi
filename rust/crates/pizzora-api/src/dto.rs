use std::collections::BTreeMap;

use pizzora_core::{
    CartValidationIssueCode, FulfillmentType, OrderStatus, PaymentMethod, PaymentStatus,
    ScheduleType, SizeCode,
};
use pizzora_db::{
    AppliedCouponSummaryDto, InventoryItemDto, OrderDetailDto, OrderSummaryDto,
};
use serde::{Deserialize, Serialize};

fn default_true() -> bool {
    true
}

fn default_timezone_ist() -> String {
    "Asia/Kolkata".to_owned()
}

fn default_put_method() -> String {
    "PUT".to_owned()
}

// ---------- Health & Readiness ----------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ReadinessState {
    Healthy,
    Degraded,
    Unhealthy,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HealthLivenessResponse {
    pub status: String,
    pub service: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HealthReadinessResponse {
    pub status: ReadinessState,
    pub dependencies: BTreeMap<String, pizzora_db::DependencyHealthStatus>,
    pub service: String,
}

// ---------- Catalog ----------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ProductImageStatus {
    Pending,
    Active,
    Deleted,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CategoryDto {
    pub id: String,
    pub name: String,
    pub sort_order: i32,
    pub is_active: bool,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateCategoryRequest {
    pub name: String,
    #[serde(default)]
    pub sort_order: i32,
    #[serde(default = "default_true")]
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateCategoryRequest {
    pub name: Option<String>,
    pub sort_order: Option<i32>,
    pub is_active: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProductVariantDto {
    pub id: String,
    pub product_id: String,
    pub size_code: SizeCode,
    pub price_paise: i64,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateVariantRequest {
    pub size_code: SizeCode,
    pub price_paise: i64,
    #[serde(default = "default_true")]
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateVariantRequest {
    pub price_paise: Option<i64>,
    pub is_active: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CrustOptionDto {
    pub id: String,
    pub name: String,
    pub extra_price_paise: i64,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateCrustRequest {
    pub name: String,
    pub extra_price_paise: i64,
    #[serde(default = "default_true")]
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateCrustRequest {
    pub name: Option<String>,
    pub extra_price_paise: Option<i64>,
    pub is_active: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToppingDto {
    pub id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateToppingRequest {
    pub name: String,
    #[serde(default = "default_true")]
    pub is_veg: bool,
    pub extra_price_paise: i64,
    #[serde(default = "default_true")]
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateToppingRequest {
    pub name: Option<String>,
    pub is_veg: Option<bool>,
    pub extra_price_paise: Option<i64>,
    pub is_active: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProductImageDto {
    pub id: String,
    pub product_id: String,
    pub object_key: String,
    pub public_url: String,
    pub mime_type: String,
    pub size_bytes: i64,
    pub status: ProductImageStatus,
    pub sort_order: i32,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProductDto {
    pub id: String,
    pub category_id: String,
    pub category_name: String,
    pub name: String,
    pub description: String,
    pub is_veg: bool,
    pub is_active: bool,
    pub is_taxable: bool,
    #[serde(default)]
    pub variants: Vec<ProductVariantDto>,
    #[serde(default)]
    pub images: Vec<ProductImageDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateProductRequest {
    pub category_id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default = "default_true")]
    pub is_veg: bool,
    #[serde(default = "default_true")]
    pub is_active: bool,
    #[serde(default = "default_true")]
    pub is_taxable: bool,
    pub variants: Vec<CreateVariantRequest>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateProductRequest {
    pub category_id: Option<String>,
    pub name: Option<String>,
    pub description: Option<String>,
    pub is_veg: Option<bool>,
    pub is_active: Option<bool>,
    pub is_taxable: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PublicMenuResponse {
    pub categories: Vec<CategoryDto>,
    pub products: Vec<ProductDto>,
    pub crusts: Vec<CrustOptionDto>,
    pub toppings: Vec<ToppingDto>,
    pub gst_bps: i32,
    pub store_name: String,
    pub is_accepting_orders: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PresignProductImageRequest {
    pub mime_type: String,
    pub size_bytes: i64,
    #[serde(default)]
    pub sort_order: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PresignProductImageResponse {
    pub image_id: String,
    pub object_key: String,
    pub upload_url: String,
    #[serde(default = "default_put_method")]
    pub method: String,
    #[serde(default)]
    pub required_headers: BTreeMap<String, String>,
    pub expires_in_seconds: i64,
    pub public_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConfirmProductImageRequest {
    pub image_id: String,
}

// ---------- Cart & Pricing & Checkout ----------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartValidationIssueDto {
    pub code: CartValidationIssueCode,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartItemInputDto {
    #[serde(default)]
    pub id: Option<String>,
    pub product_id: String,
    pub variant_id: String,
    pub crust_id: String,
    #[serde(default)]
    pub topping_ids: Vec<String>,
    pub quantity: i32,
    #[serde(default)]
    pub client_unit_price_paise: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ReplaceCartRequest {
    #[serde(default)]
    pub items: Vec<CartItemInputDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ValidateCartRequest {
    #[serde(default)]
    pub items: Vec<CartItemInputDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartToppingViewDto {
    pub topping_id: String,
    pub name: String,
    pub is_veg: bool,
    pub extra_price_paise: i64,
    pub is_available: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartItemViewDto {
    pub id: String,
    pub product_id: String,
    pub product_name: String,
    pub is_veg: bool,
    #[serde(default)]
    pub image_url: Option<String>,
    pub variant_id: String,
    #[serde(default)]
    pub size_code: Option<SizeCode>,
    pub base_price_paise: i64,
    pub crust_id: String,
    pub crust_name: String,
    pub crust_extra_price_paise: i64,
    #[serde(default)]
    pub toppings: Vec<CartToppingViewDto>,
    pub quantity: i32,
    pub unit_price_paise: i64,
    pub line_total_paise: i64,
    pub is_available: bool,
    #[serde(default)]
    pub issues: Vec<CartValidationIssueDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CartResponseDto {
    #[serde(default)]
    pub cart_id: Option<String>,
    #[serde(default)]
    pub items: Vec<CartItemViewDto>,
    #[serde(default)]
    pub subtotal_paise: i64,
    #[serde(default)]
    pub total_item_count: i32,
    #[serde(default = "default_true")]
    pub all_available: bool,
    #[serde(default)]
    pub unavailable_count: i32,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PricingQuoteRequest {
    pub items: Vec<CartItemInputDto>,
    #[serde(default = "default_delivery_fulfillment")]
    pub fulfillment_type: FulfillmentType,
    #[serde(default)]
    pub address_id: Option<String>,
    #[serde(default)]
    pub pincode: Option<String>,
    #[serde(default)]
    pub coupon_code: Option<String>,
}

fn default_delivery_fulfillment() -> FulfillmentType {
    FulfillmentType::Delivery
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeliveryZoneQuoteDto {
    pub pincode: String,
    pub area_name: String,
    pub min_order_paise: i64,
    pub delivery_fee_paise: i64,
    pub estimated_mins: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PriceBreakdownDto {
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

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PricingQuoteResponse {
    pub items: Vec<CartItemViewDto>,
    pub fulfillment_type: FulfillmentType,
    pub breakdown: PriceBreakdownDto,
    #[serde(default)]
    pub applied_coupon: Option<AppliedCouponSummaryDto>,
    #[serde(default)]
    pub delivery_zone: Option<DeliveryZoneQuoteDto>,
    #[serde(default = "default_true")]
    pub is_accepting_orders: bool,
    pub gst_bps: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CheckoutRequest {
    pub items: Vec<CartItemInputDto>,
    pub fulfillment_type: FulfillmentType,
    #[serde(default = "default_asap")]
    pub schedule_type: ScheduleType,
    #[serde(default)]
    pub scheduled_slot_id: Option<String>,
    #[serde(default)]
    pub address_id: Option<String>,
    pub payment_method: PaymentMethod,
    #[serde(default)]
    pub coupon_code: Option<String>,
    #[serde(default = "default_true")]
    pub clear_cart: bool,
}

fn default_asap() -> ScheduleType {
    ScheduleType::Asap
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CheckoutResponse {
    pub order: OrderDetailDto,
    #[serde(default)]
    pub idempotent_replay: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct CancelOrderRequest {
    #[serde(default)]
    pub reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TransitionOrderStatusRequest {
    pub to_status: OrderStatus,
    #[serde(default)]
    pub reason: Option<String>,
}

// ---------- Recipes ----------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RecipeTargetType {
    Variant,
    Crust,
    Topping,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecipeIngredientLineRequest {
    pub ingredient_id: String,
    pub quantity: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecipeIngredientLineDto {
    pub id: String,
    pub ingredient_id: String,
    pub ingredient_name: String,
    pub unit: pizzora_db::IngredientUnit,
    pub quantity: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecipeBindingDto {
    pub target_type: RecipeTargetType,
    pub target_id: String,
    pub target_label: String,
    #[serde(default)]
    pub ingredients: Vec<RecipeIngredientLineDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecipesOverviewResponse {
    #[serde(default)]
    pub variants: Vec<RecipeBindingDto>,
    #[serde(default)]
    pub crusts: Vec<RecipeBindingDto>,
    #[serde(default)]
    pub toppings: Vec<RecipeBindingDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PutRecipeRequest {
    pub target_type: RecipeTargetType,
    pub target_id: String,
    pub ingredients: Vec<RecipeIngredientLineRequest>,
}

// ---------- KDS ----------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KdsTicketItemDto {
    pub id: String,
    pub product_id: String,
    pub product_name: String,
    pub variant_size: String,
    pub crust_name: String,
    #[serde(default)]
    pub toppings: Vec<String>,
    pub quantity: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KdsTicketDto {
    pub id: String,
    pub order_number: String,
    pub customer_name: String,
    #[serde(default)]
    pub customer_phone: Option<String>,
    pub fulfillment_type: FulfillmentType,
    pub schedule_type: ScheduleType,
    pub status: OrderStatus,
    pub version: i64,
    pub is_priority: bool,
    pub payment_method: PaymentMethod,
    pub payment_status: PaymentStatus,
    pub total_paise: i64,
    #[serde(default)]
    pub delivery_area: Option<String>,
    #[serde(default)]
    pub accepted_at: Option<String>,
    #[serde(default)]
    pub prep_started_at: Option<String>,
    #[serde(default)]
    pub ready_at: Option<String>,
    #[serde(default)]
    pub completed_at: Option<String>,
    pub created_at: String,
    pub items: Vec<KdsTicketItemDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KdsTicketsResponse {
    pub tickets: Vec<KdsTicketDto>,
    pub server_now: String,
    pub prep_sla_minutes: i32,
    #[serde(default)]
    pub priority_count: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct KdsActionRequest {
    #[serde(default)]
    pub reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UpdateOrderPriorityRequest {
    pub is_priority: bool,
    #[serde(default)]
    pub reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KdsTicketActionResponse {
    pub ticket: KdsTicketDto,
    pub server_now: String,
    pub prep_sla_minutes: i32,
}

// ---------- Owner & Config ----------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PublicConfigResponse {
    pub store_name: String,
    pub is_accepting_orders: bool,
    pub gst_bps: i32,
    pub default_prep_mins: i32,
    #[serde(default = "default_timezone_ist")]
    pub timezone: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RestaurantSettingsDto {
    pub store_name: String,
    pub is_accepting_orders: bool,
    pub gst_bps: i32,
    pub default_prep_mins: i32,
    #[serde(default = "default_timezone_ist")]
    pub timezone: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateRestaurantSettingsRequest {
    pub store_name: Option<String>,
    pub is_accepting_orders: Option<bool>,
    pub gst_bps: Option<i32>,
    pub default_prep_mins: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OwnerDashboardResponse {
    pub today_revenue_paise: i64,
    pub today_order_count: i32,
    pub active_order_count: i32,
    pub pending_payment_count: i32,
    pub completed_today_count: i32,
    pub low_stock_count: i32,
    #[serde(default)]
    pub low_stock_items: Vec<InventoryItemDto>,
    #[serde(default)]
    pub recent_orders: Vec<OrderSummaryDto>,
    pub settings: RestaurantSettingsDto,
    pub server_now: String,
}

// ---------- Delivery Zones & Scheduled Slots ----------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeliveryZoneDto {
    pub id: String,
    pub pincode: String,
    pub area_name: String,
    pub min_order_paise: i64,
    pub delivery_fee_paise: i64,
    pub estimated_mins: i32,
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeliveryZoneCheckResponse {
    pub pincode: String,
    pub is_serviceable: bool,
    #[serde(default)]
    pub zone: Option<DeliveryZoneDto>,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateDeliveryZoneRequest {
    pub pincode: String,
    pub area_name: String,
    pub min_order_paise: i64,
    pub delivery_fee_paise: i64,
    pub estimated_mins: i32,
    #[serde(default = "default_true")]
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateDeliveryZoneRequest {
    pub area_name: Option<String>,
    pub min_order_paise: Option<i64>,
    pub delivery_fee_paise: Option<i64>,
    pub estimated_mins: Option<i32>,
    pub is_active: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScheduledSlotDto {
    pub id: String,
    pub slot_date: String,
    pub fulfillment_type: FulfillmentType,
    pub start_time_ist: String,
    pub end_time_ist: String,
    pub cutoff_at: String,
    pub max_capacity: i32,
    pub reserved_count: i32,
    pub remaining_capacity: i32,
    pub is_active: bool,
    pub is_bookable: bool,
    #[serde(default)]
    pub unavailable_reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AvailableSlotsResponse {
    pub date: String,
    pub fulfillment_type: FulfillmentType,
    #[serde(default = "default_timezone_ist")]
    pub timezone: String,
    pub server_now: String,
    pub slots: Vec<ScheduledSlotDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateScheduledSlotRequest {
    pub slot_date: String,
    pub fulfillment_type: FulfillmentType,
    pub start_time_ist: String,
    pub end_time_ist: String,
    #[serde(default)]
    pub cutoff_at: Option<String>,
    #[serde(default)]
    pub cutoff_minutes_before_start: Option<i32>,
    pub max_capacity: i32,
    #[serde(default = "default_true")]
    pub is_active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct UpdateScheduledSlotRequest {
    pub cutoff_at: Option<String>,
    pub cutoff_minutes_before_start: Option<i32>,
    pub max_capacity: Option<i32>,
    pub is_active: Option<bool>,
}

// ---------- Reports ----------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DailySalesBucketDto {
    pub date: String,
    pub order_count: i32,
    pub gross_sales_paise: i64,
    pub refunded_paise: i64,
    pub net_sales_paise: i64,
    pub tax_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TopSellingProductDto {
    pub product_id: String,
    pub product_name: String,
    pub quantity_sold: i32,
    pub gross_revenue_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CouponRoiDto {
    pub coupon_id: String,
    pub code: String,
    pub redemption_count: i32,
    pub total_discount_paise: i64,
    pub gross_order_revenue_paise: i64,
    pub net_order_revenue_paise: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SalesReportResponse {
    pub start_date: String,
    pub end_date: String,
    #[serde(default = "default_timezone_ist")]
    pub timezone: String,
    pub order_count: i32,
    pub cancelled_order_count: i32,
    pub subtotal_paise: i64,
    pub discount_paise: i64,
    pub delivery_fee_paise: i64,
    pub tax_paise: i64,
    pub cgst_paise: i64,
    pub sgst_paise: i64,
    pub gross_sales_paise: i64,
    pub refunded_paise: i64,
    pub pending_refunds_paise: i64,
    pub net_sales_paise: i64,
    #[serde(default)]
    pub daily_breakdown: Vec<DailySalesBucketDto>,
    #[serde(default)]
    pub top_products: Vec<TopSellingProductDto>,
    #[serde(default)]
    pub coupon_performance: Vec<CouponRoiDto>,
    pub server_now: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OperationalStockMovementDto {
    pub ingredient_id: String,
    pub ingredient_name: String,
    pub unit: pizzora_db::IngredientUnit,
    pub restocked_qty: i64,
    pub consumed_qty: i64,
    pub restored_qty: i64,
    pub wastage_qty: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct OperationsReportResponse {
    pub start_date: String,
    pub end_date: String,
    #[serde(default = "default_timezone_ist")]
    pub timezone: String,
    pub prep_sla_minutes: i32,
    pub total_orders: i32,
    pub completed_orders: i32,
    pub cancelled_orders: i32,
    pub expired_orders: i32,
    pub delivery_orders: i32,
    pub pickup_orders: i32,
    pub scheduled_orders: i32,
    pub measured_prep_orders: i32,
    pub avg_prep_minutes: f64,
    pub max_prep_minutes: f64,
    pub avg_completion_minutes: f64,
    pub sla_breached_count: i32,
    pub sla_compliance_percent: f64,
    pub restocked_units: i64,
    pub consumed_units: i64,
    pub restored_units: i64,
    pub wastage_units: i64,
    #[serde(default)]
    pub stock_movements: Vec<OperationalStockMovementDto>,
    #[serde(default)]
    pub low_stock_items: Vec<InventoryItemDto>,
    pub server_now: String,
}

// ---------- Realtime & WebSocket ----------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RealtimeEventDto {
    pub event_id: String,
    pub stream_seq: i64,
    pub event_type: String,
    pub aggregate_type: String,
    pub aggregate_id: String,
    pub aggregate_version: i64,
    pub payload: serde_json::Map<String, serde_json::Value>,
    #[serde(default)]
    pub sequenced_at: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EventsReplayResponse {
    pub events: Vec<RealtimeEventDto>,
    pub server_high_water_seq: i64,
    #[serde(default)]
    pub has_more: bool,
    pub server_now: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WsEventsFrame {
    #[serde(rename = "type")]
    pub frame_type: String,
    #[serde(default)]
    pub events: Vec<RealtimeEventDto>,
    pub server_high_water_seq: i64,
    pub server_now: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resumed_from_seq: Option<i64>,
}
