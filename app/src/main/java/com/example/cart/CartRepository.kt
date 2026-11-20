package com.example.cart

import com.example.core.database.CartDao
import com.example.core.database.CartEditMergeAction
import com.example.core.database.CartItemEntity
import com.example.core.database.CartItemToppingEntity
import com.example.core.database.CartItemWithToppings
import com.example.core.domain.CartSyncAction
import com.example.core.domain.CartSyncPlanRequest
import com.example.core.domain.CartSyncTrigger
import com.example.core.domain.DomainBridge
import com.example.core.domain.RustDomainBridge
import com.example.core.domain.SessionIdentitySnapshot
import com.example.core.network.PizzoraApiClient
import com.example.core.network.PizzoraApiException
import com.example.core.security.SessionTransitionCoordinator
import com.example.core.security.TokenStorage
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageStatus
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.ValidateCartRequest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class CartSyncResult(
  val isOffline: Boolean,
  val unavailableCount: Int,
  val errorMessage: String? = null,
)

data class RemovedCartItemSnapshot(
  val userId: String,
  val entry: CartItemWithToppings,
)

data class CartEditResult(
  val isOffline: Boolean,
  val unavailableCount: Int,
  val wasMerged: Boolean = false,
  val wasQuantityCapped: Boolean = false,
  val finalQuantity: Int = 0,
  val errorMessage: String? = null,
)

interface CartRepository {
  fun observeCart(): Flow<List<CartItemWithToppings>>
  fun observeCartBadgeCount(): Flow<Int>
  suspend fun addCustomizedItem(
    product: ProductDto,
    variant: ProductVariantDto,
    crust: CrustOptionDto,
    toppings: List<ToppingDto>,
    quantity: Int,
  ): CartSyncResult

  suspend fun updateCustomizedItem(
    existingItemId: String,
    product: ProductDto,
    variant: ProductVariantDto,
    crust: CrustOptionDto,
    toppings: List<ToppingDto>,
    quantity: Int,
  ): CartEditResult

