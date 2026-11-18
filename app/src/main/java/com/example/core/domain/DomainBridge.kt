package com.example.core.domain

import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CausalDecision
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PizzaBuilderEvaluation
import com.pizzora.contracts.PriceBreakdownDto
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.UserRole

enum class CartSyncTrigger {
  PASSIVE_BOOTSTRAP,
  EXPLICIT_MUTATION,
  EXPLICIT_CLEAR_OR_REMOVE,
  PERIODIC_VALIDATE,
}

enum class CartSyncAction {
  DISCARD_STALE_SESSION,
  FETCH_SERVER_CART,
  REPLACE_SERVER_CART_EMPTY,
  REPLACE_SERVER_CART_WITH_VALIDATE_FALLBACK,
  VALIDATE_CART_ONLY,
  NO_OP_EMPTY_LOCAL,
}

data class SessionIdentitySnapshot(
  val epoch: Long,
  val userId: String,
)

data class CartSyncPlanRequest(
  val activeSession: SessionIdentitySnapshot?,
  val capturedSession: SessionIdentitySnapshot,
  val trigger: CartSyncTrigger,
  val localItemCount: Int,
  val persistToServer: Boolean,
  val allowEmptyReplace: Boolean,
)

interface DomainCausalTracker {
  val lastSeenStreamSeq: Long
  fun advanceCursor(serverHighWaterSeq: Long)
  fun recordSnapshot(orderId: String, version: Long)
  fun localVersionFor(orderId: String): Long?
  fun evaluate(event: RealtimeEventDto): CausalDecision
  fun clear()
}

interface DomainBridge {
  val isNativeRustActive: Boolean
  val bridgeImplementationName: String

  fun domainVersion(): String
  fun bpsOf(amountPaise: Long, bps: Int): Long
  fun formatInr(paise: Long): String
  fun calculateLineTotalPaise(unitPricePaise: Long, quantity: Int): Long
  fun calculateDiscountPaise(subtotalPaise: Long, coupon: CouponCalculationSpec?): Long
  fun calculateQuote(
    lines: List<PricedLineInput>,
    gstBps: Int,
    deliveryFeePaise: Long = 0L,
    coupon: CouponCalculationSpec? = null,
  ): PriceBreakdownDto

  fun computePizzaConfigurationSignature(
    input: CartItemInputDto,
    menu: PublicMenuResponse? = null,
  ): String

  fun evaluatePizzaCustomization(
    input: CartItemInputDto,
    menu: PublicMenuResponse,
  ): PizzaBuilderEvaluation

  fun normalizeCouponCode(raw: String): String
  fun canCustomerCancel(status: OrderStatus): Boolean
  fun canOwnerCancel(status: OrderStatus): Boolean
  fun canCancelOrder(status: OrderStatus, actorRole: UserRole): Boolean
  fun canTransitionOrder(
    from: OrderStatus,
    to: OrderStatus,
    fulfillmentType: FulfillmentType,
    actorRole: UserRole?,
  ): Boolean

  fun nextKdsStatus(status: OrderStatus, fulfillmentType: FulfillmentType): OrderStatus?
  fun canTransitionPayment(from: PaymentStatus, to: PaymentStatus): Boolean
  fun canTransitionPaymentAttempt(from: PaymentAttemptStatus, to: PaymentAttemptStatus): Boolean

  fun planCartSync(request: CartSyncPlanRequest): CartSyncAction
  fun fallbackOnReplaceHttpError(statusCode: Int): CartSyncAction?
  fun validateStorageObjectKey(rawKey: String): String

  fun createCausalTracker(maxTrackedEventIds: Int = 2_000): DomainCausalTracker
}
