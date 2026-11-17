package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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
