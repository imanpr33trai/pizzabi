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
