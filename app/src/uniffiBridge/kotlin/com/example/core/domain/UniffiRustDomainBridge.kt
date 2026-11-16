package com.example.core.domain

import com.pizzora.contracts.CausalDecision
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PriceBreakdownDto
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.UserRole
import uniffi.pizzora_uniffi.FfiCartSyncAction
import uniffi.pizzora_uniffi.FfiCartSyncPlanRequest
import uniffi.pizzora_uniffi.FfiCartSyncTrigger
import uniffi.pizzora_uniffi.FfiCausalDecision
import uniffi.pizzora_uniffi.FfiCouponCalculationSpec
import uniffi.pizzora_uniffi.FfiDiscountType
import uniffi.pizzora_uniffi.FfiFulfillmentType
import uniffi.pizzora_uniffi.FfiOrderCausalTracker
import uniffi.pizzora_uniffi.FfiOrderStatus
import uniffi.pizzora_uniffi.FfiPaymentAttemptStatus
import uniffi.pizzora_uniffi.FfiPaymentStatus
import uniffi.pizzora_uniffi.FfiPricedLineInput
import uniffi.pizzora_uniffi.FfiRealtimeEventInput
import uniffi.pizzora_uniffi.FfiSessionIdentity
import uniffi.pizzora_uniffi.FfiUserRole
import uniffi.pizzora_uniffi.cartFallbackOnReplaceHttpError
import uniffi.pizzora_uniffi.cartPlanSync
import uniffi.pizzora_uniffi.couponNormalizeCode
import uniffi.pizzora_uniffi.moneyBpsOf
import uniffi.pizzora_uniffi.moneyFormatInr
import uniffi.pizzora_uniffi.orderCanCancel
import uniffi.pizzora_uniffi.orderCanCustomerCancel
import uniffi.pizzora_uniffi.orderCanOwnerCancel
import uniffi.pizzora_uniffi.orderCanTransition
import uniffi.pizzora_uniffi.orderNextKdsStatus
import uniffi.pizzora_uniffi.paymentCanTransitionAttempt
import uniffi.pizzora_uniffi.paymentCanTransitionPayment
import uniffi.pizzora_uniffi.pizzoraCoreVersion
import uniffi.pizzora_uniffi.pricingCalculateDiscountPaise
import uniffi.pizzora_uniffi.pricingCalculateLineTotalPaise
import uniffi.pizzora_uniffi.pricingCalculateQuote
import uniffi.pizzora_uniffi.storageValidateObjectKey

internal object PlatformRustDomainBridgeProvider {
  fun createOrNull(): DomainBridge? =
    try {
      UniffiRustDomainBridge().also { it.domainVersion() }
    } catch (_: Throwable) {
      null
    }
}

class UniffiRustDomainBridge : DomainBridge {
  override val isNativeRustActive: Boolean = true
  override val bridgeImplementationName: String = "uniffi-pizzora-core"

  override fun domainVersion(): String = pizzoraCoreVersion()

  override fun bpsOf(amountPaise: Long, bps: Int): Long =
    mapDomainException { moneyBpsOf(amountPaise, bps) }

  override fun formatInr(paise: Long): String = moneyFormatInr(paise)

  override fun calculateLineTotalPaise(unitPricePaise: Long, quantity: Int): Long =
    mapDomainException { pricingCalculateLineTotalPaise(unitPricePaise, quantity) }

  override fun calculateDiscountPaise(subtotalPaise: Long, coupon: CouponCalculationSpec?): Long =
    mapDomainException {
      pricingCalculateDiscountPaise(subtotalPaise, coupon?.toFfi())
    }

