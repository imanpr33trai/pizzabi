package com.example

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.CartItemEntity
import com.example.core.database.CartItemToppingEntity
import com.example.core.database.NotificationInboxEntity
import com.example.core.database.PizzoraDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1RoomMigrationTest {

  private lateinit var context: Context
  private lateinit var dbName: String
  private var migratedDb: PizzoraDatabase? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    dbName = "pizzora_migration_test_${System.nanoTime()}.db"
  }

  @After
  fun tearDown() {
    migratedDb?.close()
    migratedDb = null
    File(context.getDatabasePath(dbName).path).delete()
    File("${context.getDatabasePath(dbName).path}-wal").delete()
    File("${context.getDatabasePath(dbName).path}-shm").delete()
  }

  private fun createAuthenticV2DatabaseWithSeedData() {
    val config = SupportSQLiteOpenHelper.Configuration.builder(context)
      .name(dbName)
      .callback(
        object : SupportSQLiteOpenHelper.Callback(2) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `cart_items` (`id` TEXT NOT NULL, `product_id` TEXT NOT NULL, `product_name` TEXT NOT NULL, `image_url` TEXT, `variant_id` TEXT NOT NULL, `size_code` TEXT NOT NULL, `crust_id` TEXT NOT NULL, `crust_name` TEXT NOT NULL, `unit_price_paise` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `is_available` INTEGER NOT NULL, `unavailable_reason` TEXT, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `cart_item_toppings` (`cart_item_id` TEXT NOT NULL, `topping_id` TEXT NOT NULL, `topping_name` TEXT NOT NULL, `extra_price_paise` INTEGER NOT NULL, PRIMARY KEY(`cart_item_id`, `topping_id`), FOREIGN KEY(`cart_item_id`) REFERENCES `cart_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_cart_item_toppings_cart_item_id` ON `cart_item_toppings` (`cart_item_id`)",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `cached_products` (`id` TEXT NOT NULL, `category_id` TEXT NOT NULL, `category_name` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `is_veg` INTEGER NOT NULL, `image_url` TEXT, `min_price_paise` INTEGER NOT NULL, `payload_json` TEXT NOT NULL, `cached_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `cached_menu_snapshot` (`id` INTEGER NOT NULL, `payload_json` TEXT NOT NULL, `cached_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `notification_inbox` (`id` TEXT NOT NULL, `event_id` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `category` TEXT NOT NULL, `reference_id` TEXT, `is_read` INTEGER NOT NULL, `created_at_iso` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
              "CREATE UNIQUE INDEX IF NOT EXISTS `index_notification_inbox_event_id` ON `notification_inbox` (`event_id`)",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            db.execSQL(
              "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '100c562610cd2d16a1e2b6abdf66f692')",
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        },
      )
      .build()

    val helper = FrameworkSQLiteOpenHelperFactory().create(config)
    helper.writableDatabase.use { db ->
      db.execSQL(
        "INSERT INTO cart_items (id, product_id, product_name, image_url, variant_id, size_code, crust_id, crust_name, unit_price_paise, quantity, is_available, unavailable_reason, updated_at_ms) VALUES ('legacy-ci-1', 'prod-1', 'Margherita', NULL, 'var-1', 'REGULAR', 'crust-1', 'Classic', 29900, 2, 1, NULL, 1700000000000)",
      )
      db.execSQL(
        "INSERT INTO cart_item_toppings (cart_item_id, topping_id, topping_name, extra_price_paise) VALUES ('legacy-ci-1', 'top-1', 'Basil', 2500)",
      )
      db.execSQL(
        "INSERT INTO notification_inbox (id, event_id, title, body, category, reference_id, is_read, created_at_iso) VALUES ('legacy-n-1', 'evt-legacy', 'Old Alert', 'Should be quarantined', 'ORDER', 'ord-old', 0, '2026-11-13T10:00:00Z')",
      )
      db.execSQL(
        "INSERT INTO cached_products (id, category_id, category_name, name, description, is_veg, image_url, min_price_paise, payload_json, cached_at_ms) VALUES ('prod-1', 'cat-1', 'Classics', 'Margherita Verace', 'Wood-fired tomato & basil', 1, NULL, 29900, '{\"id\":\"prod-1\"}', 1700000000000)",
      )
      db.execSQL(
        "INSERT INTO cached_menu_snapshot (id, payload_json, cached_at_ms) VALUES (1, '{\"storeName\":\"PIZZORA\"}', 1700000000000)",
      )
    }
    helper.close()
  }

  @Test
  fun `v2 to v3 migration quarantines unowned cart and inbox rows, preserves catalog cache, and enforces foreign keys`() = runBlocking {
    createAuthenticV2DatabaseWithSeedData()

    val db = Room.databaseBuilder(context, PizzoraDatabase::class.java, dbName)
      .allowMainThreadQueries()
      .addMigrations(PizzoraDatabase.MIGRATION_2_3, PizzoraDatabase.MIGRATION_3_4)
      .addCallback(PizzoraDatabase.FOREIGN_KEY_CALLBACK)
      .build()
    migratedDb = db

    val writable = db.openHelper.writableDatabase

    writable.query("PRAGMA foreign_keys").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(1, cursor.getInt(0))
    }
    writable.query("PRAGMA foreign_key_check").use { cursor ->
      assertFalse("Expected zero foreign_key_check violations", cursor.moveToFirst())
    }

    // Legacy unowned v2 user data must be quarantined/removed
    writable.query("SELECT COUNT(*) FROM cart_items").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0, cursor.getInt(0))
    }
    writable.query("SELECT COUNT(*) FROM cart_item_toppings").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0, cursor.getInt(0))
    }
    writable.query("SELECT COUNT(*) FROM notification_inbox").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0, cursor.getInt(0))
    }

    // Catalog & menu snapshot must survive migration untouched
    val cachedProducts = db.catalogCacheDao().observeCachedProducts().first()
    assertEquals(1, cachedProducts.size)
    assertEquals("Margherita Verace", cachedProducts.first().name)
    val snapshot = db.catalogCacheDao().getMenuSnapshot()
    assertNotNull(snapshot)
    assertEquals("{\"storeName\":\"PIZZORA\"}", snapshot?.payloadJson)

    // User-scoped cart isolation + cascade delete
    val cartDao = db.cartDao()
    cartDao.upsertItemWithToppings(
      CartItemEntity(
        id = "item-a",
        userId = "user-a",
        productId = "prod-1",
        productName = "Margherita Verace",
        variantId = "var-1",
        sizeCode = "REGULAR",
        crustId = "crust-1",
        crustName = "Classic",
        unitPricePaise = 32_400L,
        quantity = 1,
      ),
      listOf(CartItemToppingEntity("item-a", "top-1", "Basil", 2_500L)),
    )

    assertEquals(1, cartDao.observeCartItemsWithToppings("user-a").first().size)
    assertEquals(0, cartDao.observeCartItemsWithToppings("user-b").first().size)

    // Cross-user delete must not affect user-a
    cartDao.deleteItemById("user-b", "item-a")
    assertEquals(1, cartDao.observeCartItemsWithToppings("user-a").first().size)

    // Owner delete cascades to cart_item_toppings
    cartDao.deleteItemById("user-a", "item-a")
    assertEquals(0, cartDao.observeCartItemsWithToppings("user-a").first().size)
    writable.query("SELECT COUNT(*) FROM cart_item_toppings").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0, cursor.getInt(0))
    }

    // Orphan child row insert must fail under PRAGMA foreign_keys = ON
    try {
      cartDao.insertToppings(
        listOf(CartItemToppingEntity("missing-parent", "top-1", "Basil", 2_500L)),
      )
      fail("Expected SQLiteConstraintException for orphan cart_item_toppings row")
    } catch (_: SQLiteConstraintException) {
      // expected
    }

    // Composite (user_id, event_id) index allows same event_id for two distinct users, ignores same-user dup
    val notifDao = db.notificationInboxDao()
    notifDao.insertIgnore(
      listOf(
        NotificationInboxEntity(
          id = "n-a-1",
          userId = "user-a",
          eventId = "evt-shared",
          title = "User A Alert",
          body = "A body",
          category = "ORDER",
          referenceId = "ord-a",
          isRead = false,
          createdAtIso = "2026-11-16T08:00:00Z",
        ),
        NotificationInboxEntity(
          id = "n-a-dup",
          userId = "user-a",
          eventId = "evt-shared",
          title = "User A Dup",
          body = "Ignored",
          category = "ORDER",
          referenceId = "ord-a",
          isRead = false,
          createdAtIso = "2026-11-16T08:01:00Z",
        ),
        NotificationInboxEntity(
          id = "n-b-1",
          userId = "user-b",
          eventId = "evt-shared",
          title = "User B Alert",
          body = "B body",
          category = "ORDER",
          referenceId = "ord-b",
          isRead = false,
          createdAtIso = "2026-11-16T08:02:00Z",
        ),
      ),
    )
    assertEquals(1, notifDao.observeNotifications("user-a").first().size)
    assertEquals(1, notifDao.observeNotifications("user-b").first().size)
  }

  private fun createAuthenticV3DatabaseWithSeedData() {
    val config = SupportSQLiteOpenHelper.Configuration.builder(context)
      .name(dbName)
      .callback(
        object : SupportSQLiteOpenHelper.Callback(3) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `cart_items` (`id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `product_id` TEXT NOT NULL, `product_name` TEXT NOT NULL, `image_url` TEXT, `variant_id` TEXT NOT NULL, `size_code` TEXT NOT NULL, `crust_id` TEXT NOT NULL, `crust_name` TEXT NOT NULL, `unit_price_paise` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `is_available` INTEGER NOT NULL, `unavailable_reason` TEXT, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_cart_items_user_id` ON `cart_items` (`user_id`)",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `cart_item_toppings` (`cart_item_id` TEXT NOT NULL, `topping_id` TEXT NOT NULL, `topping_name` TEXT NOT NULL, `extra_price_paise` INTEGER NOT NULL, PRIMARY KEY(`cart_item_id`, `topping_id`), FOREIGN KEY(`cart_item_id`) REFERENCES `cart_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_cart_item_toppings_cart_item_id` ON `cart_item_toppings` (`cart_item_id`)",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `cached_products` (`id` TEXT NOT NULL, `category_id` TEXT NOT NULL, `category_name` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `is_veg` INTEGER NOT NULL, `image_url` TEXT, `min_price_paise` INTEGER NOT NULL, `payload_json` TEXT NOT NULL, `cached_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `cached_menu_snapshot` (`id` INTEGER NOT NULL, `payload_json` TEXT NOT NULL, `cached_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `notification_inbox` (`id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `event_id` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `category` TEXT NOT NULL, `reference_id` TEXT, `is_read` INTEGER NOT NULL, `created_at_iso` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
              "CREATE UNIQUE INDEX IF NOT EXISTS `index_notification_inbox_user_id_event_id` ON `notification_inbox` (`user_id`, `event_id`)",
            )
            db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_notification_inbox_user_id` ON `notification_inbox` (`user_id`)",
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            db.execSQL(
              "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'bfe1970f116d4cc65e4a0d92ee10277a')",
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        },
      )
      .build()

    val helper = FrameworkSQLiteOpenHelperFactory().create(config)
    helper.writableDatabase.use { db ->
      db.execSQL(
        "INSERT INTO cart_items (id, user_id, product_id, product_name, image_url, variant_id, size_code, crust_id, crust_name, unit_price_paise, quantity, is_available, unavailable_reason, updated_at_ms) VALUES ('ci-v3-1', 'user-v3', 'prod-1', 'Margherita Verace', NULL, 'var-1', 'LARGE', 'crust-1', 'Classic', 57400, 2, 1, NULL, 1700000000000)",
      )
      db.execSQL(
        "INSERT INTO cart_item_toppings (cart_item_id, topping_id, topping_name, extra_price_paise) VALUES ('ci-v3-1', 'top-1', 'Extra Basil', 2500)",
      )
    }
    helper.close()
  }

  @Test
  fun `v3 to v4 migration preserves existing cart rows and enables half-and-half composite topping keys`() = runBlocking {
    createAuthenticV3DatabaseWithSeedData()

    val db = Room.databaseBuilder(context, PizzoraDatabase::class.java, dbName)
      .allowMainThreadQueries()
      .addMigrations(PizzoraDatabase.MIGRATION_2_3, PizzoraDatabase.MIGRATION_3_4)
      .addCallback(PizzoraDatabase.FOREIGN_KEY_CALLBACK)
      .build()
    migratedDb = db

    val writable = db.openHelper.writableDatabase
    writable.query("PRAGMA foreign_keys").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(1, cursor.getInt(0))
    }
    writable.query("PRAGMA foreign_key_check").use { cursor ->
      assertFalse("Expected zero foreign_key_check violations after v3->v4", cursor.moveToFirst())
    }

    val cartDao = db.cartDao()
    val migratedItems = cartDao.observeCartItemsWithToppings("user-v3").first()
    assertEquals(1, migratedItems.size)
    val preserved = migratedItems.first()
    assertEquals("ci-v3-1", preserved.item.id)
    assertEquals(2, preserved.item.quantity)
    assertFalse(preserved.item.isHalfAndHalf)
    assertFalse(preserved.item.extraCheese)
    assertEquals("[]", preserved.item.removedIncludedToppingIdsJson)
    assertEquals("", preserved.item.configurationSignature)
    assertEquals(1, preserved.toppings.size)
    assertEquals("WHOLE", preserved.toppings.first().placement)
    assertEquals(1, preserved.toppings.first().quantity)

    // Verify same topping_id can now be inserted with LEFT and RIGHT placements on the same cart_item_id
    cartDao.upsertItemWithToppings(
      CartItemEntity(
        id = "ci-v4-half",
        userId = "user-v3",
        productId = "prod-1",
        productName = "Half & Half: Margherita / Farmhouse",
        variantId = "var-2",
        sizeCode = "LARGE",
        crustId = "crust-1",
        crustName = "Classic",
        unitPricePaise = 69_900L,
        quantity = 1,
        isHalfAndHalf = true,
        rightProductId = "prod-2",
        rightProductName = "Farmhouse",
        rightVariantId = "var-right-2",
      ),
      listOf(
        CartItemToppingEntity("ci-v4-half", "top-shared", "Jalapenos", 3_000L, placement = "LEFT", quantity = 1),
        CartItemToppingEntity("ci-v4-half", "top-shared", "Jalapenos", 3_000L, placement = "RIGHT", quantity = 2),
      ),
    )

    val allUserV3 = cartDao.observeCartItemsWithToppings("user-v3").first()
    assertEquals(2, allUserV3.size)
    val halfEntry = allUserV3.first { it.item.id == "ci-v4-half" }
    assertEquals(2, halfEntry.toppings.size)
    assertEquals(setOf("LEFT", "RIGHT"), halfEntry.toppings.map { it.placement }.toSet())

    // Deleting parent cascades to composite-PK topping rows
    cartDao.deleteItemById("user-v3", "ci-v4-half")
    assertEquals(1, cartDao.observeCartItemsWithToppings("user-v3").first().size)
  }
}
