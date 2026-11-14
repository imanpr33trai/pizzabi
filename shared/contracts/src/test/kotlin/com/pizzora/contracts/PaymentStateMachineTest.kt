package com.pizzora.contracts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentStateMachineTest {

  @Test
  fun `payment state machine enforces normal and exceptional late-capture transitions`() {
    assertTrue(PaymentStateMachine.canTransitionPayment(PaymentStatus.UNPAID, PaymentStatus.CAPTURED))
    assertTrue(PaymentStateMachine.canTransitionPayment(PaymentStatus.UNPAID, PaymentStatus.EXPIRED))
    assertTrue(PaymentStateMachine.canTransitionPayment(PaymentStatus.UNPAID, PaymentStatus.CANCELLED))
    assertTrue(PaymentStateMachine.canTransitionPayment(PaymentStatus.CAPTURED, PaymentStatus.REFUND_PENDING))
    assertTrue(PaymentStateMachine.canTransitionPayment(PaymentStatus.EXPIRED, PaymentStatus.REFUND_PENDING))
    assertTrue(PaymentStateMachine.canTransitionPayment(PaymentStatus.CANCELLED, PaymentStatus.REFUND_PENDING))
    assertTrue(PaymentStateMachine.canTransitionPayment(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED))
    assertTrue(PaymentStateMachine.canTransitionPayment(PaymentStatus.REFUND_PENDING, PaymentStatus.PARTIALLY_REFUNDED))

    assertFalse(PaymentStateMachine.canTransitionPayment(PaymentStatus.REFUNDED, PaymentStatus.REFUND_PENDING))
    assertFalse(PaymentStateMachine.canTransitionPayment(PaymentStatus.COD_COLLECTED, PaymentStatus.CANCELLED))
    assertFalse(PaymentStateMachine.canTransitionPayment(PaymentStatus.EXPIRED, PaymentStatus.CAPTURED))
  }

  @Test
  fun `payment attempt state machine enforces attempt lifecycle`() {
    assertTrue(
      PaymentStateMachine.canTransitionAttempt(
        PaymentAttemptStatus.INITIATING,
        PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
      ),
    )
    assertTrue(
      PaymentStateMachine.canTransitionAttempt(
        PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
        PaymentAttemptStatus.CAPTURED,
      ),
    )
    assertTrue(
      PaymentStateMachine.canTransitionAttempt(
        PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT,
        PaymentAttemptStatus.FAILED,
      ),
    )
    assertFalse(
      PaymentStateMachine.canTransitionAttempt(
        PaymentAttemptStatus.FAILED,
        PaymentAttemptStatus.CAPTURED,
      ),
    )
    assertFalse(
      PaymentStateMachine.canTransitionAttempt(
        PaymentAttemptStatus.CAPTURED,
        PaymentAttemptStatus.FAILED,
      ),
    )
  }
}
