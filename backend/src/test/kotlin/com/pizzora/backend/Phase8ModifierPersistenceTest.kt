package com.pizzora.backend

import com.pizzora.backend.cart.CartService
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.security.PasswordHasher
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Verifies that all Phase 8 cart modifier columns survive a persist/load round-trip. */
class Phase8ModifierPersistenceTest {
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
  fun `cart modifier columns JSONB arrays and resolved views round-trip and merge by full configuration`() = runBlocking {
    val fx = Phase8TestSupport.seedCatalog(pg.postgresDatabase)
    val userId = Phase8TestSupport.insertUser(
      pg.postgresDatabase,
      "cart-p8-${UUID.randomUUID()}@pizzora.test",
      "Phase 8 Cart Tester",
      "CartPass123!",
      passwordHasher,
    )
    val service = CartService(pg.postgresDatabase, publicUrlResolver = { null })

    val input = com.pizzora.contracts.CartItemInputDto(
      productId = fx.productId.toString(),
      variantId = fx.variantId.toString(),
      crustId = fx.classicCrustId.toString(),
      toppingIds = listOf(fx.toppingLeftId.toString()),
      quantity = 1,
      cheeseId = fx.cheeseId.toString(),
      sauceId = fx.sauceId.toString(),
      standardToppingIds = listOf(fx.defaultStandardToppingId.toString(), fx.extraStandardToppingId.toString()),
      halfAndHalfConfigId = fx.halfAndHalfConfigId.toString(),
      halfAndHalfLeftToppingIds = listOf(fx.toppingLeftId.toString()),
      halfAndHalfRightToppingIds = listOf(fx.toppingRightId.toString()),
    )
    val stored = service.replaceCart(
      userId,
      com.pizzora.contracts.ReplaceCartRequest(listOf(input, input.copy())),
    )
    val item = stored.items.single()
    assertEquals(2, item.quantity)
    assertTrue(item.isAvailable)

    assertEquals(fx.cheeseId.toString(), item.cheese?.cheeseId)
    assertEquals("Mozzarella", item.cheese?.name?.substringBefore(" "))
    assertEquals(fx.sauceId.toString(), item.sauce?.sauceId)
    assertEquals("Tomato", item.sauce?.name?.substringBefore(" "))
    assertEquals(3, item.standardToppings.size)
    assertTrue(item.standardToppings.single { it.standardToppingId == fx.defaultStandardToppingId.toString() }.isDefault)
    assertFalse(item.standardToppings.single { it.standardToppingId == fx.defaultStandardToppingId.toString() }.isRemoved)
    assertTrue(item.standardToppings.single { it.standardToppingId == fx.extraStandardToppingId.toString() }.isAvailable)
    assertEquals(fx.halfAndHalfConfigId.toString(), item.halfAndHalf?.halfAndHalfConfigId)
    assertEquals(listOf(fx.toppingLeftId.toString()), item.halfAndHalf?.leftToppingIds)
    assertEquals(listOf(fx.toppingRightId.toString()), item.halfAndHalf?.rightToppingIds)
    assertEquals(1_000L, item.halfAndHalf?.halfAndHalfSurchargePaise)

    // 14500 average + 1000 surcharge + 2000 cheese + 1500 sauce + 1000 selected non-default standard.
    assertEquals(20_000L, item.unitPricePaise)
    assertEquals(40_000L, item.lineTotalPaise)

    Phase8TestSupport.withConnection(pg.postgresDatabase) { conn ->
      conn.prepareStatement(
        """SELECT cheese_id, sauce_id, standard_topping_ids::text AS standards,
                  half_and_half_config_id, half_and_half_left_topping_ids::text AS left_ids,
                  half_and_half_right_topping_ids::text AS right_ids
           FROM cart_items WHERE cart_id = (SELECT id FROM carts WHERE user_id = ?)""".trimIndent(),
      ).use { ps ->
        ps.setObject(1, userId)
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals(fx.cheeseId, rs.getObject("cheese_id", UUID::class.java))
          assertEquals(fx.sauceId, rs.getObject("sauce_id", UUID::class.java))
          assertEquals(fx.halfAndHalfConfigId, rs.getObject("half_and_half_config_id", UUID::class.java))
          assertTrue(rs.getString("standards").contains(fx.defaultStandardToppingId.toString()))
          assertTrue(rs.getString("standards").contains(fx.extraStandardToppingId.toString()))
          assertTrue(rs.getString("left_ids").contains(fx.toppingLeftId.toString()))
          assertTrue(rs.getString("right_ids").contains(fx.toppingRightId.toString()))
          assertFalse(rs.next())
        }
      }
    }

    val reloaded = service.getCart(userId)
    val reloadedItem = reloaded.items.single()
    assertEquals(2, reloadedItem.quantity)
    assertEquals(item.cheese?.cheeseId, reloadedItem.cheese?.cheeseId)
    assertEquals(item.sauce?.sauceId, reloadedItem.sauce?.sauceId)
    assertEquals(item.halfAndHalf?.halfAndHalfConfigId, reloadedItem.halfAndHalf?.halfAndHalfConfigId)
    assertEquals(item.standardToppings.map { it.standardToppingId }, reloadedItem.standardToppings.map { it.standardToppingId })
    assertEquals(item.unitPricePaise, reloadedItem.unitPricePaise)
  }
}
