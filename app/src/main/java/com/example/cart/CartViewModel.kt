package com.example.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.CartItemWithToppings
import com.example.core.network.PizzoraApiClient
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartItemViewDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.ToppingDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * The API client is created by AppContainer (outside W7's write scope). The orders screen records
 * the container's client here so reorder can use the same authenticated instance; tests inject
 * their own client through [CartViewModel]'s provider parameter instead.
 */
object Phase8ApiLocator {
  @Volatile
  var current: PizzoraApiClient? = null
}

private const val CART_UNAVAILABLE_MESSAGE = "Your cart is unavailable right now. Please try again."
private const val EDIT_UNAVAILABLE_MESSAGE = "Unable to update this item right now."

/** Cart-side entry point used by the reorder confirmation dialog. */
interface ReorderCartSink {
  suspend fun addAvailableReorderItems(items: List<CartItemInputDto>): CartSyncResult
}

/** Bridges the active cart ViewModel to the orders ViewModel without editing navigation wiring. */
object ReorderCartBridge : ReorderCartSink {
  @Volatile
  private var sink: ReorderCartSink? = null

  fun register(sink: ReorderCartSink) {
    this.sink = sink
  }

  fun unregister(sink: ReorderCartSink) {
    if (this.sink === sink) this.sink = null
  }

  override suspend fun addAvailableReorderItems(items: List<CartItemInputDto>): CartSyncResult =
    sink?.addAvailableReorderItems(items)
      ?: CartSyncResult(
        isOffline = false,
        unavailableCount = 0,
        errorMessage = "Your cart isn't ready yet. Please try again.",
      )
}

/** Preserves every Phase 8 modifier ID when folding an existing server cart line into a replace. */
internal fun cartItemViewToInput(view: CartItemViewDto): CartItemInputDto = CartItemInputDto(
  id = view.id,
  productId = view.productId,
  variantId = view.variantId,
  crustId = view.crustId,
  toppingIds = view.toppings.map { it.toppingId }.distinct(),
  quantity = view.quantity,
  clientUnitPricePaise = view.unitPricePaise,
  cheeseId = view.cheese?.cheeseId,
  sauceId = view.sauce?.sauceId,
  // Removed defaults are represented by absence, never by a negative contribution.
  standardToppingIds = view.standardToppings.filterNot { it.isRemoved }.map { it.standardToppingId }.distinct(),
  halfAndHalfConfigId = view.halfAndHalf?.halfAndHalfConfigId,
  halfAndHalfLeftToppingIds = view.halfAndHalf?.leftToppingIds.orEmpty().distinct(),
  halfAndHalfRightToppingIds = view.halfAndHalf?.rightToppingIds.orEmpty().distinct(),
)

class CartViewModel(
  private val cartRepository: CartRepository,
  /**
   * Explicit provider signals that this ViewModel is wired to the authenticated server API.
   * Pre-Phase-8 callers that omit it keep the offline-first Room path through [addCustomizedPizza]
   * and [updateEditedPizza]; production AppContainer supplies the provider so main customer flows
   * use the modifier-preserving extended path.
   */
  private val reorderApiClientProvider: (() -> PizzoraApiClient?)? = null,
) : ViewModel(), ReorderCartSink {

  private val apiClientProvider: () -> PizzoraApiClient? =
    reorderApiClientProvider ?: { Phase8ApiLocator.current }

  /** True when the builder can safely forward full DTOs to the authenticated server cart. */
  val usesAuthenticatedModifierApi: Boolean = reorderApiClientProvider != null

  private data class SyncStatusState(
    val isSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val statusMessage: String? = null,
  )

  private val syncState = MutableStateFlow(SyncStatusState())
  /**
   * Read-only snapshot of the authenticated server cart. The builder uses it to restore Phase 8
   * modifier selections when editing an existing line; it never mutates the server cart.
   */
  private val _serverCartItems = MutableStateFlow<List<CartItemViewDto>>(emptyList())
  val serverCartItems: StateFlow<List<CartItemViewDto>> = _serverCartItems.asStateFlow()
  private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val events: SharedFlow<String> = _events.asSharedFlow()
  private val _undoPrompts = MutableSharedFlow<CartUndoPrompt>(extraBufferCapacity = 4)
  val undoPrompts: SharedFlow<CartUndoPrompt> = _undoPrompts.asSharedFlow()

  private var sessionSyncJob: kotlinx.coroutines.Job? = null
  private var pendingUndoPrompt: CartUndoPrompt? = null
  private var isClearingCart: Boolean = false
  private val editingItemIdsInFlight = mutableSetOf<String>()
  private val removalMutex = Mutex()

  init {
    ReorderCartBridge.register(this)
  }

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

  /**
   * Phase 8 builder add path. Unlike [addCustomizedPizza], this forwards the exact
   * [CartItemInputDto] emitted by the builder (including every modifier ID) through the
   * authenticated server cart replace. Existing lines are preserved verbatim.
   */
  fun addCartItemWithModifiers(
    input: CartItemInputDto,
    onResult: (success: Boolean, errorMessage: String?) -> Unit = { _, _ -> },
  ) {
    viewModelScope.launch {
      syncState.update { it.copy(isSyncing = true, statusMessage = null) }
      val api = apiClientProvider()
      if (api == null) {
        syncState.update { it.copy(isSyncing = false, statusMessage = CART_UNAVAILABLE_MESSAGE) }
        onResult(false, CART_UNAVAILABLE_MESSAGE)
        return@launch
      }
      try {
        val existingInputs = api.getCart().items.map(::cartItemViewToInput)
        val serverCart = api.replaceCart(ReplaceCartRequest(existingInputs + input.copy(id = null)))
        val localRefresh = refreshLocalCartAfterServerReplace()
        syncState.update {
          it.copy(
            isSyncing = false,
            isOffline = localRefresh.isOffline,
            statusMessage = cartStatusMessage(serverCart.unavailableCount, localRefresh),
          )
        }
        onResult(true, null)
        _events.tryEmit("Pizza added to cart")
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        val message = t.message?.takeIf { it.isNotBlank() } ?: "Unable to add item to cart."
        syncState.update { it.copy(isSyncing = false, isOffline = true, statusMessage = message) }
        onResult(false, message)
      }
    }
  }

  /**
   * Phase 8 builder edit path. The builder must supply the existing cart line id; the server
   * replace swaps only that line and leaves every other line (and its modifier IDs) untouched.
   */
  fun updateCartItemWithModifiers(
    input: CartItemInputDto,
    onResult: (success: Boolean, errorMessage: String?) -> Unit = { _, _ -> },
    onEditCommitted: (CartEditResult) -> Unit = {},
  ) {
    val existingItemId = input.id
    if (existingItemId.isNullOrBlank()) {
      syncState.update { it.copy(statusMessage = EDIT_UNAVAILABLE_MESSAGE) }
      onResult(false, EDIT_UNAVAILABLE_MESSAGE)
      return
    }
    if (!editingItemIdsInFlight.add(existingItemId)) {
      return
    }
    viewModelScope.launch {
      try {
        syncState.update { it.copy(isSyncing = true, statusMessage = null) }
        val api = apiClientProvider()
        if (api == null) {
          syncState.update { it.copy(isSyncing = false, statusMessage = CART_UNAVAILABLE_MESSAGE) }
          onResult(false, CART_UNAVAILABLE_MESSAGE)
          return@launch
        }
        val existingInputs = api.getCart().items.map(::cartItemViewToInput)
        if (existingInputs.none { it.id == existingItemId }) {
          val message = "This item is no longer in your cart. Please refresh and try again."
          syncState.update { it.copy(isSyncing = false, statusMessage = message) }
          onResult(false, message)
          return@launch
        }
        val replacedInputs = existingInputs.map { line ->
          if (line.id == existingItemId) input else line
        }
        val serverCart = api.replaceCart(ReplaceCartRequest(replacedInputs))
        val localRefresh = refreshLocalCartAfterServerReplace()
        syncState.update {
          it.copy(
            isSyncing = false,
            isOffline = localRefresh.isOffline,
            statusMessage = cartStatusMessage(serverCart.unavailableCount, localRefresh),
          )
        }
        onEditCommitted(
          CartEditResult(
            isOffline = localRefresh.isOffline,
            unavailableCount = serverCart.unavailableCount,
            finalQuantity = input.quantity,
            resultingItemId = existingItemId,
            errorMessage = localRefresh.errorMessage,
          ),
        )
        onResult(true, null)
        _events.tryEmit("Pizza updated")
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        val message = t.message?.takeIf { it.isNotBlank() } ?: EDIT_UNAVAILABLE_MESSAGE
        syncState.update { it.copy(isSyncing = false, isOffline = true, statusMessage = message) }
        onResult(false, message)
      } finally {
        editingItemIdsInFlight.remove(existingItemId)
      }
    }
  }

  /** Refreshes Room from the server after the user confirms a builder add/edit. */
  private suspend fun refreshLocalCartAfterServerReplace(): CartSyncResult = try {
    cartRepository.bootstrapFromServer()
  } catch (t: Throwable) {
    CartSyncResult(isOffline = true, unavailableCount = 0, errorMessage = t.message)
  }

  private fun cartStatusMessage(unavailableCount: Int, localRefresh: CartSyncResult): String? =
    localRefresh.errorMessage?.let { "Cart updated online; local refresh pending." }
      ?: unavailableCount.takeIf { it > 0 }?.let { "$it cart item(s) need attention." }

  /** Loads the read-only server cart view used to restore modifier selections in edit mode. */
  fun refreshServerCartItems() {
    viewModelScope.launch {
      val api = apiClientProvider() ?: return@launch
      try {
        _serverCartItems.value = api.getCart().items
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        _serverCartItems.value = emptyList()
      }
    }
  }

  /**
   * Phase 8 reorder confirmation path. Keeps any existing cart lines (with their Phase 8 IDs),
   * appends only the available reconstructed lines, then submits the existing server-side cart
   * replace for validation and refreshes Room through the existing repository bootstrap path.
   * No direct local DB writes and no client-side substitution are performed.
   */
  override suspend fun addAvailableReorderItems(items: List<CartItemInputDto>): CartSyncResult {
    if (items.isEmpty()) {
      return CartSyncResult(isOffline = false, unavailableCount = 0)
    }
    syncState.update { it.copy(isSyncing = true, statusMessage = null) }
    val api = apiClientProvider()
    if (api == null) {
      syncState.update {
        it.copy(isSyncing = false, statusMessage = "Your cart is unavailable right now. Please try again.")
      }
      return CartSyncResult(
        isOffline = false,
        unavailableCount = 0,
        errorMessage = "Your cart is unavailable right now. Please try again.",
      )
    }
    return try {
      val existingInputs = api.getCart().items.map(::cartItemViewToInput)
      val serverCart = api.replaceCart(ReplaceCartRequest(existingInputs + items))
      val localRefresh = try {
        cartRepository.bootstrapFromServer()
      } catch (t: Throwable) {
        CartSyncResult(isOffline = true, unavailableCount = 0, errorMessage = t.message)
      }
      syncState.update {
        it.copy(
          isSyncing = false,
          isOffline = localRefresh.isOffline,
          statusMessage = localRefresh.errorMessage?.let { "Cart updated online; local refresh pending." },
        )
      }
      CartSyncResult(
        isOffline = localRefresh.isOffline,
        unavailableCount = serverCart.unavailableCount,
        errorMessage = localRefresh.errorMessage,
      )
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      syncState.update {
        it.copy(isSyncing = false, isOffline = true, statusMessage = t.message ?: "Unable to update cart.")
      }
      CartSyncResult(isOffline = true, unavailableCount = 0, errorMessage = t.message ?: "Unable to update cart.")
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
    onEditCommitted: (CartEditResult) -> Unit = {},
  ) {
    if (!editingItemIdsInFlight.add(existingItemId)) {
      return
    }
    viewModelScope.launch {
      try {
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
          onEditCommitted(result)
          val eventMessage = when {
            result.wasMerged && result.wasQuantityCapped -> "Combined with matching item (capped at max 25)"
            result.wasMerged -> "Combined with matching item in your cart"
            else -> "${product.name} updated"
          }
          onResult(true, null)
          _events.tryEmit(eventMessage)
        }
      } finally {
        editingItemIdsInFlight.remove(existingItemId)
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
      removalMutex.withLock {
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
  }

  fun undoRemoval(token: String) {
    val prompt = pendingUndoPrompt
    if (prompt == null || prompt.token != token) {
      return
    }
    pendingUndoPrompt = null
    viewModelScope.launch {
      removalMutex.withLock {
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
    editingItemIdsInFlight.clear()
    _serverCartItems.value = emptyList()
    syncState.value = SyncStatusState()
  }

  override fun onCleared() {
    ReorderCartBridge.unregister(this)
    super.onCleared()
  }
}
