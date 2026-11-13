package com.pizzora.contracts

object OrderStateMachine {

  fun canCustomerCancel(status: OrderStatus): Boolean =
    status == OrderStatus.PENDING_PAYMENT ||
      status == OrderStatus.PLACED ||
      status == OrderStatus.ACCEPTED

  fun canOwnerCancel(status: OrderStatus): Boolean =
    status == OrderStatus.PENDING_PAYMENT ||
      status == OrderStatus.PLACED ||
      status == OrderStatus.ACCEPTED ||
      status == OrderStatus.PREPARING ||
      status == OrderStatus.READY

  fun canCancel(status: OrderStatus, actorRole: UserRole): Boolean =
    when (actorRole) {
      UserRole.CUSTOMER -> canCustomerCancel(status)
      UserRole.OWNER -> canOwnerCancel(status)
      UserRole.KITCHEN_STAFF -> false
    }

  fun canTransition(
    from: OrderStatus,
    to: OrderStatus,
    fulfillmentType: FulfillmentType,
    actorRole: UserRole?,
  ): Boolean {
    if (from.isTerminal || from == to) return false
    return when (to) {
      OrderStatus.PLACED -> from == OrderStatus.PENDING_PAYMENT && actorRole == null
      OrderStatus.EXPIRED -> from == OrderStatus.PENDING_PAYMENT && actorRole == null
      OrderStatus.ACCEPTED -> from == OrderStatus.PLACED && isKitchenOrOwner(actorRole)
      OrderStatus.PREPARING -> from == OrderStatus.ACCEPTED && isKitchenOrOwner(actorRole)
      OrderStatus.READY -> from == OrderStatus.PREPARING && isKitchenOrOwner(actorRole)
      OrderStatus.OUT_FOR_DELIVERY ->
        from == OrderStatus.READY &&
          fulfillmentType == FulfillmentType.DELIVERY &&
          isKitchenOrOwner(actorRole)
      OrderStatus.COMPLETED ->
        isKitchenOrOwner(actorRole) && (
          (from == OrderStatus.READY && fulfillmentType == FulfillmentType.PICKUP) ||
            (from == OrderStatus.OUT_FOR_DELIVERY && fulfillmentType == FulfillmentType.DELIVERY)
          )
      OrderStatus.CANCELLED -> actorRole != null && canCancel(from, actorRole)
      OrderStatus.PENDING_PAYMENT -> false
    }
  }

  private fun isKitchenOrOwner(actorRole: UserRole?): Boolean =
    actorRole == UserRole.KITCHEN_STAFF || actorRole == UserRole.OWNER
}
