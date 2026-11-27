package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CheckoutRequest(
  val items: List<CartItemInputDto>,
  @SerialName("fulfillment_type") val fulfillmentType: FulfillmentType,
  @SerialName("schedule_type") val scheduleType: ScheduleType = ScheduleType.ASAP,
  @SerialName("scheduled_slot_id") val scheduledSlotId: String? = null,
  @SerialName("address_id") val addressId: String? = null,
  @SerialName("payment_method") val paymentMethod: PaymentMethod,
  @SerialName("coupon_code") val couponCode: String? = null,
  @SerialName("clear_cart") val clearCart: Boolean = true,
)

@Serializable
data class DeliveryAddressSnapshotDto(
  @SerialName("address_id") val addressId: String,
  val label: String,
  val line1: String,
  val line2: String? = null,
  val landmark: String? = null,
  val pincode: String,
  val city: String,
)

// Phase 8: Extended OrderItemDto with all modifier IDs, snapshots, and preview data
@Serializable
data class OrderItemDto(
  val id: String,
  @SerialName("product_id") val productId: String,
  @SerialName("product_name") val productName: String,
  @SerialName("variant_size") val variantSize: String,
  @SerialName("crust_name") val crustName: String,
  val toppings: List<String> = emptyList(),
  @SerialName("unit_price_paise") val unitPricePaise: Long,
  val quantity: Int,
  @SerialName("line_total_paise") val lineTotalPaise: Long,
  // Phase 8: New modifier ID fields (nullable for historical orders)
  @SerialName("variant_id") val variantId: String? = null,
  @SerialName("crust_id") val crustId: String? = null,
  @SerialName("cheese_id") val cheeseId: String? = null,
  @SerialName("sauce_id") val sauceId: String? = null,
  @SerialName("topping_ids") val toppingIds: List<String> = emptyList(),
  @SerialName("standard_topping_ids") val standardToppingIds: List<String> = emptyList(),
  @SerialName("standard_topping_removed_ids") val standardToppingRemovedIds: List<String> = emptyList(),
  @SerialName("half_and_half_config_id") val halfAndHalfConfigId: String? = null,
  @SerialName("half_and_half_left_topping_ids") val halfAndHalfLeftToppingIds: List<String> = emptyList(),
  @SerialName("half_and_half_right_topping_ids") val halfAndHalfRightToppingIds: List<String> = emptyList(),
  // Concrete value type: kotlinx.serialization cannot resolve a serializer for `Any`.
  @SerialName("modifiers_snapshot") val modifiersSnapshot: Map<String, String> = emptyMap(),
)

