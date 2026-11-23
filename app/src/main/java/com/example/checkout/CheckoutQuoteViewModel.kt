package com.example.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.CartItemWithToppings
import com.example.core.network.PizzoraApiException
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CustomerCouponDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.Money
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuoteItemConfig(
  val id: String,
  val productId: String,
  val variantId: String,
  val crustId: String,
  val toppingIds: List<String>,
  val quantity: Int,
)

/**
 * Canonical pricing-equivalence key (Section 4.3). Payment method, schedule type,
 * slot id and clientUnitPricePaise are deliberately absent: /v1/pricing/quote
 * never reads them, so they must never invalidate a quote.
 */
data class QuoteRequestSignature(
  val itemsConfig: List<QuoteItemConfig>,
  val hasUnavailableItems: Boolean,
  val fulfillmentType: FulfillmentType,
  val deliveryContext: String,
  val normalizedCouponCode: String?,
)

data class CheckoutQuoteUiState(
  val fulfillmentType: FulfillmentType = FulfillmentType.DELIVERY,
  val pincodeInput: String = "",
  val checkedPincode: String? = null,
  val zoneCheckResult: DeliveryZoneCheckResponse? = null,
  val isCheckingZone: Boolean = false,
  val couponInput: String = "",
  val appliedCouponCode: String? = null,
  val quote: PricingQuoteResponse? = null,
  val availableCoupons: List<CustomerCouponDto> = emptyList(),
  val isLoadingQuote: Boolean = false,
  val isLoadingCoupons: Boolean = false,
  val isCouponSheetOpen: Boolean = false,
  val couponError: String? = null,
  val quoteError: String? = null,
  val couponSheetError: String? = null,
  val currentSignature: QuoteRequestSignature? = null,
  val quotedSignature: QuoteRequestSignature? = null,
  val inFlightSignature: QuoteRequestSignature? = null,
  val hasUnavailableItems: Boolean = false,
  val priceDriftNotice: String? = null,
) {
  val isQuoteFresh: Boolean
    get() = quote != null &&
      quotedSignature != null &&
      quotedSignature == currentSignature &&
      !isLoadingQuote &&
      quoteError == null &&
      !hasUnavailableItems

  val isQuoteStale: Boolean
    get() = quote != null && !isQuoteFresh
}

