package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.config.RazorpayConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.health.DependencyProbe
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.redis.LettuceRedisStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.images.InspectedObject
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.backend.razorpay.GatewayOrderResult
import com.pizzora.backend.razorpay.GatewayPaymentResult
import com.pizzora.backend.razorpay.GatewayRefundResult
import com.pizzora.backend.razorpay.RazorpayGateway
import com.pizzora.backend.razorpay.RazorpaySignatureVerifier
import com.pizzora.backend.realtime.RealtimeHub
import com.pizzora.backend.worker.EventSequencerWorker
import com.pizzora.backend.worker.OutboxDispatcherWorker
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCouponRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateDeliveryZoneRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DependencyHealthStatus
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.EventsReplayResponse
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.HealthReadinessResponse
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.KdsTicketsResponse
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PublicConfigResponse
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.ReadinessState
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.VerifyEmailRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.WsEventsFrame
import com.pizzora.contracts.WsTicketResponse
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase19ConcurrencyOutageAndIndexTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }
    private val slotDayCounter = AtomicInteger(30)

    private val ownerEmail = "owner-p19-${UUID.randomUUID()}@pizzora.test"
    private val ownerPass = "OwnerPass123!"
    private const val TEST_KEY_ID = "rzp_test_pizzora_19"
    private const val TEST_KEY_SECRET = "rzp_secret_pizzora_super_secret_19"
    private const val TEST_WEBHOOK_SECRET = "rzp_webhook_pizzora_secret_19"

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
          fullName = "Owner P19",
          phone = "9819191919",
        )
      }
    }

    @JvmStatic
    @AfterClass
    fun stopInfra() {
      if (::pg.isInitialized) pg.close()
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

  private class ToggleableThrowingCacheStore : EphemeralCacheStore {
    val outageActive = AtomicBoolean(false)
    private val delegate = InMemoryCacheStore()

    private fun checkUp() {
      if (outageActive.get()) {
        throw IllegalStateException("Simulated Redis socket reset / timeout")
      }
    }

    override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean {
      checkUp()
      return delegate.setEx(key, ttlSeconds, value)
    }

    override suspend fun get(key: String): String? {
      checkUp()
      return delegate.get(key)
    }

    override suspend fun getDel(key: String): String? {
      checkUp()
      return delegate.getDel(key)
    }

    override suspend fun del(key: String): Boolean {
      checkUp()
      return delegate.del(key)
    }

    override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? {
      checkUp()
      return delegate.incrementWithinWindow(key, windowSeconds)
    }
  }

  private object DummyStorageClient : ObjectStorageClient {
    override fun presignPut(objectKey: String, contentType: String, expiresInSeconds: Long): String =
      "http://localhost:9000/pizzora/$objectKey"
    override fun publicUrlFor(objectKey: String): String =
      "http://localhost:9000/pizzora/$objectKey"
    override suspend fun inspectObject(objectKey: String): InspectedObject? = null
    override suspend fun deleteObject(objectKey: String): Boolean = true
  }

  private class FakeRazorpayGateway : RazorpayGateway {
    override val publicKeyId: String = TEST_KEY_ID
    private val seq = AtomicInteger(1)

    override suspend fun createOrder(
      amountPaise: Long,
      currency: String,
      receipt: String,
      notes: Map<String, String>,
    ): GatewayOrderResult = GatewayOrderResult(
      gatewayOrderId = "order_p19_${seq.getAndIncrement()}",
      receipt = receipt,
      amountPaise = amountPaise,
      currency = currency,
      status = "created",
    )

    override suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult> = emptyList()

    override suspend fun createRefund(
      gatewayPaymentId: String,
      amountPaise: Long,
      idempotencyKey: String,
      notes: Map<String, String>,
    ): GatewayRefundResult = GatewayRefundResult(
      gatewayRefundId = "rfnd_p19_${seq.getAndIncrement()}",
      gatewayPaymentId = gatewayPaymentId,
      amountPaise = amountPaise,
      status = "processed",
    )

    override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> = emptyList()
  }

  private fun baseTestConfig(
    rateLimitEnabled: Boolean = false,
  ): AppConfig {
    val base = AppConfig.fromEnv(mapOf("JWT_SECRET" to "test-jwt-secret-minimum-32-bytes-long-key"))
    return base.copy(
      razorpay = RazorpayConfig(
        keyId = TEST_KEY_ID,
        keySecret = TEST_KEY_SECRET,
        webhookSecret = TEST_WEBHOOK_SECRET,
      ),
      security = base.security.copy(
        rateLimitEnabled = rateLimitEnabled,
      ),
    )
  }

  private suspend fun ApplicationTestBuilder.loginOwner(): AuthTokensResponse {
    val resp = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    return json.decodeFromString(resp.bodyAsText())
  }

  private suspend fun ApplicationTestBuilder.registerAndLoginCustomer(
    emailProvider: InMemoryEmailDeliveryProvider,
    prefix: String,
  ): AuthTokensResponse {
    val email = "$prefix-${UUID.randomUUID()}@pizzora.test"
    val reg = client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(email, "CustPass123!", "Phase19 User", "9819000019")))
    }
    assertEquals(HttpStatusCode.Created, reg.status)
    val token = emailProvider.outbox.last { it.toEmail == email && it.type == "VERIFICATION" }.token
    val ver = client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest(token)))
    }
    assertEquals(HttpStatusCode.OK, ver.status)
    val login = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(email, "CustPass123!")))
    }
    assertEquals(HttpStatusCode.OK, login.status)
    return json.decodeFromString(login.bodyAsText())
  }

  @Test
  fun `50 concurrent checkouts complete with zero deadlocks, zero oversells, and exact inventory, slot, and coupon invariants`() =
    testApplication {
      val mailProvider = InMemoryEmailDeliveryProvider()
      application {
        pizzoraModule(
          config = baseTestConfig(rateLimitEnabled = false),
          probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
          dataSource = pg.postgresDatabase,
          cacheStore = InMemoryCacheStore(),
          emailDeliveryProvider = mailProvider,
          objectStorageClient = DummyStorageClient,
          passwordHasher = fastHasher,
        )
      }

      val ownerToken = loginOwner().accessToken
      val suf = UUID.randomUUID().toString().take(8)

      val cat = json.decodeFromString<CategoryDto>(
        client.post("/api/v1/owner/categories") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCategoryRequest("P19-Load-$suf", 1, true)))
        }.bodyAsText(),
      )

      // Product A + Product B share Dough/Cheese/Basil in different combinations to stress lock ordering
      val prodA = json.decodeFromString<ProductDto>(
        client.post("/api/v1/owner/products") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateProductRequest(
                categoryId = cat.id,
                name = "Load Margherita $suf",
                description = "San Marzano & Mozzarella",
                isVeg = true,
                variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 40_000L, true)),
              ),
            ),
          )
        }.bodyAsText(),
      )
      val prodB = json.decodeFromString<ProductDto>(
        client.post("/api/v1/owner/products") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateProductRequest(
                categoryId = cat.id,
                name = "Load Pepperoni $suf",
                description = "Smoked Pepperoni & Cheese",
                isVeg = false,
                variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 48_000L, true)),
              ),
            ),
          )
        }.bodyAsText(),
      )
      val crust = json.decodeFromString<CrustOptionDto>(
        client.post("/api/v1/owner/crusts") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCrustRequest("P19-Crust-$suf", 3_000L, true)))
        }.bodyAsText(),
      )
      val topping = json.decodeFromString<ToppingDto>(
        client.post("/api/v1/owner/toppings") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateToppingRequest("P19-Basil-$suf", true, 4_000L, true)))
        }.bodyAsText(),
      )

      // Constrain Dough to exactly 12 pizzas (3,000g @ 250g/pizza) across 50 concurrent checkouts
      val dough = json.decodeFromString<InventoryItemDto>(
        client.post("/api/v1/owner/inventory") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateIngredientRequest("P19-Dough-$suf", IngredientUnit.GRAMS, 200L, 3_000L)))
        }.bodyAsText(),
      )
      val cheese = json.decodeFromString<InventoryItemDto>(
        client.post("/api/v1/owner/inventory") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateIngredientRequest("P19-Cheese-$suf", IngredientUnit.GRAMS, 200L, 10_000L)))
        }.bodyAsText(),
      )
      val basil = json.decodeFromString<InventoryItemDto>(
        client.post("/api/v1/owner/inventory") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateIngredientRequest("P19-BasilIng-$suf", IngredientUnit.GRAMS, 50L, 10_000L)))
        }.bodyAsText(),
      )

      suspend fun putRecipe(targetType: RecipeTargetType, targetId: String, lines: List<RecipeIngredientLineRequest>) {
        val resp = client.put("/api/v1/owner/recipes") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(PutRecipeRequest(targetType, targetId, lines)))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
      }

      // Opposing ingredient combinations to stress deterministic sorted-UUID row locking
      putRecipe(
        RecipeTargetType.VARIANT,
        prodA.variants.first().id,
        listOf(
          RecipeIngredientLineRequest(dough.ingredientId, 250),
          RecipeIngredientLineRequest(cheese.ingredientId, 100),
        ),
      )
      putRecipe(
        RecipeTargetType.VARIANT,
        prodB.variants.first().id,
        listOf(
          RecipeIngredientLineRequest(cheese.ingredientId, 120),
          RecipeIngredientLineRequest(dough.ingredientId, 250),
        ),
      )
      putRecipe(
        RecipeTargetType.TOPPING,
        topping.id,
        listOf(RecipeIngredientLineRequest(basil.ingredientId, 15)),
      )

      val customers = (1..50).map { idx ->
        registerAndLoginCustomer(mailProvider, "c50-$idx")
      }

      val startGate = CompletableDeferred<Unit>()
      val results = coroutineScope {
        val jobs = customers.mapIndexed { idx, cust ->
          async(Dispatchers.IO) {
            startGate.await()
            val selectedProd = if (idx % 2 == 0) prodA else prodB
            val req = CheckoutRequest(
              items = listOf(
                CartItemInputDto(
                  productId = selectedProd.id,
                  variantId = selectedProd.variants.first().id,
                  crustId = crust.id,
                  toppingIds = if (idx % 3 == 0) listOf(topping.id) else emptyList(),
                  quantity = 1,
                ),
              ),
              fulfillmentType = FulfillmentType.PICKUP,
              scheduleType = ScheduleType.ASAP,
              paymentMethod = PaymentMethod.COD,
              clearCart = false,
            )
            client.post("/api/v1/checkout") {
              header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
              header("Idempotency-Key", "p19-50-${cust.user.id}")
              contentType(ContentType.Application.Json)
              setBody(json.encodeToString(req))
            }
          }
        }
        startGate.complete(Unit)
        jobs.awaitAll()
      }

      val statusCodes = results.map { it.status }
      val createdCount = statusCodes.count { it == HttpStatusCode.Created }
      val stockRejectedCount = statusCodes.count { it == HttpStatusCode.UnprocessableEntity }
      val unexpected = results.filter { it.status != HttpStatusCode.Created && it.status != HttpStatusCode.UnprocessableEntity }

      assertTrue("Unexpected HTTP statuses during 50-checkout race: ${unexpected.map { it.status to runBlocking { it.bodyAsText() } }}", unexpected.isEmpty())
      assertEquals(12, createdCount)
      assertEquals(38, stockRejectedCount)

      results.filter { it.status == HttpStatusCode.UnprocessableEntity }.forEach { resp ->
        val err = json.decodeFromString<ApiErrorEnvelope>(resp.bodyAsText())
        assertEquals("INSUFFICIENT_INVENTORY", err.error.code)
      }

      // Verify inventory, ledger, scheduled slot, and coupon invariants in PostgreSQL
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT on_hand_qty, reserved_qty, available_qty FROM inventory WHERE ingredient_id = ?::uuid").use { ps ->
          ps.setString(1, dough.ingredientId)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(0L, rs.getLong("on_hand_qty"))
            assertEquals(0L, rs.getLong("reserved_qty"))
            assertEquals(0L, rs.getLong("available_qty"))
          }
        }
        conn.prepareStatement("SELECT COUNT(*) FROM inventory_ledger WHERE ingredient_id = ?::uuid AND reason = 'COMMIT'").use { ps ->
          ps.setString(1, dough.ingredientId)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(12, rs.getInt(1))
          }
        }
      }

      // Part B: 20 concurrent scheduled checkouts competing for capacity=5 slot and max_uses=5 coupon
      val pincode = (561100 + (Math.abs(UUID.randomUUID().hashCode()) % 800)).toString()
      val slotId = UUID.randomUUID()
      val couponCode = "P19CAP-${suf.uppercase()}"
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement(
          "INSERT INTO delivery_zones (id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active) VALUES (?, ?, 'Koramangala', 20000, 3000, 25, TRUE)",
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.setString(2, pincode)
          ps.executeUpdate()
        }
        conn.prepareStatement(
          "INSERT INTO scheduled_slots (id, slot_date, fulfillment_type, start_time_ist, end_time_ist, cutoff_at, max_capacity, reserved_count, is_active) VALUES (?, ?, 'DELIVERY', ?, ?, ?, 5, 0, TRUE)",
        ).use { ps ->
          ps.setObject(1, slotId)
          ps.setObject(2, LocalDate.now().plusDays(slotDayCounter.getAndIncrement().toLong()))
          ps.setObject(3, LocalTime.of(19, 0))
          ps.setObject(4, LocalTime.of(20, 0))
          ps.setTimestamp(5, Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)))
          ps.executeUpdate()
        }
      }

      // Restock dough generously so slot + coupon cap are the contention points
      client.post("/api/v1/owner/inventory/adjust") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            com.pizzora.contracts.AdjustInventoryRequest(
              ingredientId = dough.ingredientId,
              operationId = "p19-restock-$suf",
              deltaQty = 10_000L,
              reason = com.pizzora.contracts.InventoryLedgerReason.RESTOCK,
            ),
          ),
        )
      }.also { assertEquals(HttpStatusCode.OK, it.status) }

      client.post("/api/v1/owner/coupons") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateCouponRequest(
              code = couponCode,
              discountType = DiscountType.FLAT_PAISE,
              discountValue = 5_000L,
              minOrderPaise = 30_000L,
              maxUses = 5,
              maxUsesPerUser = 1,
              startsAt = Instant.now().minus(1, ChronoUnit.HOURS).toString(),
              endsAt = Instant.now().plus(2, ChronoUnit.DAYS).toString(),
              isActive = true,
            ),
          ),
        )
      }.also { assertEquals(HttpStatusCode.Created, it.status) }

      val slotAddresses = customers.take(20).map { cust ->
        val addrResp = client.post("/api/v1/me/addresses") {
          header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(UpsertAddressRequest("Home", "100 Feet Road", null, null, pincode, "Bengaluru", true)))
        }
        assertEquals(HttpStatusCode.Created, addrResp.status)
        cust to json.decodeFromString<AddressDto>(addrResp.bodyAsText()).id
      }

      val slotGate = CompletableDeferred<Unit>()
      val slotResults = coroutineScope {
        val jobs = slotAddresses.map { (cust, addrId) ->
          async(Dispatchers.IO) {
            slotGate.await()
            val req = CheckoutRequest(
              items = listOf(
                CartItemInputDto(
                  productId = prodA.id,
                  variantId = prodA.variants.first().id,
                  crustId = crust.id,
                  toppingIds = emptyList(),
                  quantity = 1,
                ),
              ),
              fulfillmentType = FulfillmentType.DELIVERY,
              scheduleType = ScheduleType.SCHEDULED,
              scheduledSlotId = slotId.toString(),
              addressId = addrId,
              paymentMethod = PaymentMethod.COD,
              couponCode = couponCode,
            )
            client.post("/api/v1/checkout") {
              header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
              header("Idempotency-Key", "p19-slot-${cust.user.id}")
              contentType(ContentType.Application.Json)
              setBody(json.encodeToString(req))
            }
          }
        }
        slotGate.complete(Unit)
        jobs.awaitAll()
      }

      assertEquals(5, slotResults.count { it.status == HttpStatusCode.Created })
      assertEquals(0, slotResults.count { it.status.value >= 500 })

      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement(
          """
          SELECT s.reserved_count,
                 (SELECT COUNT(*) FROM scheduled_slot_reservations r WHERE r.slot_id = s.id AND r.status IN ('ACTIVE','COMMITTED')) AS active_res_count
          FROM scheduled_slots s
          WHERE s.id = ?
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, slotId)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(5, rs.getInt("reserved_count"))
            assertEquals(5, rs.getInt("active_res_count"))
          }
        }
        conn.prepareStatement("SELECT usage_count FROM coupons WHERE code = ?").use { ps ->
          ps.setString(1, couponCode)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(5, rs.getInt("usage_count"))
          }
        }
      }
    }

  @Test
  fun `Redis outage causes zero correctness failures across readiness, catalog, RBAC, ws-ticket, checkout, payment, KDS, replay, and rate limiting`() =
    testApplication {
      val mailProvider = InMemoryEmailDeliveryProvider()
      val gateway = FakeRazorpayGateway()
      val hub = RealtimeHub()
      val failingCache = ToggleableThrowingCacheStore().apply { outageActive.set(true) }
      val deadLettuceStore = LettuceRedisStore("redis://127.0.0.1:63999", timeoutMs = 100L)
      val sequencerWorker = EventSequencerWorker(pg.postgresDatabase)
      val outboxWorker = OutboxDispatcherWorker(
        dataSource = pg.postgresDatabase,
        razorpayGateway = gateway,
        maxAttempts = 3,
        realtimeDispatcher = hub,
      )

      try {
        application {
          pizzoraModule(
            config = baseTestConfig(rateLimitEnabled = true),
            probes = listOf(
              PostgresJdbcProbe(pg.postgresDatabase),
              object : DependencyProbe {
                override val name: String = "redis"
                override val required: Boolean = false
                override suspend fun check(): DependencyHealthStatus = DependencyHealthStatus.DOWN
              },
            ),
            dataSource = pg.postgresDatabase,
            cacheStore = failingCache,
            emailDeliveryProvider = mailProvider,
            objectStorageClient = DummyStorageClient,
            razorpayGateway = gateway,
            realtimeHub = hub,
            passwordHasher = fastHasher,
          )
        }

        // 0) Real LettuceRedisStore against unreachable port cleanly returns null/false without throwing
        assertFalse(deadLettuceStore.setEx("probe", 10L, "val"))
        assertEquals(null, deadLettuceStore.get("probe"))
        assertEquals(null, deadLettuceStore.getDel("probe"))
        assertEquals(null, deadLettuceStore.incrementWithinWindow("rl:probe", 60L))

        // 1) Health readiness stays 200 DEGRADED while Redis is DOWN and Postgres is UP
        val readyResp = client.get("/api/v1/health/ready")
        assertEquals(HttpStatusCode.OK, readyResp.status)
        val ready = json.decodeFromString<HealthReadinessResponse>(readyResp.bodyAsText())
        assertEquals(ReadinessState.DEGRADED, ready.status)
        assertEquals(DependencyHealthStatus.UP, ready.dependencies["postgres"])
        assertEquals(DependencyHealthStatus.DOWN, ready.dependencies["redis"])

        // 2) Public config and catalog fall back cleanly to PostgreSQL even when cache throws
        val configResp = client.get("/api/v1/config/public")
        assertEquals(HttpStatusCode.OK, configResp.status)
        assertEquals("Asia/Kolkata", json.decodeFromString<PublicConfigResponse>(configResp.bodyAsText()).timezone)

        // 3) Auth, RBAC, and catalog writes continue seamlessly with failing cache
        val ownerToken = loginOwner().accessToken
        val suf = UUID.randomUUID().toString().take(8)
        val cat = json.decodeFromString<CategoryDto>(
          client.post("/api/v1/owner/categories") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateCategoryRequest("OutageCat-$suf", 1, true)))
          }.bodyAsText(),
        )
        val prod = json.decodeFromString<ProductDto>(
          client.post("/api/v1/owner/products") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody(
              json.encodeToString(
                CreateProductRequest(
                  categoryId = cat.id,
                  name = "OutagePizza-$suf",
                  description = "Resilient Margherita",
                  isVeg = true,
                  variants = listOf(CreateVariantRequest(SizeCode.REGULAR, 29_900L, true)),
                ),
              ),
            )
          }.bodyAsText(),
        )
        val crust = json.decodeFromString<CrustOptionDto>(
          client.post("/api/v1/owner/crusts") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateCrustRequest("OutageCrust-$suf", 2_000L, true)))
          }.bodyAsText(),
        )
        val ing = json.decodeFromString<InventoryItemDto>(
          client.post("/api/v1/owner/inventory") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateIngredientRequest("OutageDough-$suf", IngredientUnit.GRAMS, 100L, 1_000L)))
          }.bodyAsText(),
        )
        client.put("/api/v1/owner/recipes") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              PutRecipeRequest(
                RecipeTargetType.VARIANT,
                prod.variants.first().id,
                listOf(RecipeIngredientLineRequest(ing.ingredientId, 200)),
              ),
            ),
          )
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val menuResp = client.get("/api/v1/catalog/menu")
        assertEquals(HttpStatusCode.OK, menuResp.status)
        val menu = json.decodeFromString<PublicMenuResponse>(menuResp.bodyAsText())
        assertTrue(menu.products.any { it.id == prod.id })

        // 4) Customer registers, verifies, checks out Razorpay order, verifies payment, tracks WS ticket fallback
        val cust = registerAndLoginCustomer(mailProvider, "outage")
        // Verify RBAC enforcement still rejects customer on owner endpoint during Redis outage
        val forbiddenResp = client.get("/api/v1/owner/dashboard") {
          header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenResp.status)

        // Mint WS ticket while Redis throws -> persists in PostgreSQL ws_tickets table
        val ticketResp = client.post("/api/v1/auth/ws-ticket") {
          header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
        }
        assertEquals(HttpStatusCode.OK, ticketResp.status)
        val ticket = json.decodeFromString<WsTicketResponse>(ticketResp.bodyAsText()).ticket

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/ws/v1/events?ticket=$ticket&since_seq=0") {
          val initialFrame = json.decodeFromString<WsEventsFrame>((incoming.receive() as Frame.Text).readText())
          assertTrue(initialFrame.serverHighWaterSeq >= 0L)
        }
        // Single-use replay of PG fallback ticket MUST still be rejected with 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/ws/v1/events?ticket=$ticket").status)

        val baselineSeq = json.decodeFromString<EventsReplayResponse>(
          client.get("/api/v1/events/replay?since_seq=0") {
            header(HttpHeaders.Authorization, "Bearer ${ownerToken}")
          }.bodyAsText(),
        ).serverHighWaterSeq

        val checkoutResp = client.post("/api/v1/checkout") {
          header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
          header("Idempotency-Key", "outage-rzp-${UUID.randomUUID()}")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CheckoutRequest(
                items = listOf(
                  CartItemInputDto(
                    productId = prod.id,
                    variantId = prod.variants.first().id,
                    crustId = crust.id,
                    toppingIds = emptyList(),
                    quantity = 1,
                  ),
                ),
                fulfillmentType = FulfillmentType.PICKUP,
                scheduleType = ScheduleType.ASAP,
                paymentMethod = PaymentMethod.RAZORPAY,
              ),
            ),
          )
        }
        assertEquals(HttpStatusCode.Created, checkoutResp.status)
        val order = json.decodeFromString<CheckoutResponse>(checkoutResp.bodyAsText()).order

        // Outbox & sequencer run cleanly with Redis down
        outboxWorker.runOnce()
        val detail = json.decodeFromString<OrderDetailDto>(
          client.get("/api/v1/orders/${order.id}") {
            header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
          }.bodyAsText(),
        )
        val gwOrderId = detail.payment.latestAttempt?.gatewayOrderId!!
        val gwPayId = "pay_outage_${UUID.randomUUID().toString().take(8)}"
        val sig = RazorpaySignatureVerifier.computeHmacSha256Hex("$gwOrderId|$gwPayId".toByteArray(), TEST_KEY_SECRET)

        client.post("/api/v1/payments/razorpay/verify") {
          header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(VerifyRazorpayPaymentRequest(order.id, gwOrderId, gwPayId, sig)))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        // KDS advances order through COMPLETED
        for (action in listOf("ack", "start-prep", "ready", "complete")) {
          val kdsAction = client.post("/api/v1/kds/orders/${order.id}/$action") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
          }
          assertEquals("Failed KDS $action during Redis outage", HttpStatusCode.OK, kdsAction.status)
        }

        repeat(5) {
          sequencerWorker.runOnce()
          outboxWorker.runOnce()
        }

        val replay = json.decodeFromString<EventsReplayResponse>(
          client.get("/api/v1/events/replay?since_seq=$baselineSeq&limit=50") {
            header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
          }.bodyAsText(),
        )
        assertTrue(replay.events.count { it.aggregateId == order.id } >= 5)

        // 5) Rate limiter falls back to in-memory token bucket when Redis throws and still blocks abuse with 429
        var hitRateLimit = false
        repeat(25) {
          val attempt = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"nobody@pizzora.test","password":"wrong"}""")
          }
          if (attempt.status == HttpStatusCode.TooManyRequests) {
            hitRateLimit = true
          }
        }
        assertTrue("Expected ResilientRateLimiter in-memory fallback to return 429 during Redis outage", hitRateLimit)
      } finally {
        deadLettuceStore.close()
      }
    }

  @Test
  fun `EXPLAIN ANALYZE confirms hot-path queries for catalog, KDS, sequencer, dispatcher, expiry, and reporting use indexes`() {
    val ownerId = pg.postgresDatabase.connection.use { conn ->
      conn.createStatement().use { st ->
        st.executeQuery("SELECT id FROM users WHERE email = '$ownerEmail'").use { rs ->
          rs.next()
          rs.getObject("id", UUID::class.java)
        }
      }
    }

    // Seed realistic multi-thousand row dataset so PostgreSQL's real cost-based planner evaluates index vs seq scan accurately
    val categoryIds = (1..5).map { UUID.randomUUID() }
    val ingredientIds = (1..10).map { UUID.randomUUID() }
    val sampleProductIds = mutableListOf<UUID>()

    pg.postgresDatabase.connection.use { conn ->
      conn.autoCommit = false
      conn.prepareStatement("INSERT INTO categories (id, name, sort_order, is_active) VALUES (?, ?, ?, TRUE)").use { ps ->
        categoryIds.forEachIndexed { i, id ->
          ps.setObject(1, id)
          ps.setString(2, "PlanCat-$i-$id")
          ps.setInt(3, i)
          ps.addBatch()
        }
        ps.executeBatch()
      }
      conn.prepareStatement("INSERT INTO ingredients (id, name, unit, low_stock_threshold) VALUES (?, ?, 'GRAMS', 100)").use { ps ->
        ingredientIds.forEachIndexed { i, id ->
          ps.setObject(1, id)
          ps.setString(2, "PlanIng-$i-$id")
          ps.addBatch()
        }
        ps.executeBatch()
      }

      val prodPs = conn.prepareStatement("INSERT INTO products (id, category_id, name, description, is_veg, is_active, is_taxable) VALUES (?, ?, ?, 'Desc', TRUE, ?, TRUE)")
      val varPs = conn.prepareStatement("INSERT INTO product_variants (id, product_id, size_code, price_paise, is_active) VALUES (?, ?, 'MEDIUM', 35000, ?)")
      val imgPs = conn.prepareStatement("INSERT INTO product_images (id, product_id, object_key, mime_type, size_bytes, status, sort_order, uploaded_by) VALUES (?, ?, ?, 'image/jpeg', 12000, ?, 0, ?)")
      for (i in 1..400) {
        val pid = UUID.randomUUID()
        if (i <= 5) sampleProductIds += pid
        val isActive = i <= 40 // 10% active -> highly selective partial index
        prodPs.setObject(1, pid)
        prodPs.setObject(2, categoryIds[i % categoryIds.size])
        prodPs.setString(3, "Pizza-$i-$pid")
        prodPs.setBoolean(4, isActive)
        prodPs.addBatch()

        varPs.setObject(1, UUID.randomUUID())
        varPs.setObject(2, pid)
        varPs.setBoolean(3, isActive)
        varPs.addBatch()

        imgPs.setObject(1, UUID.randomUUID())
        imgPs.setObject(2, pid)
        imgPs.setString(3, "products/$pid/img-$i.jpg")
        imgPs.setString(4, if (isActive) "ACTIVE" else "DELETED")
        imgPs.setObject(5, ownerId)
        imgPs.addBatch()
      }
      prodPs.executeBatch()
      varPs.executeBatch()
      imgPs.executeBatch()
      prodPs.close()
      varPs.close()
      imgPs.close()

      val orderPs = conn.prepareStatement(
        """
        INSERT INTO orders (
          id, order_number, customer_id, idempotency_key, request_hash, fulfillment_type,
          schedule_type, status, version, subtotal_paise, discount_paise, delivery_fee_paise,
          tax_paise, total_paise, gst_bps_snapshot, is_priority, expires_at, created_at
        ) VALUES (?, ?, ?, ?, 'h', 'PICKUP', 'ASAP', ?, 1, 35000, 0, 0, 1750, 36750, 500, ?, ?, ?)
        """.trimIndent(),
      )
      val itemPs = conn.prepareStatement(
        """
        INSERT INTO order_items (
          id, order_id, product_id, product_name_snapshot, variant_size_snapshot,
          crust_name_snapshot, toppings_snapshot, unit_price_paise, quantity, line_total_paise
        ) VALUES (?, ?, ?, 'Plan Pizza', 'MEDIUM', 'Hand Tossed', '[]'::jsonb, 35000, 1, 35000)
        """.trimIndent(),
      )
      val refundPs = conn.prepareStatement(
        """
        INSERT INTO payments (id, order_id, method, status, amount_paise, captured_paise, refunded_paise)
        VALUES (?, ?, 'RAZORPAY', 'CAPTURED', 36750, 36750, 0)
        """.trimIndent(),
      )
      val refRowPs = conn.prepareStatement(
        """
        INSERT INTO refunds (id, payment_id, order_id, idempotency_key, amount_paise, status, reason)
        VALUES (?, ?, ?, ?, 10000, 'PROCESSED', 'Partial')
        """.trimIndent(),
      )
      val ledgerPs = conn.prepareStatement(
        """
        INSERT INTO inventory_ledger (id, ingredient_id, order_id, operation_id, delta_on_hand, delta_reserved, reason, created_at)
        VALUES (?, ?, ?, ?, -200, 0, 'COMMIT', ?)
        """.trimIndent(),
      )
      val outboxPs = conn.prepareStatement(
        """
        INSERT INTO outbox_events (
          id, aggregate_type, aggregate_id, aggregate_version, event_type, payload_json,
          stream_seq, sequenced_at, dispatch_status, published_at, next_attempt_at, created_at
        ) VALUES (?, 'ORDER', ?, 1, 'ORDER_PLACED', '{}'::jsonb, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      )

      val now = Instant.now()
      val baseSeq = 1_000_000L
      val activeOrderIds = mutableListOf<UUID>()

      for (i in 1..1_500) {
        val oid = UUID.randomUUID()
        val status = when {
          i <= 15 -> {
            activeOrderIds += oid
            "PREPARING"
          }
          i <= 30 -> "PENDING_PAYMENT"
          else -> "COMPLETED"
        }
        val createdAt = Timestamp.from(now.minus((i % 90).toLong(), ChronoUnit.DAYS))
        val expiresAt = if (status == "PENDING_PAYMENT") Timestamp.from(now.minus(5, ChronoUnit.MINUTES)) else null

        orderPs.setObject(1, oid)
        orderPs.setString(2, "PZ-PLAN-$i-${oid.toString().take(6)}")
        orderPs.setObject(3, ownerId)
        orderPs.setString(4, "plan-idem-$oid")
        orderPs.setString(5, status)
        orderPs.setBoolean(6, i <= 3)
        orderPs.setTimestamp(7, expiresAt)
        orderPs.setTimestamp(8, createdAt)
        orderPs.addBatch()

        val itemsPerOrder = if (status == "COMPLETED") 5 else 1
        repeat(itemsPerOrder) {
          itemPs.setObject(1, UUID.randomUUID())
          itemPs.setObject(2, oid)
          itemPs.setObject(3, sampleProductIds[i % sampleProductIds.size])
          itemPs.addBatch()
        }

        val payId = UUID.randomUUID()
        refundPs.setObject(1, payId)
        refundPs.setObject(2, oid)
        refundPs.addBatch()

        if (i <= 250) {
          refRowPs.setObject(1, UUID.randomUUID())
          refRowPs.setObject(2, payId)
          refRowPs.setObject(3, oid)
          refRowPs.setString(4, "plan-rfnd-$oid")
          refRowPs.addBatch()
        }

        ledgerPs.setObject(1, UUID.randomUUID())
        ledgerPs.setObject(2, ingredientIds[i % ingredientIds.size])
        ledgerPs.setObject(3, oid)
        ledgerPs.setString(4, "plan-op-$oid")
        ledgerPs.setTimestamp(5, createdAt)
        ledgerPs.addBatch()

        val isUnsequenced = i <= 15
        val isPendingBroadcast = i in 16..30
        outboxPs.setObject(1, UUID.randomUUID())
        outboxPs.setObject(2, oid)
        if (isUnsequenced) {
          outboxPs.setNull(3, java.sql.Types.BIGINT)
          outboxPs.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
          outboxPs.setString(5, "PENDING")
          outboxPs.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        } else if (isPendingBroadcast) {
          outboxPs.setLong(3, baseSeq + i)
          outboxPs.setTimestamp(4, Timestamp.from(now))
          outboxPs.setString(5, "PENDING")
          outboxPs.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        } else {
          outboxPs.setLong(3, baseSeq + i)
          outboxPs.setTimestamp(4, Timestamp.from(now))
          outboxPs.setString(5, "DISPATCHED")
          outboxPs.setTimestamp(6, Timestamp.from(now))
        }
        outboxPs.setTimestamp(7, Timestamp.from(now))
        outboxPs.setTimestamp(8, createdAt)
        outboxPs.addBatch()
      }

      orderPs.executeBatch()
      itemPs.executeBatch()
      refundPs.executeBatch()
      refRowPs.executeBatch()
      ledgerPs.executeBatch()
      outboxPs.executeBatch()
      orderPs.close()
      itemPs.close()
      refundPs.close()
      refRowPs.close()
      ledgerPs.close()
      outboxPs.close()

      conn.commit()
      conn.autoCommit = true
      conn.createStatement().use { it.execute("VACUUM ANALYZE") }

      fun explainText(sql: String, binder: (java.sql.PreparedStatement) -> Unit = {}): String =
        conn.prepareStatement("EXPLAIN (ANALYZE, COSTS, BUFFERS) $sql").use { ps ->
          binder(ps)
          ps.executeQuery().use { rs ->
            buildString {
              while (rs.next()) appendLine(rs.getString(1))
            }
          }
        }

      fun assertUsesIndex(name: String, plan: String, forbiddenSeqTable: String) {
        assertTrue(
          "$name expected index scan in plan:\n$plan",
          plan.contains("Index Scan") || plan.contains("Index Only Scan") || plan.contains("Bitmap Index Scan"),
        )
        assertFalse(
          "$name hit unindexed Seq Scan on $forbiddenSeqTable:\n$plan",
          plan.contains("Seq Scan on $forbiddenSeqTable "),
        )
      }

      // 1) KDS active board header query
      val kdsHeaderPlan = explainText(
        """
        SELECT o.id, o.order_number, o.status, o.is_priority, o.created_at
        FROM orders o
        WHERE o.status = ANY(?)
        ORDER BY o.is_priority DESC, o.created_at ASC, o.id ASC
        LIMIT 100
        """.trimIndent(),
      ) { ps ->
        ps.setArray(1, conn.createArrayOf("text", arrayOf("PLACED", "ACCEPTED", "PREPARING", "READY", "OUT_FOR_DELIVERY")))
      }
      assertUsesIndex("KDS header query", kdsHeaderPlan, "orders")

      // 2) KDS / Order items batch load
      val kdsItemsPlan = explainText(
        "SELECT id, order_id, product_name_snapshot FROM order_items WHERE order_id = ANY(?) ORDER BY order_id ASC, id ASC",
      ) { ps ->
        ps.setArray(1, conn.createArrayOf("uuid", activeOrderIds.take(10).toTypedArray()))
      }
      assertUsesIndex("KDS order_items batch query", kdsItemsPlan, "order_items")

      // 3) EventSequencerWorker unsequenced poll
      val seqPollPlan = explainText(
        "SELECT id FROM outbox_events WHERE stream_seq IS NULL ORDER BY created_at ASC, id ASC LIMIT 100",
      )
      assertUsesIndex("EventSequencerWorker poll", seqPollPlan, "outbox_events")

      // 4) OutboxDispatcherWorker broadcast queue poll
      val dispatchPollPlan = explainText(
        """
        SELECT id, stream_seq
        FROM outbox_events
        WHERE stream_seq IS NOT NULL
          AND event_type NOT IN ('PAYMENT_GATEWAY_ORDER_REQUESTED', 'REFUND_REQUESTED')
          AND dispatch_status IN ('PENDING', 'PROCESSING')
          AND next_attempt_at <= NOW()
        ORDER BY stream_seq ASC
        LIMIT 10
        """.trimIndent(),
      )
      assertUsesIndex("OutboxDispatcherWorker broadcast poll", dispatchPollPlan, "outbox_events")

      // 5) ExpirySweepWorker candidate poll
      val expiryPlan = explainText(
        "SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND expires_at IS NOT NULL AND expires_at <= NOW() ORDER BY expires_at ASC LIMIT 25",
      )
      assertUsesIndex("ExpirySweepWorker candidate poll", expiryPlan, "orders")

      // 6) ReportingService narrow-window sales & inventory ledger queries
      val startWindow = Timestamp.from(now.minus(1, ChronoUnit.DAYS))
      val endWindow = Timestamp.from(now.plus(1, ChronoUnit.DAYS))
      val salesOrdersPlan = explainText(
        "SELECT id, status, total_paise FROM orders WHERE created_at >= ? AND created_at < ?",
      ) { ps ->
        ps.setTimestamp(1, startWindow)
        ps.setTimestamp(2, endWindow)
      }
      assertUsesIndex("Sales report orders range query", salesOrdersPlan, "orders")

      val ledgerPlan = explainText(
        "SELECT reason, delta_on_hand FROM inventory_ledger WHERE created_at >= ? AND created_at < ?",
      ) { ps ->
        ps.setTimestamp(1, startWindow)
        ps.setTimestamp(2, endWindow)
      }
      assertUsesIndex("Operations report inventory_ledger range query", ledgerPlan, "inventory_ledger")

      // 7) Catalog active variants & images queries
      val activeVariantsPlan = explainText(
        "SELECT id, product_id, size_code, price_paise FROM product_variants WHERE is_active = TRUE AND product_id = ?",
      ) { ps ->
        ps.setObject(1, sampleProductIds.first())
      }
      assertUsesIndex("Catalog active variants query", activeVariantsPlan, "product_variants")

      val activeImagesPlan = explainText(
        "SELECT id, object_key FROM product_images WHERE status = 'ACTIVE' AND product_id = ? ORDER BY sort_order ASC, created_at ASC",
      ) { ps ->
        ps.setObject(1, sampleProductIds.first())
      }
      assertUsesIndex("Catalog active images query", activeImagesPlan, "product_images")
    }
  }

  @Test
  fun `mixed read-write load test across catalog, pricing quote, KDS board, and sales reports completes cleanly`() =
    testApplication {
      val mailProvider = InMemoryEmailDeliveryProvider()
      application {
        pizzoraModule(
          config = baseTestConfig(rateLimitEnabled = false),
          probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
          dataSource = pg.postgresDatabase,
          cacheStore = InMemoryCacheStore(),
          emailDeliveryProvider = mailProvider,
          objectStorageClient = DummyStorageClient,
          passwordHasher = fastHasher,
        )
      }

      val ownerToken = loginOwner().accessToken
      val cust = registerAndLoginCustomer(mailProvider, "load19")
      val suf = UUID.randomUUID().toString().take(8)
      val cat = json.decodeFromString<CategoryDto>(
        client.post("/api/v1/owner/categories") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCategoryRequest("LoadCat-$suf", 1, true)))
        }.bodyAsText(),
      )
      val prod = json.decodeFromString<ProductDto>(
        client.post("/api/v1/owner/products") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateProductRequest(
                categoryId = cat.id,
                name = "LoadPizza-$suf",
                description = "Load test pizza",
                isVeg = true,
                variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 40_000L, true)),
              ),
            ),
          )
        }.bodyAsText(),
      )
      val crust = json.decodeFromString<CrustOptionDto>(
        client.post("/api/v1/owner/crusts") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCrustRequest("LoadCrust-$suf", 5_000L, true)))
        }.bodyAsText(),
      )

      val statuses = coroutineScope {
        (1..100).map { i ->
          async(Dispatchers.IO) {
            when (i % 4) {
              0 -> client.get("/api/v1/catalog/menu").status
              1 -> client.post("/api/v1/pricing/quote") {
                header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                  json.encodeToString(
                    PricingQuoteRequest(
                      items = listOf(
                        CartItemInputDto(
                          productId = prod.id,
                          variantId = prod.variants.first().id,
                          crustId = crust.id,
                          toppingIds = emptyList(),
                          quantity = 1,
                        ),
                      ),
                      fulfillmentType = FulfillmentType.PICKUP,
                    ),
                  ),
                )
              }.also {
                val quote = json.decodeFromString<PricingQuoteResponse>(it.bodyAsText())
                assertEquals(45_000L, quote.breakdown.subtotalPaise)
              }.status
              2 -> client.get("/api/v1/kds/tickets") {
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
              }.also {
                assertNotNull(json.decodeFromString<KdsTicketsResponse>(it.bodyAsText()).serverNow)
              }.status
              else -> client.get("/api/v1/owner/reports/sales") {
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
              }.status
            }
          }
        }.awaitAll()
      }

      assertTrue("Expected all 100 load requests to return 200 OK; got ${statuses.filter { it != HttpStatusCode.OK }}", statuses.all { it == HttpStatusCode.OK })
    }
}
