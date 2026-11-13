package com.example.cart

import com.example.core.database.CartDao
import com.example.core.database.CartItemEntity
import com.example.core.database.CartItemToppingEntity
import com.example.core.database.CartItemWithToppings
import com.example.core.network.PizzoraApiClient
import com.example.core.network.PizzoraApiException
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
import kotlinx.coroutines.flow.Flow

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
  suspend fun syncAndValidateCart(persistToServer: Boolean = true): CartSyncResult
}

class NetworkCartRepository(
  private val apiClient: PizzoraApiClient,
  private val cartDao: CartDao,
) : CartRepository {

  override fun observeCart(): Flow<List<CartItemWithToppings>> =
    cartDao.observeCartItemsWithToppings()

  override fun observeCartBadgeCount(): Flow<Int> =
    cartDao.observeCartBadgeCount()

  override suspend fun addCustomizedItem(
    product: ProductDto,
    variant: ProductVariantDto,
    crust: CrustOptionDto,
    toppings: List<ToppingDto>,
    quantity: Int,
  ): CartSyncResult {
    require(quantity > 0) { "Quantity must be positive" }
    val distinctToppings = toppings.distinctBy { it.id }.sortedBy { it.id }
    val signatureToppingIds = distinctToppings.map { it.id }
    val unitPricePaise = variant.pricePaise + crust.extraPricePaise + distinctToppings.sumOf { it.extraPricePaise }
    val now = System.currentTimeMillis()

    val currentItems = cartDao.getCartItemsWithToppingsOnce()
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
    return syncAndValidateCart(persistToServer = true)
  }

  override suspend fun updateQuantity(itemId: String, newQuantity: Int): CartSyncResult {
    if (newQuantity <= 0) {
      return removeItem(itemId)
    }
    cartDao.updateQuantity(itemId, newQuantity.coerceAtMost(25), System.currentTimeMillis())
    return syncAndValidateCart(persistToServer = true)
  }

  override suspend fun removeItem(itemId: String): CartSyncResult {
    cartDao.deleteItemById(itemId)
    return syncAndValidateCart(persistToServer = true)
  }

  override suspend fun clearCart(): CartSyncResult {
    cartDao.clearCart()
    return syncAndValidateCart(persistToServer = true)
  }

  override suspend fun syncAndValidateCart(persistToServer: Boolean): CartSyncResult {
    val local = cartDao.getCartItemsWithToppingsOnce()
    return try {
      val serverCart: CartResponseDto = if (local.isEmpty()) {
        if (persistToServer) apiClient.getCart()
        else return CartSyncResult(isOffline = false, unavailableCount = 0)
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

      val now = System.currentTimeMillis()
      val mapped = serverCart.items.mapIndexed { idx, view ->
        val issueText = view.issues.joinToString(" • ") { it.message }.ifBlank { null }
        val itemEntity = CartItemEntity(
          id = view.id,
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
      cartDao.replaceAllItems(mapped)

      CartSyncResult(
        isOffline = false,
        unavailableCount = serverCart.unavailableCount,
      )
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      val localUnavailable = local.count { !it.item.isAvailable }
      CartSyncResult(
        isOffline = true,
        unavailableCount = localUnavailable,
        errorMessage = t.message,
      )
    }
  }
}
