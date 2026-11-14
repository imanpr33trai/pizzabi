package com.pizzora.contracts

object PaymentStateMachine {

  fun canTransitionPayment(
    from: PaymentStatus,
    to: PaymentStatus,
  ): Boolean {
    if (from == to) return false
    return when (from) {
      PaymentStatus.UNPAID ->
        to == PaymentStatus.CAPTURED ||
          to == PaymentStatus.EXPIRED ||
          to == PaymentStatus.CANCELLED
      PaymentStatus.COD_PENDING ->
        to == PaymentStatus.COD_COLLECTED ||
          to == PaymentStatus.CANCELLED
      PaymentStatus.CAPTURED ->
        to == PaymentStatus.REFUND_PENDING
      // Explicit exceptional late-capture refund transition when capture arrives after order EXPIRED/CANCELLED
      PaymentStatus.EXPIRED, PaymentStatus.CANCELLED ->
        to == PaymentStatus.REFUND_PENDING
      PaymentStatus.REFUND_PENDING ->
        to == PaymentStatus.PARTIALLY_REFUNDED ||
          to == PaymentStatus.REFUNDED
      PaymentStatus.PARTIALLY_REFUNDED ->
        to == PaymentStatus.REFUND_PENDING ||
          to == PaymentStatus.REFUNDED
      PaymentStatus.COD_COLLECTED, PaymentStatus.REFUNDED -> false
    }
  }

  fun canTransitionAttempt(
    from: PaymentAttemptStatus,
    to: PaymentAttemptStatus,
  ): Boolean {
    if (from == to) return false
    return when (from) {
      PaymentAttemptStatus.INITIATING ->
        to == PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT ||
          to == PaymentAttemptStatus.FAILED ||
          to == PaymentAttemptStatus.EXPIRED ||
          to == PaymentAttemptStatus.CANCELLED
      PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT ->
        to == PaymentAttemptStatus.AUTHORIZED ||
          to == PaymentAttemptStatus.CAPTURED ||
          to == PaymentAttemptStatus.FAILED ||
          to == PaymentAttemptStatus.EXPIRED ||
          to == PaymentAttemptStatus.CANCELLED
      PaymentAttemptStatus.AUTHORIZED ->
        to == PaymentAttemptStatus.CAPTURED ||
          to == PaymentAttemptStatus.FAILED ||
          to == PaymentAttemptStatus.EXPIRED ||
          to == PaymentAttemptStatus.CANCELLED
      PaymentAttemptStatus.CAPTURED,
      PaymentAttemptStatus.FAILED,
      PaymentAttemptStatus.EXPIRED,
      PaymentAttemptStatus.CANCELLED -> false
    }
  }
}
