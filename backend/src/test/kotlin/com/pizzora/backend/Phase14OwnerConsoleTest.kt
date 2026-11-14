package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.images.InspectedObject
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OwnerDashboardResponse
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PublicConfigResponse
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.RestaurantSettingsDto
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyEmailRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase14OwnerConsoleTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-p14-${UUID.randomUUID()}@pizzora.test"
    private val ownerPass = "OwnerPass123!"

    @JvmStatic
    @BeforeClass
    fun startInfra() {
      pg = EmbeddedPostgres.builder().start()
      DatabaseFactory.migrateAndConnect(pg.postgresDatabase)
      runBlocking {
        BootstrapOwner.bootstrap(
          dataSource = pg.postgresDatabase,
          passwordHasher = fastHasher,
          email = ownerEmail,
          password = ownerPass,
          fullName = "Owner P14",
          phone = "9833333314",
        )
      }
    }

    @JvmStatic
    @AfterClass
    fun stopInfra() {
      pg.close()
    }
  }

  private class InMemoryCacheStore : EphemeralCacheStore {
    private val map = ConcurrentHashMap<String, String>()
    override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean {
      map[key] = value
      return true
    }
    override suspend fun get(key: String): String? = map[key]
    override suspend fun getDel(key: String): String? = map.remove(key)
    override suspend fun del(key: String): Boolean = map.remove(key) != null
    override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? = null
  }

  private object DummyStorageClient : ObjectStorageClient {
    override fun presignPut(objectKey: String, contentType: String, expiresInSeconds: Long): String =
      "http://localhost:9000/pizzora/$objectKey"
    override fun publicUrlFor(objectKey: String): String =
      "http://localhost:9000/pizzora/$objectKey"
    override suspend fun inspectObject(objectKey: String): InspectedObject? = null
    override suspend fun deleteObject(objectKey: String): Boolean = true
  }

  private data class Fixtures(
    val ownerToken: String,
    val kitchenToken: String,
    val customerToken: String,
    val productId: String,
    val variantId: String,
    val crustId: String,
    val toppingId: String,
    val addressId: String,
  )

  private suspend fun ApplicationTestBuilder.setupFixtures(): Fixtures {
    val mailProvider = InMemoryEmailDeliveryProvider()
    application {
      pizzoraModule(
        config = AppConfig.fromEnv(),
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = InMemoryCacheStore(),
        emailDeliveryProvider = mailProvider,
        objectStorageClient = DummyStorageClient,
        passwordHasher = fastHasher,
      )
    }

    val ownerLogin = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
    }
    val ownerToken = json.decodeFromString<AuthTokensResponse>(ownerLogin.bodyAsText()).accessToken

    val staffEmail = "chef-p14-${UUID.randomUUID()}@pizzora.test"
    val staffPass = "ChefPass123!"
    val staffResp = client.post("/api/v1/owner/staff") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateStaffRequest(
            email = staffEmail,
            password = staffPass,
            fullName = "Chef P14",
            phone = "9844444414",
            role = UserRole.KITCHEN_STAFF,
            employeeCode = "CHEF14-${UUID.randomUUID().toString().take(6)}",
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, staffResp.status)
    json.decodeFromString<StaffMemberDto>(staffResp.bodyAsText())

    val staffLogin = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(staffEmail, staffPass)))
    }
    val kitchenToken = json.decodeFromString<AuthTokensResponse>(staffLogin.bodyAsText()).accessToken

    val custEmail = "cust-p14-${UUID.randomUUID()}@pizzora.test"
    client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(custEmail, "CustPass123!", "Aarav Rao", "9811111414")))
    }
    val verifyToken = mailProvider.outbox.last { it.toEmail == custEmail && it.type == "VERIFICATION" }.token
    client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest(verifyToken)))
    }
    val custLogin = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(custEmail, "CustPass123!")))
    }
    val customerToken = json.decodeFromString<AuthTokensResponse>(custLogin.bodyAsText()).accessToken

    val catResp = client.post("/api/v1/owner/categories") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCategoryRequest("P14 Woodfire ${UUID.randomUUID()}", 1, true)))
    }
    val cat = json.decodeFromString<CategoryDto>(catResp.bodyAsText())

    val prodResp = client.post("/api/v1/owner/products") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateProductRequest(
            categoryId = cat.id,
            name = "Burrata Marinara",
            description = "San Marzano tomato + burrata",
            isVeg = true,
            variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 45_000L, true)),
          ),
        ),
      )
    }
    val prod = json.decodeFromString<ProductDto>(prodResp.bodyAsText())

    val crustResp = client.post("/api/v1/owner/crusts") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCrustRequest("P14 Classic ${UUID.randomUUID()}", 0L, true)))
    }
    val crust = json.decodeFromString<CrustOptionDto>(crustResp.bodyAsText())

    val topResp = client.post("/api/v1/owner/toppings") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateToppingRequest("P14 Basil ${UUID.randomUUID()}", true, 4_000L, true)))
    }
    val top = json.decodeFromString<ToppingDto>(topResp.bodyAsText())

    val pincode = "56${(1000..9999).random()}"
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        """
        INSERT INTO delivery_zones (id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active)
        VALUES (?, ?, 'Indiranagar', 0, 2500, 25, TRUE)
        ON CONFLICT (pincode) DO NOTHING
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setString(2, pincode)
        ps.executeUpdate()
      }
    }

    val addrResp = client.post("/api/v1/me/addresses") {
      header(HttpHeaders.Authorization, "Bearer $customerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          UpsertAddressRequest("Home", "100 Feet Rd", pincode = pincode, city = "Bengaluru", isDefault = true),
        ),
      )
    }
    val addr = json.decodeFromString<AddressDto>(addrResp.bodyAsText())

    return Fixtures(
      ownerToken = ownerToken,
      kitchenToken = kitchenToken,
      customerToken = customerToken,
      productId = prod.id,
      variantId = prod.variants.first().id,
      crustId = crust.id,
      toppingId = top.id,
      addressId = addr.id,
    )
  }

  @Test
  fun `owner can toggle store closed and open, update gst with audit log, and fetch dashboard KPIs`() = testApplication {
    val fx = setupFixtures()

    // Create low-stock ingredient so dashboard reports low_stock_count >= 1
    val ingResp = client.post("/api/v1/owner/inventory") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateIngredientRequest(
            name = "San Marzano Tomatoes ${UUID.randomUUID()}",
            unit = IngredientUnit.GRAMS,
            lowStockThreshold = 500,
            initialStockQty = 200,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, ingResp.status)
    val lowIngredient = json.decodeFromString<InventoryItemDto>(ingResp.bodyAsText())
    assertTrue(lowIngredient.isLowStock)

    // Toggle store CLOSED and update GST bps to 1200 (12%)
    val closeResp = client.put("/api/v1/owner/settings") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          UpdateRestaurantSettingsRequest(
            storeName = "PIZZORA Indiranagar",
            isAcceptingOrders = false,
            gstBps = 1200,
            defaultPrepMins = 25,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.OK, closeResp.status)
    val closedSettings = json.decodeFromString<RestaurantSettingsDto>(closeResp.bodyAsText())
    assertFalse(closedSettings.isAcceptingOrders)
    assertEquals(1200, closedSettings.gstBps)
    assertEquals(25, closedSettings.defaultPrepMins)

    val pubClosed = client.get("/api/v1/config/public")
    assertEquals(HttpStatusCode.OK, pubClosed.status)
    val pubClosedDto = json.decodeFromString<PublicConfigResponse>(pubClosed.bodyAsText())
    assertFalse(pubClosedDto.isAcceptingOrders)
    assertEquals(1200, pubClosedDto.gstBps)

    // Customer checkout rejected cleanly when store closed
    val checkoutReq = CheckoutRequest(
      items = listOf(
        CartItemInputDto(
          productId = fx.productId,
          variantId = fx.variantId,
          crustId = fx.crustId,
          toppingIds = listOf(fx.toppingId),
          quantity = 1,
        ),
      ),
      fulfillmentType = FulfillmentType.DELIVERY,
      scheduleType = ScheduleType.ASAP,
      addressId = fx.addressId,
      paymentMethod = PaymentMethod.COD,
    )
    val rejectedCheckout = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(checkoutReq))
    }
    assertEquals(HttpStatusCode.Conflict, rejectedCheckout.status)
    val errorEnv = json.decodeFromString<ApiErrorEnvelope>(rejectedCheckout.bodyAsText())
    assertEquals("STORE_CLOSED", errorEnv.error.code)

    // Reopen store -> pricing quote reflects 1200 bps and checkout succeeds
    val openResp = client.put("/api/v1/owner/settings") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateRestaurantSettingsRequest(isAcceptingOrders = true)))
    }
    assertEquals(HttpStatusCode.OK, openResp.status)

    val quoteResp = client.post("/api/v1/pricing/quote") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          PricingQuoteRequest(
            items = checkoutReq.items,
            fulfillmentType = FulfillmentType.DELIVERY,
            addressId = fx.addressId,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.OK, quoteResp.status)
    val quote = json.decodeFromString<PricingQuoteResponse>(quoteResp.bodyAsText())
    assertEquals(1200, quote.gstBps)
    assertTrue(quote.isAcceptingOrders)

    val acceptedCheckout = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(checkoutReq))
    }
    assertEquals(HttpStatusCode.Created, acceptedCheckout.status)
    val created = json.decodeFromString<CheckoutResponse>(acceptedCheckout.bodyAsText())
    assertEquals(OrderStatus.PLACED, created.order.status)

    // Owner dashboard shows updated KPIs, low-stock item, and recent order
    val dashResp = client.get("/api/v1/owner/dashboard") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
    }
    assertEquals(HttpStatusCode.OK, dashResp.status)
    val dash = json.decodeFromString<OwnerDashboardResponse>(dashResp.bodyAsText())
    assertTrue(dash.todayOrderCount >= 1)
    assertTrue(dash.activeOrderCount >= 1)
    assertTrue(dash.todayRevenuePaise >= created.order.totalPaise)
    assertTrue(dash.lowStockCount >= 1)
    assertTrue(dash.lowStockItems.any { it.ingredientId == lowIngredient.ingredientId })
    assertTrue(dash.recentOrders.any { it.id == created.order.id })

    // Owner orders listing returns order
    val ordersResp = client.get("/api/v1/owner/orders?status=PLACED") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
    }
    assertEquals(HttpStatusCode.OK, ordersResp.status)
    val ordersList = json.decodeFromString<OrderListResponse>(ordersResp.bodyAsText())
    assertTrue(ordersList.orders.any { it.id == created.order.id })

    // Verify audit log recorded settings updates
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'RESTAURANT_SETTINGS_UPDATED' AND entity_type = 'RESTAURANT_SETTINGS'",
      ).use { ps ->
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertTrue(rs.getInt(1) >= 2)
        }
      }
    }
  }

  @Test
  fun `customer and kitchen staff receive 403 on owner dashboard, settings, and orders`() = testApplication {
    val fx = setupFixtures()

    for (token in listOf(fx.customerToken, fx.kitchenToken)) {
      val dash = client.get("/api/v1/owner/dashboard") {
        header(HttpHeaders.Authorization, "Bearer $token")
      }
      assertEquals(HttpStatusCode.Forbidden, dash.status)

      val settingsGet = client.get("/api/v1/owner/settings") {
        header(HttpHeaders.Authorization, "Bearer $token")
      }
      assertEquals(HttpStatusCode.Forbidden, settingsGet.status)

      val settingsPut = client.put("/api/v1/owner/settings") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(UpdateRestaurantSettingsRequest(isAcceptingOrders = false)))
      }
      assertEquals(HttpStatusCode.Forbidden, settingsPut.status)

      val ownerOrders = client.get("/api/v1/owner/orders") {
        header(HttpHeaders.Authorization, "Bearer $token")
      }
      assertEquals(HttpStatusCode.Forbidden, ownerOrders.status)
    }
  }
}
