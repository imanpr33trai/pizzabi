package com.example.cart

import com.example.core.database.CartDao
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
import com.pizzora.contracts.CartItemCustomizationSummaryDto
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartToppingInputDto
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.PizzaBuilderEngine
import com.pizzora.contracts.PizzaPriceBreakdownDto
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.ToppingPlacement
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
import kotlinx.serialization.json.Json

internal val cartPersistenceJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

fun CartItemWithToppings.customizationSummaryOrNull(
  json: Json = cartPersistenceJson,
): CartItemCustomizationSummaryDto? =
  item.customizationJson?.takeIf { it.isNotBlank() }?.let { raw ->
    runCatching { json.decodeFromString<CartItemCustomizationSummaryDto>(raw) }.getOrNull()
  }

fun CartItemWithToppings.priceBreakdownOrNull(
  json: Json = cartPersistenceJson,
): PizzaPriceBreakdownDto? =
  item.priceBreakdownJson?.takeIf { it.isNotBlank() }?.let { raw ->
    runCatching { json.decodeFromString<PizzaPriceBreakdownDto>(raw) }.getOrNull()
  }

fun CartItemWithToppings.removedIncludedToppingIdsList(
  json: Json = cartPersistenceJson,
): List<String> {
  val decoded = item.removedIncludedToppingIdsJson
    .takeIf { it.isNotBlank() && it != "[]" }
    ?.let { raw -> runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull() }
    .orEmpty()
  if (decoded.isNotEmpty()) return decoded
  return extractRemovedIdsFromSignature(item.configurationSignature)
}

fun CartItemWithToppings.toCartItemInputDto(
  json: Json = cartPersistenceJson,
): CartItemInputDto {
  val toppingSelections = toppings.map { top ->
    val placement = runCatching { ToppingPlacement.valueOf(top.placement) }
      .getOrDefault(ToppingPlacement.WHOLE)
    CartToppingInputDto(
      toppingId = top.toppingId,
      placement = placement,
      quantity = top.quantity.coerceAtLeast(1),
    )
  }
  val hasAdvancedToppings = toppingSelections.any {
    it.placement != ToppingPlacement.WHOLE || it.quantity != 1
  }
  return CartItemInputDto(
    id = item.id,
    productId = item.productId,
    variantId = item.variantId,
    crustId = item.crustId,
    toppingIds = if (!hasAdvancedToppings) {
      toppings.map { it.toppingId }.sorted()
    } else {
      emptyList()
    },
    quantity = item.quantity,
    clientUnitPricePaise = item.unitPricePaise,
    cheeseId = item.cheeseId,
    extraCheese = item.extraCheese,
    sauceId = item.sauceId,
    extraSauce = item.extraSauce,
    isHalfAndHalf = item.isHalfAndHalf,
    rightProductId = item.rightProductId,
    rightVariantId = item.rightVariantId,
    rightSauceId = item.rightSauceId,
    removedIncludedToppingIds = removedIncludedToppingIdsList(json),
    toppingSelections = toppingSelections,
  )
}