  suspend fun updateQuantity(itemId: String, newQuantity: Int): CartSyncResult
  suspend fun removeItem(itemId: String): CartSyncResult
  suspend fun removeItemWithSnapshot(itemId: String): Pair<RemovedCartItemSnapshot?, CartSyncResult>
  suspend fun restoreRemovedItem(snapshot: RemovedCartItemSnapshot): CartSyncResult
  suspend fun clearCart(): CartSyncResult
  suspend fun bootstrapFromServer(): CartSyncResult
  suspend fun syncAndValidateCart(persistToServer: Boolean = true): CartSyncResult
}

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkCartRepository(
  private val apiClient: PizzoraApiClient,
  private val cartDao: CartDao,
  private val tokenStorage: TokenStorage = apiClient.sessionTokenStorage,
  private val sessionCoordinator: SessionTransitionCoordinator? = null,
  private val domainBridge: DomainBridge = RustDomainBridge,
) : CartRepository {

  private fun captureSession(): Pair<Long, String>? {
    val snapshot = sessionCoordinator?.currentSnapshot()
    val userId = snapshot?.userId ?: tokenStorage.currentSession()?.user?.id ?: return null
    val epoch = snapshot?.epoch ?: 0L
    return epoch to userId
  }

  private fun activeSessionSnapshot(): SessionIdentitySnapshot? {
    val snapshot = sessionCoordinator?.currentSnapshot()
    if (snapshot != null) {
      return snapshot.userId?.let { SessionIdentitySnapshot(epoch = snapshot.epoch, userId = it) }
    }
    val userId = tokenStorage.currentSession()?.user?.id ?: return null
    return SessionIdentitySnapshot(epoch = 0L, userId = userId)
  }

  private fun isSessionActive(capturedEpoch: Long, capturedUserId: String): Boolean {
    return sessionCoordinator?.isCurrentSession(capturedEpoch, capturedUserId)
      ?: (tokenStorage.currentSession()?.user?.id == capturedUserId)
  }

  private suspend fun <T> commitIfSessionMatches(
    capturedEpoch: Long,
    capturedUserId: String,
    block: suspend () -> T,
  ): T? {
    if (sessionCoordinator != null) {
      return sessionCoordinator.runIfSessionMatches(capturedEpoch, capturedUserId, block)
    }
    if (tokenStorage.currentSession()?.user?.id != capturedUserId) {
      return null
    }
    return block()
  }

  override fun observeCart(): Flow<List<CartItemWithToppings>> =
    tokenStorage.sessionFlow
      .map { it?.user?.id }
      .distinctUntilChanged()
      .flatMapLatest { userId ->
        if (userId == null) flowOf(emptyList()) else cartDao.observeCartItemsWithToppings(userId)
      }

  override fun observeCartBadgeCount(): Flow<Int> =
    tokenStorage.sessionFlow
      .map { it?.user?.id }
      .distinctUntilChanged()
      .flatMapLatest { userId ->
        if (userId == null) flowOf(0) else cartDao.observeCartBadgeCount(userId)
      }

  override suspend fun addCustomizedItem(
    product: ProductDto,
    variant: ProductVariantDto,
    crust: CrustOptionDto,
    toppings: List<ToppingDto>,
    quantity: Int,
  ): CartSyncResult {
    require(quantity > 0) { "Quantity must be positive" }
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Authentication required")

    val distinctToppings = toppings.distinctBy { it.id }.sortedBy { it.id }
    val signatureToppingIds = distinctToppings.map { it.id }
    val unitPricePaise = variant.pricePaise + crust.extraPricePaise + distinctToppings.sumOf { it.extraPricePaise }
    val now = System.currentTimeMillis()

    val written = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      val currentItems = cartDao.getCartItemsWithToppingsOnce(capturedUserId)
      val matching = currentItems.firstOrNull { existing ->
        existing.item.productId == product.id &&
          existing.item.variantId == variant.id &&
          existing.item.crustId == crust.id &&
          existing.toppings.map { it.toppingId }.sorted() == signatureToppingIds
      }

      val itemId = matching?.item?.id ?: UUID.randomUUID().toString()
      val nextQty = ((matching?.item?.quantity ?: 0) + quantity).coerceAtMost(25)

      val entity = CartItemEntity(
        id = itemId,
        userId = capturedUserId,
        productId = product.id,
        productName = product.name,
        imageUrl = primaryProductImageUrl(product),
        variantId = variant.id,
        sizeCode = variant.sizeCode.name,
        crustId = crust.id,
        crustName = crust.name,
        unitPricePaise = unitPricePaise,
        quantity = nextQty,
        isAvailable = true,
        unavailableReason = null,
        updatedAtMs = now,
      )
      val toppingEntities = distinctToppings.map { topping ->
        CartItemToppingEntity(
          cartItemId = itemId,
          toppingId = topping.id,
          toppingName = topping.name,
          extraPricePaise = topping.extraPricePaise,
        )
      }
      cartDao.upsertItemWithToppings(entity, toppingEntities)
      true
    }
    if (written != true) {
      return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }
    return syncAndValidateCartInternal(
      trigger = CartSyncTrigger.EXPLICIT_MUTATION,
      persistToServer = true,
      allowEmptyReplace = false,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )
  }

  override suspend fun updateCustomizedItem(
    existingItemId: String,
    product: ProductDto,
    variant: ProductVariantDto,
    crust: CrustOptionDto,
    toppings: List<ToppingDto>,
    quantity: Int,
  ): CartEditResult {
    require(quantity > 0) { "Quantity must be positive" }
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartEditResult(isOffline = false, unavailableCount = 0, errorMessage = "Authentication required")

    val distinctToppings = toppings.distinctBy { it.id }.sortedBy { it.id }
    val unitPricePaise = variant.pricePaise + crust.extraPricePaise + distinctToppings.sumOf { it.extraPricePaise }
    val safeQty = quantity.coerceIn(1, 25)
    val now = System.currentTimeMillis()

    val updatedEntity = CartItemEntity(
      id = existingItemId,
      userId = capturedUserId,
      productId = product.id,
      productName = product.name,
      imageUrl = primaryProductImageUrl(product),
      variantId = variant.id,
      sizeCode = variant.sizeCode.name,
      crustId = crust.id,
      crustName = crust.name,
      unitPricePaise = unitPricePaise,
      quantity = safeQty,
      isAvailable = true,
      unavailableReason = null,
      updatedAtMs = now,
    )
    val toppingEntities = distinctToppings.map { topping ->
      CartItemToppingEntity(
        cartItemId = existingItemId,
        toppingId = topping.id,
        toppingName = topping.name,
        extraPricePaise = topping.extraPricePaise,
      )
    }

    val daoResult = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      cartDao.replaceOrMergeItemWithToppings(
        userId = capturedUserId,
        existingLocalId = existingItemId,
        updatedItem = updatedEntity,
        updatedToppings = toppingEntities,
      )
    } ?: return CartEditResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")

    if (daoResult.action == CartEditMergeAction.NOT_FOUND) {
      return CartEditResult(
        isOffline = false,
        unavailableCount = 0,
        errorMessage = "This cart item is no longer in your cart.",
      )
    }

    val syncResult = syncAndValidateCartInternal(
      trigger = CartSyncTrigger.EXPLICIT_MUTATION,
      persistToServer = true,
      allowEmptyReplace = false,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )

    return CartEditResult(
      isOffline = syncResult.isOffline,
      unavailableCount = syncResult.unavailableCount,
      wasMerged = daoResult.action == CartEditMergeAction.MERGED_INTO_EXISTING,
      wasQuantityCapped = daoResult.wasQuantityCapped,
      finalQuantity = daoResult.finalQuantity,
      errorMessage = syncResult.errorMessage,
    )
  }

  override suspend fun updateQuantity(itemId: String, newQuantity: Int): CartSyncResult {
    if (newQuantity <= 0) {
      return removeItem(itemId)
    }
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Authentication required")

    val updated = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      cartDao.updateQuantity(capturedUserId, itemId, newQuantity.coerceAtMost(25), System.currentTimeMillis())
      true
    }
    if (updated != true) {
      return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }
    return syncAndValidateCartInternal(
      trigger = CartSyncTrigger.EXPLICIT_MUTATION,
      persistToServer = true,
      allowEmptyReplace = false,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )
  }

  override suspend fun removeItem(itemId: String): CartSyncResult =
    removeItemWithSnapshot(itemId).second

  override suspend fun removeItemWithSnapshot(
    itemId: String,
  ): Pair<RemovedCartItemSnapshot?, CartSyncResult> {
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return null to CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Authentication required")

    val snapshotOrNull = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      val existing = cartDao.getCartItemsWithToppingsOnce(capturedUserId)
        .firstOrNull { it.item.id == itemId }
      cartDao.deleteItemById(capturedUserId, itemId)
      existing?.let { RemovedCartItemSnapshot(userId = capturedUserId, entry = it) }
    }
    if (snapshotOrNull == null && !isSessionActive(capturedEpoch, capturedUserId)) {
      return null to CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }
    val syncResult = syncAndValidateCartInternal(
      trigger = CartSyncTrigger.EXPLICIT_CLEAR_OR_REMOVE,
      persistToServer = true,
      allowEmptyReplace = true,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )
    return snapshotOrNull to syncResult
  }

  override suspend fun restoreRemovedItem(snapshot: RemovedCartItemSnapshot): CartSyncResult {
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Authentication required")
    if (snapshot.userId != capturedUserId) {
      return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }

    val restored = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      cartDao.restoreItemWithToppings(
        userId = capturedUserId,
        item = snapshot.entry.item,
        toppings = snapshot.entry.toppings,
      )
    }
    if (restored != true) {
      return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }
    return syncAndValidateCartInternal(
      trigger = CartSyncTrigger.EXPLICIT_MUTATION,
      persistToServer = true,
      allowEmptyReplace = false,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )
  }

  private fun primaryProductImageUrl(product: ProductDto): String? =
    product.images
      .filter { it.status == ProductImageStatus.ACTIVE }
      .minByOrNull { it.sortOrder }
      ?.publicUrl
      ?.takeIf { it.isNotBlank() }
      ?: product.images.firstOrNull()?.publicUrl?.takeIf { it.isNotBlank() }

  override suspend fun clearCart(): CartSyncResult {
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Authentication required")

    val cleared = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      cartDao.clearCartForUser(capturedUserId)
      true
    }
    if (cleared != true) {
      return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }
    return syncAndValidateCartInternal(
      trigger = CartSyncTrigger.EXPLICIT_CLEAR_OR_REMOVE,
      persistToServer = true,
      allowEmptyReplace = true,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )
  }

  override suspend fun bootstrapFromServer(): CartSyncResult {
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartSyncResult(isOffline = false, unavailableCount = 0)
    val cachedLocal = cartDao.getCartItemsWithToppingsOnce(capturedUserId)
    val action = domainBridge.planCartSync(
      CartSyncPlanRequest(
        activeSession = activeSessionSnapshot(),
        capturedSession = SessionIdentitySnapshot(epoch = capturedEpoch, userId = capturedUserId),
        trigger = CartSyncTrigger.PASSIVE_BOOTSTRAP,
        localItemCount = cachedLocal.size,
        persistToServer = true,
        allowEmptyReplace = false,
      ),
    )
    if (action == CartSyncAction.DISCARD_STALE_SESSION) {
      return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }

    return try {
      val serverCart = coroutineScope {
        coroutineContext[Job]?.let { job ->
          sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
        }
        apiClient.getCart()
      }
      val mapped = mapServerCartToEntities(capturedUserId, serverCart)
      val committed = commitIfSessionMatches(capturedEpoch, capturedUserId) {
        cartDao.replaceAllItemsForUser(capturedUserId, mapped)
        CartSyncResult(
          isOffline = false,
          unavailableCount = serverCart.unavailableCount,
        )
      }
      committed ?: CartSyncResult(
        isOffline = false,
        unavailableCount = 0,
        errorMessage = "Stale session discarded",
      )
    } catch (ce: CancellationException) {
      currentCoroutineContext().ensureActive()
      CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = ce.message)
    } catch (t: Throwable) {
      val localUnavailable = if (isSessionActive(capturedEpoch, capturedUserId)) {
        cachedLocal.count { !it.item.isAvailable }
      } else {
        0
      }
      CartSyncResult(
        isOffline = true,
        unavailableCount = localUnavailable,
        errorMessage = t.message,
      )
    }
  }

  override suspend fun syncAndValidateCart(persistToServer: Boolean): CartSyncResult {
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartSyncResult(isOffline = false, unavailableCount = 0)
    return syncAndValidateCartInternal(
      trigger = CartSyncTrigger.PERIODIC_VALIDATE,
      persistToServer = persistToServer,
      allowEmptyReplace = false,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )
  }

  private suspend fun syncAndValidateCartInternal(
    trigger: CartSyncTrigger,
    persistToServer: Boolean,
    allowEmptyReplace: Boolean,
    capturedEpoch: Long,
    capturedUserId: String,
  ): CartSyncResult {
    val local = cartDao.getCartItemsWithToppingsOnce(capturedUserId)
    val plannedAction = domainBridge.planCartSync(
      CartSyncPlanRequest(
        activeSession = activeSessionSnapshot(),
        capturedSession = SessionIdentitySnapshot(epoch = capturedEpoch, userId = capturedUserId),
        trigger = trigger,
        localItemCount = local.size,
        persistToServer = persistToServer,
        allowEmptyReplace = allowEmptyReplace,
      ),
    )

    if (plannedAction == CartSyncAction.DISCARD_STALE_SESSION) {
      return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }
    if (plannedAction == CartSyncAction.NO_OP_EMPTY_LOCAL) {
      return CartSyncResult(isOffline = false, unavailableCount = 0)
    }

    return try {
      val inputs by lazy {
        local.map { entry ->
          CartItemInputDto(
            id = entry.item.id,
            productId = entry.item.productId,
            variantId = entry.item.variantId,
            crustId = entry.item.crustId,
            toppingIds = entry.toppings.map { it.toppingId }.sorted(),
            quantity = entry.item.quantity,
            clientUnitPricePaise = entry.item.unitPricePaise,
          )
        }
      }
      val serverCart: CartResponseDto = when (plannedAction) {
        CartSyncAction.DISCARD_STALE_SESSION ->
          return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
        CartSyncAction.NO_OP_EMPTY_LOCAL ->
          return CartSyncResult(isOffline = false, unavailableCount = 0)
        CartSyncAction.REPLACE_SERVER_CART_EMPTY -> coroutineScope {
          coroutineContext[Job]?.let { job ->
            sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
          }
          if (!isSessionActive(capturedEpoch, capturedUserId)) {
            throw CancellationException("Session changed before cart clear")
          }
          apiClient.replaceCart(ReplaceCartRequest(emptyList()))
        }
        CartSyncAction.FETCH_SERVER_CART -> coroutineScope {
          coroutineContext[Job]?.let { job ->
            sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
          }
          apiClient.getCart()
        }
        CartSyncAction.REPLACE_SERVER_CART_WITH_VALIDATE_FALLBACK -> coroutineScope {
          coroutineContext[Job]?.let { job ->
            sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
          }
          if (!isSessionActive(capturedEpoch, capturedUserId)) {
            throw CancellationException("Session changed before cart sync")
          }
          try {
            apiClient.replaceCart(ReplaceCartRequest(inputs))
          } catch (apiEx: PizzoraApiException) {
            if (domainBridge.fallbackOnReplaceHttpError(apiEx.statusCode) == CartSyncAction.VALIDATE_CART_ONLY) {
              apiClient.validateCart(ValidateCartRequest(inputs))
            } else {
              throw apiEx
            }
          }
        }
        CartSyncAction.VALIDATE_CART_ONLY -> coroutineScope {
          coroutineContext[Job]?.let { job ->
            sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
          }
          if (!isSessionActive(capturedEpoch, capturedUserId)) {
            throw CancellationException("Session changed before cart validate")
          }
          apiClient.validateCart(ValidateCartRequest(inputs))
        }
      }

      val mapped = mapServerCartToEntities(capturedUserId, serverCart)
      val committed = commitIfSessionMatches(capturedEpoch, capturedUserId) {
        cartDao.replaceAllItemsForUser(capturedUserId, mapped)
        CartSyncResult(
          isOffline = false,
          unavailableCount = serverCart.unavailableCount,
        )
      }
      committed ?: CartSyncResult(
        isOffline = false,
        unavailableCount = 0,
        errorMessage = "Stale session discarded",
      )
    } catch (ce: CancellationException) {
      currentCoroutineContext().ensureActive()
      CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = ce.message)
    } catch (t: Throwable) {
      val localUnavailable = if (isSessionActive(capturedEpoch, capturedUserId)) {
        local.count { !it.item.isAvailable }
      } else {
        0
      }
      CartSyncResult(
        isOffline = true,
        unavailableCount = localUnavailable,
        errorMessage = t.message,
      )
    }
  }

  private fun mapServerCartToEntities(
    userId: String,
    serverCart: CartResponseDto,
  ): List<Pair<CartItemEntity, List<CartItemToppingEntity>>> {
    val now = System.currentTimeMillis()
    return serverCart.items.mapIndexed { idx, view ->
      val issueText = view.issues.joinToString(" • ") { it.message }.ifBlank { null }
      val itemEntity = CartItemEntity(
        id = view.id,
        userId = userId,
        productId = view.productId,
        productName = view.productName,
        imageUrl = view.imageUrl,
        variantId = view.variantId,
        sizeCode = view.sizeCode?.name ?: "UNKNOWN",
        crustId = view.crustId,
        crustName = view.crustName,
        unitPricePaise = view.unitPricePaise,
        quantity = view.quantity,
        isAvailable = view.isAvailable,
        unavailableReason = issueText,
        updatedAtMs = now - idx,
      )
      val toppingEntities = view.toppings.map { top ->
        CartItemToppingEntity(
          cartItemId = view.id,
          toppingId = top.toppingId,
          toppingName = top.name,
          extraPricePaise = top.extraPricePaise,
        )
      }
      itemEntity to toppingEntities
    }
  }
}
