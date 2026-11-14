package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.config.RazorpayConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.images.InspectedObject
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.orders.OrderService
import com.pizzora.backend.payments.PaymentService
import com.pizzora.backend.razorpay.GatewayOrderResult
import com.pizzora.backend.razorpay.GatewayPaymentResult
import com.pizzora.backend.razorpay.GatewayRefundResult
import com.pizzora.backend.razorpay.RazorpayGateway
import com.pizzora.backend.razorpay.RazorpaySignatureVerifier
import com.pizzora.backend.realtime.RealtimeEventDispatcher
import com.pizzora.backend.realtime.RealtimeHub
import com.pizzora.backend.worker.EventSequencerWorker
import com.pizzora.backend.worker.OutboxDispatcherWorker
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.EventsReplayResponse
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.TransitionOrderStatusRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.VerifyEmailRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.WsEventsFrame
import com.pizzora.contracts.WsTicketResponse
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase12RealtimeAndOrderTrackingTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-p12-${UUID.randomUUID()}@pizzora.test"
    private val ownerPass = "OwnerPass123!"
    private val testKeyId = "rzp_test_pizzora_12"
    private val testKeySecret = "rzp_secret_pizzora_super_secret_12"
    private val testWebhookSecret = "rzp_webhook_pizzora_secret_12"

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
          fullName = "Owner P12",
          phone = "9999990012",
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
    override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long = 1L
  }

  private class StubStorage : ObjectStorageClient {
    override fun presignPut(objectKey: String, contentType: String, expiresInSeconds: Long) =
      "http://localhost:9000/test/$objectKey"
    override suspend fun inspectObject(objectKey: String): InspectedObject? =
      InspectedObject(128L, "image/jpeg", byteArrayOf())
    override suspend fun deleteObject(objectKey: String): Boolean = true
    override fun publicUrlFor(objectKey: String) = "http://localhost:9000/test/$objectKey"
  }

  private class FakeRazorpayGateway : RazorpayGateway {
    override val publicKeyId: String = testKeyId
    val seq = AtomicInteger(1)

    override suspend fun createOrder(
      amountPaise: Long,
      currency: String,
      receipt: String,
      notes: Map<String, String>,
    ): GatewayOrderResult = GatewayOrderResult(
      gatewayOrderId = "order_p12_${seq.getAndIncrement()}",
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
      gatewayRefundId = "rfnd_p12_${seq.getAndIncrement()}",
      gatewayPaymentId = gatewayPaymentId,
      amountPaise = amountPaise,
      status = "processed",
    )

    override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> = emptyList()
  }

  private fun testConfig(): AppConfig {
    val base = AppConfig.fromEnv(mapOf("JWT_SECRET" to "test-jwt-secret-minimum-32-bytes-long-key"))
    return base.copy(
      razorpay = RazorpayConfig(
        keyId = testKeyId,
        keySecret = testKeySecret,
        webhookSecret = testWebhookSecret,
      ),
    )
  }

  private data class TestFixture(
    val emailProvider: InMemoryEmailDeliveryProvider,
    val cacheStore: InMemoryCacheStore,
    val razorpayGateway: FakeRazorpayGateway,
    val realtimeHub: RealtimeHub,
    val sequencerWorker: EventSequencerWorker,
    val outboxWorker: OutboxDispatcherWorker,
    val orderService: OrderService,
  )

  private fun ApplicationTestBuilder.setupApp(
    customDispatcher: RealtimeEventDispatcher? = null,
  ): TestFixture {
    val emailProvider = InMemoryEmailDeliveryProvider()
    val cache = InMemoryCacheStore()
    val gateway = FakeRazorpayGateway()
    val hub = RealtimeHub()
    val cfg = testConfig()
    val inventoryService = InventoryService(pg.postgresDatabase)
    val couponService = CouponService(pg.postgresDatabase)
    val orderService = OrderService(pg.postgresDatabase, inventoryService, couponService)
    val sequencerWorker = EventSequencerWorker(pg.postgresDatabase)
    val outboxWorker = OutboxDispatcherWorker(
      dataSource = pg.postgresDatabase,
      razorpayGateway = gateway,
      maxAttempts = 3,
      realtimeDispatcher = customDispatcher ?: hub,
    )

    application {
      pizzoraModule(
        config = cfg,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = cache,
        emailDeliveryProvider = emailProvider,
        objectStorageClient = StubStorage(),
        razorpayGateway = gateway,
        realtimeHub = hub,
        passwordHasher = fastHasher,
      )
    }

    return TestFixture(
      emailProvider = emailProvider,
      cacheStore = cache,
      razorpayGateway = gateway,
      realtimeHub = hub,
      sequencerWorker = sequencerWorker,
      outboxWorker = outboxWorker,
      orderService = orderService,
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
    prefix: String = "cust12",
  ): AuthTokensResponse {
    val email = "$prefix-${UUID.randomUUID()}@pizzora.test"
    val reg = client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(email, "CustPass123!", "Phase12 User", "9876540012")))
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

  private data class SeedCatalog(
    val productId: String,
    val variantId: String,
    val crustId: String,
    val toppingId: String,
  )

  private suspend fun ApplicationTestBuilder.seedProductWithStock(ownerToken: String, stock: Long = 5000L): SeedCatalog {
    val suf = UUID.randomUUID().toString().take(8)
    val catResp = client.post("/api/v1/owner/categories") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCategoryRequest("Realtime-$suf", 1)))
    }
    val category = json.decodeFromString<CategoryDto>(catResp.bodyAsText())

    val prodResp = client.post("/api/v1/owner/products") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateProductRequest(
            categoryId = category.id,
            name = "Diavola-$suf",
            description = "Wood-fired",
            isVeg = true,
            variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 40_000L)),
          ),
        ),
      )
    }
    val product = json.decodeFromString<ProductDto>(prodResp.bodyAsText())
    val variant = product.variants.first()

    val crustResp = client.post("/api/v1/owner/crusts") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCrustRequest("Crust12-$suf", 5_000L)))
    }
    val crust = json.decodeFromString<CrustOptionDto>(crustResp.bodyAsText())

    val topResp = client.post("/api/v1/owner/toppings") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateToppingRequest("Basil12-$suf", true, 5_000L)))
    }
    val topping = json.decodeFromString<ToppingDto>(topResp.bodyAsText())

    val ingResp = client.post("/api/v1/owner/inventory") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateIngredientRequest("Dough12-$suf", IngredientUnit.GRAMS, 100L, stock)))
    }
    val ing = json.decodeFromString<InventoryItemDto>(ingResp.bodyAsText())

    client.put("/api/v1/owner/recipes") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          PutRecipeRequest(
            targetType = RecipeTargetType.VARIANT,
            targetId = variant.id,
            ingredients = listOf(RecipeIngredientLineRequest(ing.ingredientId, 10)),
          ),
        ),
      )
    }

    return SeedCatalog(product.id, variant.id, crust.id, topping.id)
  }

  private suspend fun ApplicationTestBuilder.createPickupOrder(
    token: String,
    seed: SeedCatalog,
    paymentMethod: PaymentMethod,
  ): OrderDetailDto {
    val resp = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer $token")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CheckoutRequest(
            items = listOf(
              CartItemInputDto(
                productId = seed.productId,
                variantId = seed.variantId,
                crustId = seed.crustId,
                toppingIds = listOf(seed.toppingId),
                quantity = 1,
              ),
            ),
            fulfillmentType = FulfillmentType.PICKUP,
            scheduleType = ScheduleType.ASAP,
            paymentMethod = paymentMethod,
            clearCart = false,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, resp.status)
    return json.decodeFromString<CheckoutResponse>(resp.bodyAsText()).order
  }

  private suspend fun drainSequencerAndDispatcher(f: TestFixture) {
    for (i in 0 until 10) {
      val s = f.sequencerWorker.runOnce()
      val d = f.outboxWorker.runOnce()
      if (s == 0 && d == 0) break
    }
  }

  @Test
  fun `out-of-order concurrent transactions never skip committed events in stream_seq replay`() = testApplication {
    val f = setupApp()
    val owner = loginOwner()
    val custA = registerAndLoginCustomer(f.emailProvider, "ooo-a")
    val custB = registerAndLoginCustomer(f.emailProvider, "ooo-b")

    val baselineCursor = f.sequencerWorker.runOnce().let {
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT last_seq FROM event_stream_cursor WHERE id = 1").use { ps ->
          ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
      }
    }

    val slowOrderId = UUID.randomUUID()
    val fastOrderId = UUID.randomUUID()
    val slowInsertedBeforeCommit = CompletableDeferred<Unit>()
    val releaseSlowCommit = CompletableDeferred<Unit>()

    coroutineScope {
      val slowTxJob = async(Dispatchers.IO) {
        pg.postgresDatabase.connection.use { conn ->
          conn.autoCommit = false
          try {
            val cidA = UUID.fromString(custA.user.id)
            conn.prepareStatement(
              """
              INSERT INTO orders (
                id, order_number, customer_id, idempotency_key, request_hash, fulfillment_type,
                schedule_type, status, version, subtotal_paise, discount_paise, delivery_fee_paise,
                tax_paise, total_paise, gst_bps_snapshot
              ) VALUES (?, ?, ?, ?, 'h1', 'PICKUP', 'ASAP', 'PLACED', 1, 10000, 0, 0, 500, 10500, 500)
              """.trimIndent(),
            ).use { ps ->
              ps.setObject(1, slowOrderId)
              ps.setString(2, "PZ-SLOW-${slowOrderId.toString().take(6)}")
              ps.setObject(3, cidA)
              ps.setString(4, "idemp-slow-$slowOrderId")
              ps.executeUpdate()
            }
            conn.prepareStatement(
              """
              INSERT INTO outbox_events (
                id, aggregate_type, aggregate_id, aggregate_version, event_type,
                payload_json, stream_seq, sequenced_at, dispatch_status, published_at, created_at
              ) VALUES (?, 'ORDER', ?, 1, 'ORDER_PLACED', ?::jsonb, NULL, NULL, 'PENDING', NULL, NOW() - INTERVAL '5 seconds')
              """.trimIndent(),
            ).use { ps ->
              ps.setObject(1, UUID.randomUUID())
              ps.setObject(2, slowOrderId)
              ps.setString(3, "{\"order_id\":\"$slowOrderId\",\"customer_id\":\"$cidA\",\"order_number\":\"SLOW\"}")
              ps.executeUpdate()
            }
            slowInsertedBeforeCommit.complete(Unit)
            releaseSlowCommit.await()
            conn.commit()
          } catch (t: Throwable) {
            conn.rollback()
            throw t
          }
        }
      }

      slowInsertedBeforeCommit.await()

      // Concurrent faster transaction B inserts and commits first
      pg.postgresDatabase.connection.use { conn ->
        conn.autoCommit = false
        val cidB = UUID.fromString(custB.user.id)
        conn.prepareStatement(
          """
          INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash, fulfillment_type,
            schedule_type, status, version, subtotal_paise, discount_paise, delivery_fee_paise,
            tax_paise, total_paise, gst_bps_snapshot
          ) VALUES (?, ?, ?, ?, 'h2', 'PICKUP', 'ASAP', 'PLACED', 1, 10000, 0, 0, 500, 10500, 500)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, fastOrderId)
          ps.setString(2, "PZ-FAST-${fastOrderId.toString().take(6)}")
          ps.setObject(3, cidB)
          ps.setString(4, "idemp-fast-$fastOrderId")
          ps.executeUpdate()
        }
        conn.prepareStatement(
          """
          INSERT INTO outbox_events (
            id, aggregate_type, aggregate_id, aggregate_version, event_type,
            payload_json, stream_seq, sequenced_at, dispatch_status, published_at
          ) VALUES (?, 'ORDER', ?, 1, 'ORDER_PLACED', ?::jsonb, NULL, NULL, 'PENDING', NULL)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.setObject(2, fastOrderId)
          ps.setString(3, "{\"order_id\":\"$fastOrderId\",\"customer_id\":\"$cidB\",\"order_number\":\"FAST\"}")
          ps.executeUpdate()
        }
        conn.commit()
      }

      // Sequencer runs BEFORE slow TX A commits -> assigns seq (baseline + 1) to fast TX B
      val firstSeqCount = f.sequencerWorker.runOnce()
      assertEquals(1, firstSeqCount)

      // Now allow slow TX A (with earlier created_at) to commit
      releaseSlowCommit.complete(Unit)
      slowTxJob.await()
    }

    // Sequencer runs AFTER slow TX A commits -> picks up uncommitted-until-now row cleanly with seq (baseline + 2)
    val secondSeqCount = f.sequencerWorker.runOnce()
    assertEquals(1, secondSeqCount)

    val replayResp = client.get("/api/v1/events/replay?since_seq=$baselineCursor&limit=50") {
      header(HttpHeaders.Authorization, "Bearer ${owner.accessToken}")
    }
    assertEquals(HttpStatusCode.OK, replayResp.status)
    val replay = json.decodeFromString<EventsReplayResponse>(replayResp.bodyAsText())
    assertEquals(listOf(fastOrderId.toString(), slowOrderId.toString()), replay.events.map { it.aggregateId })
    assertEquals(listOf(baselineCursor + 1L, baselineCursor + 2L), replay.events.map { it.streamSeq })
    assertEquals(baselineCursor + 2L, replay.serverHighWaterSeq)
  }

  @Test
  fun `crash after sequencing keeps published_at null until dispatcher succeeds and redelivers idempotently`() = testApplication {
    var shouldFailDispatch = true
    val deliveredEvents = mutableListOf<String>()
    val failingDispatcher = RealtimeEventDispatcher { event ->
      if (shouldFailDispatch) {
        error("Simulated WebSocket broker outage mid-dispatch")
      }
      deliveredEvents += event.wireEvent.eventId
      true
    }

    val f = setupApp(customDispatcher = failingDispatcher)
    val owner = loginOwner()
    val cust = registerAndLoginCustomer(f.emailProvider, "crash-rec")
    val seed = seedProductWithStock(owner.accessToken)
    val order = createPickupOrder(cust.accessToken, seed, PaymentMethod.COD)

    // Stage 1: Sequencer assigns stream_seq and sequenced_at while published_at remains NULL
    val seqCount = f.sequencerWorker.runOnce()
    assertTrue(seqCount >= 1)

    val preDispatchState = pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        "SELECT id, stream_seq, sequenced_at, dispatch_status, published_at FROM outbox_events WHERE aggregate_id = ?",
      ).use { ps ->
        ps.setObject(1, UUID.fromString(order.id))
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          Triple(
            rs.getObject("id", UUID::class.java),
            rs.getLong("stream_seq") to rs.getTimestamp("sequenced_at"),
            rs.getString("dispatch_status") to rs.getTimestamp("published_at"),
          )
        }
      }
    }
    assertTrue(preDispatchState.second.first > 0L)
    assertNotNull(preDispatchState.second.second)
    assertEquals("PENDING", preDispatchState.third.first)
    assertNull(preDispatchState.third.second)

    // Stage 2 attempt 1 fails: published_at MUST stay NULL and row stays retryable
    f.outboxWorker.runOnce()
    val afterFailureState = pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        "SELECT dispatch_status, published_at, attempts, last_error FROM outbox_events WHERE id = ?",
      ).use { ps ->
        ps.setObject(1, preDispatchState.first)
        ps.executeQuery().use { rs ->
          rs.next()
          Triple(
            rs.getString("dispatch_status"),
            rs.getTimestamp("published_at"),
            rs.getInt("attempts") to rs.getString("last_error"),
          )
        }
      }
    }
    assertEquals("PENDING", afterFailureState.first)
    assertNull(afterFailureState.second)
    assertEquals(1, afterFailureState.third.first)
    assertTrue(afterFailureState.third.second?.contains("Simulated WebSocket broker outage") == true)

    // Simulate worker crash recovery where lease was PROCESSING and expired
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        "UPDATE outbox_events SET dispatch_status = 'PROCESSING', next_attempt_at = ? WHERE id = ?",
      ).use { ps ->
        ps.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(5)))
        ps.setObject(2, preDispatchState.first)
        ps.executeUpdate()
      }
    }

    shouldFailDispatch = false
    val recovered = f.outboxWorker.runOnce()
    assertEquals(1, recovered)
    assertEquals(listOf(preDispatchState.first.toString()), deliveredEvents)

    val afterRecoveryState = pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        "SELECT dispatch_status, published_at FROM outbox_events WHERE id = ?",
      ).use { ps ->
        ps.setObject(1, preDispatchState.first)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.getString("dispatch_status") to rs.getTimestamp("published_at")
        }
      }
    }
    assertEquals("DISPATCHED", afterRecoveryState.first)
    assertNotNull(afterRecoveryState.second)
  }

  @Test
  fun `cross-room filtered replay advances server_high_water_seq cleanly without leaking other users events`() = testApplication {
    val f = setupApp()
    val owner = loginOwner()
    val custA = registerAndLoginCustomer(f.emailProvider, "room-a")
    val custB = registerAndLoginCustomer(f.emailProvider, "room-b")
    val seed = seedProductWithStock(owner.accessToken)

    // Drain any prior events cleanly
    drainSequencerAndDispatcher(f)
    val startCursor = json.decodeFromString<EventsReplayResponse>(
      client.get("/api/v1/events/replay?since_seq=0&limit=200") {
        header(HttpHeaders.Authorization, "Bearer ${owner.accessToken}")
      }.bodyAsText(),
    ).serverHighWaterSeq

    val orderA = createPickupOrder(custA.accessToken, seed, PaymentMethod.COD)
    val orderB = createPickupOrder(custB.accessToken, seed, PaymentMethod.COD)

    // Transition Customer B's order twice (PLACED -> ACCEPTED -> PREPARING)
    val accResp = client.patch("/api/v1/owner/orders/${orderB.id}/status") {
      header(HttpHeaders.Authorization, "Bearer ${owner.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(TransitionOrderStatusRequest(OrderStatus.ACCEPTED, "Oven ready")))
    }
    assertEquals(HttpStatusCode.OK, accResp.status)
    val prepResp = client.patch("/api/v1/owner/orders/${orderB.id}/status") {
      header(HttpHeaders.Authorization, "Bearer ${owner.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(TransitionOrderStatusRequest(OrderStatus.PREPARING, "Baking")))
    }
    assertEquals(HttpStatusCode.OK, prepResp.status)

    drainSequencerAndDispatcher(f)

    // Customer A replays from startCursor: sees ONLY Order A (1 event) yet server_high_water_seq == startCursor + 4!
    val replayA = json.decodeFromString<EventsReplayResponse>(
      client.get("/api/v1/events/replay?since_seq=$startCursor&limit=50") {
        header(HttpHeaders.Authorization, "Bearer ${custA.accessToken}")
      }.bodyAsText(),
    )
    assertEquals(1, replayA.events.size)
    assertEquals(orderA.id, replayA.events.first().aggregateId)
    assertEquals(startCursor + 4L, replayA.serverHighWaterSeq)
    assertFalse(replayA.hasMore)

    // Follow-up replay from server_high_water_seq returns 0 events
    val followUpA = json.decodeFromString<EventsReplayResponse>(
      client.get("/api/v1/events/replay?since_seq=${replayA.serverHighWaterSeq}&limit=50") {
        header(HttpHeaders.Authorization, "Bearer ${custA.accessToken}")
      }.bodyAsText(),
    )
    assertTrue(followUpA.events.isEmpty())
    assertEquals(replayA.serverHighWaterSeq, followUpA.serverHighWaterSeq)

    // Customer B replays and sees all 3 of Order B's events (created + accepted + preparing)
    val replayB = json.decodeFromString<EventsReplayResponse>(
      client.get("/api/v1/events/replay?since_seq=$startCursor&limit=50") {
        header(HttpHeaders.Authorization, "Bearer ${custB.accessToken}")
      }.bodyAsText(),
    )
    assertEquals(3, replayB.events.size)
    assertTrue(replayB.events.all { it.aggregateId == orderB.id })
    assertEquals(listOf(1L, 2L, 3L), replayB.events.map { it.aggregateVersion })
  }

  @Test
  fun `websocket enforces single-use ws-ticket and delivers room-isolated gateway_ready and order status progression`() = testApplication {
    val f = setupApp()
    val owner = loginOwner()
    val custA = registerAndLoginCustomer(f.emailProvider, "ws-a")
    val custB = registerAndLoginCustomer(f.emailProvider, "ws-b")
    val seed = seedProductWithStock(owner.accessToken)

    drainSequencerAndDispatcher(f)
    val baselineSeq = json.decodeFromString<EventsReplayResponse>(
      client.get("/api/v1/events/replay?since_seq=0") {
        header(HttpHeaders.Authorization, "Bearer ${owner.accessToken}")
      }.bodyAsText(),
    ).serverHighWaterSeq

    // 1) Unauthenticated or invalid ticket upgrade must return 401 before upgrading
    val noAuthResp = client.get("/ws/v1/events")
    assertEquals(HttpStatusCode.Unauthorized, noAuthResp.status)
    val badTicketResp = client.get("/ws/v1/events?ticket=invalid-ticket-xyz")
    assertEquals(HttpStatusCode.Unauthorized, badTicketResp.status)

    // 2) Mint single-use ws-tickets for Customer A and Customer B
    val ticketAResp = client.post("/api/v1/auth/ws-ticket") {
      header(HttpHeaders.Authorization, "Bearer ${custA.accessToken}")
    }
    assertEquals(HttpStatusCode.OK, ticketAResp.status)
    val ticketA = json.decodeFromString<WsTicketResponse>(ticketAResp.bodyAsText()).ticket

    val ticketB = json.decodeFromString<WsTicketResponse>(
      client.post("/api/v1/auth/ws-ticket") {
        header(HttpHeaders.Authorization, "Bearer ${custB.accessToken}")
      }.bodyAsText(),
    ).ticket

    val wsClient = createClient {
      install(ClientWebSockets)
    }

    val receivedByA = mutableListOf<WsEventsFrame>()
    val receivedByB = mutableListOf<WsEventsFrame>()

    val aConnected = CompletableDeferred<Unit>()
    val bConnected = CompletableDeferred<Unit>()
    val doneStreaming = CompletableDeferred<Unit>()

    lateinit var orderA: OrderDetailDto
    coroutineScope {
      val jobA = async {
        wsClient.webSocket("/ws/v1/events?ticket=$ticketA&since_seq=$baselineSeq") {
          val first = json.decodeFromString<WsEventsFrame>((incoming.receive() as Frame.Text).readText())
          receivedByA += first
          aConnected.complete(Unit)
          withTimeout(5_000) {
            while (!doneStreaming.isCompleted) {
              val frame = incoming.tryReceive().getOrNull() as? Frame.Text
              if (frame != null) {
                receivedByA += json.decodeFromString<WsEventsFrame>(frame.readText())
              } else {
                kotlinx.coroutines.delay(25)
              }
            }
            // Drain any trailing queued frames cleanly
            while (true) {
              val trailing = incoming.tryReceive().getOrNull() as? Frame.Text ?: break
              receivedByA += json.decodeFromString<WsEventsFrame>(trailing.readText())
            }
          }
        }
      }

      val jobB = async {
        wsClient.webSocket("/ws/v1/events?ticket=$ticketB&since_seq=$baselineSeq") {
          val first = json.decodeFromString<WsEventsFrame>((incoming.receive() as Frame.Text).readText())
          receivedByB += first
          bConnected.complete(Unit)
          withTimeout(5_000) {
            while (!doneStreaming.isCompleted) {
              val frame = incoming.tryReceive().getOrNull() as? Frame.Text
              if (frame != null) {
                receivedByB += json.decodeFromString<WsEventsFrame>(frame.readText())
              } else {
                kotlinx.coroutines.delay(25)
              }
            }
          }
        }
      }

      aConnected.await()
      bConnected.await()

      // Reusing consumed single-use ticketA must be rejected with 401
      val replayTicketResp = client.get("/ws/v1/events?ticket=$ticketA")
      assertEquals(HttpStatusCode.Unauthorized, replayTicketResp.status)

      // Customer A places Razorpay pickup order -> gateway_ready -> verify payment -> ACCEPTED -> PREPARING -> READY
      orderA = createPickupOrder(custA.accessToken, seed, PaymentMethod.RAZORPAY)
      // Outbox pass 1: creates gateway order + inserts PAYMENT_GATEWAY_READY
      f.outboxWorker.runOnce()
      // Sequence & dispatch PAYMENT_GATEWAY_READY
      drainSequencerAndDispatcher(f)

      // Verify Razorpay payment (PENDING_PAYMENT -> PLACED, version 2)
      val detailAfterGw = json.decodeFromString<OrderDetailDto>(
        client.get("/api/v1/orders/${orderA.id}") {
          header(HttpHeaders.Authorization, "Bearer ${custA.accessToken}")
        }.bodyAsText(),
      )
      val gwOrderId = detailAfterGw.payment.latestAttempt?.gatewayOrderId!!
      val gwPayId = "pay_p12_${UUID.randomUUID().toString().take(8)}"
      val sig = RazorpaySignatureVerifier.computeHmacSha256Hex("$gwOrderId|$gwPayId".toByteArray(), testKeySecret)

      val verifyResp = client.post("/api/v1/payments/razorpay/verify") {
        header(HttpHeaders.Authorization, "Bearer ${custA.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(VerifyRazorpayPaymentRequest(orderA.id, gwOrderId, gwPayId, sig)))
      }
      assertEquals(HttpStatusCode.OK, verifyResp.status)

      // 3 status transitions (PLACED -> ACCEPTED -> PREPARING -> READY)
      for (nextStatus in listOf(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY)) {
        val trResp = client.patch("/api/v1/owner/orders/${orderA.id}/status") {
          header(HttpHeaders.Authorization, "Bearer ${owner.accessToken}")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(TransitionOrderStatusRequest(nextStatus, "Stage $nextStatus")))
        }
        assertEquals(HttpStatusCode.OK, trResp.status)
      }

      drainSequencerAndDispatcher(f)
      kotlinx.coroutines.delay(250)
      doneStreaming.complete(Unit)
      jobA.await()
      jobB.await()
    }

    val eventsA = receivedByA.flatMap { it.events }
    val eventsB = receivedByB.flatMap { it.events }

    // Customer B never saw any of Customer A's events
    assertTrue(eventsB.none { it.aggregateId == orderA.id })

    // Customer A received payment.gateway_ready (v1), order.created (v2), and 3 status changes (v3, v4, v5) in exact stream_seq order
    assertEquals(
      listOf(
        "payment.gateway_ready",
        "order.created",
        "order.status_changed",
        "order.status_changed",
        "order.status_changed",
      ),
      eventsA.map { it.eventType },
    )
    assertEquals(listOf(1L, 2L, 3L, 4L, 5L), eventsA.map { it.aggregateVersion })
    assertTrue(eventsA.zipWithNext().all { (a, b) -> a.streamSeq < b.streamSeq })

    // Reconnect test: fresh Bearer-authenticated WS connection with ?since_seq=baselineSeq receives all 5 missed events cleanly
    val reconnectedEvents = mutableListOf<WsEventsFrame>()
    wsClient.webSocket(
      urlString = "/ws/v1/events?since_seq=$baselineSeq",
      request = {
        header(HttpHeaders.Authorization, "Bearer ${custA.accessToken}")
      },
    ) {
      val replayFrame = json.decodeFromString<WsEventsFrame>((incoming.receive() as Frame.Text).readText())
      reconnectedEvents += replayFrame
    }
    val replayed = reconnectedEvents.flatMap { it.events }
    assertEquals(5, replayed.size)
    assertEquals(eventsA.map { it.eventId }, replayed.map { it.eventId })
    assertEquals(eventsA.map { it.streamSeq }, replayed.map { it.streamSeq })
  }
}
