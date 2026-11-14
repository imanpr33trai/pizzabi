package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RefundDto(
  val id: String,
  @SerialName("payment_id") val paymentId: String,
  @SerialName("payment_attempt_id") val paymentAttemptId: String? = null,
  @SerialName("order_id") val orderId: String,
  @SerialName("idempotency_key") val idempotencyKey: String,
  @SerialName("amount_paise") val amountPaise: Long,
  val status: RefundStatus,
  @SerialName("gateway_refund_id") val gatewayRefundId: String? = null,
  val reason: String,
  @SerialName("initiated_by") val initiatedBy: String? = null,
  @SerialName("created_at") val createdAt: String,
  @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class OrderPaymentStatusResponse(
  @SerialName("order_id") val orderId: String,
  @SerialName("order_number") val orderNumber: String,
  @SerialName("order_status") val orderStatus: OrderStatus,
  val payment: PaymentSummaryDto,
  @SerialName("razorpay_key_id") val razorpayKeyId: String? = null,
  @SerialName("can_retry") val canRetry: Boolean = false,
  @SerialName("expires_at") val expiresAt: String? = null,
  val refunds: List<RefundDto> = emptyList(),
)

@Serializable
data class RetryPaymentRequest(
  val reason: String? = null,
)

@Serializable
data class FailPaymentAttemptRequest(
  @SerialName("attempt_id") val attemptId: String? = null,
  @SerialName("gateway_order_id") val gatewayOrderId: String? = null,
  @SerialName("failure_code") val failureCode: String? = null,
  @SerialName("failure_reason") val failureReason: String? = null,
)

@Serializable
data class VerifyRazorpayPaymentRequest(
  @SerialName("order_id") val orderId: String,
  @SerialName("razorpay_order_id") val razorpayOrderId: String,
  @SerialName("razorpay_payment_id") val razorpayPaymentId: String,
  @SerialName("razorpay_signature") val razorpaySignature: String,
)

@Serializable
data class VerifyRazorpayPaymentResponse(
  val order: OrderDetailDto,
  @SerialName("already_captured") val alreadyCaptured: Boolean = false,
  @SerialName("late_capture_refund_triggered") val lateCaptureRefundTriggered: Boolean = false,
)

@Serializable
data class OwnerRefundRequest(
  @SerialName("amount_paise") val amountPaise: Long? = null,
  val reason: String,
)
