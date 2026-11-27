package com.pizzora.backend

import com.pizzora.backend.cart.CartService
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.orders.OrderService
import com.pizzora.contracts.ReorderUnavailableReason
import com.pizzora.contracts.UserRole
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Verifies U8-05: unavailable order modifiers are reported, never substituted, and never added to the cart. */
class Phase8UnavailableReconciliationTest {
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
  fun `deactivated modifiers become unavailable reconciliation entries with no substitution and no cart mutation`() =
    runBlocking {
      val label = "reconcile"
      val fx = Phase8TestSupport.seedCatalog(pg.postgresDatabase, label)
      val customerId = Phase8TestSupport.insertUser(
        pg.postgresDatabase,
        "reconcile-${UUID.randomUUID()}@pizzora.test",
        "Reconcile Tester",
        "Pass1234!",
        passwordHasher,
      )
      val order = Phase8TestSupport.seedOrder(
        dataSource = pg.postgresDatabase,
        customerId = customerId,
        productId = fx.productId,
        productName = "Phase8 Pizza $label",
        variantId = fx.variantId,
        crustId = fx.classicCrustId,
        cheeseId = fx.cheeseId,
        sauceId = fx.sauceId,
        toppingIds = listOf(fx.toppingLeftId),
        toppingNames = listOf("Left $label"),
        standardToppingIds = listOf(fx.extraStandardToppingId),
        halfAndHalfConfigId = fx.halfAndHalfConfigId,
        halfAndHalfLeftToppingIds = listOf(fx.toppingLeftId),
        halfAndHalfRightToppingIds = listOf(fx.toppingRightId),
      )

      Phase8TestSupport.withConnection(pg.postgresDatabase) { conn ->
        conn.prepareStatement("UPDATE cheeses SET is_active = FALSE WHERE id = ?").use { ps ->
          ps.setObject(1, fx.cheeseId)
          ps.executeUpdate()
        }
        conn.prepareStatement("UPDATE toppings SET is_active = FALSE WHERE id = ?").use { ps ->
          ps.setObject(1, fx.toppingLeftId)
          ps.executeUpdate()
        }
      }

      val orderService = OrderService(
        dataSource = pg.postgresDatabase,
        inventoryService = InventoryService(pg.postgresDatabase),
        couponService = CouponService(pg.postgresDatabase),
      )
      val response = orderService.reorderOrder(order.orderId, customerId, UserRole.CUSTOMER)

      assertFalse(response.canReorder)
      assertEquals(order.orderId.toString(), response.orderId)
      val item = response.items.single()
      assertFalse(item.isAvailable)
      assertNull(item.cheeseId)
      assertEquals(fx.sauceId.toString(), item.sauceId)
      assertTrue(item.toppingIds.isEmpty())
      assertEquals(fx.halfAndHalfConfigId.toString(), item.halfAndHalfConfigId)
      assertTrue(item.halfAndHalfLeftToppingIds.isEmpty())
      assertEquals(listOf(fx.toppingRightId.toString()), item.halfAndHalfRightToppingIds)

      val reasons = response.unavailable.map { it.reason }.toSet()
      assertTrue(reasons.contains(ReorderUnavailableReason.CHEESE_UNAVAILABLE))
      assertTrue(reasons.contains(ReorderUnavailableReason.TOPPING_UNAVAILABLE))
      assertTrue(response.unavailable.all { it.orderItemId == order.itemId.toString() })

      val cart = CartService(pg.postgresDatabase, publicUrlResolver = { null }).getCart(customerId)
      assertTrue(cart.items.isEmpty())
    }
}
