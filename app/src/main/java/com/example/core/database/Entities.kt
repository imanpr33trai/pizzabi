package com.example.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "cart_items")
data class CartItemEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "product_id") val productId: String,
  @ColumnInfo(name = "product_name") val productName: String,
  @ColumnInfo(name = "variant_id") val variantId: String,
  @ColumnInfo(name = "size_code") val sizeCode: String,
  @ColumnInfo(name = "crust_id") val crustId: String,
  @ColumnInfo(name = "crust_name") val crustName: String,
  @ColumnInfo(name = "unit_price_paise") val unitPricePaise: Long,
  val quantity: Int,
  @ColumnInfo(name = "is_available") val isAvailable: Boolean = true,
  @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "cart_item_toppings",
  primaryKeys = ["cart_item_id", "topping_id"],
  foreignKeys = [
    ForeignKey(
      entity = CartItemEntity::class,
      parentColumns = ["id"],
      childColumns = ["cart_item_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["cart_item_id"])],
)
data class CartItemToppingEntity(
  @ColumnInfo(name = "cart_item_id") val cartItemId: String,
  @ColumnInfo(name = "topping_id") val toppingId: String,
  @ColumnInfo(name = "topping_name") val toppingName: String,
  @ColumnInfo(name = "extra_price_paise") val extraPricePaise: Long,
)

data class CartItemWithToppings(
  @Embedded val item: CartItemEntity,
  @Relation(
    parentColumn = "id",
    entityColumn = "cart_item_id",
  )
  val toppings: List<CartItemToppingEntity>,
)

@Entity(tableName = "cached_products")
data class CachedProductEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "category_id") val categoryId: String,
  @ColumnInfo(name = "category_name") val categoryName: String,
  val name: String,
  val description: String,
  @ColumnInfo(name = "is_veg") val isVeg: Boolean,
  @ColumnInfo(name = "image_url") val imageUrl: String?,
  @ColumnInfo(name = "min_price_paise") val minPricePaise: Long,
  @ColumnInfo(name = "payload_json") val payloadJson: String,
  @ColumnInfo(name = "cached_at_ms") val cachedAtMs: Long,
)

@Entity(
  tableName = "notification_inbox",
  indices = [Index(value = ["event_id"], unique = true)],
)
data class NotificationInboxEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "event_id") val eventId: String,
  val title: String,
  val body: String,
  val category: String,
  @ColumnInfo(name = "reference_id") val referenceId: String?,
  @ColumnInfo(name = "is_read") val isRead: Boolean,
  @ColumnInfo(name = "created_at_iso") val createdAtIso: String,
)