  override fun calculateQuote(
    lines: List<PricedLineInput>,
    gstBps: Int,
    deliveryFeePaise: Long,
    coupon: CouponCalculationSpec?,
  ): PriceBreakdownDto =
    mapDomainException {
      val ffi = pricingCalculateQuote(
        lines = lines.map {
          FfiPricedLineInput(
            unitPricePaise = it.unitPricePaise,
            quantity = it.quantity,
            isTaxable = it.isTaxable,
          )
        },
        gstBps = gstBps,
        deliveryFeePaise = deliveryFeePaise,
        coupon = coupon?.toFfi(),
      )
      PriceBreakdownDto(
        subtotalPaise = ffi.subtotalPaise,
        taxableSubtotalPaise = ffi.taxableSubtotalPaise,
        discountPaise = ffi.discountPaise,
        taxableAmountPaise = ffi.taxableAmountPaise,
        deliveryFeePaise = ffi.deliveryFeePaise,
        gstBps = ffi.gstBps,
        taxPaise = ffi.taxPaise,
        cgstPaise = ffi.cgstPaise,
        sgstPaise = ffi.sgstPaise,
        totalPaise = ffi.totalPaise,
      )
    }

  override fun normalizeCouponCode(raw: String): String =
    mapDomainException { couponNormalizeCode(raw) }

  override fun canCustomerCancel(status: OrderStatus): Boolean =
    orderCanCustomerCancel(status.toFfi())

  override fun canOwnerCancel(status: OrderStatus): Boolean =
    orderCanOwnerCancel(status.toFfi())

  override fun canCancelOrder(status: OrderStatus, actorRole: UserRole): Boolean =
    orderCanCancel(status.toFfi(), actorRole.toFfi())

  override fun canTransitionOrder(
    from: OrderStatus,
    to: OrderStatus,
    fulfillmentType: FulfillmentType,
    actorRole: UserRole?,
  ): Boolean = orderCanTransition(
    from.toFfi(),
    to.toFfi(),
    fulfillmentType.toFfi(),
    actorRole?.toFfi(),
  )

  override fun nextKdsStatus(status: OrderStatus, fulfillmentType: FulfillmentType): OrderStatus? =
    orderNextKdsStatus(status.toFfi(), fulfillmentType.toFfi())?.toContract()

  override fun canTransitionPayment(from: PaymentStatus, to: PaymentStatus): Boolean =
    paymentCanTransitionPayment(from.toFfi(), to.toFfi())

  override fun canTransitionPaymentAttempt(
    from: PaymentAttemptStatus,
    to: PaymentAttemptStatus,
  ): Boolean = paymentCanTransitionAttempt(from.toFfi(), to.toFfi())

  override fun planCartSync(request: CartSyncPlanRequest): CartSyncAction =
    mapDomainException {
      cartPlanSync(
        FfiCartSyncPlanRequest(
          activeSession = request.activeSession?.let {
            FfiSessionIdentity(epoch = it.epoch, userId = it.userId)
          },
          capturedSession = FfiSessionIdentity(
            epoch = request.capturedSession.epoch,
            userId = request.capturedSession.userId,
          ),
          trigger = request.trigger.toFfi(),
          localItemCount = request.localItemCount,
          persistToServer = request.persistToServer,
          allowEmptyReplace = request.allowEmptyReplace,
        ),
      ).toDomain()
    }

  override fun fallbackOnReplaceHttpError(statusCode: Int): CartSyncAction? =
    cartFallbackOnReplaceHttpError(statusCode)?.toDomain()

  override fun validateStorageObjectKey(rawKey: String): String =
    mapDomainException { storageValidateObjectKey(rawKey) }

