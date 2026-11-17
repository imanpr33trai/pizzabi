package com.example.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.CartItemWithToppings
import com.example.core.network.PizzoraApiException
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CustomerCouponDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

class CheckoutQuoteViewModel(
  private val pricingRepository: PricingRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CheckoutQuoteUiState())
  val uiState: StateFlow<CheckoutQuoteUiState> = _uiState.asStateFlow()

  private var latestCartItems: List<CartItemWithToppings> = emptyList()
  private var latestSubtotalPaise: Long = 0L
  private var quoteJob: Job? = null

  fun onCartChanged(items: List<CartItemWithToppings>, subtotalPaise: Long) {
    val previousSignature = latestCartItems.toSignature()
    val newSignature = items.toSignature()
    latestCartItems = items
    latestSubtotalPaise = subtotalPaise

    if (items.isEmpty()) {
      quoteJob?.cancel()
      _uiState.update {
        it.copy(
          quote = null,
          isLoadingQuote = false,
          couponError = null,
          quoteError = null,
        )
      }
      return
    }

    if (previousSignature != newSignature || _uiState.value.quote == null) {
      requestQuote()
    }
  }

  fun onFulfillmentTypeChange(type: FulfillmentType) {
    if (_uiState.value.fulfillmentType == type) return
    _uiState.update {
      it.copy(
        fulfillmentType = type,
        quoteError = null,
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
      _uiState.update { it.copy(checkedPincode = null, zoneCheckResult = null, quoteError = null) }
      if (latestCartItems.isNotEmpty()) {
        requestQuote(overridePincode = null, clearPincodeOnZoneError = true)
      }
      return
    }
    if (!pin.matches(Regex("^[1-9][0-9]{5}$"))) {
      _uiState.update {
        it.copy(
          checkedPincode = null,
          zoneCheckResult = null,
          quoteError = "Enter a valid 6-digit Indian pincode.",
        )
      }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isCheckingZone = true, checkedPincode = pin, quoteError = null) }
      val zoneResult = pricingRepository.checkDeliveryZone(pin)
      zoneResult
        .onSuccess { check ->
          _uiState.update {
            it.copy(
              isCheckingZone = false,
              zoneCheckResult = check,
              checkedPincode = if (check.isServiceable) pin else null,
              quoteError = if (!check.isServiceable) check.message else null,
            )
          }
          if (check.isServiceable && latestCartItems.isNotEmpty()) {
            requestQuote(overridePincode = pin, clearPincodeOnZoneError = true)
          }
        }
        .onFailure { err ->
          val apiEx = err as? PizzoraApiException
          if (apiEx != null) {
            _uiState.update {
              it.copy(
                isCheckingZone = false,
                zoneCheckResult = null,
                checkedPincode = null,
                quoteError = apiEx.message,
              )
            }
          } else {
            // Non-API fallback (e.g., offline or legacy quote-only MockEngine): defer to pricing quote
            _uiState.update { it.copy(isCheckingZone = false, zoneCheckResult = null) }
            if (latestCartItems.isNotEmpty()) {
              requestQuote(overridePincode = pin, clearPincodeOnZoneError = true)
            }
          }
        }
    }
  }

  fun onCouponInputChange(value: String) {
    _uiState.update { it.copy(couponInput = value.uppercase(), couponError = null) }
  }

  fun openCouponPicker() {
    _uiState.update { it.copy(isCouponSheetOpen = true, isLoadingCoupons = true) }
    viewModelScope.launch {
      val subtotalArg = latestSubtotalPaise.takeIf { it > 0L }
      pricingRepository.fetchAvailableCoupons(subtotalArg)
        .onSuccess { resp ->
          _uiState.update {
            it.copy(
              availableCoupons = resp.coupons,
              isLoadingCoupons = false,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isLoadingCoupons = false,
              couponError = err.message ?: "Unable to load coupons right now.",
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
        couponError = null,
      )
    }
    if (latestCartItems.isNotEmpty()) {
      requestQuote(overrideCouponCode = normalized, commitCouponOnSuccess = true)
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
      requestQuote()
    }
  }

  private fun requestQuote(
    overrideCouponCode: String? = _uiState.value.appliedCouponCode,
    forceClearCoupon: Boolean = false,
    commitCouponOnSuccess: Boolean = false,
    overridePincode: String? = _uiState.value.checkedPincode,
    clearPincodeOnZoneError: Boolean = false,
  ) {
    val availableItems = latestCartItems.filter { it.item.isAvailable }
    if (availableItems.isEmpty()) {
      _uiState.update { it.copy(quote = null, isLoadingQuote = false) }
      return
    }

    val state = _uiState.value
    val effectiveCoupon = if (forceClearCoupon) null else overrideCouponCode
    val effectivePincode = if (state.fulfillmentType == FulfillmentType.DELIVERY) overridePincode else null

    val req = PricingQuoteRequest(
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
      fulfillmentType = state.fulfillmentType,
      pincode = effectivePincode,
      couponCode = effectiveCoupon,
    )

    quoteJob?.cancel()
    quoteJob = viewModelScope.launch {
      _uiState.update { it.copy(isLoadingQuote = true, quoteError = null) }
      pricingRepository.fetchPricingQuote(req)
        .onSuccess { response ->
          _uiState.update {
            it.copy(
              quote = response,
              appliedCouponCode = response.appliedCoupon?.code ?: if (forceClearCoupon) null else it.appliedCouponCode,
              checkedPincode = response.deliveryZone?.pincode ?: effectivePincode,
              isLoadingQuote = false,
              couponError = null,
              quoteError = null,
            )
          }
        }
        .onFailure { err ->
          val apiEx = err as? PizzoraApiException
          val isCouponErr = apiEx?.code?.startsWith("COUPON_") == true
          val isZoneErr = apiEx?.code == "ZONE_UNSERVICEABLE" || apiEx?.code == "DELIVERY_MIN_ORDER_NOT_MET"

          if (isCouponErr) {
            _uiState.update {
              it.copy(
                isLoadingQuote = false,
                appliedCouponCode = if (commitCouponOnSuccess) it.appliedCouponCode else null,
                couponError = apiEx.message,
              )
            }
            // Refresh base quote without the rejected coupon so user still sees itemized totals
            val fallbackReq = req.copy(couponCode = null)
            pricingRepository.fetchPricingQuote(fallbackReq).onSuccess { baseQuote ->
              _uiState.update { it.copy(quote = baseQuote) }
            }
          } else if (isZoneErr) {
            _uiState.update {
              it.copy(
                isLoadingQuote = false,
                checkedPincode = if (clearPincodeOnZoneError) null else it.checkedPincode,
                quoteError = apiEx?.message ?: "Delivery unavailable for selected pincode.",
              )
            }
          } else {
            _uiState.update {
              it.copy(
                isLoadingQuote = false,
                quoteError = err.message ?: "Unable to fetch price quote.",
              )
            }
          }
        }
    }
  }

  fun clearSessionState() {
    quoteJob?.cancel()
    quoteJob = null
    latestCartItems = emptyList()
    latestSubtotalPaise = 0L
    _uiState.value = CheckoutQuoteUiState()
  }

  private fun List<CartItemWithToppings>.toSignature(): String =
    joinToString("|") { entry ->
      val toppings = entry.toppings.map { it.toppingId }.sorted().joinToString(",")
      "${entry.item.id}:${entry.item.variantId}:${entry.item.crustId}:$toppings:${entry.item.quantity}:${entry.item.isAvailable}"
    }
}
