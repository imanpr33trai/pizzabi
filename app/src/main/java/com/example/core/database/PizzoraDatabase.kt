package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    CartItemEntity::class,
    CartItemToppingEntity::class,
    CachedProductEntity::class,
    NotificationInboxEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
abstract class PizzoraDatabase : RoomDatabase() {
  abstract fun cartDao(): CartDao
  abstract fun catalogCacheDao(): CatalogCacheDao
  abstract fun notificationInboxDao(): NotificationInboxDao

  companion object {
    fun build(context: Context): PizzoraDatabase =
      Room.databaseBuilder(
        context.applicationContext,
        PizzoraDatabase::class.java,
        "pizzora.db",
      ).build()
  }
}
