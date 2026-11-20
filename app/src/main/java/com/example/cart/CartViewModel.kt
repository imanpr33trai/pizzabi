package com.example.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.CartItemWithToppings
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ToppingDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CartUiState(
  val items: List<CartItemWithToppings> = emptyList(),
  val subtotalPaise: Long = 0L,
  val totalItemCount: Int = 0,
  val isSyncing: Boolean = false,
  val isOffline: Boolean = false,
  val hasUnavailableItems: Boolean = false,
  val statusMessage: String? = null,
)

class CartViewModel(
  private val cartRepository: CartRepository,
) : ViewModel() {

  private data class SyncStatusState(
    val isSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val statusMessage: String? = null,
  )

  private val syncState = MutableStateFlow(SyncStatusState())
  private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val events: SharedFlow<String> = _events.asSharedFlow()
  private var sessionSyncJob: kotlinx.coroutines.Job? = null

  val uiState: StateFlow<CartUiState> = combine(
    cartRepository.observeCart(),
    syncState,
  ) { items, sync ->
    val availableSubtotal = items
      .filter { it.item.isAvailable }
      .sumOf { it.item.unitPricePaise * it.item.quantity }
    CartUiState(
      items = items,
      subtotalPaise = availableSubtotal,
      totalItemCount = items.sumOf { it.item.quantity },
      isSyncing = sync.isSyncing,
      isOffline = sync.isOffline,
      hasUnavailableItems = items.any { !it.item.isAvailable },
      statusMessage = sync.statusMessage,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.Eagerly,
    initialValue = CartUiState(),
  )

  val badgeCount: StateFlow<Int> = cartRepository.observeCartBadgeCount()
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = 0,
    )

  fun addCustomizedPizza(
    product: ProductDto,
    variant: ProductVariantDto,
    crust: CrustOptionDto,
    toppings: List<ToppingDto>,
    quantity: Int,
    onResult: (success: Boolean, errorMessage: String?) -> Unit = { _, _ -> },
  ) {
    viewModelScope.launch {
      syncState.update { it.copy(isSyncing = true, statusMessage = null) }
      val result = try {
        cartRepository.addCustomizedItem(product, variant, crust, toppings, quantity)
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        CartSyncResult(
          isOffline = false,
          unavailableCount = 0,
          errorMessage = t.message ?: "Unable to add item to cart.",
        )
      }
      val isFatalFailure = result.errorMessage != null && !result.isOffline
      if (isFatalFailure) {
        syncState.update {
          it.copy(
            isSyncing = false,
            isOffline = false,
            statusMessage = result.errorMessage,
          )
        }
        onResult(false, result.errorMessage)
      } else {
        syncState.update {
          it.copy(
            isSyncing = false,
            isOffline = result.isOffline,
            statusMessage = if (result.isOffline) "Saved offline — will validate when back online." else null,
          )
        }
        onResult(true, null)
        _events.tryEmit("${product.name} added to cart")
      }
    }
  }

  fun updateQuantity(itemId: String, newQuantity: Int) {
    viewModelScope.launch {
      val result = cartRepository.updateQuantity(itemId, newQuantity)
      syncState.update { it.copy(isOffline = result.isOffline) }
    }
  }

  fun removeItem(itemId: String) {
    viewModelScope.launch {
      val result = cartRepository.removeItem(itemId)
      syncState.update { it.copy(isOffline = result.isOffline) }
    }
  }

  fun clearCart() {
    viewModelScope.launch {
      val result = cartRepository.clearCart()
      syncState.update { it.copy(isOffline = result.isOffline, statusMessage = null) }
    }
  }

  fun syncOnSessionActive() {
    sessionSyncJob?.cancel()
    sessionSyncJob = viewModelScope.launch {
      syncState.update { it.copy(isSyncing = true) }
      val result = cartRepository.bootstrapFromServer()
      syncState.update {
        it.copy(
          isSyncing = false,
          isOffline = result.isOffline,
          statusMessage = if (!result.isOffline && result.unavailableCount > 0) {
            "${result.unavailableCount} cart item(s) need attention."
          } else {
            null
          },
        )
      }
    }
  }

  fun validateAndSyncCart() {
    sessionSyncJob?.cancel()
    sessionSyncJob = viewModelScope.launch {
      syncState.update { it.copy(isSyncing = true, statusMessage = null) }
      val result = cartRepository.syncAndValidateCart(persistToServer = true)
      val message = when {
        result.isOffline -> "Offline mode — local cart preserved."
        result.unavailableCount > 0 -> "${result.unavailableCount} cart item(s) need attention."
        else -> "Cart synced with latest menu prices."
      }
      syncState.update {
        it.copy(
          isSyncing = false,
          isOffline = result.isOffline,
          statusMessage = message,
        )
      }
    }
  }

  fun clearSessionState() {
    sessionSyncJob?.cancel()
    sessionSyncJob = null
    syncState.value = SyncStatusState()
  }
}