class CheckoutQuoteViewModel(
  private val pricingRepository: PricingRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CheckoutQuoteUiState())
  val uiState: StateFlow<CheckoutQuoteUiState> = _uiState.asStateFlow()

  private var latestCartItems: List<CartItemWithToppings> = emptyList()
  private var latestSubtotalPaise: Long = 0L
  private var quoteJob: Job? = null
  private var zoneCheckJob: Job? = null
  private var quoteRequestGeneration: Long = 0L

  fun onCartChanged(items: List<CartItemWithToppings>, subtotalPaise: Long) {
    val previousSignature = latestCartItems.toSignature()
    latestCartItems = items
    latestSubtotalPaise = subtotalPaise
    val newSignature = items.toSignature()

    _uiState.update { it.copy(hasUnavailableItems = items.any { entry -> !entry.item.isAvailable }) }

    if (items.isEmpty()) {
      cancelPendingQuoteWork()
      _uiState.update {
        it.copy(
          quote = null,
          quotedSignature = null,
          currentSignature = null,
          inFlightSignature = null,
          isLoadingQuote = false,
          isCheckingZone = false,
          couponError = null,
          quoteError = null,
          priceDriftNotice = null,
        )
      }
      return
    }

    if (previousSignature != newSignature || _uiState.value.quote == null) {
      requestQuote()
    } else {
      // Inputs are unchanged: keep freshness bookkeeping aligned without a new POST.
      _uiState.update { it.copy(currentSignature = newSignature) }
    }
  }

  fun onFulfillmentTypeChange(type: FulfillmentType) {
    if (_uiState.value.fulfillmentType == type) return
    cancelPendingQuoteWork()
    _uiState.update {
      it.copy(
        fulfillmentType = type,
        quoteError = null,
        isCheckingZone = false,
      )
    }
    if (latestCartItems.isNotEmpty()) {
      requestQuote()
    }
  }

  fun onPincodeInputChange(value: String) {
    val sanitized = value.filter { it.isDigit() }.take(6)
    _uiState.update {
      it.copy(
        pincodeInput = sanitized,
        zoneCheckResult = if (sanitized == it.zoneCheckResult?.pincode) it.zoneCheckResult else null,
        quoteError = null,
      )
    }
  }

  fun applyPincodeCheck() {
    val pin = _uiState.value.pincodeInput.trim().takeIf { it.isNotEmpty() }
    if (pin == null) {
      cancelPendingQuoteWork()
      _uiState.update {
        it.copy(
          checkedPincode = null,
          zoneCheckResult = null,
          quote = null,
          quotedSignature = null,
          currentSignature = null,
          inFlightSignature = null,
          quoteError = null,
          isCheckingZone = false,
          isLoadingQuote = false,
          priceDriftNotice = null,
        )
      }
      if (latestCartItems.isNotEmpty()) {
        requestQuote(overridePincode = null, clearPincodeOnZoneError = true)
      }
      return
    }
    if (!pin.matches(Regex("^[1-9][0-9]{5}$"))) {
      // Invalid pincode is authoritative: no previously quoted total may remain usable.
      cancelPendingQuoteWork()
      _uiState.update {
        it.copy(
          checkedPincode = null,
          zoneCheckResult = null,
          quote = null,
          quotedSignature = null,
          currentSignature = null,
          inFlightSignature = null,
          isCheckingZone = false,
          isLoadingQuote = false,
          quoteError = "Enter a valid 6-digit Indian pincode.",
          priceDriftNotice = null,
        )
      }
      return
    }

    // The checked pincode is quote-affecting: invalidate authority and cancel any
    // previous quote before the zone check starts, not after it completes.
    cancelPendingQuoteWork()
    val newSignature = computeSignature(overridePincode = pin)
    _uiState.update {
      it.copy(
        checkedPincode = pin,
        currentSignature = newSignature,
        inFlightSignature = null,
        isCheckingZone = true,
        isLoadingQuote = latestCartItems.isNotEmpty(),
        quoteError = null,
      )
    }
    val checkGen = quoteRequestGeneration
    zoneCheckJob = viewModelScope.launch {
      if (checkGen != quoteRequestGeneration || coroutineContext[Job]?.isActive != true) return@launch
      val zoneResult = pricingRepository.checkDeliveryZone(pin)
      zoneResult
        .onSuccess { check ->
          if (checkGen != quoteRequestGeneration || coroutineContext[Job]?.isActive != true) return@launch
          if (!check.isServiceable) {
            _uiState.update {
              it.copy(
                isCheckingZone = false,
                zoneCheckResult = check,
                checkedPincode = null,
                quote = null,
                quotedSignature = null,
                inFlightSignature = null,
                isLoadingQuote = false,
                quoteError = check.message,
                priceDriftNotice = null,
              )
            }
            return@launch
          }
          _uiState.update {
            it.copy(
              isCheckingZone = false,
              zoneCheckResult = check,
              checkedPincode = pin,
              quoteError = null,
            )
          }
          if (latestCartItems.isNotEmpty()) {
            requestQuote(overridePincode = pin, clearPincodeOnZoneError = true)
          } else {
            _uiState.update { it.copy(isLoadingQuote = false) }
          }
        }
        .onFailure { err ->
          if (checkGen != quoteRequestGeneration || coroutineContext[Job]?.isActive != true) return@launch
          val apiEx = err as? PizzoraApiException
          if (apiEx != null && (apiEx.code == "ZONE_UNSERVICEABLE" || apiEx.code == "INVALID_PINCODE")) {
            _uiState.update {
              it.copy(
                isCheckingZone = false,
                zoneCheckResult = null,
                checkedPincode = null,
                quote = null,
                quotedSignature = null,
                inFlightSignature = null,
                isLoadingQuote = false,
                quoteError = apiEx.message ?: "Delivery unavailable for selected pincode.",
                priceDriftNotice = null,
              )
            }
            return@launch
          }
          // Non-API fallback (offline or legacy quote-only MockEngine): defer to pricing quote.
          _uiState.update { it.copy(isCheckingZone = false, zoneCheckResult = null) }
          if (latestCartItems.isNotEmpty()) {
            requestQuote(overridePincode = pin, clearPincodeOnZoneError = true)
          } else {
            _uiState.update { it.copy(isLoadingQuote = false) }
          }
        }
    }
  }

  fun onCouponInputChange(value: String) {
    _uiState.update { it.copy(couponInput = value.uppercase(), couponError = null) }
  }

  fun openCouponPicker() {
    _uiState.update { it.copy(isCouponSheetOpen = true, isLoadingCoupons = true, couponSheetError = null) }
    viewModelScope.launch {
      val subtotalArg = latestSubtotalPaise.takeIf { it > 0L }
      pricingRepository.fetchAvailableCoupons(subtotalArg)
        .onSuccess { resp ->
          _uiState.update {
            it.copy(
              availableCoupons = resp.coupons,
              isLoadingCoupons = false,
              couponSheetError = null,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isLoadingCoupons = false,
              couponSheetError = err.message ?: "Unable to load coupons right now.",
            )
          }
        }
    }
  }

  fun dismissCouponPicker() {
    _uiState.update { it.copy(isCouponSheetOpen = false) }
  }

  fun applyCouponCode(rawCode: String) {
    val normalized = rawCode.trim().uppercase()
    if (normalized.isEmpty()) {
      _uiState.update { it.copy(couponError = "Please enter a coupon code.") }
      return
    }
    _uiState.update {
      it.copy(
        couponInput = normalized,
        isCouponSheetOpen = false,
        appliedCouponCode = normalized,
        couponError = null,
      )
    }
    if (latestCartItems.isNotEmpty()) {
      requestQuote(overrideCouponCode = normalized)
    }
  }

  fun removeCoupon() {
    _uiState.update {
      it.copy(
        appliedCouponCode = null,
        couponInput = "",
        couponError = null,
      )
    }
    if (latestCartItems.isNotEmpty()) {
      requestQuote(overrideCouponCode = null, forceClearCoupon = true)
    }
  }

  fun refreshQuote() {
    if (latestCartItems.isNotEmpty()) {
      // Normal dedup applies: an in-flight or already-fresh quote needs no retry.
      // After a failure quotedSignature is cleared, so Retry always dispatches.
      requestQuote()
    }
  }

  /**
   * Forces one fresh authoritative quote after an order-time 422, when the last quote
   * is still fresh and ordinary Retry dedup would otherwise suppress the POST.
   */
  fun forceRefreshQuote() {
    if (latestCartItems.isNotEmpty()) {
      requestQuote(forceRefresh = true)
    }
  }

  /**
   * Checkout address auto-sync. A different pincode triggers exactly one zone check
   * followed by one signature-deduplicated quote; an unchanged, fresh quote is reused.
   */
  fun syncCheckoutAddressPincode(pincode: String) {
    val pin = pincode.filter { it.isDigit() }.take(6).takeIf { it.isNotBlank() }
    if (pin == null) {
      cancelPendingQuoteWork()
      _uiState.update {
        it.copy(
          pincodeInput = "",
          checkedPincode = null,
          zoneCheckResult = null,
          isCheckingZone = false,
          quote = null,
          quotedSignature = null,
          currentSignature = null,
          inFlightSignature = null,
          quoteError = null,
          isLoadingQuote = false,
          priceDriftNotice = null,
        )
      }
      return
    }

    val currentState = _uiState.value
    val newSig = computeSignature(overridePincode = pin)

    if (currentState.inFlightSignature == newSig && currentState.isLoadingQuote) {
      _uiState.update { it.copy(pincodeInput = pin, checkedPincode = pin) }
      return
    }

    if (currentState.isQuoteFresh && currentState.quotedSignature == newSig) {
      _uiState.update { it.copy(pincodeInput = pin, checkedPincode = pin) }
      return
    }

    // Different pincode (or an invalidated quote): mark inputs current immediately so
    // the previously quoted total is stale while the new zone/quote cycle runs.
    cancelPendingQuoteWork()
    _uiState.update {
      it.copy(
        pincodeInput = pin,
        checkedPincode = pin,
        zoneCheckResult = if (it.zoneCheckResult?.pincode == pin) it.zoneCheckResult else null,
        currentSignature = newSig,
        inFlightSignature = null,
        isLoadingQuote = latestCartItems.isNotEmpty(),
        isCheckingZone = true,
        quoteError = null,
      )
    }

    val syncGen = quoteRequestGeneration
    zoneCheckJob = viewModelScope.launch {
      if (syncGen != quoteRequestGeneration || coroutineContext[Job]?.isActive != true) return@launch
      val zoneResult = pricingRepository.checkDeliveryZone(pin)
      zoneResult
        .onSuccess { check ->
          if (syncGen != quoteRequestGeneration || coroutineContext[Job]?.isActive != true) return@launch
          if (!check.isServiceable) {
            _uiState.update {
              it.copy(
                isCheckingZone = false,
                zoneCheckResult = check,
                checkedPincode = null,
                quote = null,
                quotedSignature = null,
                inFlightSignature = null,
                isLoadingQuote = false,
                quoteError = check.message,
                priceDriftNotice = null,
              )
            }
            return@launch
          }
          _uiState.update {
            it.copy(
              isCheckingZone = false,
              zoneCheckResult = check,
              checkedPincode = pin,
              quoteError = null,
            )
          }
          if (latestCartItems.isNotEmpty()) {
            requestQuote(overridePincode = pin, clearPincodeOnZoneError = true, forceRefresh = true)
          } else {
            _uiState.update { it.copy(isLoadingQuote = false) }
          }
        }
        .onFailure { err ->
          if (syncGen != quoteRequestGeneration || coroutineContext[Job]?.isActive != true) return@launch
          val apiEx = err as? PizzoraApiException
          if (apiEx != null && (apiEx.code == "ZONE_UNSERVICEABLE" || apiEx.code == "INVALID_PINCODE")) {
            _uiState.update {
              it.copy(
                isCheckingZone = false,
                zoneCheckResult = null,
                checkedPincode = null,
                quote = null,
                quotedSignature = null,
                inFlightSignature = null,
                isLoadingQuote = false,
                quoteError = apiEx.message ?: "Delivery unavailable for selected pincode.",
                priceDriftNotice = null,
              )
            }
            return@launch
          }
          _uiState.update { it.copy(isCheckingZone = false, zoneCheckResult = null, quoteError = null) }
          if (latestCartItems.isNotEmpty()) {
            requestQuote(overridePincode = pin, clearPincodeOnZoneError = true, forceRefresh = true)
          } else {
            _uiState.update { it.copy(isLoadingQuote = false) }
          }
        }
    }
  }

  private fun computeSignature(
    items: List<CartItemWithToppings> = latestCartItems,
    overrideCouponCode: String? = _uiState.value.appliedCouponCode,
    overridePincode: String? = _uiState.value.checkedPincode,
  ): QuoteRequestSignature {
    val state = _uiState.value
    val availableItems = items.filter { it.item.isAvailable }

    val itemsConfig = availableItems.map { entry ->
      QuoteItemConfig(
        id = entry.item.id,
        productId = entry.item.productId,
        variantId = entry.item.variantId,
        crustId = entry.item.crustId,
        toppingIds = entry.toppings.map { it.toppingId }.distinct().sorted(),
        quantity = entry.item.quantity,
      )
    }

    val deliveryContext = if (state.fulfillmentType == FulfillmentType.PICKUP) {
      "PICKUP"
    } else {
      "${(overridePincode ?: state.checkedPincode).orEmpty()}|"
    }

    return QuoteRequestSignature(
      itemsConfig = itemsConfig,
      hasUnavailableItems = items.any { !it.item.isAvailable },
      fulfillmentType = state.fulfillmentType,
      deliveryContext = deliveryContext,
      normalizedCouponCode = overrideCouponCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
    )
  }

  private fun buildQuoteRequest(
    availableItems: List<CartItemWithToppings>,
    fulfillmentType: FulfillmentType,
    pincode: String?,
    couponCode: String?,
  ): PricingQuoteRequest = PricingQuoteRequest(
    items = availableItems.map { entry ->
      CartItemInputDto(
        id = entry.item.id,
        productId = entry.item.productId,
        variantId = entry.item.variantId,
        crustId = entry.item.crustId,
        toppingIds = entry.toppings.map { it.toppingId },
        quantity = entry.item.quantity,
        clientUnitPricePaise = entry.item.unitPricePaise,
      )
    },
    fulfillmentType = fulfillmentType,
    pincode = pincode,
    couponCode = couponCode,
  )

  private fun requestQuote(
    overrideCouponCode: String? = _uiState.value.appliedCouponCode,
    forceClearCoupon: Boolean = false,
    overridePincode: String? = _uiState.value.checkedPincode,
    clearPincodeOnZoneError: Boolean = false,
    forceRefresh: Boolean = false,
  ) {
    val fulfillmentType = _uiState.value.fulfillmentType
    val effectiveCoupon = if (forceClearCoupon) null else overrideCouponCode
    val effectivePincode = if (fulfillmentType == FulfillmentType.DELIVERY) overridePincode else null
    val sig = computeSignature(overrideCouponCode = effectiveCoupon, overridePincode = effectivePincode)

    // Freshness changes synchronously with the input, even when dedup skips a POST.
    _uiState.update { it.copy(currentSignature = sig) }

    if (!forceRefresh) {
      val state = _uiState.value
      if (state.inFlightSignature == sig) return
      if (state.quotedSignature == sig && state.quote != null && state.quoteError == null && !state.isLoadingQuote) {
        return
      }
    }

    val availableItems = latestCartItems.filter { it.item.isAvailable }
    if (availableItems.isEmpty()) {
      // An unavailable-only cart must invalidate an in-flight quote too: otherwise an older
      // response can republish a total for a cart state that no longer exists.
      cancelPendingQuoteWork()
      _uiState.update {
        it.copy(
          quote = null,
          quotedSignature = null,
          currentSignature = null,
          inFlightSignature = null,
          isLoadingQuote = false,
          priceDriftNotice = null,
        )
      }
      return
    }

    val req = buildQuoteRequest(availableItems, fulfillmentType, effectivePincode, effectiveCoupon)

    // A new quote supersedes any zone check too; leaving it "in flight" would strand
    // isCheckingZone = true when its generation-guarded response is dropped.
    cancelPendingQuoteWork()
    val gen = quoteRequestGeneration
    _uiState.update {
      it.copy(
        currentSignature = sig,
        inFlightSignature = sig,
        isLoadingQuote = true,
        isCheckingZone = false,
        quoteError = null,
      )
    }

    quoteJob = viewModelScope.launch {
      pricingRepository.fetchPricingQuote(req)
        .onSuccess { response ->
          if (!isCurrentGeneration(gen, coroutineContext[Job]?.isActive == true)) return@launch
          publishQuote(
            response = response,
            requestSignature = sig,
            requestedPincode = effectivePincode,
            couponError = null,
            forceClearCoupon = forceClearCoupon,
          )
        }
        .onFailure { err ->
          if (!isCurrentGeneration(gen, coroutineContext[Job]?.isActive == true)) return@launch
          val apiEx = err as? PizzoraApiException
          when {
            apiEx?.code?.startsWith("COUPON_") == true -> {
              startCouponFallback(apiEx, effectivePincode, clearPincodeOnZoneError)
            }
            apiEx?.code == "ZONE_UNSERVICEABLE" ||
              apiEx?.code == "DELIVERY_MIN_ORDER_NOT_MET" ||
              apiEx?.code == "INVALID_PINCODE" -> {
              clearQuoteForZoneFailure(apiEx, effectivePincode, clearPincodeOnZoneError)
            }
            else -> {
              // Network/5xx: keep the previous quote for dimming, but remove authority.
              _uiState.update {
                it.copy(
                  inFlightSignature = null,
                  isLoadingQuote = false,
                  quotedSignature = null,
                  quoteError = err.message ?: "Unable to fetch price quote.",
                )
              }
            }
          }
        }
    }
  }

  private fun startCouponFallback(
    apiEx: PizzoraApiException,
    requestedPincode: String?,
    clearPincodeOnZoneError: Boolean,
  ) {
    _uiState.update {
      it.copy(
        isLoadingQuote = false,
        appliedCouponCode = null,
        couponError = apiEx.message,
      )
    }

    quoteJob?.cancel()
    quoteRequestGeneration++
    val fallbackGen = quoteRequestGeneration
    val fallbackSig = computeSignature(overrideCouponCode = null, overridePincode = requestedPincode)
    _uiState.update {
      it.copy(
        currentSignature = fallbackSig,
        inFlightSignature = fallbackSig,
        isLoadingQuote = true,
        quoteError = null,
      )
    }

    quoteJob = viewModelScope.launch {
      if (!isCurrentGeneration(fallbackGen, coroutineContext[Job]?.isActive == true)) return@launch

      // KNOWN DEFECT FIX: the cart may have changed between coupon rejection and
      // fallback dispatch. Re-read the latest available items *inside* the retry.
      val fallbackItems = latestCartItems.filter { it.item.isAvailable }
      if (fallbackItems.isEmpty()) {
        _uiState.update {
          it.copy(
            quote = null,
            quotedSignature = null,
            currentSignature = null,
            inFlightSignature = null,
            isLoadingQuote = false,
            priceDriftNotice = null,
          )
        }
        return@launch
      }

      val fulfillmentType = _uiState.value.fulfillmentType
      val fallbackPincode = if (fulfillmentType == FulfillmentType.DELIVERY) requestedPincode else null
      val fallbackReq = buildQuoteRequest(fallbackItems, fulfillmentType, fallbackPincode, null)
      val freshestSig = computeSignature(overrideCouponCode = null, overridePincode = fallbackPincode)
      _uiState.update {
        it.copy(
          currentSignature = freshestSig,
          inFlightSignature = freshestSig,
        )
      }

      pricingRepository.fetchPricingQuote(fallbackReq)
        .onSuccess { baseQuote ->
          if (!isCurrentGeneration(fallbackGen, coroutineContext[Job]?.isActive == true)) return@launch
          publishQuote(
            response = baseQuote,
            requestSignature = freshestSig,
            requestedPincode = fallbackPincode,
            couponError = apiEx.message,
            forceClearCoupon = true,
          )
        }
        .onFailure { fallbackErr ->
          if (!isCurrentGeneration(fallbackGen, coroutineContext[Job]?.isActive == true)) return@launch
          val fallbackApiEx = fallbackErr as? PizzoraApiException
          if (fallbackApiEx?.code == "ZONE_UNSERVICEABLE" ||
            fallbackApiEx?.code == "DELIVERY_MIN_ORDER_NOT_MET" ||
            fallbackApiEx?.code == "INVALID_PINCODE"
          ) {
            clearQuoteForZoneFailure(fallbackApiEx, fallbackPincode, clearPincodeOnZoneError)
          } else {
            _uiState.update {
              it.copy(
                inFlightSignature = null,
                quotedSignature = null,
                isLoadingQuote = false,
                quoteError = fallbackErr.message ?: "Unable to fetch price quote.",
              )
            }
          }
        }
    }
  }

  private fun publishQuote(
    response: PricingQuoteResponse,
    requestSignature: QuoteRequestSignature,
    requestedPincode: String?,
    couponError: String?,
    forceClearCoupon: Boolean,
  ) {
    val appliedCouponCode = response.appliedCoupon?.code?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    val deliveredPincode = response.deliveryZone?.pincode?.takeIf { it.isNotBlank() } ?: requestedPincode
    val quotedSig = requestSignature.copy(
      deliveryContext = if (requestSignature.fulfillmentType == FulfillmentType.PICKUP) {
        "PICKUP"
      } else {
        "${deliveredPincode.orEmpty()}|"
      },
      normalizedCouponCode = appliedCouponCode,
    )
    val driftNotice = if (response.breakdown.subtotalPaise != latestSubtotalPaise) {
      "Menu prices updated — your total has been refreshed to ${Money.formatInr(response.breakdown.totalPaise)}"
    } else {
      null
    }

    _uiState.update {
      it.copy(
        quote = response,
        quotedSignature = quotedSig,
        currentSignature = quotedSig,
        inFlightSignature = null,
        isLoadingQuote = false,
        couponError = couponError,
        quoteError = null,
        appliedCouponCode = appliedCouponCode ?: if (forceClearCoupon) null else it.appliedCouponCode,
        checkedPincode = if (requestSignature.fulfillmentType == FulfillmentType.DELIVERY) {
          deliveredPincode ?: it.checkedPincode
        } else {
          it.checkedPincode
        },
        priceDriftNotice = driftNotice,
      )
    }
  }

  private fun clearQuoteForZoneFailure(
    apiEx: PizzoraApiException,
    pincode: String?,
    clearPincode: Boolean,
  ) {
    _uiState.update {
      it.copy(
        quote = null,
        quotedSignature = null,
        inFlightSignature = null,
        isLoadingQuote = false,
        checkedPincode = if (clearPincode) null else it.checkedPincode,
        quoteError = zoneFailureMessage(apiEx, pincode),
        priceDriftNotice = null,
      )
    }
  }

  private fun zoneFailureMessage(apiEx: PizzoraApiException, pincode: String?): String {
    if (apiEx.code == "INVALID_PINCODE") return "Invalid pincode."
    if (apiEx.code == "DELIVERY_MIN_ORDER_NOT_MET") {
      val minOrder = apiEx.details?.get("min_order_paise")?.toLongOrNull()
      if (minOrder != null) {
        val shortfall = minOrder - latestSubtotalPaise
        if (shortfall > 0) {
          return "Add ${Money.formatInr(shortfall)} more for delivery to ${pincode.orEmpty()}."
        }
      }
    }
    return apiEx.message ?: "Delivery unavailable for selected pincode."
  }

  private fun cancelPendingQuoteWork() {
    quoteJob?.cancel()
    zoneCheckJob?.cancel()
    quoteRequestGeneration++
  }

  private fun isCurrentGeneration(gen: Long, isActive: Boolean): Boolean =
    gen == quoteRequestGeneration && isActive

  fun clearSessionState() {
    cancelPendingQuoteWork()
    quoteJob = null
    zoneCheckJob = null
    latestCartItems = emptyList()
    latestSubtotalPaise = 0L
    _uiState.value = CheckoutQuoteUiState()
  }

  private fun List<CartItemWithToppings>.toSignature(): QuoteRequestSignature = computeSignature(items = this)
}