  override fun createCausalTracker(maxTrackedEventIds: Int): DomainCausalTracker {
    val nativeTracker = FfiOrderCausalTracker(maxTrackedEventIds.coerceAtLeast(1))
    return object : DomainCausalTracker {
      override val lastSeenStreamSeq: Long
        get() = nativeTracker.lastSeenStreamSeq()

      override fun advanceCursor(serverHighWaterSeq: Long) {
        nativeTracker.advanceCursor(serverHighWaterSeq)
      }

      override fun recordSnapshot(orderId: String, version: Long) {
        nativeTracker.recordSnapshot(orderId, version)
      }

      override fun localVersionFor(orderId: String): Long? =
        nativeTracker.localVersionFor(orderId)

      override fun evaluate(event: RealtimeEventDto): CausalDecision {
        val ffiDecision = nativeTracker.evaluate(
          FfiRealtimeEventInput(
            eventId = event.eventId,
            streamSeq = event.streamSeq,
            eventType = event.eventType,
            aggregateType = event.aggregateType,
            aggregateId = event.aggregateId,
            aggregateVersion = event.aggregateVersion,
            orderId = event.stringField("order_id"),
          ),
        )
        return when (ffiDecision) {
          FfiCausalDecision.IgnoreDuplicate -> CausalDecision.IgnoreDuplicate
          FfiCausalDecision.IgnoreStale -> CausalDecision.IgnoreStale
          is FfiCausalDecision.ApplyOrderEvent -> CausalDecision.ApplyOrderEvent(event)
          is FfiCausalDecision.TriggerResync -> CausalDecision.TriggerResync(
            orderId = ffiDecision.orderId,
            event = event,
          )
          is FfiCausalDecision.ApplyNonOrderEvent -> CausalDecision.ApplyNonOrderEvent(event)
        }
      }

      override fun clear() {
        nativeTracker.clear()
      }
    }
  }
}

private inline fun <T> mapDomainException(block: () -> T): T =
  try {
    block()
  } catch (e: RuntimeException) {
    throw e
  } catch (e: Exception) {
    throw IllegalArgumentException(e.message ?: "Domain validation error", e)
  }

private fun CouponCalculationSpec.toFfi(): FfiCouponCalculationSpec =
  FfiCouponCalculationSpec(
    discountType = when (discountType) {
      DiscountType.PERCENT_BPS -> FfiDiscountType.PERCENT_BPS
      DiscountType.FLAT_PAISE -> FfiDiscountType.FLAT_PAISE
    },
    discountValue = discountValue,
    maxDiscountPaise = maxDiscountPaise,
    minOrderPaise = minOrderPaise,
    isActive = isActive,
  )

private fun UserRole.toFfi(): FfiUserRole = when (this) {
  UserRole.CUSTOMER -> FfiUserRole.CUSTOMER
  UserRole.KITCHEN_STAFF -> FfiUserRole.KITCHEN_STAFF
  UserRole.OWNER -> FfiUserRole.OWNER
}

private fun FulfillmentType.toFfi(): FfiFulfillmentType = when (this) {
  FulfillmentType.DELIVERY -> FfiFulfillmentType.DELIVERY
  FulfillmentType.PICKUP -> FfiFulfillmentType.PICKUP
}

private fun OrderStatus.toFfi(): FfiOrderStatus = when (this) {
  OrderStatus.PENDING_PAYMENT -> FfiOrderStatus.PENDING_PAYMENT
  OrderStatus.PLACED -> FfiOrderStatus.PLACED
  OrderStatus.ACCEPTED -> FfiOrderStatus.ACCEPTED
  OrderStatus.PREPARING -> FfiOrderStatus.PREPARING
  OrderStatus.READY -> FfiOrderStatus.READY
  OrderStatus.OUT_FOR_DELIVERY -> FfiOrderStatus.OUT_FOR_DELIVERY
  OrderStatus.COMPLETED -> FfiOrderStatus.COMPLETED
  OrderStatus.CANCELLED -> FfiOrderStatus.CANCELLED
  OrderStatus.EXPIRED -> FfiOrderStatus.EXPIRED
}

