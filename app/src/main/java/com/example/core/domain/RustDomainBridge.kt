package com.example.core.domain

import com.pizzora.contracts.CausalDecision
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PriceBreakdownDto
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.UserRole

object RustDomainBridge : DomainBridge {
  private val fallbackBridge: DomainBridge = KotlinContractsDomainBridge()

  @Volatile
  private var testOverride: DomainBridge? = null

  private val defaultBridge: DomainBridge by lazy {
    PlatformRustDomainBridgeProvider.createOrNull() ?: fallbackBridge
  }

  private val delegate: DomainBridge
    get() = testOverride ?: defaultBridge

  fun overrideForTesting(bridge: DomainBridge?) {
    testOverride = bridge
  }

  fun fallbackInstance(): DomainBridge = fallbackBridge

  override val isNativeRustActive: Boolean
    get() = delegate.isNativeRustActive

  override val bridgeImplementationName: String
    get() = delegate.bridgeImplementationName

  override fun domainVersion(): String = delegate.domainVersion()

  override fun bpsOf(amountPaise: Long, bps: Int): Long = delegate.bpsOf(amountPaise, bps)

  override fun formatInr(paise: Long): String = delegate.formatInr(paise)

  override fun calculateLineTotalPaise(unitPricePaise: Long, quantity: Int): Long =
    delegate.calculateLineTotalPaise(unitPricePaise, quantity)

  override fun calculateDiscountPaise(subtotalPaise: Long, coupon: CouponCalculationSpec?): Long =
    delegate.calculateDiscountPaise(subtotalPaise, coupon)

  override fun calculateQuote(
    lines: List<PricedLineInput>,
    gstBps: Int,
    deliveryFeePaise: Long,
    coupon: CouponCalculationSpec?,
  ): PriceBreakdownDto = delegate.calculateQuote(lines, gstBps, deliveryFeePaise, coupon)

  override fun normalizeCouponCode(raw: String): String = delegate.normalizeCouponCode(raw)

  override fun canCustomerCancel(status: OrderStatus): Boolean = delegate.canCustomerCancel(status)

  override fun canOwnerCancel(status: OrderStatus): Boolean = delegate.canOwnerCancel(status)

  override fun canCancelOrder(status: OrderStatus, actorRole: UserRole): Boolean =
    delegate.canCancelOrder(status, actorRole)

  override fun canTransitionOrder(
    from: OrderStatus,
    to: OrderStatus,
    fulfillmentType: FulfillmentType,
    actorRole: UserRole?,
  ): Boolean = delegate.canTransitionOrder(from, to, fulfillmentType, actorRole)

  override fun nextKdsStatus(status: OrderStatus, fulfillmentType: FulfillmentType): OrderStatus? =
    delegate.nextKdsStatus(status, fulfillmentType)

  override fun canTransitionPayment(from: PaymentStatus, to: PaymentStatus): Boolean =
    delegate.canTransitionPayment(from, to)

  override fun canTransitionPaymentAttempt(
    from: PaymentAttemptStatus,
    to: PaymentAttemptStatus,
  ): Boolean = delegate.canTransitionPaymentAttempt(from, to)

  override fun planCartSync(request: CartSyncPlanRequest): CartSyncAction =
    delegate.planCartSync(request)

  override fun fallbackOnReplaceHttpError(statusCode: Int): CartSyncAction? =
    delegate.fallbackOnReplaceHttpError(statusCode)

  override fun validateStorageObjectKey(rawKey: String): String =
    delegate.validateStorageObjectKey(rawKey)

  override fun createCausalTracker(maxTrackedEventIds: Int): DomainCausalTracker =
    delegate.createCausalTracker(maxTrackedEventIds)
}
