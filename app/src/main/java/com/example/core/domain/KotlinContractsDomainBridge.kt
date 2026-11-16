package com.example.core.domain

import com.pizzora.contracts.CausalDecision
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.OrderCausalTracker
import com.pizzora.contracts.OrderStateMachine
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentStateMachine
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PriceBreakdownDto
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.PricingEngine
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.UserRole
import java.util.Locale

private val SAFE_STORAGE_OBJECT_KEY_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9/._-]{0,254}$")
private val COUPON_CODE_REGEX = Regex("^[A-Z0-9_-]{3,32}$")

class KotlinContractsDomainBridge : DomainBridge {
  override val isNativeRustActive: Boolean = false
  override val bridgeImplementationName: String = "kotlin-shared-contracts"

  override fun domainVersion(): String = "kotlin-contracts-fallback"

  override fun bpsOf(amountPaise: Long, bps: Int): Long = Money.bpsOf(amountPaise, bps)

  override fun formatInr(paise: Long): String = Money.formatInr(paise)

  override fun calculateLineTotalPaise(unitPricePaise: Long, quantity: Int): Long =
    PricingEngine.calculateLineTotalPaise(unitPricePaise, quantity)

  override fun calculateDiscountPaise(subtotalPaise: Long, coupon: CouponCalculationSpec?): Long =
    PricingEngine.calculateDiscountPaise(subtotalPaise, coupon)

  override fun calculateQuote(
    lines: List<PricedLineInput>,
    gstBps: Int,
    deliveryFeePaise: Long,
    coupon: CouponCalculationSpec?,
  ): PriceBreakdownDto = PricingEngine.calculateQuote(lines, gstBps, deliveryFeePaise, coupon)

  override fun normalizeCouponCode(raw: String): String {
    val normalized = raw.trim().uppercase(Locale.US)
    require(COUPON_CODE_REGEX.matches(normalized)) {
      "Coupon code must be 3-32 uppercase alphanumeric, '_' or '-' characters."
    }
    return normalized
  }

  override fun canCustomerCancel(status: OrderStatus): Boolean =
    OrderStateMachine.canCustomerCancel(status)

  override fun canOwnerCancel(status: OrderStatus): Boolean =
    OrderStateMachine.canOwnerCancel(status)

  override fun canCancelOrder(status: OrderStatus, actorRole: UserRole): Boolean =
    OrderStateMachine.canCancel(status, actorRole)

  override fun canTransitionOrder(
    from: OrderStatus,
    to: OrderStatus,
    fulfillmentType: FulfillmentType,
    actorRole: UserRole?,
  ): Boolean = OrderStateMachine.canTransition(from, to, fulfillmentType, actorRole)

  override fun nextKdsStatus(status: OrderStatus, fulfillmentType: FulfillmentType): OrderStatus? =
    OrderStateMachine.nextKdsStatus(status, fulfillmentType)

  override fun canTransitionPayment(from: PaymentStatus, to: PaymentStatus): Boolean =
    PaymentStateMachine.canTransitionPayment(from, to)

  override fun canTransitionPaymentAttempt(
    from: PaymentAttemptStatus,
    to: PaymentAttemptStatus,
  ): Boolean = PaymentStateMachine.canTransitionAttempt(from, to)

  override fun planCartSync(request: CartSyncPlanRequest): CartSyncAction {
    require(request.localItemCount >= 0) { "localItemCount must be non-negative" }
    val active = request.activeSession
    if (active == null ||
      active.epoch != request.capturedSession.epoch ||
      active.userId != request.capturedSession.userId
    ) {
      return CartSyncAction.DISCARD_STALE_SESSION
    }

    if (request.trigger == CartSyncTrigger.PASSIVE_BOOTSTRAP) {
      return CartSyncAction.FETCH_SERVER_CART
    }

    if (request.localItemCount == 0) {
      val explicitEmptyAllowed = request.allowEmptyReplace &&
        request.persistToServer &&
        request.trigger == CartSyncTrigger.EXPLICIT_CLEAR_OR_REMOVE
      return when {
        explicitEmptyAllowed -> CartSyncAction.REPLACE_SERVER_CART_EMPTY
        request.persistToServer -> CartSyncAction.FETCH_SERVER_CART
        else -> CartSyncAction.NO_OP_EMPTY_LOCAL
      }
    }

    return if (request.persistToServer) {
      CartSyncAction.REPLACE_SERVER_CART_WITH_VALIDATE_FALLBACK
    } else {
      CartSyncAction.VALIDATE_CART_ONLY
    }
  }

  override fun fallbackOnReplaceHttpError(statusCode: Int): CartSyncAction? =
    if (statusCode == 422) CartSyncAction.VALIDATE_CART_ONLY else null

  override fun validateStorageObjectKey(rawKey: String): String {
    val trimmed = rawKey.trim()
    require(trimmed.isNotEmpty() && rawKey == trimmed) {
      "Object key cannot be empty or contain surrounding whitespace."
    }
    val lower = rawKey.lowercase(Locale.US)
    require(
      !rawKey.startsWith('/') &&
        !rawKey.contains('\\') &&
        !rawKey.contains('\u0000') &&
        !rawKey.contains("//") &&
        !rawKey.contains("..") &&
        !lower.contains("%2e") &&
        !lower.contains("%2f") &&
        !lower.contains("%5c") &&
        SAFE_STORAGE_OBJECT_KEY_REGEX.matches(rawKey),
    ) {
      "Object key contains prohibited path traversal or unsafe characters."
    }
    return rawKey
  }

  override fun createCausalTracker(maxTrackedEventIds: Int): DomainCausalTracker {
    val delegate = OrderCausalTracker(maxTrackedEventIds = maxTrackedEventIds.coerceAtLeast(1))
    return object : DomainCausalTracker {
      override val lastSeenStreamSeq: Long
        get() = delegate.lastSeenStreamSeq

      override fun advanceCursor(serverHighWaterSeq: Long) {
        delegate.advanceCursor(serverHighWaterSeq)
      }

      override fun recordSnapshot(orderId: String, version: Long) {
        delegate.recordSnapshot(orderId, version)
      }

      override fun localVersionFor(orderId: String): Long? = delegate.localVersionFor(orderId)

      override fun evaluate(event: RealtimeEventDto): CausalDecision = delegate.evaluate(event)

      override fun clear() {
        delegate.clear()
      }
    }
  }
}
