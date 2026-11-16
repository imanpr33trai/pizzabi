package com.example.cart

import com.example.core.database.CartDao
import com.example.core.database.CartItemEntity
import com.example.core.database.CartItemToppingEntity
import com.example.core.database.CartItemWithToppings
import com.example.core.network.PizzoraApiClient
import com.example.core.network.PizzoraApiException
import com.example.core.security.SessionTransitionCoordinator
import com.example.core.security.TokenStorage
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.ProductDto
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

  suspend fun updateQuantity(itemId: String, newQuantity: Int): CartSyncResult
  suspend fun removeItem(itemId: String): CartSyncResult
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
) : CartRepository {

  private fun captureSession(): Pair<Long, String>? {
    val snapshot = sessionCoordinator?.currentSnapshot()
    val userId = snapshot?.userId ?: tokenStorage.currentSession()?.user?.id ?: return null
    val epoch = snapshot?.epoch ?: 0L
    return epoch to userId
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
        imageUrl = product.images.firstOrNull()?.publicUrl,
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
      persistToServer = true,
      allowEmptyReplace = false,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
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
      persistToServer = true,
      allowEmptyReplace = false,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )
  }

  override suspend fun removeItem(itemId: String): CartSyncResult {
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Authentication required")

    val removed = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      cartDao.deleteItemById(capturedUserId, itemId)
      true
    }
    if (removed != true) {
      return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }
    return syncAndValidateCartInternal(
      persistToServer = true,
      allowEmptyReplace = true,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )
  }

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
      persistToServer = persistToServer,
      allowEmptyReplace = false,
      capturedEpoch = capturedEpoch,
      capturedUserId = capturedUserId,
    )
  }

  private suspend fun syncAndValidateCartInternal(
    persistToServer: Boolean,
    allowEmptyReplace: Boolean,
    capturedEpoch: Long,
    capturedUserId: String,
  ): CartSyncResult {
    if (!isSessionActive(capturedEpoch, capturedUserId)) {
      return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Stale session discarded")
    }
    val local = cartDao.getCartItemsWithToppingsOnce(capturedUserId)
    return try {
      val serverCart: CartResponseDto = if (local.isEmpty()) {
        when {
          allowEmptyReplace && persistToServer -> coroutineScope {
            coroutineContext[Job]?.let { job ->
              sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
            }
            if (!isSessionActive(capturedEpoch, capturedUserId)) {
              throw CancellationException("Session changed before cart clear")
            }
            apiClient.replaceCart(ReplaceCartRequest(emptyList()))
          }
          persistToServer -> coroutineScope {
            coroutineContext[Job]?.let { job ->
              sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
            }
            apiClient.getCart()
          }
          else -> return CartSyncResult(isOffline = false, unavailableCount = 0)
        }
      } else {
        val inputs = local.map { entry ->
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
        coroutineScope {
          coroutineContext[Job]?.let { job ->
            sessionCoordinator?.registerInFlightJob(capturedEpoch, job)
          }
          if (!isSessionActive(capturedEpoch, capturedUserId)) {
            throw CancellationException("Session changed before cart sync")
          }
          if (persistToServer) {
            try {
              apiClient.replaceCart(ReplaceCartRequest(inputs))
            } catch (apiEx: PizzoraApiException) {
              if (apiEx.statusCode == 422) {
                apiClient.validateCart(ValidateCartRequest(inputs))
              } else {
                throw apiEx
              }
            }
          } else {
            apiClient.validateCart(ValidateCartRequest(inputs))
          }
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
