package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    CartItemEntity::class,
    CartItemToppingEntity::class,
    CachedProductEntity::class,
    CachedMenuSnapshotEntity::class,
    NotificationInboxEntity::class,
  ],
  version = 3,
  exportSchema = true,
)
abstract class PizzoraDatabase : RoomDatabase() {
  abstract fun cartDao(): CartDao
  abstract fun catalogCacheDao(): CatalogCacheDao
  abstract fun notificationInboxDao(): NotificationInboxDao

  companion object {
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")

        // Quarantine/delete unowned legacy v2 user-scoped tables (child table before parent).
        db.execSQL("DROP TABLE IF EXISTS `cart_item_toppings`")
        db.execSQL("DROP TABLE IF EXISTS `cart_items`")
        db.execSQL("DROP TABLE IF EXISTS `notification_inbox`")

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `cart_items` (
            `id` TEXT NOT NULL,
            `user_id` TEXT NOT NULL,
            `product_id` TEXT NOT NULL,
            `product_name` TEXT NOT NULL,
            `image_url` TEXT,
            `variant_id` TEXT NOT NULL,
            `size_code` TEXT NOT NULL,
            `crust_id` TEXT NOT NULL,
            `crust_name` TEXT NOT NULL,
            `unit_price_paise` INTEGER NOT NULL,
            `quantity` INTEGER NOT NULL,
            `is_available` INTEGER NOT NULL,
            `unavailable_reason` TEXT,
            `updated_at_ms` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
          )
          """.trimIndent(),
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_cart_items_user_id` ON `cart_items` (`user_id`)",
        )

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `cart_item_toppings` (
            `cart_item_id` TEXT NOT NULL,
            `topping_id` TEXT NOT NULL,
            `topping_name` TEXT NOT NULL,
            `extra_price_paise` INTEGER NOT NULL,
            PRIMARY KEY(`cart_item_id`, `topping_id`),
            FOREIGN KEY(`cart_item_id`) REFERENCES `cart_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
          )
          """.trimIndent(),
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_cart_item_toppings_cart_item_id` ON `cart_item_toppings` (`cart_item_id`)",
        )

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `notification_inbox` (
            `id` TEXT NOT NULL,
            `user_id` TEXT NOT NULL,
            `event_id` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `body` TEXT NOT NULL,
            `category` TEXT NOT NULL,
            `reference_id` TEXT,
            `is_read` INTEGER NOT NULL,
            `created_at_iso` TEXT NOT NULL,
            PRIMARY KEY(`id`)
          )
          """.trimIndent(),
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS `index_notification_inbox_user_id_event_id` ON `notification_inbox` (`user_id`, `event_id`)",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_notification_inbox_user_id` ON `notification_inbox` (`user_id`)",
        )

        db.query("PRAGMA foreign_key_check").use { cursor ->
          if (cursor.moveToFirst()) {
            throw IllegalStateException("Room v2->v3 migration detected foreign key constraint violation.")
          }
        }
      }
    }

    val FOREIGN_KEY_CALLBACK: Callback = object : Callback() {
      override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        if (!db.inTransaction()) {
          db.setForeignKeyConstraintsEnabled(true)
        }
      }
    }

    fun build(context: Context): PizzoraDatabase =
      Room.databaseBuilder(
        context.applicationContext,
        PizzoraDatabase::class.java,
        "pizzora.db",
      )
        .addMigrations(MIGRATION_2_3)
        .addCallback(FOREIGN_KEY_CALLBACK)
        .build()
  }
}
