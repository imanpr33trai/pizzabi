package com.pizzora.backend

import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.orders.OrderService
import com.pizzora.contracts.UserRole
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Verifies that historical orders keep snapshot display data and fall back by name on reorder. */
class Phase8HistoricalOrderDisplayTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val passwordHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)

    @JvmStatic
    @BeforeClass
    fun startInfra() {
      pg = EmbeddedPostgres.builder().start()
      DatabaseFactory.migrateAndConnect(pg.postgresDatabase)
      Phase8TestSupport.applyPhase8Schema(pg.postgresDatabase)
    }

    @JvmStatic
    @AfterClass
    fun stopInfra() {
      pg.close()
    }
  }

  @Test
  fun `historical order item displays snapshots and reorder resolves variant crust and toppings by name`() =
    runBlocking {
      val label = "history"
      val fx = Phase8TestSupport.seedCatalog(pg.postgresDatabase, label)
      val customerId = Phase8TestSupport.insertUser(
        pg.postgresDatabase,
        "history-${UUID.randomUUID()}@pizzora.test",
        "History Tester",
        "Pass1234!",
        passwordHasher,
      )
      val productName = "Phase8 Pizza $label"
      val order = Phase8TestSupport.seedOrder(
        dataSource = pg.postgresDatabase,
        customerId = customerId,
        productId = fx.productId,
        productName = productName,
        variantSize = "MEDIUM",
        crustName = "Classic $label",
        toppingNames = listOf("Left $label", "Right $label"),
        modifiersSnapshot = mapOf(
          "cheese_name" to "Mozzarella $label",
          "sauce_name" to "Tomato $label",
          "standard_topping_names" to "Basil $label",
          "half_and_half_name" to "Half & Half $label",
        ),
      )

      val orderService = OrderService(
        dataSource = pg.postgresDatabase,
        inventoryService = InventoryService(pg.postgresDatabase),
        couponService = CouponService(pg.postgresDatabase),
      )
      val detail = orderService.getOrderDetail(order.orderId, customerId, UserRole.CUSTOMER)
      val item = detail.items.single()
      assertEquals(productName, item.productName)
      assertEquals("MEDIUM", item.variantSize)
      assertEquals("Classic $label", item.crustName)
      assertEquals(listOf("Left $label", "Right $label"), item.toppings)
      assertEquals(null, item.variantId)
      assertEquals(null, item.crustId)
      assertTrue(item.toppingIds.isEmpty())
      assertEquals("Mozzarella $label", item.modifiersSnapshot["cheese_name"])
      assertEquals("Tomato $label", item.modifiersSnapshot["sauce_name"])

      val summary = orderService.listCustomerOrders(customerId, 10).orders.single()
      val preview = summary.itemPreviewSummary.orEmpty()
      assertTrue(preview.contains(productName))
      assertTrue(preview.contains("MEDIUM"))
      assertTrue(preview.contains("Mozzarella $label"))
      assertTrue(preview.contains("Tomato $label"))
      assertTrue(preview.contains("Left $label"))

      val reorder = orderService.reorderOrder(order.orderId, customerId, UserRole.CUSTOMER)
      assertTrue(reorder.canReorder)
      assertTrue(reorder.unavailable.isEmpty())
      assertTrue(reorder.warnings.any { it.startsWith("Historical order:") })
      val reorderedItem = reorder.items.single()
      assertEquals(fx.variantId.toString(), reorderedItem.variantId)
      assertEquals(fx.classicCrustId.toString(), reorderedItem.crustId)
      assertEquals(
        setOf(fx.toppingLeftId.toString(), fx.toppingRightId.toString()),
        reorderedItem.toppingIds.toSet(),
      )
      assertEquals(null, reorderedItem.cheeseId)
      assertEquals(null, reorderedItem.sauceId)
      assertEquals(null, reorderedItem.halfAndHalfConfigId)
    }
}
