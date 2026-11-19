package com.example.core.domain

import com.pizzora.contracts.CartItemCustomizationSummaryDto
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartToppingViewDto
import com.pizzora.contracts.CartValidationIssueCode
import com.pizzora.contracts.CartValidationIssueDto
import com.pizzora.contracts.CausalDecision
import com.pizzora.contracts.CouponCalculationSpec
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PizzaBuilderEvaluation
import com.pizzora.contracts.PizzaPriceBreakdownDto
import com.pizzora.contracts.PriceBreakdownDto
import com.pizzora.contracts.PricedLineInput
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.RealtimeEventDto
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingPlacement
import com.pizzora.contracts.UserRole
import uniffi.pizzora_uniffi.FfiCartItemInput
import uniffi.pizzora_uniffi.FfiCartSyncAction
import uniffi.pizzora_uniffi.FfiCartSyncPlanRequest
import uniffi.pizzora_uniffi.FfiCartSyncTrigger
import uniffi.pizzora_uniffi.FfiCartToppingInput
import uniffi.pizzora_uniffi.FfiCartValidationIssueCode
import uniffi.pizzora_uniffi.FfiCatalogCheeseRef
import uniffi.pizzora_uniffi.FfiCatalogCrustRef
import uniffi.pizzora_uniffi.FfiCatalogLookupInput
import uniffi.pizzora_uniffi.FfiCatalogProductRef
import uniffi.pizzora_uniffi.FfiCatalogSauceRef
import uniffi.pizzora_uniffi.FfiCatalogSizeRuleRef
import uniffi.pizzora_uniffi.FfiCatalogToppingRef
import uniffi.pizzora_uniffi.FfiCatalogVariantRef
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
import uniffi.pizzora_uniffi.FfiSizeCode
import uniffi.pizzora_uniffi.FfiToppingPlacement
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
import uniffi.pizzora_uniffi.pizzaBuilderComputeSignature
import uniffi.pizzora_uniffi.pizzaBuilderEvaluate
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

  override fun computePizzaConfigurationSignature(
    input: CartItemInputDto,
    menu: PublicMenuResponse?,
  ): String =
    mapDomainException {
      pizzaBuilderComputeSignature(input.toFfi(), menu?.toFfiLookup())
    }

  override fun evaluatePizzaCustomization(
    input: CartItemInputDto,
    menu: PublicMenuResponse,
  ): PizzaBuilderEvaluation =
    mapDomainException {
      val ffi = pizzaBuilderEvaluate(input.toFfi(), menu.toFfiLookup())
      PizzaBuilderEvaluation(
        isValid = ffi.isValid,
        issues = ffi.issues.map {
          CartValidationIssueDto(
            code = it.code.toContract(),
            message = it.message,
          )
        },
        priceBreakdown = PizzaPriceBreakdownDto(
          basePricePaise = ffi.priceBreakdown.basePricePaise,
          halfAndHalfSurchargePaise = ffi.priceBreakdown.halfAndHalfSurchargePaise,
          crustPricePaise = ffi.priceBreakdown.crustPricePaise,
          cheesePricePaise = ffi.priceBreakdown.cheesePricePaise,
          extraCheesePricePaise = ffi.priceBreakdown.extraCheesePricePaise,
          saucePricePaise = ffi.priceBreakdown.saucePricePaise,
          extraSaucePricePaise = ffi.priceBreakdown.extraSaucePricePaise,
          toppingsPricePaise = ffi.priceBreakdown.toppingsPricePaise,
          unitPricePaise = ffi.priceBreakdown.unitPricePaise,
          lineTotalPaise = ffi.priceBreakdown.lineTotalPaise,
        ),
        unitPricePaise = ffi.unitPricePaise,
        lineTotalPaise = ffi.lineTotalPaise,
        toppings = ffi.toppings.map {
          CartToppingViewDto(
            toppingId = it.toppingId,
            name = it.name,
            isVeg = it.isVeg,
            extraPricePaise = it.extraPricePaise,
            isAvailable = it.isAvailable,
            placement = it.placement.toContract(),
            quantity = it.quantity,
            isIncluded = it.isIncluded,
            unitPricePaise = it.unitPricePaise,
            totalPricePaise = it.totalPricePaise,
          )
        },
        customization = CartItemCustomizationSummaryDto(
          isHalfAndHalf = ffi.customization.isHalfAndHalf,
          rightProductId = ffi.customization.rightProductId,
          rightProductName = ffi.customization.rightProductName,
          rightVariantId = ffi.customization.rightVariantId,
          cheeseId = ffi.customization.cheeseId,
          cheeseName = ffi.customization.cheeseName,
          extraCheese = ffi.customization.extraCheese,
          sauceId = ffi.customization.sauceId,
          sauceName = ffi.customization.sauceName,
          rightSauceId = ffi.customization.rightSauceId,
          rightSauceName = ffi.customization.rightSauceName,
          extraSauce = ffi.customization.extraSauce,
          removedIncludedToppings = ffi.customization.removedIncludedToppings,
          configurationSignature = ffi.customization.configurationSignature,
        ),
        configurationSignature = ffi.configurationSignature,
        effectiveSizeCode = ffi.effectiveSizeCode?.toContract(),
        effectiveCheeseId = ffi.effectiveCheeseId,
        effectiveSauceId = ffi.effectiveSauceId,
        effectiveRightSauceId = ffi.effectiveRightSauceId,
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

private fun SizeCode.toFfi(): FfiSizeCode = when (this) {
  SizeCode.REGULAR -> FfiSizeCode.REGULAR
  SizeCode.MEDIUM -> FfiSizeCode.MEDIUM
  SizeCode.LARGE -> FfiSizeCode.LARGE
  SizeCode.EXTRA_LARGE -> FfiSizeCode.EXTRA_LARGE
}

private fun FfiSizeCode.toContract(): SizeCode = when (this) {
  FfiSizeCode.REGULAR -> SizeCode.REGULAR
  FfiSizeCode.MEDIUM -> SizeCode.MEDIUM
  FfiSizeCode.LARGE -> SizeCode.LARGE
  FfiSizeCode.EXTRA_LARGE -> SizeCode.EXTRA_LARGE
}

private fun ToppingPlacement.toFfi(): FfiToppingPlacement = when (this) {
  ToppingPlacement.WHOLE -> FfiToppingPlacement.WHOLE
  ToppingPlacement.LEFT -> FfiToppingPlacement.LEFT
  ToppingPlacement.RIGHT -> FfiToppingPlacement.RIGHT
}

private fun FfiToppingPlacement.toContract(): ToppingPlacement = when (this) {
  FfiToppingPlacement.WHOLE -> ToppingPlacement.WHOLE
  FfiToppingPlacement.LEFT -> ToppingPlacement.LEFT
  FfiToppingPlacement.RIGHT -> ToppingPlacement.RIGHT
}

private fun FfiCartValidationIssueCode.toContract(): CartValidationIssueCode = when (this) {
  FfiCartValidationIssueCode.PRODUCT_UNAVAILABLE -> CartValidationIssueCode.PRODUCT_UNAVAILABLE
  FfiCartValidationIssueCode.VARIANT_UNAVAILABLE -> CartValidationIssueCode.VARIANT_UNAVAILABLE
  FfiCartValidationIssueCode.CRUST_UNAVAILABLE -> CartValidationIssueCode.CRUST_UNAVAILABLE
  FfiCartValidationIssueCode.TOPPING_UNAVAILABLE -> CartValidationIssueCode.TOPPING_UNAVAILABLE
  FfiCartValidationIssueCode.INVALID_QUANTITY -> CartValidationIssueCode.INVALID_QUANTITY
  FfiCartValidationIssueCode.VARIANT_PRODUCT_MISMATCH ->
    CartValidationIssueCode.VARIANT_PRODUCT_MISMATCH
  FfiCartValidationIssueCode.SIZE_UNAVAILABLE -> CartValidationIssueCode.SIZE_UNAVAILABLE
  FfiCartValidationIssueCode.CHEESE_UNAVAILABLE -> CartValidationIssueCode.CHEESE_UNAVAILABLE
  FfiCartValidationIssueCode.SAUCE_UNAVAILABLE -> CartValidationIssueCode.SAUCE_UNAVAILABLE
  FfiCartValidationIssueCode.EXTRA_CHEESE_UNAVAILABLE ->
    CartValidationIssueCode.EXTRA_CHEESE_UNAVAILABLE
  FfiCartValidationIssueCode.EXTRA_SAUCE_UNAVAILABLE ->
    CartValidationIssueCode.EXTRA_SAUCE_UNAVAILABLE
  FfiCartValidationIssueCode.HALF_AND_HALF_UNSUPPORTED ->
    CartValidationIssueCode.HALF_AND_HALF_UNSUPPORTED
  FfiCartValidationIssueCode.RIGHT_HALF_INVALID -> CartValidationIssueCode.RIGHT_HALF_INVALID
  FfiCartValidationIssueCode.TOPPING_PLACEMENT_INVALID ->
    CartValidationIssueCode.TOPPING_PLACEMENT_INVALID
  FfiCartValidationIssueCode.TOPPING_LIMIT_EXCEEDED ->
    CartValidationIssueCode.TOPPING_LIMIT_EXCEEDED
  FfiCartValidationIssueCode.EXTRA_TOPPING_LIMIT_EXCEEDED ->
    CartValidationIssueCode.EXTRA_TOPPING_LIMIT_EXCEEDED
  FfiCartValidationIssueCode.DUPLICATE_SELECTION -> CartValidationIssueCode.DUPLICATE_SELECTION
  FfiCartValidationIssueCode.INCLUDED_TOPPING_INVALID ->
    CartValidationIssueCode.INCLUDED_TOPPING_INVALID
}

private fun CartItemInputDto.toFfi(): FfiCartItemInput =
  FfiCartItemInput(
    id = id,
    productId = productId,
    variantId = variantId,
    crustId = crustId,
    toppingIds = toppingIds,
    quantity = quantity,
    cheeseId = cheeseId,
    extraCheese = extraCheese,
    sauceId = sauceId,
    extraSauce = extraSauce,
    isHalfAndHalf = isHalfAndHalf,
    rightProductId = rightProductId,
    rightVariantId = rightVariantId,
    rightSauceId = rightSauceId,
    removedIncludedToppingIds = removedIncludedToppingIds,
    toppingSelections = toppingSelections.map {
      FfiCartToppingInput(
        toppingId = it.toppingId,
        placement = it.placement.toFfi(),
        quantity = it.quantity,
      )
    },
  )

private fun PublicMenuResponse.toFfiLookup(): FfiCatalogLookupInput {
  val activeCategoryIds = categories.filter { it.isActive }.map { it.id }.toSet()
  return FfiCatalogLookupInput(
    products = products.map { p ->
      FfiCatalogProductRef(
        id = p.id,
        name = p.name,
        isVeg = p.isVeg,
        isTaxable = p.isTaxable,
        isEffectiveActive = p.isActive && (activeCategoryIds.isEmpty() || p.categoryId in activeCategoryIds),
        imageUrl = p.images.firstOrNull()?.publicUrl,
        isCustomizable = p.isCustomizable,
        supportsHalfAndHalf = p.supportsHalfAndHalf,
        defaultCheeseId = p.defaultCheeseId,
        defaultSauceId = p.defaultSauceId,
        includedToppingIds = p.includedToppingIds,
        maxToppingsOverride = p.maxToppingsOverride,
      )
    },
    variants = products.flatMap { p ->
      p.variants.map { v ->
        FfiCatalogVariantRef(
          id = v.id,
          productId = v.productId,
          sizeCode = v.sizeCode.toFfi(),
          pricePaise = v.pricePaise,
          isActive = v.isActive,
        )
      }
    },
    crusts = crusts.map { c ->
      FfiCatalogCrustRef(
        id = c.id,
        name = c.name,
        extraPricePaise = c.extraPricePaise,
        isActive = c.isActive,
        supportsHalfAndHalf = c.supportsHalfAndHalf,
      )
    },
    toppings = toppings.map { t ->
      FfiCatalogToppingRef(
        id = t.id,
        name = t.name,
        isVeg = t.isVeg,
        extraPricePaise = t.extraPricePaise,
        isActive = t.isActive,
        maxQuantity = t.maxQuantity,
        supportsHalfAndHalf = t.supportsHalfAndHalf,
      )
    },
    sizeRules = sizeRules.map { sr ->
      FfiCatalogSizeRuleRef(
        sizeCode = sr.sizeCode.toFfi(),
        displayName = sr.displayName,
        maxToppings = sr.maxToppings,
        maxExtraToppings = sr.maxExtraToppings,
        supportsHalfAndHalf = sr.supportsHalfAndHalf,
        ingredientMultiplierBps = sr.ingredientMultiplierBps,
        isActive = sr.isActive,
      )
    },
    cheeses = cheeses.map { ch ->
      FfiCatalogCheeseRef(
        id = ch.id,
        name = ch.name,
        extraPricePaise = ch.extraPricePaise,
        extraCheesePricePaise = ch.extraCheesePricePaise,
        supportsExtra = ch.supportsExtra,
        isVegan = ch.isVegan,
        isActive = ch.isActive,
      )
    },
    sauces = sauces.map { sc ->
      FfiCatalogSauceRef(
        id = sc.id,
        name = sc.name,
        extraPricePaise = sc.extraPricePaise,
        extraSaucePricePaise = sc.extraSaucePricePaise,
        supportsExtra = sc.supportsExtra,
        isVegan = sc.isVegan,
        isActive = sc.isActive,
      )
    },
  )
}
