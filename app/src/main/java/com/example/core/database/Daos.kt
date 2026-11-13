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
  @Query("SELECT * FROM cart_items ORDER BY updated_at_ms DESC")
  fun observeCartItemsWithToppings(): Flow<List<CartItemWithToppings>>

  @Query("SELECT COALESCE(SUM(quantity), 0) FROM cart_items")
  fun observeCartBadgeCount(): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertItem(item: CartItemEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertToppings(toppings: List<CartItemToppingEntity>)

  @Query("DELETE FROM cart_item_toppings WHERE cart_item_id = :cartItemId")
  suspend fun deleteToppingsForItem(cartItemId: String)

  @Transaction
  suspend fun upsertItemWithToppings(
    item: CartItemEntity,
    toppings: List<CartItemToppingEntity>,
  ) {
    upsertItem(item)
    deleteToppingsForItem(item.id)
    if (toppings.isNotEmpty()) {
      insertToppings(toppings)
    }
  }

  @Query("UPDATE cart_items SET quantity = :quantity, updated_at_ms = :updatedAtMs WHERE id = :itemId")
  suspend fun updateQuantity(itemId: String, quantity: Int, updatedAtMs: Long = System.currentTimeMillis())

  @Query("DELETE FROM cart_items WHERE id = :itemId")
  suspend fun deleteItemById(itemId: String)

  @Query("DELETE FROM cart_items")
  suspend fun clearCart()
}

@Dao
interface CatalogCacheDao {
  @Query("SELECT * FROM cached_products ORDER BY category_name ASC, name ASC")
  fun observeCachedProducts(): Flow<List<CachedProductEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun replaceAll(products: List<CachedProductEntity>)

  @Query("DELETE FROM cached_products")
  suspend fun clearAll()

  @Transaction
  suspend fun replaceCatalog(products: List<CachedProductEntity>) {
    clearAll()
    if (products.isNotEmpty()) {
      replaceAll(products)
    }
  }
}

@Dao
interface NotificationInboxDao {
  @Query("SELECT * FROM notification_inbox ORDER BY created_at_iso DESC")
  fun observeNotifications(): Flow<List<NotificationInboxEntity>>

  @Query("SELECT COUNT(*) FROM notification_inbox WHERE is_read = 0")
  fun observeUnreadCount(): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIgnore(items: List<NotificationInboxEntity>)

  @Query("UPDATE notification_inbox SET is_read = 1 WHERE id = :id")
  suspend fun markRead(id: String)

  @Query("DELETE FROM notification_inbox")
  suspend fun clearAll()
}
