package com.pizzora.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UserRole {
  @SerialName("CUSTOMER")
  CUSTOMER,

  @SerialName("KITCHEN_STAFF")
  KITCHEN_STAFF,

  @SerialName("OWNER")
  OWNER,
}

@Serializable
enum class OrderStatus {
  @SerialName("PENDING_PAYMENT")
  PENDING_PAYMENT,

  @SerialName("PLACED")
  PLACED,

  @SerialName("ACCEPTED")
  ACCEPTED,

  @SerialName("PREPARING")
  PREPARING,

  @SerialName("READY")
  READY,

  @SerialName("OUT_FOR_DELIVERY")
  OUT_FOR_DELIVERY,

  @SerialName("COMPLETED")
  COMPLETED,

  @SerialName("CANCELLED")
  CANCELLED,

  @SerialName("EXPIRED")
  EXPIRED;

  val isTerminal: Boolean
    get() = this == COMPLETED || this == CANCELLED || this == EXPIRED
}

@Serializable
enum class FulfillmentType {
  @SerialName("DELIVERY")
  DELIVERY,

  @SerialName("PICKUP")
  PICKUP,
}

@Serializable
enum class ScheduleType {
  @SerialName("ASAP")
  ASAP,

  @SerialName("SCHEDULED")
  SCHEDULED,
}

@Serializable
enum class PaymentMethod {
  @SerialName("RAZORPAY")
  RAZORPAY,

  @SerialName("COD")
  COD,
}

@Serializable
enum class PaymentStatus {
  @SerialName("UNPAID")
  UNPAID,

  @SerialName("COD_PENDING")
  COD_PENDING,

  @SerialName("COD_COLLECTED")
  COD_COLLECTED,

  @SerialName("CAPTURED")
  CAPTURED,

  @SerialName("EXPIRED")
  EXPIRED,

  @SerialName("CANCELLED")
  CANCELLED,

  @SerialName("REFUND_PENDING")
  REFUND_PENDING,

  @SerialName("PARTIALLY_REFUNDED")
  PARTIALLY_REFUNDED,

  @SerialName("REFUNDED")
  REFUNDED,
}

@Serializable
enum class PaymentAttemptStatus {
  @SerialName("INITIATING")
  INITIATING,

  @SerialName("AWAITING_CUSTOMER_PAYMENT")
  AWAITING_CUSTOMER_PAYMENT,

  @SerialName("AUTHORIZED")
  AUTHORIZED,

  @SerialName("CAPTURED")
  CAPTURED,

  @SerialName("FAILED")
  FAILED,

  @SerialName("EXPIRED")
  EXPIRED,

  @SerialName("CANCELLED")
  CANCELLED,
}

@Serializable
enum class RefundStatus {
  @SerialName("PENDING")
  PENDING,

  @SerialName("PROCESSING")
  PROCESSING,

  @SerialName("PROCESSED")
  PROCESSED,

  @SerialName("FAILED")
  FAILED,
}

@Serializable
enum class DiscountType {
  @SerialName("PERCENT_BPS")
  PERCENT_BPS,

  @SerialName("FLAT_PAISE")
  FLAT_PAISE,
}