private fun extractRemovedIdsFromSignature(signature: String): List<String> {
  if (signature.isBlank()) return emptyList()
  val parts = signature.split('|')
  if (parts.size < 13) return emptyList()
  val removedField = parts[11].trim()
  if (removedField.isEmpty()) return emptyList()
  return removedField.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

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

  suspend fun addConfiguredPizza(
    input: CartItemInputDto,
    menu: PublicMenuResponse,
  ): CartSyncResult = error("addConfiguredPizza is not implemented")

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
    val input = CartItemInputDto(
      productId = product.id,
      variantId = variant.id,
      crustId = crust.id,
      toppingIds = signatureToppingIds,
      quantity = quantity,
      cheeseId = product.defaultCheeseId,
      sauceId = product.defaultSauceId,
    )
    val signature = domainBridge.computePizzaConfigurationSignature(input, null)
    val unitPricePaise = variant.pricePaise + crust.extraPricePaise + distinctToppings.sumOf { it.extraPricePaise }
    val now = System.currentTimeMillis()

    val written = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      val currentItems = cartDao.getCartItemsWithToppingsOnce(capturedUserId)
      val matching = currentItems.firstOrNull { existing ->
        val existingSig = existing.item.configurationSignature.takeIf { it.isNotBlank() }
          ?: domainBridge.computePizzaConfigurationSignature(existing.toCartItemInputDto(), null)
        existingSig == signature ||
          (
            !existing.item.isHalfAndHalf &&
              existing.item.productId == product.id &&
              existing.item.variantId == variant.id &&
              existing.item.crustId == crust.id &&
              existing.item.cheeseId == product.defaultCheeseId &&
              !existing.item.extraCheese &&
              existing.item.sauceId == product.defaultSauceId &&
              !existing.item.extraSauce &&
              existing.toppings.all { it.placement == ToppingPlacement.WHOLE.name && it.quantity == 1 } &&
              existing.toppings.map { it.toppingId }.sorted() == signatureToppingIds
            )
      }

      val itemId = matching?.item?.id ?: UUID.randomUUID().toString()
      val nextQty = ((matching?.item?.quantity ?: 0) + quantity).coerceAtMost(PizzaBuilderEngine.MAX_ITEM_QUANTITY)

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
        cheeseId = product.defaultCheeseId,
        sauceId = product.defaultSauceId,
        configurationSignature = signature,
      )
      val toppingEntities = distinctToppings.map { topping ->
        CartItemToppingEntity(
          cartItemId = itemId,
          toppingId = topping.id,
          toppingName = topping.name,
          extraPricePaise = topping.extraPricePaise,
          placement = ToppingPlacement.WHOLE.name,
          quantity = 1,
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

  override suspend fun addConfiguredPizza(
    input: CartItemInputDto,
    menu: PublicMenuResponse,
  ): CartSyncResult {
    require(input.quantity > 0) { "Quantity must be positive" }
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Authentication required")

    val evaluation = domainBridge.evaluatePizzaCustomization(input, menu)
    if (!evaluation.isValid) {
      return CartSyncResult(
        isOffline = false,
        unavailableCount = 1,
        errorMessage = evaluation.issues.firstOrNull()?.message ?: "Invalid pizza customization.",
      )
    }

    val leftProduct = menu.products.firstOrNull { it.id == input.productId }
      ?: return CartSyncResult(isOffline = false, unavailableCount = 1, errorMessage = "Selected pizza not found.")
    val crust = menu.crusts.firstOrNull { it.id == input.crustId }
      ?: return CartSyncResult(isOffline = false, unavailableCount = 1, errorMessage = "Selected crust not found.")

    val signature = evaluation.configurationSignature
    val removedIdsJson = cartPersistenceJson.encodeToString(
      input.removedIncludedToppingIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted(),
    )
    val customizationJson = cartPersistenceJson.encodeToString(evaluation.customization)
    val breakdownJson = cartPersistenceJson.encodeToString(evaluation.priceBreakdown)
    val displayTitle = if (input.isHalfAndHalf && !evaluation.customization.rightProductName.isNullOrBlank()) {
      "Half & Half: ${leftProduct.name} / ${evaluation.customization.rightProductName}"
    } else {
      leftProduct.name
    }
    val now = System.currentTimeMillis()

    val written = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      val currentItems = cartDao.getCartItemsWithToppingsOnce(capturedUserId)
      val matching = currentItems.firstOrNull { existing ->
        val existingSig = existing.item.configurationSignature.takeIf { it.isNotBlank() }
          ?: domainBridge.computePizzaConfigurationSignature(existing.toCartItemInputDto(), menu)
        existingSig == signature
      }

      val itemId = matching?.item?.id ?: UUID.randomUUID().toString()
      val nextQty = ((matching?.item?.quantity ?: 0) + input.quantity)
        .coerceAtMost(PizzaBuilderEngine.MAX_ITEM_QUANTITY)

      val unitBreakdown = evaluation.priceBreakdown.copy(
        lineTotalPaise = evaluation.unitPricePaise * nextQty.toLong(),
      )

      val entity = CartItemEntity(
        id = itemId,
        userId = capturedUserId,
        productId = leftProduct.id,
        productName = displayTitle,
        imageUrl = leftProduct.images.firstOrNull()?.publicUrl,
        variantId = input.variantId,
        sizeCode = (evaluation.effectiveSizeCode ?: leftProduct.variants.firstOrNull { it.id == input.variantId }?.sizeCode)?.name
          ?: "UNKNOWN",
        crustId = crust.id,
        crustName = crust.name,
        unitPricePaise = evaluation.unitPricePaise,
        quantity = nextQty,
        isAvailable = true,
        unavailableReason = null,
        updatedAtMs = now,
        cheeseId = evaluation.effectiveCheeseId,
        extraCheese = input.extraCheese,
        sauceId = evaluation.effectiveSauceId,
        extraSauce = input.extraSauce,
        isHalfAndHalf = input.isHalfAndHalf,
        rightProductId = if (input.isHalfAndHalf) input.rightProductId else null,
        rightProductName = evaluation.customization.rightProductName,
        rightVariantId = if (input.isHalfAndHalf) input.rightVariantId else null,
        rightSauceId = evaluation.effectiveRightSauceId,
        removedIncludedToppingIdsJson = removedIdsJson,
        customizationJson = customizationJson,
        priceBreakdownJson = cartPersistenceJson.encodeToString(unitBreakdown),
        configurationSignature = signature,
      )
      val toppingEntities = evaluation.toppings.map { top ->
        CartItemToppingEntity(
          cartItemId = itemId,
          toppingId = top.toppingId,
          toppingName = top.name,
          extraPricePaise = top.extraPricePaise,
          placement = top.placement.name,
          quantity = top.quantity,
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

  override suspend fun updateQuantity(itemId: String, newQuantity: Int): CartSyncResult {
    if (newQuantity <= 0) {
      return removeItem(itemId)
    }
    val (capturedEpoch, capturedUserId) = captureSession()
      ?: return CartSyncResult(isOffline = false, unavailableCount = 0, errorMessage = "Authentication required")

    val updated = commitIfSessionMatches(capturedEpoch, capturedUserId) {
      cartDao.updateQuantity(
        capturedUserId,
        itemId,
        newQuantity.coerceAtMost(PizzaBuilderEngine.MAX_ITEM_QUANTITY),
        System.currentTimeMillis(),
      )
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
      trigger = CartSyncTrigger.EXPLICIT_CLEAR_OR_REMOVE,
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
      val mapped = mapServerCartToEntities(capturedUserId, serverCart, cachedLocal)
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
        local.map { entry -> entry.toCartItemInputDto(cartPersistenceJson) }
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

      val mapped = mapServerCartToEntities(capturedUserId, serverCart, local)
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
    previousLocal: List<CartItemWithToppings> = emptyList(),
  ): List<Pair<CartItemEntity, List<CartItemToppingEntity>>> {
    val now = System.currentTimeMillis()
    val localBySignature = previousLocal
      .filter { it.item.configurationSignature.isNotBlank() }
      .associateBy { it.item.configurationSignature }
    val localById = previousLocal.associateBy { it.item.id }

    return serverCart.items.mapIndexed { idx, view ->
      val issueText = view.issues.joinToString(" • ") { it.message }.ifBlank { null }
      val cust = view.customization
      val signature = cust?.configurationSignature.orEmpty()
      val matchedLocal = signature.takeIf { it.isNotBlank() }?.let { localBySignature[it] }
        ?: localById[view.id]
      val preservedRemovedJson = matchedLocal?.item?.removedIncludedToppingIdsJson
        ?.takeIf { it.isNotBlank() && it != "[]" }
        ?: cartPersistenceJson.encodeToString(extractRemovedIdsFromSignature(signature))

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
        cheeseId = cust?.cheeseId ?: matchedLocal?.item?.cheeseId,
        extraCheese = cust?.extraCheese ?: matchedLocal?.item?.extraCheese ?: false,
        sauceId = cust?.sauceId ?: matchedLocal?.item?.sauceId,
        extraSauce = cust?.extraSauce ?: matchedLocal?.item?.extraSauce ?: false,
        isHalfAndHalf = cust?.isHalfAndHalf ?: matchedLocal?.item?.isHalfAndHalf ?: false,
        rightProductId = cust?.rightProductId ?: matchedLocal?.item?.rightProductId,
        rightProductName = cust?.rightProductName ?: matchedLocal?.item?.rightProductName,
        rightVariantId = cust?.rightVariantId ?: matchedLocal?.item?.rightVariantId,
        rightSauceId = cust?.rightSauceId ?: matchedLocal?.item?.rightSauceId,
        removedIncludedToppingIdsJson = preservedRemovedJson,
        customizationJson = cust?.let { cartPersistenceJson.encodeToString(it) }
          ?: matchedLocal?.item?.customizationJson,
        priceBreakdownJson = view.priceBreakdown?.let { cartPersistenceJson.encodeToString(it) }
          ?: matchedLocal?.item?.priceBreakdownJson,
        configurationSignature = signature.ifBlank { matchedLocal?.item?.configurationSignature.orEmpty() },
      )
      val toppingEntities = view.toppings.map { top ->
        CartItemToppingEntity(
          cartItemId = view.id,
          toppingId = top.toppingId,
          toppingName = top.name,
          extraPricePaise = top.extraPricePaise,
          placement = top.placement.name,
          quantity = top.quantity,
        )
      }
      itemEntity to toppingEntities
    }
  }
}
