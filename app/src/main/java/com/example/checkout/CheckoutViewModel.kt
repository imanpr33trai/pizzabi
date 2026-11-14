package com.example.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.CartItemWithToppings
import com.example.core.network.PizzoraApiException
import com.example.orders.OrderRepository
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.ScheduledSlotDto
import com.pizzora.contracts.UpsertAddressRequest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckoutUiState(
  val addresses: List<AddressDto> = emptyList(),
  val selectedAddressId: String? = null,
  val isLoadingAddresses: Boolean = false,
  val isSavingAddress: Boolean = false,
  val isAddAddressDialogOpen: Boolean = false,
  val paymentMethod: PaymentMethod = PaymentMethod.COD,
  val scheduleType: ScheduleType = ScheduleType.ASAP,
  val selectedSlotDate: String = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString(),
  val availableSlots: List<ScheduledSlotDto> = emptyList(),
  val selectedSlotId: String? = null,
  val isLoadingSlots: Boolean = false,
  val slotsError: String? = null,
  val selectedAddressZoneCheck: DeliveryZoneCheckResponse? = null,
  val isCheckingAddressZone: Boolean = false,
  val isPlacingOrder: Boolean = false,
  val checkoutError: String? = null,
  val addressError: String? = null,
)

class CheckoutViewModel(
  private val orderRepository: OrderRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CheckoutUiState())
  val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

  private val _orderCreatedEvents = MutableSharedFlow<OrderDetailDto>(extraBufferCapacity = 4)
  val orderCreatedEvents: SharedFlow<OrderDetailDto> = _orderCreatedEvents.asSharedFlow()

  private var currentDraftSignature: String? = null
  private var currentIdempotencyKey: String = UUID.randomUUID().toString()

  fun loadAddresses() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingAddresses = true, addressError = null) }
      orderRepository.getAddresses()
        .onSuccess { list ->
          _uiState.update { state ->
            val currentSelection = state.selectedAddressId?.takeIf { id -> list.any { it.id == id } }
              ?: list.firstOrNull { it.isDefault }?.id
              ?: list.firstOrNull()?.id
            state.copy(
              addresses = list,
              selectedAddressId = currentSelection,
              isLoadingAddresses = false,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isLoadingAddresses = false,
              addressError = err.message ?: "Unable to load saved addresses.",
            )
          }
        }
    }
  }

  fun selectAddress(addressId: String) {
    _uiState.update {
      it.copy(
        selectedAddressId = addressId,
        selectedAddressZoneCheck = null,
        checkoutError = null,
      )
    }
  }

  fun checkSelectedAddressServiceability() {
    val state = _uiState.value
    val selected = state.addresses.firstOrNull { it.id == state.selectedAddressId } ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(isCheckingAddressZone = true, checkoutError = null) }
      orderRepository.checkDeliveryZone(selected.pincode)
        .onSuccess { response ->
          _uiState.update {
            it.copy(
              isCheckingAddressZone = false,
              selectedAddressZoneCheck = response,
              checkoutError = if (!response.isServiceable) response.message else null,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isCheckingAddressZone = false,
              checkoutError = err.message ?: "Could not verify delivery zone.",
            )
          }
        }
    }
  }

  fun selectPaymentMethod(method: PaymentMethod) {
    _uiState.update { it.copy(paymentMethod = method, checkoutError = null) }
  }

  fun onScheduleTypeChange(type: ScheduleType, fulfillmentType: FulfillmentType) {
    if (_uiState.value.scheduleType == type) return
    _uiState.update {
      it.copy(
        scheduleType = type,
        selectedSlotId = if (type == ScheduleType.ASAP) null else it.selectedSlotId,
        slotsError = null,
        checkoutError = null,
      )
    }
    if (type == ScheduleType.SCHEDULED) {
      loadAvailableSlots(fulfillmentType, _uiState.value.selectedSlotDate)
    }
  }

  fun onSlotDateChange(date: String, fulfillmentType: FulfillmentType) {
    _uiState.update {
      it.copy(
        selectedSlotDate = date,
        selectedSlotId = null,
        slotsError = null,
        checkoutError = null,
      )
    }
    if (_uiState.value.scheduleType == ScheduleType.SCHEDULED) {
      loadAvailableSlots(fulfillmentType, date)
    }
  }

  fun onFulfillmentChanged(fulfillmentType: FulfillmentType) {
    _uiState.update {
      it.copy(
        selectedSlotId = null,
        slotsError = null,
        checkoutError = null,
      )
    }
    if (_uiState.value.scheduleType == ScheduleType.SCHEDULED) {
      loadAvailableSlots(fulfillmentType, _uiState.value.selectedSlotDate)
    }
  }

  fun loadAvailableSlots(
    fulfillmentType: FulfillmentType,
    date: String = _uiState.value.selectedSlotDate,
  ) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingSlots = true, slotsError = null) }
      orderRepository.getAvailableSlots(fulfillmentType, date)
        .onSuccess { response ->
          _uiState.update { state ->
            val validSelected = state.selectedSlotId?.takeIf { id ->
              response.slots.any { it.id == id && it.isBookable }
            }
            state.copy(
              isLoadingSlots = false,
              selectedSlotDate = response.date,
              availableSlots = response.slots,
              selectedSlotId = validSelected,
              slotsError = null,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isLoadingSlots = false,
              availableSlots = emptyList(),
              selectedSlotId = null,
              slotsError = err.message ?: "Unable to load scheduled slots.",
            )
          }
        }
    }
  }

  fun selectSlot(slotId: String) {
    val slot = _uiState.value.availableSlots.firstOrNull { it.id == slotId } ?: return
    if (!slot.isBookable) return
    _uiState.update { it.copy(selectedSlotId = slotId, checkoutError = null) }
  }

  fun openAddAddressDialog() {
    _uiState.update { it.copy(isAddAddressDialogOpen = true, addressError = null) }
  }

  fun dismissAddAddressDialog() {
    _uiState.update { it.copy(isAddAddressDialogOpen = false, addressError = null) }
  }

  fun saveNewAddress(
    label: String,
    line1: String,
    pincode: String,
    city: String,
    line2: String? = null,
    landmark: String? = null,
    isDefault: Boolean? = null,
  ) {
    val cleanLabel = label.trim()
    val cleanLine1 = line1.trim()
    val cleanPin = pincode.trim()
    val cleanCity = city.trim()
    val cleanLine2 = line2?.trim()?.takeIf { it.isNotEmpty() }
    val cleanLandmark = landmark?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanLabel.isEmpty() || cleanLine1.isEmpty() || cleanCity.isEmpty()) {
      _uiState.update { it.copy(addressError = "Label, street address, and city are required.") }
      return
    }
    if (!cleanPin.matches(Regex("^[1-9][0-9]{5}$"))) {
      _uiState.update { it.copy(addressError = "Enter a valid 6-digit Indian pincode.") }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isSavingAddress = true, addressError = null) }
      orderRepository.createAddress(
        UpsertAddressRequest(
          label = cleanLabel,
          line1 = cleanLine1,
          line2 = cleanLine2,
          landmark = cleanLandmark,
          pincode = cleanPin,
          city = cleanCity,
          isDefault = isDefault ?: _uiState.value.addresses.isEmpty(),
        ),
      )
        .onSuccess { created ->
          _uiState.update { state ->
            val updatedExisting = if (created.isDefault) {
              state.addresses.map { it.copy(isDefault = false) }
            } else {
              state.addresses
            }
            state.copy(
              addresses = listOf(created) + updatedExisting,
              selectedAddressId = created.id,
              selectedAddressZoneCheck = null,
              isSavingAddress = false,
              isAddAddressDialogOpen = false,
              checkoutError = null,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isSavingAddress = false,
              addressError = err.message ?: "Unable to save address.",
            )
          }
        }
    }
  }

  fun placeOrder(
    cartItems: List<CartItemWithToppings>,
    fulfillmentType: FulfillmentType,
    couponCode: String?,
    onCartCleared: () -> Unit,
  ) {
    if (_uiState.value.isPlacingOrder) return
    val available = cartItems.filter { it.item.isAvailable }
    if (available.isEmpty()) {
      _uiState.update { it.copy(checkoutError = "No available items in cart to place order.") }
      return
    }

    val state = _uiState.value
    val selectedAddressId = if (fulfillmentType == FulfillmentType.DELIVERY) state.selectedAddressId else null
    if (fulfillmentType == FulfillmentType.DELIVERY && selectedAddressId.isNullOrBlank()) {
      _uiState.update { it.copy(checkoutError = "Please select or add a delivery address.") }
      return
    }

    if (state.scheduleType == ScheduleType.SCHEDULED && state.selectedSlotId.isNullOrBlank()) {
      _uiState.update { it.copy(checkoutError = "Please select a scheduled delivery/pickup time slot.") }
      return
    }

    val effectiveSlotId = if (state.scheduleType == ScheduleType.SCHEDULED) state.selectedSlotId else null
    val req = CheckoutRequest(
      items = available.map { entry ->
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
      scheduleType = state.scheduleType,
      scheduledSlotId = effectiveSlotId,
      addressId = selectedAddressId,
      paymentMethod = state.paymentMethod,
      couponCode = couponCode?.takeIf { it.isNotBlank() },
      clearCart = true,
    )

    val signature = buildSignature(req)
    if (signature != currentDraftSignature) {
      currentDraftSignature = signature
      currentIdempotencyKey = UUID.randomUUID().toString()
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isPlacingOrder = true, checkoutError = null) }
      orderRepository.placeOrder(currentIdempotencyKey, req)
        .onSuccess { response ->
          currentDraftSignature = null
          currentIdempotencyKey = UUID.randomUUID().toString()
          _uiState.update {
            it.copy(
              isPlacingOrder = false,
              checkoutError = null,
              scheduleType = ScheduleType.ASAP,
              selectedSlotId = null,
            )
          }
          onCartCleared()
          _orderCreatedEvents.tryEmit(response.order)
        }
        .onFailure { err ->
          val apiMsg = (err as? PizzoraApiException)?.message ?: err.message
          _uiState.update {
            it.copy(
              isPlacingOrder = false,
              checkoutError = apiMsg ?: "Unable to complete checkout. Please try again.",
            )
          }
        }
    }
  }

  private fun buildSignature(req: CheckoutRequest): String = buildString {
    append(req.fulfillmentType).append('|')
    append(req.scheduleType).append('|')
    append(req.scheduledSlotId.orEmpty()).append('|')
    append(req.addressId.orEmpty()).append('|')
    append(req.paymentMethod).append('|')
    append(req.couponCode.orEmpty()).append('|')
    req.items.forEach {
      append(it.productId).append(':').append(it.variantId).append(':').append(it.crustId)
        .append(':').append(it.toppingIds.sorted().joinToString(",")).append(':').append(it.quantity).append(';')
    }
  }
}
