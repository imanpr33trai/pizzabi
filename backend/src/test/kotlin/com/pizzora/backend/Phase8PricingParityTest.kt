package com.pizzora.backend

import com.pizzora.backend.cart.CartService
import com.pizzora.backend.catalog.CatalogService
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.pricing.PricingService
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.ValidateCartRequest
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Kotlin-side coverage for reviewed vectors U-01..U-12 plus one half-and-half vector. */
class Phase8PricingParityTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres

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
  fun `U-01 through U-12 and a half-and-half case match the binding paise formulas`() = runBlocking {
    val fx = Phase8TestSupport.seedCatalog(pg.postgresDatabase)
    val service = PricingService(
      dataSource = pg.postgresDatabase,
      couponService = CouponService(pg.postgresDatabase),
      publicUrlResolver = { null },
    )
    val userId = UUID.randomUUID()

    suspend fun price(
      label: String,
      crustId: UUID = fx.classicCrustId,
      cheeseId: UUID? = null,
      sauceId: UUID? = null,
      standardToppingIds: List<UUID> = emptyList(),
      halfAndHalfConfigId: UUID? = null,
      halfLeft: List<UUID> = emptyList(),
      halfRight: List<UUID> = emptyList(),
    ): Long {
      val response = service.quotePrice(
        userId = userId,
        request = PricingQuoteRequest(
          items = listOf(
            CartItemInputDto(
              productId = fx.productId.toString(),
              variantId = fx.variantId.toString(),
              crustId = crustId.toString(),
              quantity = 1,
              cheeseId = cheeseId?.toString(),
              sauceId = sauceId?.toString(),
              standardToppingIds = standardToppingIds.map(UUID::toString),
              halfAndHalfConfigId = halfAndHalfConfigId?.toString(),
              halfAndHalfLeftToppingIds = halfLeft.map(UUID::toString),
              halfAndHalfRightToppingIds = halfRight.map(UUID::toString),
            ),
          ),
          fulfillmentType = FulfillmentType.PICKUP,
        ),
      )
      val item = response.items.single()
      assertTrue("$label should be available: ${item.issues}", item.isAvailable)
      return item.unitPricePaise
    }

    // §8 vectors U-01..U-12.
    assertEquals(10_000L, price("U-01"))
    assertEquals(12_000L, price("U-02", cheeseId = fx.cheeseId))
    assertEquals(11_500L, price("U-03", sauceId = fx.sauceId))
    assertEquals(13_500L, price("U-04", cheeseId = fx.cheeseId, sauceId = fx.sauceId))
    assertEquals(11_000L, price("U-05", crustId = fx.premiumCrustId))
    assertEquals(11_000L, price("U-06", standardToppingIds = listOf(fx.extraStandardToppingId)))
    assertEquals(
      11_500L,
      price(
        "U-07",
        standardToppingIds = listOf(fx.extraStandardToppingId, fx.secondExtraStandardToppingId),
      ),
    )
    assertEquals(10_000L, price("U-08", standardToppingIds = listOf(fx.defaultStandardToppingId)))
    assertEquals(10_000L, price("U-09"))
    assertEquals(
      11_000L,
      price(
        "U-10",
        standardToppingIds = listOf(fx.defaultStandardToppingId, fx.extraStandardToppingId),
      ),
    )
    assertEquals(
      11_000L,
      price("U-11", standardToppingIds = listOf(fx.extraStandardToppingId)),
    )
    assertEquals(
      15_500L,
      price(
        "U-12",
        crustId = fx.premiumCrustId,
        cheeseId = fx.cheeseId,
        sauceId = fx.sauceId,
        standardToppingIds = listOf(fx.extraStandardToppingId),
      ),
    )

    // Reviewed half-and-half example: (14000 + 15000 + 1) / 2 + 1000 surcharge = 15500.
    assertEquals(
      15_500L,
      price(
        "half-and-half",
        halfAndHalfConfigId = fx.halfAndHalfConfigId,
        halfLeft = listOf(fx.toppingLeftId),
        halfRight = listOf(fx.toppingRightId),
      ),
    )
  }

  @Test
  fun `inactive foreign modifiers and half-and-half overlap are reported as issues`() = runBlocking {
    val fx = Phase8TestSupport.seedCatalog(pg.postgresDatabase)
    val cartService = CartService(pg.postgresDatabase, publicUrlResolver = { null })

    suspend fun issuesFor(item: CartItemInputDto) = cartService
      .validateCart(ValidateCartRequest(listOf(item)))
      .items.single().issues.map { it.code }

    val base = CartItemInputDto(
      productId = fx.productId.toString(),
      variantId = fx.variantId.toString(),
      crustId = fx.classicCrustId.toString(),
      quantity = 1,
    )
    assertTrue(
      issuesFor(base.copy(cheeseId = fx.inactiveCheeseId.toString())).contains(
        com.pizzora.contracts.CartValidationIssueCode.CHEESE_UNAVAILABLE,
      ),
    )
    assertTrue(
      issuesFor(base.copy(sauceId = fx.inactiveSauceId.toString())).contains(
        com.pizzora.contracts.CartValidationIssueCode.SAUCE_UNAVAILABLE,
      ),
    )
    assertTrue(
      issuesFor(base.copy(toppingIds = listOf(fx.unavailableToppingId.toString()))).contains(
        com.pizzora.contracts.CartValidationIssueCode.TOPPING_UNAVAILABLE,
      ),
    )
    assertTrue(
      issuesFor(
        base.copy(
          halfAndHalfConfigId = fx.halfAndHalfConfigId.toString(),
          halfAndHalfLeftToppingIds = listOf(fx.toppingLeftId.toString()),
          halfAndHalfRightToppingIds = listOf(fx.toppingLeftId.toString()),
        ),
      ).contains(com.pizzora.contracts.CartValidationIssueCode.HALF_AND_HALF_LEFT_RIGHT_OVERLAP),
    )
  }

  @Test
  fun `public menu exposes global active modifier arrays and preserves per-product arrays`() = runBlocking {
    val fx = Phase8TestSupport.seedCatalog(pg.postgresDatabase, "menu")
    val cache = object : EphemeralCacheStore {
      private val values = ConcurrentHashMap<String, String>()
      override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean {
        values[key] = value
        return true
      }
      override suspend fun get(key: String): String? = values[key]
      override suspend fun getDel(key: String): String? = values.remove(key)
      override suspend fun del(key: String): Boolean = values.remove(key) != null
      override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? = null
    }
    val menu = CatalogService(
      dataSource = pg.postgresDatabase,
      cacheStore = cache,
      publicUrlResolver = { null },
    ).getPublicMenu()

    assertTrue(menu.cheeses.any { it.id == fx.cheeseId.toString() })
    assertTrue(menu.cheeses.none { it.id == fx.inactiveCheeseId.toString() })
    assertTrue(menu.sauces.any { it.id == fx.sauceId.toString() })
    assertTrue(menu.sauces.none { it.id == fx.inactiveSauceId.toString() })
    assertTrue(menu.standardToppings.any { it.id == fx.defaultStandardToppingId.toString() })
    assertTrue(menu.standardToppings.any { it.id == fx.extraStandardToppingId.toString() })
    val halfConfig = menu.halfAndHalfConfigs.single { it.id == fx.halfAndHalfConfigId.toString() }
    assertEquals(1_000L, halfConfig.halfAndHalfSurchargePaise)
    assertTrue(halfConfig.leftToppingIds.contains(fx.toppingLeftId.toString()))

    val product = menu.products.single { it.id == fx.productId.toString() }
    assertTrue(product.cheeses.any { it.id == fx.cheeseId.toString() })
    assertTrue(product.sauces.any { it.id == fx.sauceId.toString() })
    assertTrue(product.standardToppings.any { it.id == fx.extraStandardToppingId.toString() })
    assertTrue(product.halfAndHalfConfigs.any { it.id == fx.halfAndHalfConfigId.toString() })
  }

  @Test
  fun `quote breakdown reports non-zero extended modifier totals with checked multiplication`() = runBlocking {
    val fx = Phase8TestSupport.seedCatalog(pg.postgresDatabase, "breakdown")
    val service = PricingService(
      dataSource = pg.postgresDatabase,
      couponService = CouponService(pg.postgresDatabase),
      publicUrlResolver = { null },
    )
    val response = service.quotePrice(
      userId = UUID.randomUUID(),
      request = PricingQuoteRequest(
        items = listOf(
          CartItemInputDto(
            productId = fx.productId.toString(),
            variantId = fx.variantId.toString(),
            crustId = fx.classicCrustId.toString(),
            quantity = 2,
            cheeseId = fx.cheeseId.toString(),
            sauceId = fx.sauceId.toString(),
            standardToppingIds = listOf(fx.extraStandardToppingId.toString()),
            halfAndHalfConfigId = fx.halfAndHalfConfigId.toString(),
            halfAndHalfLeftToppingIds = listOf(fx.toppingLeftId.toString()),
            halfAndHalfRightToppingIds = listOf(fx.toppingRightId.toString()),
          ),
        ),
        fulfillmentType = FulfillmentType.PICKUP,
      ),
    )

    val breakdown = response.breakdown
    assertEquals(4_000L, breakdown.cheeseExtraPaise)
    assertEquals(3_000L, breakdown.sauceExtraPaise)
    assertEquals(2_000L, breakdown.standardToppingsExtraPaise)
    assertEquals(2_000L, breakdown.halfAndHalfSurchargePaise)
    assertEquals(11_000L, breakdown.modifierExtraPaise)
    assertEquals(40_000L, breakdown.subtotalPaise)
    assertEquals(20_000L, response.items.single().unitPricePaise)
  }
}