@Serializable
data class OrderStatusHistoryDto(
  val id: String,
  @SerialName("from_status") val fromStatus: OrderStatus? = null,
  @SerialName("to_status") val toStatus: OrderStatus,
  @SerialName("changed_by") val changedBy: String? = null,
  val reason: String? = null,
  @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PaymentAttemptSummaryDto(
  val id: String,
  @SerialName("attempt_number") val attemptNumber: Int,
  val method: PaymentMethod,
  val status: PaymentAttemptStatus,
  @SerialName("amount_paise") val amountPaise: Long,
  @SerialName("idempotency_receipt") val idempotencyReceipt: String,
  @SerialName("gateway_order_id") val gatewayOrderId: String? = null,
  @SerialName("gateway_payment_id") val gatewayPaymentId: String? = null,
  @SerialName("failure_code") val failureCode: String? = null,
  @SerialName("failure_reason") val failureReason: String? = null,
  @SerialName("expires_at") val expiresAt: String? = null,
  @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PaymentSummaryDto(
  val id: String,
  val method: PaymentMethod,
  val status: PaymentStatus,
  @SerialName("amount_paise") val amountPaise: Long,
  @SerialName("captured_paise") val capturedPaise: Long,
  @SerialName("refunded_paise") val refundedPaise: Long,
  val currency: String = "INR",
  @SerialName("latest_attempt") val latestAttempt: PaymentAttemptSummaryDto? = null,
)

@Serializable
data class OrderSummaryDto(
  val id: String,
  @SerialName("order_number") val orderNumber: String,
  @SerialName("fulfillment_type") val fulfillmentType: FulfillmentType,
  @SerialName("schedule_type") val scheduleType: ScheduleType,
  val status: OrderStatus,
  val version: Long,
  @SerialName("total_paise") val totalPaise: Long,
  @SerialName("item_count") val itemCount: Int,
  @SerialName("payment_method") val paymentMethod: PaymentMethod,
  @SerialName("payment_status") val paymentStatus: PaymentStatus,
  @SerialName("is_priority") val isPriority: Boolean,
  @SerialName("created_at") val createdAt: String,
  @SerialName("expires_at") val expiresAt: String? = null,
  // Phase 8: Item preview summary for list view
  @SerialName("item_preview_summary") val itemPreviewSummary: String? = null,
)

@Serializable
data class OrderDetailDto(
  val id: String,
  @SerialName("order_number") val orderNumber: String,
  @SerialName("customer_id") val customerId: String,
  @SerialName("fulfillment_type") val fulfillmentType: FulfillmentType,
  @SerialName("schedule_type") val scheduleType: ScheduleType,
  @SerialName("scheduled_slot_id") val scheduledSlotId: String? = null,
  @SerialName("delivery_address") val deliveryAddress: DeliveryAddressSnapshotDto? = null,
  val status: OrderStatus,
  val version: Long,
  @SerialName("subtotal_paise") val subtotalPaise: Long,
  @SerialName("discount_paise") val discountPaise: Long,
  @SerialName("delivery_fee_paise") val deliveryFeePaise: Long,
  @SerialName("tax_paise") val taxPaise: Long,
  @SerialName("cgst_paise") val cgstPaise: Long,
  @SerialName("sgst_paise") val sgstPaise: Long,
  @SerialName("total_paise") val totalPaise: Long,
  @SerialName("gst_bps_snapshot") val gstBpsSnapshot: Int,
  @SerialName("coupon_code_snapshot") val couponCodeSnapshot: String? = null,
  @SerialName("is_priority") val isPriority: Boolean,
  @SerialName("accepted_at") val acceptedAt: String? = null,
  @SerialName("prep_started_at") val prepStartedAt: String? = null,
  @SerialName("ready_at") val readyAt: String? = null,
  @SerialName("completed_at") val completedAt: String? = null,
  @SerialName("expires_at") val expiresAt: String? = null,
  @SerialName("created_at") val createdAt: String,
  val items: List<OrderItemDto>,
  @SerialName("status_history") val statusHistory: List<OrderStatusHistoryDto>,
  val payment: PaymentSummaryDto,
)

@Serializable
data class CheckoutResponse(
  val order: OrderDetailDto,
  @SerialName("idempotent_replay") val idempotentReplay: Boolean = false,
)

@Serializable
data class OrderListResponse(
  val orders: List<OrderSummaryDto>,
)

@Serializable
data class CancelOrderRequest(
  val reason: String? = null,
)

// Phase 8: Reorder DTOs per contract §6.3
@Serializable
enum class ReorderUnavailableReason {
  @SerialName("PRODUCT_UNAVAILABLE")
  PRODUCT_UNAVAILABLE,

  @SerialName("VARIANT_UNAVAILABLE")
  VARIANT_UNAVAILABLE,

  @SerialName("CRUST_UNAVAILABLE")
  CRUST_UNAVAILABLE,

  @SerialName("TOPPING_UNAVAILABLE")
  TOPPING_UNAVAILABLE,

  @SerialName("CHEESE_UNAVAILABLE")
  CHEESE_UNAVAILABLE,

  @SerialName("SAUCE_UNAVAILABLE")
  SAUCE_UNAVAILABLE,

  @SerialName("STANDARD_TOPPING_UNAVAILABLE")
  STANDARD_TOPPING_UNAVAILABLE,

  @SerialName("HALF_AND_HALF_UNAVAILABLE")
  HALF_AND_HALF_UNAVAILABLE,

  @SerialName("HALF_AND_HALF_CONFIG_UNAVAILABLE")
  HALF_AND_HALF_CONFIG_UNAVAILABLE,
}

@Serializable
data class ReorderItemDto(
  @SerialName("order_item_id") val orderItemId: String,
  @SerialName("product_id") val productId: String,
  @SerialName("product_name") val productName: String,
  @SerialName("variant_id") val variantId: String? = null,
  @SerialName("crust_id") val crustId: String? = null,
  @SerialName("cheese_id") val cheeseId: String? = null,
  @SerialName("sauce_id") val sauceId: String? = null,
  @SerialName("topping_ids") val toppingIds: List<String> = emptyList(),
  @SerialName("standard_topping_ids") val standardToppingIds: List<String> = emptyList(),
  @SerialName("standard_topping_removed_ids") val standardToppingRemovedIds: List<String> = emptyList(),
  @SerialName("half_and_half_config_id") val halfAndHalfConfigId: String? = null,
  @SerialName("half_and_half_left_topping_ids") val halfAndHalfLeftToppingIds: List<String> = emptyList(),
  @SerialName("half_and_half_right_topping_ids") val halfAndHalfRightToppingIds: List<String> = emptyList(),
  val quantity: Int,
  @SerialName("is_available") val isAvailable: Boolean,
  val issues: List<String> = emptyList(),
)

@Serializable
data class ReorderUnavailableDto(
  @SerialName("order_item_id") val orderItemId: String,
  @SerialName("product_name") val productName: String,
  @SerialName("modifier_type") val modifierType: String,
  @SerialName("modifier_id") val modifierId: String,
  @SerialName("modifier_name") val modifierName: String,
  val reason: ReorderUnavailableReason,
)

@Serializable
data class ReorderResponseDto(
  @SerialName("order_id") val orderId: String,
  @SerialName("can_reorder") val canReorder: Boolean,
  val items: List<ReorderItemDto> = emptyList(),
  val unavailable: List<ReorderUnavailableDto> = emptyList(),
  val warnings: List<String> = emptyList(),
)