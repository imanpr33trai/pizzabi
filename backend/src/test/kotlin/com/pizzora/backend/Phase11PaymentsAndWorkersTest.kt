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
import com.pizzora.backend.razorpay.RazorpayGatewayException
import com.pizzora.backend.razorpay.RazorpaySignatureVerifier
import com.pizzora.backend.worker.ExpirySweepWorker
import com.pizzora.backend.worker.OutboxDispatcherWorker
import com.pizzora.backend.worker.RazorpayReconciliationWorker
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCouponRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OwnerRefundRequest
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RefundDto
import com.pizzora.contracts.RefundStatus
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.RetryPaymentRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.VerifyEmailRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
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
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase11PaymentsAndWorkersTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }
    private val slotDayCounter = AtomicInteger(1)

    private val ownerEmail = "owner-p11-${UUID.randomUUID()}@pizzora.test"
    private val ownerPass = "OwnerPass123!"

    private val testKeyId = "rzp_test_pizzora_11"
    private val testKeySecret = "rzp_secret_pizzora_super_secret_11"
    private val testWebhookSecret = "rzp_webhook_pizzora_secret_11"

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
          fullName = "Owner P11",
          phone = "9844444449",
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

  private class FakeRazorpayGateway(
    override val publicKeyId: String = testKeyId,
  ) : RazorpayGateway {
    private val orderSeq = AtomicInteger(1)
    private val refundSeq = AtomicInteger(1)
    val createdOrders = CopyOnWriteArrayList<GatewayOrderResult>()
    val createdRefunds = CopyOnWriteArrayList<GatewayRefundResult>()
    val refundsByPayment = ConcurrentHashMap<String, MutableList<GatewayRefundResult>>()
    val paymentsByOrder = ConcurrentHashMap<String, MutableList<GatewayPaymentResult>>()

    @Volatile
    var failRefundRemainingCount: Int = 0

    @Volatile
    var failRefundTransient: Boolean = false

    @Volatile
    var simulatePostGatewayCrashOnCreateRefund: Boolean = false

    override suspend fun createOrder(
      amountPaise: Long,
      currency: String,
      receipt: String,
      notes: Map<String, String>,
    ): GatewayOrderResult {
      val existing = createdOrders.firstOrNull { it.receipt == receipt }
      if (existing != null) return existing
      val res = GatewayOrderResult(
        gatewayOrderId = "order_gw_${orderSeq.getAndIncrement()}_${UUID.randomUUID().toString().take(6)}",
        receipt = receipt,
        amountPaise = amountPaise,
        currency = currency,
        status = "created",
      )
      createdOrders += res
      return res
    }

    override suspend fun createRefund(
      gatewayPaymentId: String,
      amountPaise: Long,
      idempotencyKey: String,
      notes: Map<String, String>,
    ): GatewayRefundResult {
      if (failRefundRemainingCount > 0) {
        failRefundRemainingCount--
        throw RazorpayGatewayException(
          isTransient = failRefundTransient,
          statusCode = if (failRefundTransient) 503 else 400,
          code = "GATEWAY_REFUND_ERROR",
          message = "Simulated Razorpay refund failure",
        )
      }
      val existing = refundsByPayment[gatewayPaymentId]?.firstOrNull {
        it.notes["idempotency_key"] == idempotencyKey
      }
      val refund = existing ?: GatewayRefundResult(
        gatewayRefundId = "rfnd_gw_${refundSeq.getAndIncrement()}_${UUID.randomUUID().toString().take(6)}",
        gatewayPaymentId = gatewayPaymentId,
        amountPaise = amountPaise,
        status = "processed",
        notes = notes + ("idempotency_key" to idempotencyKey),
      ).also { created ->
        createdRefunds += created
        refundsByPayment.computeIfAbsent(gatewayPaymentId) { CopyOnWriteArrayList() }.add(created)
      }

      if (simulatePostGatewayCrashOnCreateRefund) {
        simulatePostGatewayCrashOnCreateRefund = false
        throw RazorpayGatewayException(
          isTransient = false,
          statusCode = 504,
          code = "SIMULATED_RESPONSE_TIMEOUT",
          message = "Simulated crash after gateway created refund",
        )
      }
      return refund
    }

    override suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult> =
      paymentsByOrder[gatewayOrderId]?.toList().orEmpty()

    override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> =
      refundsByPayment[gatewayPaymentId]?.toList().orEmpty()
  }

  private data class SeedFixtures(
    val config: AppConfig,
    val gateway: FakeRazorpayGateway,
    val ownerToken: String,
    val customerId: String,
    val customerToken: String,
    val productId: String,
    val variantId: String,
    val crustId: String,
    val toppingId: String,
    val doughId: String,
    val cheeseId: String,
    val addressId: String,
    val slotId: UUID,
    val couponCode: String,
    val outboxWorker: OutboxDispatcherWorker,
    val expiryWorker: ExpirySweepWorker,
    val reconcilerWorker: RazorpayReconciliationWorker,
  )

  private suspend fun ApplicationTestBuilder.setupFixtures(
    initialDoughGrams: Long = 2000L,
    initialCheeseGrams: Long = 1000L,
  ): SeedFixtures {
    val mailProvider = InMemoryEmailDeliveryProvider()
    val fakeGateway = FakeRazorpayGateway()
    val baseConfig = AppConfig.fromEnv()
    val testConfig = baseConfig.copy(
      razorpay = RazorpayConfig(
        keyId = testKeyId,
        keySecret = testKeySecret,
        webhookSecret = testWebhookSecret,
        baseUrl = "https://api.razorpay.com/v1",
      ),
    )

    application {
      pizzoraModule(
        config = testConfig,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = InMemoryCacheStore(),
        emailDeliveryProvider = mailProvider,
        objectStorageClient = DummyStorageClient,
        razorpayGateway = fakeGateway,
        passwordHasher = fastHasher,
      )
    }

    val ownerLogin = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
    }
    val ownerToken = json.decodeFromString<AuthTokensResponse>(ownerLogin.bodyAsText()).accessToken

    val custEmail = "cust-p11-${UUID.randomUUID()}@pizzora.test"
    client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(custEmail, "CustPass123!", "Giulia Verdi", "9822223344")))
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
    val custAuth = json.decodeFromString<AuthTokensResponse>(custLogin.bodyAsText())

    val cat = json.decodeFromString<CategoryDto>(
      client.post("/api/v1/owner/categories") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCategoryRequest("P11 Cat ${UUID.randomUUID()}", 1, true)))
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
              name = "Quattro Formaggi",
              description = "Four artisan Italian cheeses",
              isVeg = true,
              variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 45_000L, true)),
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
        setBody(json.encodeToString(CreateCrustRequest("P11 Crust ${UUID.randomUUID()}", 5_000L, true)))
      }.bodyAsText(),
    )
    val top = json.decodeFromString<ToppingDto>(
      client.post("/api/v1/owner/toppings") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateToppingRequest("P11 Basil ${UUID.randomUUID()}", true, 10_000L, true)))
      }.bodyAsText(),
    )
    val dough = json.decodeFromString<InventoryItemDto>(
      client.post("/api/v1/owner/inventory") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateIngredientRequest("P11 Dough ${UUID.randomUUID()}", IngredientUnit.GRAMS, 200L, initialDoughGrams)))
      }.bodyAsText(),
    )
    val cheese = json.decodeFromString<InventoryItemDto>(
      client.post("/api/v1/owner/inventory") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateIngredientRequest("P11 Cheese ${UUID.randomUUID()}", IngredientUnit.GRAMS, 100L, initialCheeseGrams)))
      }.bodyAsText(),
    )

    client.put("/api/v1/owner/recipes") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(PutRecipeRequest(RecipeTargetType.VARIANT, variantId, listOf(RecipeIngredientLineRequest(dough.ingredientId, 250)))))
    }
    client.put("/api/v1/owner/recipes") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(PutRecipeRequest(RecipeTargetType.TOPPING, top.id, listOf(RecipeIngredientLineRequest(cheese.ingredientId, 100)))))
    }

    val pincode = (560200 + (Math.abs(UUID.randomUUID().hashCode()) % 700)).toString()
    val slotId = UUID.randomUUID()
    val couponCode = "P11-${UUID.randomUUID().toString().take(6).uppercase()}"

    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        "INSERT INTO delivery_zones (id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active) VALUES (?, ?, 'Koramangala', 30000, 4000, 25, TRUE)",
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setString(2, pincode)
        ps.executeUpdate()
      }
      conn.prepareStatement(
        "INSERT INTO scheduled_slots (id, slot_date, fulfillment_type, start_time_ist, end_time_ist, cutoff_at, max_capacity, reserved_count, is_active) VALUES (?, ?, 'DELIVERY', ?, ?, ?, 2, 0, TRUE)",
      ).use { ps ->
        ps.setObject(1, slotId)
        ps.setObject(2, LocalDate.now().plusDays(slotDayCounter.getAndIncrement().toLong()))
        ps.setObject(3, LocalTime.of(20, 0))
        ps.setObject(4, LocalTime.of(21, 0))
        ps.setTimestamp(5, Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)))
        ps.executeUpdate()
      }
    }

    val addr = json.decodeFromString<AddressDto>(
      client.post("/api/v1/me/addresses") {
        header(HttpHeaders.Authorization, "Bearer ${custAuth.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(UpsertAddressRequest("Home", "12 Brick Oven St", null, null, pincode, "Bengaluru", true)))
      }.bodyAsText(),
    )

    client.post("/api/v1/owner/coupons") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateCouponRequest(
            code = couponCode,
            discountType = DiscountType.FLAT_PAISE,
            discountValue = 10_000L,
            minOrderPaise = 40_000L,
            maxUses = 10,
            maxUsesPerUser = 2,
            startsAt = Instant.now().minus(1, ChronoUnit.HOURS).toString(),
            endsAt = Instant.now().plus(2, ChronoUnit.DAYS).toString(),
            isActive = true,
          ),
        ),
      )
    }

    val inventoryService = InventoryService(pg.postgresDatabase)
    val couponService = CouponService(pg.postgresDatabase)
    val orderService = OrderService(pg.postgresDatabase, inventoryService, couponService)
    val paymentService = PaymentService(
      dataSource = pg.postgresDatabase,
      inventoryService = inventoryService,
      couponService = couponService,
      orderService = orderService,
      razorpayConfig = testConfig.razorpay,
      publicKeyIdProvider = { fakeGateway.publicKeyId },
    )

    return SeedFixtures(
      config = testConfig,
      gateway = fakeGateway,
      ownerToken = ownerToken,
      customerId = custAuth.user.id,
      customerToken = custAuth.accessToken,
      productId = prod.id,
      variantId = variantId,
      crustId = crust.id,
      toppingId = top.id,
      doughId = dough.ingredientId,
      cheeseId = cheese.ingredientId,
      addressId = addr.id,
      slotId = slotId,
      couponCode = couponCode,
      outboxWorker = OutboxDispatcherWorker(pg.postgresDatabase, fakeGateway, maxAttempts = 3),
      expiryWorker = ExpirySweepWorker(pg.postgresDatabase, inventoryService, couponService, orderService),
      reconcilerWorker = RazorpayReconciliationWorker(pg.postgresDatabase, fakeGateway, paymentService),
    )
  }

  private fun checkoutRequest(f: SeedFixtures, scheduleType: ScheduleType = ScheduleType.ASAP): CheckoutRequest =
    CheckoutRequest(
      items = listOf(
        CartItemInputDto(
          productId = f.productId,
          variantId = f.variantId,
          crustId = f.crustId,
          toppingIds = listOf(f.toppingId),
          quantity = 1,
        ),
      ),
      fulfillmentType = FulfillmentType.DELIVERY,
      scheduleType = scheduleType,
      scheduledSlotId = if (scheduleType == ScheduleType.SCHEDULED) f.slotId.toString() else null,
      addressId = f.addressId,
      paymentMethod = PaymentMethod.RAZORPAY,
      couponCode = f.couponCode,
    )

  private fun signCheckout(orderId: String, paymentId: String): String =
    RazorpaySignatureVerifier.computeHmacSha256Hex(
      "$orderId|$paymentId".toByteArray(StandardCharsets.UTF_8),
      testKeySecret,
    )

  private fun signWebhook(rawBody: ByteArray): String =
    RazorpaySignatureVerifier.computeHmacSha256Hex(rawBody, testWebhookSecret)

  @Test
  fun `RazorpaySignatureVerifier validates checkout and raw webhook signatures constantly`() {
    val sig = signCheckout("order_abc", "pay_xyz")
    assertTrue(RazorpaySignatureVerifier.verifyCheckoutSignature("order_abc", "pay_xyz", sig, testKeySecret))
    assertFalse(RazorpaySignatureVerifier.verifyCheckoutSignature("order_abc", "pay_tampered", sig, testKeySecret))
    assertFalse(RazorpaySignatureVerifier.verifyCheckoutSignature("order_abc", "pay_xyz", sig, ""))

    val body = """{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_1"}}}}""".toByteArray()
    val whSig = signWebhook(body)
    assertTrue(RazorpaySignatureVerifier.verifyWebhookSignature(body, whSig, testWebhookSecret))
    assertFalse(RazorpaySignatureVerifier.verifyWebhookSignature(body + byteArrayOf(0x20), whSig, testWebhookSecret))
    assertFalse(RazorpaySignatureVerifier.verifyWebhookSignature(body, null, testWebhookSecret))
  }

  @Test
  fun `outbox dispatches gateway order, failed attempt stays retryable, and retry verify captures and commits reservations`() =
    testApplication {
      val f = setupFixtures(initialDoughGrams = 1000L, initialCheeseGrams = 500L)

      val checkoutResp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", "p11-retry-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(checkoutRequest(f, ScheduleType.SCHEDULED)))
      }
      assertEquals(HttpStatusCode.Created, checkoutResp.status)
      val order = json.decodeFromString<CheckoutResponse>(checkoutResp.bodyAsText()).order
      assertEquals(OrderStatus.PENDING_PAYMENT, order.status)
      assertEquals(PaymentAttemptStatus.INITIATING, order.payment.latestAttempt?.status)

      // Outbox worker executes Razorpay /orders asynchronously outside DB transaction
      val dispatched = f.outboxWorker.runOnce()
      assertEquals(1, dispatched)
      assertEquals(1, f.gateway.createdOrders.size)

      val poll1 = json.decodeFromString<OrderPaymentStatusResponse>(
        client.get("/api/v1/orders/${order.id}/payment") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        }.bodyAsText(),
      )
      assertEquals(testKeyId, poll1.razorpayKeyId)
      assertTrue(poll1.canRetry)
      assertEquals(PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT, poll1.payment.latestAttempt?.status)
      val gatewayOrder1 = poll1.payment.latestAttempt?.gatewayOrderId!!

      // Bad signature is rejected with 401 and does not capture
      val badVerify = client.post("/api/v1/payments/razorpay/verify") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            VerifyRazorpayPaymentRequest(order.id, gatewayOrder1, "pay_bad_1", "deadbeef"),
          ),
        )
      }
      assertEquals(HttpStatusCode.Unauthorized, badVerify.status)
      assertEquals("INVALID_PAYMENT_SIGNATURE", json.decodeFromString<ApiErrorEnvelope>(badVerify.bodyAsText()).error.code)

      // Report failure on attempt #1 -> order remains PENDING_PAYMENT for retry
      val failResp = client.post("/api/v1/orders/${order.id}/payments/fail") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            FailPaymentAttemptRequest(
              attemptId = poll1.payment.latestAttempt?.id,
              gatewayOrderId = gatewayOrder1,
              failureCode = "BAD_REQUEST_ERROR",
              failureReason = "Bank declined transaction",
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, failResp.status)
      val afterFail = json.decodeFromString<OrderPaymentStatusResponse>(failResp.bodyAsText())
      assertEquals(OrderStatus.PENDING_PAYMENT, afterFail.orderStatus)
      assertEquals(PaymentAttemptStatus.FAILED, afterFail.payment.latestAttempt?.status)
      assertEquals("BAD_REQUEST_ERROR", afterFail.payment.latestAttempt?.failureCode)

      // Retry creates attempt #2 and outbox dispatches gatewayOrder2
      val retryResp = client.post("/api/v1/orders/${order.id}/payments/retry") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", "retry-2-${order.id}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RetryPaymentRequest("Retrying with UPI")))
      }
      assertEquals(HttpStatusCode.OK, retryResp.status)
      val retryQueued = json.decodeFromString<OrderPaymentStatusResponse>(retryResp.bodyAsText())
      assertEquals(2, retryQueued.payment.latestAttempt?.attemptNumber)
      assertEquals(PaymentAttemptStatus.INITIATING, retryQueued.payment.latestAttempt?.status)

      assertEquals(1, f.outboxWorker.runOnce())
      val poll2 = json.decodeFromString<OrderPaymentStatusResponse>(
        client.get("/api/v1/orders/${order.id}/payment") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        }.bodyAsText(),
      )
      val gatewayOrder2 = poll2.payment.latestAttempt?.gatewayOrderId!!
      assertEquals(PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT, poll2.payment.latestAttempt?.status)

      // Verify attempt #2 succeeds -> PLACED + CAPTURED + inventory/coupon/slot committed
      val gatewayPayment2 = "pay_rzp_ok_${UUID.randomUUID().toString().take(8)}"
      val verifyResp = client.post("/api/v1/payments/razorpay/verify") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            VerifyRazorpayPaymentRequest(
              orderId = order.id,
              razorpayOrderId = gatewayOrder2,
              razorpayPaymentId = gatewayPayment2,
              razorpaySignature = signCheckout(gatewayOrder2, gatewayPayment2),
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, verifyResp.status)
      val verified = json.decodeFromString<VerifyRazorpayPaymentResponse>(verifyResp.bodyAsText())
      assertFalse(verified.alreadyCaptured)
      assertEquals(OrderStatus.PLACED, verified.order.status)
      assertEquals(PaymentStatus.CAPTURED, verified.order.payment.status)
      assertEquals(verified.order.totalPaise, verified.order.payment.capturedPaise)

      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT on_hand_qty, reserved_qty FROM inventory WHERE ingredient_id = ?::uuid").use { ps ->
          ps.setString(1, f.doughId)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(750L, rs.getLong("on_hand_qty"))
            assertEquals(0L, rs.getLong("reserved_qty"))
          }
        }
        conn.prepareStatement("SELECT usage_count FROM coupons WHERE code = ?").use { ps ->
          ps.setString(1, f.couponCode)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(1, rs.getInt("usage_count"))
          }
        }
        conn.prepareStatement("SELECT status FROM scheduled_slot_reservations WHERE order_id = ?::uuid").use { ps ->
          ps.setString(1, order.id)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals("COMMITTED", rs.getString("status"))
          }
        }
      }
    }

  @Test
  fun `concurrent verify and webhook payment_captured produce exactly one PLACED transition and zero duplicate commits`() =
    testApplication {
      val f = setupFixtures(initialDoughGrams = 1000L, initialCheeseGrams = 500L)

      val order = json.decodeFromString<CheckoutResponse>(
        client.post("/api/v1/checkout") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
          header("Idempotency-Key", "p11-race-${UUID.randomUUID()}")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(checkoutRequest(f)))
        }.bodyAsText(),
      ).order
      f.outboxWorker.runOnce()

      val paymentStatus = json.decodeFromString<OrderPaymentStatusResponse>(
        client.get("/api/v1/orders/${order.id}/payment") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        }.bodyAsText(),
      )
      val gwOrderId = paymentStatus.payment.latestAttempt?.gatewayOrderId!!
      val gwPaymentId = "pay_race_${UUID.randomUUID().toString().take(8)}"

      val webhookBody = """
        {
          "id": "evt_race_${UUID.randomUUID().toString().take(8)}",
          "event": "payment.captured",
          "created_at": 1763049600,
          "payload": {
            "payment": {
              "entity": {
                "id": "$gwPaymentId",
                "order_id": "$gwOrderId",
                "amount": ${order.totalPaise},
                "currency": "INR",
                "status": "captured"
              }
            }
          }
        }
      """.trimIndent().toByteArray(StandardCharsets.UTF_8)

      // Tampered webhook signature returns 401
      val badWh = client.post("/api/v1/webhooks/razorpay") {
        header("X-Razorpay-Signature", "invalid_hex")
        contentType(ContentType.Application.Json)
        setBody(webhookBody)
      }
      assertEquals(HttpStatusCode.Unauthorized, badWh.status)

      // Fire verify + valid signed webhook concurrently
      val (verifyResp, webhookResp) = coroutineScope {
        val d1 = async {
          client.post("/api/v1/payments/razorpay/verify") {
            header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
            contentType(ContentType.Application.Json)
            setBody(
              json.encodeToString(
                VerifyRazorpayPaymentRequest(order.id, gwOrderId, gwPaymentId, signCheckout(gwOrderId, gwPaymentId)),
              ),
            )
          }
        }
        val d2 = async {
          client.post("/api/v1/webhooks/razorpay") {
            header("X-Razorpay-Signature", signWebhook(webhookBody))
            contentType(ContentType.Application.Json)
            setBody(webhookBody)
          }
        }
        awaitAll(d1, d2)
      }

      assertEquals(HttpStatusCode.OK, verifyResp.status)
      assertEquals(HttpStatusCode.OK, webhookResp.status)

      val finalOrder = json.decodeFromString<OrderDetailDto>(
        client.get("/api/v1/orders/${order.id}") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        }.bodyAsText(),
      )
      assertEquals(OrderStatus.PLACED, finalOrder.status)
      assertEquals(PaymentStatus.CAPTURED, finalOrder.payment.status)

      pg.postgresDatabase.connection.use { conn ->
        // Exactly 250g committed once -> 750g on_hand, 0 reserved
        conn.prepareStatement("SELECT on_hand_qty, reserved_qty FROM inventory WHERE ingredient_id = ?::uuid").use { ps ->
          ps.setString(1, f.doughId)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(750L, rs.getLong("on_hand_qty"))
            assertEquals(0L, rs.getLong("reserved_qty"))
          }
        }
        // Only ONE ORDER_PLACED outbox event emitted
        conn.prepareStatement("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND event_type = 'ORDER_PLACED'").use { ps ->
          ps.setString(1, order.id)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(1, rs.getInt(1))
          }
        }
        // Coupon usage incremented once
        conn.prepareStatement("SELECT usage_count FROM coupons WHERE code = ?").use { ps ->
          ps.setString(1, f.couponCode)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(1, rs.getInt(1))
          }
        }
      }
    }

  @Test
  fun `ExpirySweepWorker expires order cleanly without double-decrement and late capture triggers idempotent refund with failure reconciliation`() =
    testApplication {
      val f = setupFixtures(initialDoughGrams = 1000L, initialCheeseGrams = 500L)

      val order = json.decodeFromString<CheckoutResponse>(
        client.post("/api/v1/checkout") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
          header("Idempotency-Key", "p11-expire-${UUID.randomUUID()}")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(checkoutRequest(f, ScheduleType.SCHEDULED)))
        }.bodyAsText(),
      ).order
      f.outboxWorker.runOnce()

      val paymentStatus = json.decodeFromString<OrderPaymentStatusResponse>(
        client.get("/api/v1/orders/${order.id}/payment") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        }.bodyAsText(),
      )
      val attemptId = paymentStatus.payment.latestAttempt!!.id
      val gwOrderId = paymentStatus.payment.latestAttempt!!.gatewayOrderId!!

      // Force order expiry window into the past
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("UPDATE orders SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid").use { ps ->
          ps.setString(1, order.id)
          ps.executeUpdate()
        }
      }

      assertEquals(1, f.expiryWorker.runOnce())
      // Running sweep again must be an idempotent no-op
      assertEquals(0, f.expiryWorker.runOnce())

      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT status FROM orders WHERE id = ?::uuid").use { ps ->
          ps.setString(1, order.id)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals("EXPIRED", rs.getString("status"))
          }
        }
        conn.prepareStatement("SELECT reserved_qty FROM inventory WHERE ingredient_id = ?::uuid").use { ps ->
          ps.setString(1, f.doughId)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(0L, rs.getLong("reserved_qty"))
          }
        }
        conn.prepareStatement("SELECT reserved_count FROM scheduled_slots WHERE id = ?").use { ps ->
          ps.setObject(1, f.slotId)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(0, rs.getInt("reserved_count"))
          }
        }
      }

      // Late payment.captured webhook arrives after order EXPIRED -> order stays EXPIRED, payment moves to REFUND_PENDING
      val latePaymentId = "pay_late_${UUID.randomUUID().toString().take(8)}"
      val lateWebhook = """
        {
          "id": "evt_late_${UUID.randomUUID().toString().take(8)}",
          "event": "payment.captured",
          "created_at": 1763050000,
          "payload": {
            "payment": {
              "entity": {
                "id": "$latePaymentId",
                "order_id": "$gwOrderId",
                "amount": ${order.totalPaise},
                "currency": "INR",
                "status": "captured"
              }
            }
          }
        }
      """.trimIndent().toByteArray(StandardCharsets.UTF_8)

      val lateWhResp = client.post("/api/v1/webhooks/razorpay") {
        header("X-Razorpay-Signature", signWebhook(lateWebhook))
        contentType(ContentType.Application.Json)
        setBody(lateWebhook)
      }
      assertEquals(HttpStatusCode.OK, lateWhResp.status)

      val afterLateCapture = json.decodeFromString<OrderPaymentStatusResponse>(
        client.get("/api/v1/orders/${order.id}/payment") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        }.bodyAsText(),
      )
      assertEquals(OrderStatus.EXPIRED, afterLateCapture.orderStatus)
      assertEquals(PaymentStatus.REFUND_PENDING, afterLateCapture.payment.status)
      assertEquals(order.totalPaise, afterLateCapture.payment.capturedPaise)
      assertEquals(1, afterLateCapture.refunds.size)
      assertEquals("late_capture:$attemptId", afterLateCapture.refunds.first().idempotencyKey)
      assertEquals(RefundStatus.PENDING, afterLateCapture.refunds.first().status)

      // Simulate permanent refund worker failure -> refunds.status = FAILED & OWNER alert inserted
      f.gateway.failRefundTransient = false
      f.gateway.failRefundRemainingCount = 1
      assertEquals(1, f.outboxWorker.runOnce())

      val afterRefundFail = json.decodeFromString<OrderPaymentStatusResponse>(
        client.get("/api/v1/orders/${order.id}/payment") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        }.bodyAsText(),
      )
      assertEquals(RefundStatus.FAILED, afterRefundFail.refunds.first().status)

      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM notification_inbox WHERE reference_id = ?::uuid AND category = 'REFUND_ALERT'").use { ps ->
          ps.setString(1, order.id)
          ps.executeQuery().use { rs ->
            rs.next()
            assertTrue(rs.getInt(1) >= 1)
          }
        }
      }

      // Case A: Gateway actually created the refund before worker crashed/failed -> reconciler syncs PROCESSED without creating a duplicate refund
      val preExistingIdempKey = afterLateCapture.refunds.first().idempotencyKey
      f.gateway.simulatePostGatewayCrashOnCreateRefund = false
      // Pre-populate gateway refund as if gateway succeeded in an earlier partial failure, OR let reconciler retry:
      // Let's test Owner retry re-queueing the existing FAILED refund row (1 row total!) + post-gateway crash reconciled idempotently!
      val ownerRetryResp = client.post("/api/v1/owner/orders/${order.id}/refund") {
        header(HttpHeaders.Authorization, "Bearer ${f.ownerToken}")
        header("Idempotency-Key", "owner-retry-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(OwnerRefundRequest(reason = "Retrying failed late-capture refund")))
      }
      assertEquals(HttpStatusCode.Accepted, ownerRetryResp.status)
      val requeuedRefund = json.decodeFromString<RefundDto>(ownerRetryResp.bodyAsText())
      assertEquals(afterLateCapture.refunds.first().id, requeuedRefund.id)
      assertEquals(preExistingIdempKey, requeuedRefund.idempotencyKey)

      // Simulate gateway succeeding then response timing out before DB commit -> leaves refund PROCESSING while gateway has processed it
      f.gateway.simulatePostGatewayCrashOnCreateRefund = true
      assertEquals(1, f.outboxWorker.runOnce())

      // Now RazorpayReconciliationWorker polls gateway, matches notes.idempotency_key, and commits PROCESSED without calling createRefund again!
      val reconciled = f.reconcilerWorker.runOnce()
      assertEquals(1, reconciled)
      assertEquals(1, f.gateway.createdRefunds.size)

      val finalPaymentState = json.decodeFromString<OrderPaymentStatusResponse>(
        client.get("/api/v1/orders/${order.id}/payment") {
          header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        }.bodyAsText(),
      )
      assertEquals(PaymentStatus.REFUNDED, finalPaymentState.payment.status)
      assertEquals(order.totalPaise, finalPaymentState.payment.refundedPaise)
      assertEquals(1, finalPaymentState.refunds.size)
      assertEquals(RefundStatus.PROCESSED, finalPaymentState.refunds.first().status)
      assertNotNull(finalPaymentState.refunds.first().gatewayRefundId)
    }
}