private fun FfiOrderStatus.toContract(): OrderStatus = when (this) {
  FfiOrderStatus.PENDING_PAYMENT -> OrderStatus.PENDING_PAYMENT
  FfiOrderStatus.PLACED -> OrderStatus.PLACED
  FfiOrderStatus.ACCEPTED -> OrderStatus.ACCEPTED
  FfiOrderStatus.PREPARING -> OrderStatus.PREPARING
  FfiOrderStatus.READY -> OrderStatus.READY
  FfiOrderStatus.OUT_FOR_DELIVERY -> OrderStatus.OUT_FOR_DELIVERY
  FfiOrderStatus.COMPLETED -> OrderStatus.COMPLETED
  FfiOrderStatus.CANCELLED -> OrderStatus.CANCELLED
  FfiOrderStatus.EXPIRED -> OrderStatus.EXPIRED
}

private fun PaymentStatus.toFfi(): FfiPaymentStatus = when (this) {
  PaymentStatus.UNPAID -> FfiPaymentStatus.UNPAID
  PaymentStatus.COD_PENDING -> FfiPaymentStatus.COD_PENDING
  PaymentStatus.COD_COLLECTED -> FfiPaymentStatus.COD_COLLECTED
  PaymentStatus.CAPTURED -> FfiPaymentStatus.CAPTURED
  PaymentStatus.EXPIRED -> FfiPaymentStatus.EXPIRED
  PaymentStatus.CANCELLED -> FfiPaymentStatus.CANCELLED
  PaymentStatus.REFUND_PENDING -> FfiPaymentStatus.REFUND_PENDING
  PaymentStatus.PARTIALLY_REFUNDED -> FfiPaymentStatus.PARTIALLY_REFUNDED
  PaymentStatus.REFUNDED -> FfiPaymentStatus.REFUNDED
}

private fun PaymentAttemptStatus.toFfi(): FfiPaymentAttemptStatus = when (this) {
  PaymentAttemptStatus.INITIATING -> FfiPaymentAttemptStatus.INITIATING
  PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT -> FfiPaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT
  PaymentAttemptStatus.AUTHORIZED -> FfiPaymentAttemptStatus.AUTHORIZED
  PaymentAttemptStatus.CAPTURED -> FfiPaymentAttemptStatus.CAPTURED
  PaymentAttemptStatus.FAILED -> FfiPaymentAttemptStatus.FAILED
  PaymentAttemptStatus.EXPIRED -> FfiPaymentAttemptStatus.EXPIRED
  PaymentAttemptStatus.CANCELLED -> FfiPaymentAttemptStatus.CANCELLED
}

private fun CartSyncTrigger.toFfi(): FfiCartSyncTrigger = when (this) {
  CartSyncTrigger.PASSIVE_BOOTSTRAP -> FfiCartSyncTrigger.PASSIVE_BOOTSTRAP
  CartSyncTrigger.EXPLICIT_MUTATION -> FfiCartSyncTrigger.EXPLICIT_MUTATION
  CartSyncTrigger.EXPLICIT_CLEAR_OR_REMOVE -> FfiCartSyncTrigger.EXPLICIT_CLEAR_OR_REMOVE
  CartSyncTrigger.PERIODIC_VALIDATE -> FfiCartSyncTrigger.PERIODIC_VALIDATE
}

private fun FfiCartSyncAction.toDomain(): CartSyncAction = when (this) {
  FfiCartSyncAction.DISCARD_STALE_SESSION -> CartSyncAction.DISCARD_STALE_SESSION
  FfiCartSyncAction.FETCH_SERVER_CART -> CartSyncAction.FETCH_SERVER_CART
  FfiCartSyncAction.REPLACE_SERVER_CART_EMPTY -> CartSyncAction.REPLACE_SERVER_CART_EMPTY
  FfiCartSyncAction.REPLACE_SERVER_CART_WITH_VALIDATE_FALLBACK ->
    CartSyncAction.REPLACE_SERVER_CART_WITH_VALIDATE_FALLBACK
  FfiCartSyncAction.VALIDATE_CART_ONLY -> CartSyncAction.VALIDATE_CART_ONLY
  FfiCartSyncAction.NO_OP_EMPTY_LOCAL -> CartSyncAction.NO_OP_EMPTY_LOCAL
}
