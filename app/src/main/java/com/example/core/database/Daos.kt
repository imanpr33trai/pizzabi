package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

enum class CartEditMergeAction {
  UPDATED_IN_PLACE,
  MERGED_INTO_EXISTING,
  NOT_FOUND,
}

data class CartDaoEditResult(
  val action: CartEditMergeAction,
  val resultingItemId: String,
  val finalQuantity: Int,
  val wasQuantityCapped: Boolean,
)

@Dao
interface CartDao {
  @Transaction
  @Query("SELECT * FROM cart_items WHERE user_id = :userId ORDER BY updated_at_ms DESC")
  fun observeCartItemsWithToppings(userId: String): Flow<List<CartItemWithToppings>>

  @Transaction
  @Query("SELECT * FROM cart_items WHERE user_id = :userId ORDER BY updated_at_ms DESC")
  suspend fun getCartItemsWithToppingsOnce(userId: String): List<CartItemWithToppings>

  @Query("SELECT COALESCE(SUM(quantity), 0) FROM cart_items WHERE user_id = :userId")
  fun observeCartBadgeCount(userId: String): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertItem(item: CartItemEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertToppings(toppings: List<CartItemToppingEntity>)

  @Query(
    """
    DELETE FROM cart_item_toppings
    WHERE cart_item_id = :cartItemId
      AND cart_item_id IN (SELECT id FROM cart_items WHERE user_id = :userId)
    """,
  )
  suspend fun deleteToppingsForItemForUser(userId: String, cartItemId: String)

  @Transaction
  suspend fun upsertItemWithToppings(
    item: CartItemEntity,
    toppings: List<CartItemToppingEntity>,
  ) {
    upsertItem(item)
    deleteToppingsForItemForUser(item.userId, item.id)
    if (toppings.isNotEmpty()) {
      insertToppings(toppings)
    }
  }

  /**
   * Atomically replaces an edited cart item's configuration & toppings or merges into an existing
   * matching row (capping combined quantity at 25), leaving all unrelated cart items & child rows untouched.
   */
  @Transaction
  suspend fun replaceOrMergeItemWithToppings(
    userId: String,
    existingLocalId: String,
    updatedItem: CartItemEntity,
    updatedToppings: List<CartItemToppingEntity>,
  ): CartDaoEditResult {
    require(updatedItem.userId == userId) {
      "Updated cart item must belong to active user $userId"
    }
    val requestedQty = updatedItem.quantity.coerceIn(1, 25)
    val sanitizedToppings = updatedToppings
      .distinctBy { it.toppingId }
      .sortedBy { it.toppingId }
    val targetToppingIds = sanitizedToppings.map { it.toppingId }

    val currentItems = getCartItemsWithToppingsOnce(userId)
    currentItems.firstOrNull { it.item.id == existingLocalId }
      ?: return CartDaoEditResult(
        action = CartEditMergeAction.NOT_FOUND,
        resultingItemId = existingLocalId,
        finalQuantity = 0,
        wasQuantityCapped = false,
      )

    val matchingOther = currentItems.firstOrNull { candidate ->
      candidate.item.id != existingLocalId &&
        candidate.item.productId == updatedItem.productId &&
        candidate.item.variantId == updatedItem.variantId &&
        candidate.item.crustId == updatedItem.crustId &&
        candidate.toppings.map { it.toppingId }.distinct().sorted() == targetToppingIds
    }

    if (matchingOther != null) {
      val combinedRaw = matchingOther.item.quantity + requestedQty
      val cappedQty = combinedRaw.coerceAtMost(25)
      val wasCapped = combinedRaw > 25

      deleteToppingsForItemForUser(userId, existingLocalId)
      deleteItemById(userId, existingLocalId)

      val mergedEntity = matchingOther.item.copy(
        productName = updatedItem.productName,
        imageUrl = updatedItem.imageUrl ?: matchingOther.item.imageUrl,
        unitPricePaise = updatedItem.unitPricePaise,
        quantity = cappedQty,
        isAvailable = true,
        unavailableReason = null,
        updatedAtMs = updatedItem.updatedAtMs,
      )
      val mergedToppings = sanitizedToppings.map {
        it.copy(cartItemId = matchingOther.item.id)
      }
      upsertItemWithToppings(mergedEntity, mergedToppings)

      return CartDaoEditResult(
        action = CartEditMergeAction.MERGED_INTO_EXISTING,
        resultingItemId = matchingOther.item.id,
        finalQuantity = cappedQty,
        wasQuantityCapped = wasCapped,
      )
    }

    val normalizedUpdated = updatedItem.copy(
      id = existingLocalId,
      userId = userId,
      quantity = requestedQty,
    )
    val normalizedToppings = sanitizedToppings.map {
      it.copy(cartItemId = existingLocalId)
    }
    upsertItemWithToppings(normalizedUpdated, normalizedToppings)

    return CartDaoEditResult(
      action = CartEditMergeAction.UPDATED_IN_PLACE,
      resultingItemId = existingLocalId,
      finalQuantity = requestedQty,
      wasQuantityCapped = false,
    )
  }

  /**
   * Restores a previously removed cart item (for Undo) without touching unrelated cart rows.
   * If an identical configuration was added concurrently, merges cleanly capped at 25.
   */
  @Transaction
  suspend fun restoreItemWithToppings(
    userId: String,
    item: CartItemEntity,
    toppings: List<CartItemToppingEntity>,
  ): Boolean {
    if (item.userId != userId) return false
    val sanitizedToppings = toppings
      .distinctBy { it.toppingId }
      .sortedBy { it.toppingId }
    val targetToppingIds = sanitizedToppings.map { it.toppingId }
    val currentItems = getCartItemsWithToppingsOnce(userId)

    val matchingExisting = currentItems.firstOrNull { candidate ->
      candidate.item.productId == item.productId &&
        candidate.item.variantId == item.variantId &&
        candidate.item.crustId == item.crustId &&
        candidate.toppings.map { it.toppingId }.distinct().sorted() == targetToppingIds
    }

    if (matchingExisting != null) {
      val mergedQty = (matchingExisting.item.quantity + item.quantity.coerceIn(1, 25)).coerceAtMost(25)
      updateQuantity(
        userId = userId,
        itemId = matchingExisting.item.id,
        quantity = mergedQty,
        updatedAtMs = System.currentTimeMillis(),
      )
      return true
    }

    val safeEntity = item.copy(
      quantity = item.quantity.coerceIn(1, 25),
      updatedAtMs = System.currentTimeMillis(),
    )
    val safeToppings = sanitizedToppings.map { it.copy(cartItemId = safeEntity.id) }
    upsertItemWithToppings(safeEntity, safeToppings)
    return true
  }

  @Transaction
  suspend fun replaceAllItemsForUser(
    userId: String,
    entries: List<Pair<CartItemEntity, List<CartItemToppingEntity>>>,
  ) {
    require(entries.all { it.first.userId == userId }) {
      "All cart items must belong to active user $userId"
    }
    clearCartForUser(userId)
    entries.forEach { (item, toppings) ->
      upsertItemWithToppings(item, toppings)
    }
  }

  @Query(
    """
    UPDATE cart_items
    SET quantity = :quantity, updated_at_ms = :updatedAtMs
    WHERE id = :itemId AND user_id = :userId
    """,
  )
  suspend fun updateQuantity(
    userId: String,
    itemId: String,
    quantity: Int,
    updatedAtMs: Long = System.currentTimeMillis(),
  )

  @Query("DELETE FROM cart_items WHERE id = :itemId AND user_id = :userId")
  suspend fun deleteItemById(userId: String, itemId: String)

  @Query("DELETE FROM cart_items WHERE user_id = :userId")
  suspend fun clearCartForUser(userId: String)

  /**
   * Session-boundary wipe invoked exclusively by SessionScopeCleaner during login/logout/refresh-failure.
   */
  @Query("DELETE FROM cart_items")
  suspend fun clearAllUserCarts()
}

@Dao
interface CatalogCacheDao {
  @Query("SELECT * FROM cached_products ORDER BY category_name ASC, name ASC")
  fun observeCachedProducts(): Flow<List<CachedProductEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun replaceAll(products: List<CachedProductEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMenuSnapshot(snapshot: CachedMenuSnapshotEntity)

  @Query("SELECT * FROM cached_menu_snapshot WHERE id = 1")
  suspend fun getMenuSnapshot(): CachedMenuSnapshotEntity?

  @Query("DELETE FROM cached_products")
  suspend fun clearAll()

  @Transaction
  suspend fun replaceCatalog(products: List<CachedProductEntity>) {
    clearAll()
    if (products.isNotEmpty()) {
      replaceAll(products)
    }
  }

  @Transaction
  suspend fun replaceCatalogWithSnapshot(
    products: List<CachedProductEntity>,
    snapshot: CachedMenuSnapshotEntity,
  ) {
    replaceCatalog(products)
    upsertMenuSnapshot(snapshot)
  }
}

@Dao
interface NotificationInboxDao {
  @Query("SELECT * FROM notification_inbox WHERE user_id = :userId ORDER BY created_at_iso DESC")
  fun observeNotifications(userId: String): Flow<List<NotificationInboxEntity>>

  @Query("SELECT COUNT(*) FROM notification_inbox WHERE user_id = :userId AND is_read = 0")
  fun observeUnreadCount(userId: String): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIgnore(items: List<NotificationInboxEntity>)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIgnoreReturningIds(items: List<NotificationInboxEntity>): List<Long>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(items: List<NotificationInboxEntity>)

  @Query("UPDATE notification_inbox SET is_read = 1 WHERE user_id = :userId AND id = :id")
  suspend fun markRead(userId: String, id: String)

  @Query("UPDATE notification_inbox SET is_read = 1 WHERE user_id = :userId")
  suspend fun markAllRead(userId: String)

  @Query("DELETE FROM notification_inbox WHERE user_id = :userId")
  suspend fun clearForUser(userId: String)

  /**
   * Session-boundary wipe invoked exclusively by SessionScopeCleaner during login/logout/refresh-failure.
   */
  @Query("DELETE FROM notification_inbox")
  suspend fun clearAll()
}
