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
import java.util.UUID

data class CartUiState(
  val items: List<CartItemWithToppings> = emptyList(),
  val subtotalPaise: Long = 0L,
  val totalItemCount: Int = 0,
  val isSyncing: Boolean = false,
  val isOffline: Boolean = false,
  val hasUnavailableItems: Boolean = false,
  val statusMessage: String? = null,
)

data class CartUndoPrompt(
  val token: String,
  val productName: String,
  val snapshot: RemovedCartItemSnapshot,
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
  private val _undoPrompts = MutableSharedFlow<CartUndoPrompt>(extraBufferCapacity = 4)
  val undoPrompts: SharedFlow<CartUndoPrompt> = _undoPrompts.asSharedFlow()

  private var sessionSyncJob: kotlinx.coroutines.Job? = null
  private var pendingUndoPrompt: CartUndoPrompt? = null
  private var isClearingCart: Boolean = false

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

  fun updateEditedPizza(
    existingItemId: String,
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
        cartRepository.updateCustomizedItem(
          existingItemId = existingItemId,
          product = product,
          variant = variant,
          crust = crust,
          toppings = toppings,
          quantity = quantity,
        )
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        CartEditResult(
          isOffline = false,
          unavailableCount = 0,
          errorMessage = t.message ?: "Unable to update cart item.",
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
        val eventMessage = when {
          result.wasMerged && result.wasQuantityCapped -> "Combined with matching item (capped at max 25)"
          result.wasMerged -> "Combined with matching item in your cart"
          else -> "${product.name} updated"
        }
        onResult(true, null)
        _events.tryEmit(eventMessage)
      }
    }
  }

  fun updateQuantity(itemId: String, newQuantity: Int) {
    if (newQuantity <= 0) {
      removeItemWithUndoInternal(itemId)
      return
    }
    viewModelScope.launch {
      val result = cartRepository.updateQuantity(itemId, newQuantity)
      syncState.update { it.copy(isOffline = result.isOffline) }
    }
  }

  fun removeItem(itemId: String) {
    removeItemWithUndoInternal(itemId)
  }

  private fun removeItemWithUndoInternal(itemId: String) {
    viewModelScope.launch {
      val (snapshot, result) = cartRepository.removeItemWithSnapshot(itemId)
      syncState.update { it.copy(isOffline = result.isOffline) }
      if (snapshot != null) {
        val prompt = CartUndoPrompt(
          token = UUID.randomUUID().toString(),
          productName = snapshot.entry.item.productName,
          snapshot = snapshot,
        )
        pendingUndoPrompt = prompt
        _undoPrompts.tryEmit(prompt)
      }
    }
  }

  fun undoRemoval(token: String) {
    val prompt = pendingUndoPrompt
    if (prompt == null || prompt.token != token) {
      return
    }
    pendingUndoPrompt = null
    viewModelScope.launch {
      syncState.update { it.copy(isSyncing = true, statusMessage = null) }
      val result = try {
        cartRepository.restoreRemovedItem(prompt.snapshot)
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        CartSyncResult(
          isOffline = false,
          unavailableCount = 0,
          errorMessage = t.message ?: "Unable to restore cart item.",
        )
      }
      syncState.update {
        it.copy(
          isSyncing = false,
          isOffline = result.isOffline,
          statusMessage = if (result.isOffline) "Saved offline — will validate when back online." else null,
        )
      }
    }
  }

  fun dismissUndo(token: String) {
    if (pendingUndoPrompt?.token == token) {
      pendingUndoPrompt = null
    }
  }

  fun clearCart() {
    if (isClearingCart) return
    pendingUndoPrompt = null
    isClearingCart = true
    viewModelScope.launch {
      try {
        val result = cartRepository.clearCart()
        syncState.update { it.copy(isOffline = result.isOffline, statusMessage = null) }
      } finally {
        isClearingCart = false
      }
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
    pendingUndoPrompt = null
    isClearingCart = false
    syncState.value = SyncStatusState()
  }
}
