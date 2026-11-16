package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.config.RazorpayConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.images.InspectedObject
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.orders.OrderService
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.payments.RazorpayReceiptFormatter
import com.pizzora.backend.razorpay.GatewayOrderResult
import com.pizzora.backend.razorpay.GatewayPaymentResult
import com.pizzora.backend.razorpay.GatewayRefundResult
import com.pizzora.backend.razorpay.RazorpayGateway
import com.pizzora.backend.worker.OutboxDispatcherWorker
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
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.RetryPaymentRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.VerifyEmailRequest
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
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase2PaymentReceiptIdempotencyTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-p2-${UUID.randomUUID()}@pizzora.test"
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
          fullName = "Owner P2",
          phone = "9844444440",
        )
      }
    }

    @JvmStatic
    @AfterClass
    fun stopInfra() {
      pg.close()
    }
  }

  @Serializable
  private data class ReceiptVectorFile(
    val formula: String,
    val max_length: Int,
    val exact_length: Int,
    val vectors: List<ReceiptVector>,
  )

  @Serializable
  private data class ReceiptVector(
    val order_id: String,
    val key_or_attempt_id: String,
    val canonical_input: String,
    val expected_receipt: String,
  )

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

  private class RecordingRazorpayGateway : RazorpayGateway {
    override val publicKeyId: String = "rzp_test_p2"
    private val seq = AtomicInteger(1)
    val createdOrders = CopyOnWriteArrayList<GatewayOrderResult>()

    override suspend fun createOrder(
      amountPaise: Long,
      currency: String,
      receipt: String,
      notes: Map<String, String>,
    ): GatewayOrderResult {
      val result = GatewayOrderResult(
        gatewayOrderId = "order_p2_${seq.getAndIncrement()}_${UUID.randomUUID().toString().take(6)}",
        receipt = receipt,
        amountPaise = amountPaise,
        currency = currency,
        status = "created",
      )
      createdOrders += result
      return result
    }

    override suspend fun createRefund(
      gatewayPaymentId: String,
      amountPaise: Long,
      idempotencyKey: String,
      notes: Map<String, String>,
    ): GatewayRefundResult = error("unused")

    override suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult> = emptyList()
    override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> = emptyList()
  }

  private data class TestFixture(
    val customerToken: String,
    val productId: String,
    val variantId: String,
    val crustId: String,
    val gateway: RecordingRazorpayGateway,
    val outboxWorker: OutboxDispatcherWorker,
  )

  private suspend fun ApplicationTestBuilder.setupFixture(): TestFixture {
    val mailProvider = InMemoryEmailDeliveryProvider()
    val gateway = RecordingRazorpayGateway()
    val baseConfig = AppConfig.fromEnv()
    val config = baseConfig.copy(
      razorpay = RazorpayConfig(
        keyId = "rzp_test_p2",
        keySecret = "rzp_secret_p2_123456",
        webhookSecret = "rzp_wh_p2_123456",
        baseUrl = "https://api.razorpay.com/v1",
      ),
    )

    application {
      pizzoraModule(
        config = config,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = InMemoryCacheStore(),
        emailDeliveryProvider = mailProvider,
        objectStorageClient = DummyStorageClient,
        razorpayGateway = gateway,
        passwordHasher = fastHasher,
      )
    }

    val ownerLogin = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
    }
    val ownerToken = json.decodeFromString<AuthTokensResponse>(ownerLogin.bodyAsText()).accessToken

    val custEmail = "cust-p2-${UUID.randomUUID()}@pizzora.test"
    client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(custEmail, "CustPass123!", "Marco Bianchi", "9811112233")))
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
    val custToken = json.decodeFromString<AuthTokensResponse>(custLogin.bodyAsText()).accessToken

    val cat = json.decodeFromString<CategoryDto>(
      client.post("/api/v1/owner/categories") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCategoryRequest("P2 Cat ${UUID.randomUUID()}", 1, true)))
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
              name = "Margherita Classica",
              description = "San Marzano tomatoes",
              isVeg = true,
              variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 40_000L, true)),
            ),
          ),
        )
      }.bodyAsText(),
    )
    val variantId = prod.variants.first().id
    val crust = json.decodeFromString<CrustOptionDto>(
      client.post("/api/v1/owner/crusts") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCrustRequest("P2 Crust ${UUID.randomUUID()}", 0L, true)))
      }.bodyAsText(),
    )
    val dough = json.decodeFromString<InventoryItemDto>(
      client.post("/api/v1/owner/inventory") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateIngredientRequest("P2 Dough ${UUID.randomUUID()}", IngredientUnit.GRAMS, 200L, 10_000L)))
      }.bodyAsText(),
    )
    client.put("/api/v1/owner/recipes") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(PutRecipeRequest(RecipeTargetType.VARIANT, variantId, listOf(RecipeIngredientLineRequest(dough.ingredientId, 200)))))
    }

    return TestFixture(
      customerToken = custToken,
      productId = prod.id,
      variantId = variantId,
      crustId = crust.id,
      gateway = gateway,
      outboxWorker = OutboxDispatcherWorker(pg.postgresDatabase, gateway, maxAttempts = 3),
    )
  }

  private fun buildPickupCheckout(f: TestFixture): CheckoutRequest =
    CheckoutRequest(
      items = listOf(
        CartItemInputDto(
          productId = f.productId,
          variantId = f.variantId,
          crustId = f.crustId,
          toppingIds = emptyList(),
          quantity = 1,
        ),
      ),
      fulfillmentType = FulfillmentType.PICKUP,
      scheduleType = ScheduleType.ASAP,
      addressId = null,
      paymentMethod = PaymentMethod.RAZORPAY,
      couponCode = null,
    )

  @Test
  fun `RazorpayReceiptFormatter matches canonical SHA-256 golden vectors and stays strictly 37 chars`() {
    val vectorFile = listOf(
      File("tools/golden-vectors/razorpay_receipts.json"),
      File("../tools/golden-vectors/razorpay_receipts.json"),
    ).firstOrNull { it.isFile }
      ?: error("Missing tools/golden-vectors/razorpay_receipts.json")

    val parsed = json.decodeFromString<ReceiptVectorFile>(vectorFile.readText())
    assertTrue(parsed.vectors.isNotEmpty())

    for (vector in parsed.vectors) {
      val actual = RazorpayReceiptFormatter.formatReceipt(
        orderId = UUID.fromString(vector.order_id),
        keyOrAttemptId = vector.key_or_attempt_id,
      )
      assertEquals(vector.expected_receipt, actual)
      assertEquals(37, actual.length)
      assertTrue(actual.length <= 40)
      assertTrue(actual.matches(Regex("^rcpt_[0-9a-f]{32}$")))
    }
  }

  @Test
  fun `checkout and concurrent retry enforce canonical receipt, reuse matching retry, and return 409 on conflicts`() =
    testApplication {
      val f = setupFixture()

      // 1. Place Order 1 -> verifies initial attempt receipt is canonical 37-char rcpt_<32hex>
      val checkout1Resp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", "p2-ord1-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(buildPickupCheckout(f)))
      }
      assertEquals(HttpStatusCode.Created, checkout1Resp.status)
      val order1 = json.decodeFromString<CheckoutResponse>(checkout1Resp.bodyAsText()).order
      val attempt1 = order1.payment.latestAttempt!!
      val expectedInitialReceipt = RazorpayReceiptFormatter.formatReceipt(
        UUID.fromString(order1.id),
        attempt1.id,
      )
      assertEquals(expectedInitialReceipt, attempt1.idempotencyReceipt)
      assertEquals(37, attempt1.idempotencyReceipt.length)
      assertEquals(1, f.outboxWorker.runOnce())
      assertEquals(expectedInitialReceipt, f.gateway.createdOrders.last().receipt)

      // Place Order 2 (for cross-order Idempotency-Key replay test)
      val checkout2Resp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", "p2-ord2-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(buildPickupCheckout(f)))
      }
      assertEquals(HttpStatusCode.Created, checkout2Resp.status)
      val order2 = json.decodeFromString<CheckoutResponse>(checkout2Resp.bodyAsText()).order
      f.outboxWorker.runOnce()

      // 2. Concurrent retries on Order 1 with identical Idempotency-Key and identical reason
      val sharedRetryKey = "retry-race-${UUID.randomUUID()}-${"x".repeat(40)}"
      val retryReason = "Switching to UPI"
      val responses = coroutineScope {
        (1..6).map {
          async {
            client.post("/api/v1/orders/${order1.id}/payments/retry") {
              header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
              header("Idempotency-Key", sharedRetryKey)
              contentType(ContentType.Application.Json)
              setBody(json.encodeToString(RetryPaymentRequest(retryReason)))
            }
          }
        }.awaitAll()
      }

      for (resp in responses) {
        assertEquals(HttpStatusCode.OK, resp.status)
      }
      val parsedAttemptIds = responses.map {
        json.decodeFromString<OrderPaymentStatusResponse>(it.bodyAsText()).payment.latestAttempt!!.id
      }.toSet()
      assertEquals(1, parsedAttemptIds.size)
      val expectedRetryReceipt = RazorpayReceiptFormatter.formatReceipt(UUID.fromString(order1.id), sharedRetryKey)
      assertEquals(37, expectedRetryReceipt.length)

      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement(
          "SELECT COUNT(*), MAX(attempt_number), MAX(idempotency_receipt) FROM payment_attempts WHERE payment_id = ?::uuid",
        ).use { ps ->
          ps.setString(1, order1.payment.id)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(2, rs.getInt(1)) // Attempt #1 + exactly ONE Attempt #2
            assertEquals(2, rs.getInt(2))
            assertEquals(expectedRetryReceipt, rs.getString(3))
          }
        }
        conn.prepareStatement(
          "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND event_type = 'PAYMENT_GATEWAY_ORDER_REQUESTED'",
        ).use { ps ->
          ps.setString(1, order1.id)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(2, rs.getInt(1)) // 1 for checkout + 1 for retry #2
          }
        }
      }

      // 3. Replaying the same (order1, sharedRetryKey) with DIFFERENT reason -> 409 IDEMPOTENCY_KEY_CONFLICT (no mutations!)
      val conflictReasonResp = client.post("/api/v1/orders/${order1.id}/payments/retry") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", sharedRetryKey)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RetryPaymentRequest("Different payment note")))
      }
      assertEquals(HttpStatusCode.Conflict, conflictReasonResp.status)
      val conflictReasonBody = json.decodeFromString<ApiErrorEnvelope>(conflictReasonResp.bodyAsText())
      assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflictReasonBody.error.code)

      // 4. Cross-order Idempotency-Key replay (same sharedRetryKey sent on order2) -> 409 IDEMPOTENCY_KEY_CONFLICT
      val crossOrderResp = client.post("/api/v1/orders/${order2.id}/payments/retry") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", sharedRetryKey)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RetryPaymentRequest(retryReason)))
      }
      assertEquals(HttpStatusCode.Conflict, crossOrderResp.status)
      val crossOrderBody = json.decodeFromString<ApiErrorEnvelope>(crossOrderResp.bodyAsText())
      assertEquals("IDEMPOTENCY_KEY_CONFLICT", crossOrderBody.error.code)

      // Verify neither conflict mutated attempt counts or outbox rows on order1 or order2
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement(
          "SELECT COUNT(*), COUNT(*) FILTER (WHERE status = 'INITIATING') FROM payment_attempts WHERE payment_id = ?::uuid",
        ).use { ps ->
          ps.setString(1, order1.payment.id)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(2, rs.getInt(1))
            assertEquals(1, rs.getInt(2))
          }
        }
        conn.prepareStatement(
          "SELECT COUNT(*) FROM payment_attempts WHERE payment_id = ?::uuid",
        ).use { ps ->
          ps.setString(1, order2.payment.id)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(1, rs.getInt(1))
          }
        }
      }
    }
}
