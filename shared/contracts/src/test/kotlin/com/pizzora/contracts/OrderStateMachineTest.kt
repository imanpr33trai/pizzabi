package com.pizzora.contracts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderStateMachineTest {

  @Test
  fun `customer can cancel before PREPARING and is blocked once PREPARING begins`() {
    assertTrue(OrderStateMachine.canCancel(OrderStatus.PENDING_PAYMENT, UserRole.CUSTOMER))
    assertTrue(OrderStateMachine.canCancel(OrderStatus.PLACED, UserRole.CUSTOMER))
    assertTrue(OrderStateMachine.canCancel(OrderStatus.ACCEPTED, UserRole.CUSTOMER))
    assertFalse(OrderStateMachine.canCancel(OrderStatus.PREPARING, UserRole.CUSTOMER))
    assertFalse(OrderStateMachine.canCancel(OrderStatus.READY, UserRole.CUSTOMER))
    assertFalse(OrderStateMachine.canCancel(OrderStatus.COMPLETED, UserRole.CUSTOMER))
  }

  @Test
  fun `owner can cancel up to READY and kitchen staff cannot cancel orders`() {
    assertTrue(OrderStateMachine.canCancel(OrderStatus.PREPARING, UserRole.OWNER))
    assertTrue(OrderStateMachine.canCancel(OrderStatus.READY, UserRole.OWNER))
    assertFalse(OrderStateMachine.canCancel(OrderStatus.OUT_FOR_DELIVERY, UserRole.OWNER))
    assertFalse(OrderStateMachine.canCancel(OrderStatus.PLACED, UserRole.KITCHEN_STAFF))
  }

  @Test
  fun `dispatch transitions respect DELIVERY vs PICKUP fulfillment type`() {
    assertTrue(
      OrderStateMachine.canTransition(
        from = OrderStatus.READY,
        to = OrderStatus.OUT_FOR_DELIVERY,
        fulfillmentType = FulfillmentType.DELIVERY,
        actorRole = UserRole.KITCHEN_STAFF,
      ),
    )
    assertFalse(
      OrderStateMachine.canTransition(
        from = OrderStatus.READY,
        to = OrderStatus.COMPLETED,
        fulfillmentType = FulfillmentType.DELIVERY,
        actorRole = UserRole.KITCHEN_STAFF,
      ),
    )
    assertTrue(
      OrderStateMachine.canTransition(
        from = OrderStatus.READY,
        to = OrderStatus.COMPLETED,
        fulfillmentType = FulfillmentType.PICKUP,
        actorRole = UserRole.KITCHEN_STAFF,
      ),
    )
    assertFalse(
      OrderStateMachine.canTransition(
        from = OrderStatus.READY,
        to = OrderStatus.OUT_FOR_DELIVERY,
        fulfillmentType = FulfillmentType.PICKUP,
        actorRole = UserRole.KITCHEN_STAFF,
      ),
    )
  }

  @Test
  fun `system-only transitions from PENDING_PAYMENT reject human actors`() {
    assertTrue(
      OrderStateMachine.canTransition(
        from = OrderStatus.PENDING_PAYMENT,
        to = OrderStatus.PLACED,
        fulfillmentType = FulfillmentType.DELIVERY,
        actorRole = null,
      ),
    )
    assertFalse(
      OrderStateMachine.canTransition(
        from = OrderStatus.PENDING_PAYMENT,
        to = OrderStatus.PLACED,
        fulfillmentType = FulfillmentType.DELIVERY,
        actorRole = UserRole.CUSTOMER,
      ),
    )
    assertTrue(
      OrderStateMachine.canTransition(
        from = OrderStatus.PENDING_PAYMENT,
        to = OrderStatus.EXPIRED,
        fulfillmentType = FulfillmentType.PICKUP,
        actorRole = null,
      ),
    )
  }
}
