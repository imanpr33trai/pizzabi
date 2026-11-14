package com.pizzora.backend

import com.pizzora.backend.audit.AuditContext
import com.pizzora.backend.audit.AuditLogger
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
import com.pizzora.backend.razorpay.GatewayOrderResult
import com.pizzora.backend.razorpay.GatewayPaymentResult
import com.pizzora.backend.razorpay.GatewayRefundResult
import com.pizzora.backend.razorpay.RazorpayGateway
import com.pizzora.backend.razorpay.RazorpaySignatureVerifier
import com.pizzora.backend.worker.OutboxDispatcherWorker
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.AdjustInventoryRequest
import com.pizzora.contracts.AuditLogsResponse
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
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerReason
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OperationsReportResponse
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderPaymentStatusResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.OwnerRefundRequest
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductVariantDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RefundDto
import com.pizzora.contracts.RefundStatus
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.SalesReportResponse
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateRestaurantSettingsRequest
import com.pizzora.contracts.UpdateStaffRequest
import com.pizzora.contracts.UpdateVariantRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyEmailRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
import com.pizzora.contracts.VerifyRazorpayPaymentResponse
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
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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

class Phase17ReportingAndAuditTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }
    private val istZone = ZoneId.of("Asia/Kolkata")

    private val ownerEmail = "owner-p17-${UUID.randomUUID()}@pizzora.test"
    private val ownerPass = "OwnerPass123!"

    private val testKeyId = "rzp_test_pizzora_17"
    private val testKeySecret = "rzp_secret_pizzora_super_secret_17"
    private val testWebhookSecret = "rzp_webhook_pizzora_secret_17"

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
          fullName = "Owner P17",
          phone = "9833333317",
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
    val refundsByPayment = ConcurrentHashMap<String, MutableList<GatewayRefundResult>>()
    val paymentsByOrder = ConcurrentHashMap<String, MutableList<GatewayPaymentResult>>()

    override suspend fun createOrder(
      amountPaise: Long,
      currency: String,
      receipt: String,
      notes: Map<String, String>,
    ): GatewayOrderResult {
      val res = GatewayOrderResult(
        gatewayOrderId = "order_p17_${orderSeq.getAndIncrement()}_${UUID.randomUUID().toString().take(6)}",
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
      val res = GatewayRefundResult(
        gatewayRefundId = "rfnd_p17_${refundSeq.getAndIncrement()}_${UUID.randomUUID().toString().take(6)}",
        gatewayPaymentId = gatewayPaymentId,
        amountPaise = amountPaise,
        status = "processed",
        notes = notes + ("idempotency_key" to idempotencyKey),
      )
      refundsByPayment.computeIfAbsent(gatewayPaymentId) { CopyOnWriteArrayList() }.add(res)
      return res
    }

    override suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult> =
      paymentsByOrder[gatewayOrderId]?.toList().orEmpty()

    override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> =
      refundsByPayment[gatewayPaymentId]?.toList().orEmpty()
  }

  private data class SeedFixtures(
    val ownerToken: String,
    val kitchenToken: String,
    val customerToken: String,
    val productId: String,
    val variantId: String,
    val crustId: String,
    val toppingId: String,
    val doughId: String,
    val addressId: String,
    val gateway: FakeRazorpayGateway,
    val outboxWorker: OutboxDispatcherWorker,
  )

  private suspend fun ApplicationTestBuilder.setupFixtures(
    productPricePaise: Long = 40_000L,
    doughGrams: Long = 5000L,
  ): SeedFixtures {
    val mailProvider = InMemoryEmailDeliveryProvider()
    val fakeGateway = FakeRazorpayGateway()
    val testConfig = AppConfig.fromEnv().copy(
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

    val staffEmail = "chef-p17-${UUID.randomUUID()}@pizzora.test"
    val staffPass = "ChefPass123!"
    val staffResp = client.post("/api/v1/owner/staff") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateStaffRequest(
            email = staffEmail,
            password = staffPass,
            fullName = "Chef P17",
            phone = "9844444417",
            role = UserRole.KITCHEN_STAFF,
            employeeCode = "CHEF17-${UUID.randomUUID().toString().take(6)}",
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, staffResp.status)
    val kitchenToken = json.decodeFromString<AuthTokensResponse>(
      client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(staffEmail, staffPass)))
      }.bodyAsText(),
    ).accessToken

    val custEmail = "cust-p17-${UUID.randomUUID()}@pizzora.test"
    client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(custEmail, "CustPass123!", "Meera Nair", "9811111717")))
    }
    val verifyToken = mailProvider.outbox.last { it.toEmail == custEmail && it.type == "VERIFICATION" }.token
    client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest(verifyToken)))
    }
    val customerToken = json.decodeFromString<AuthTokensResponse>(
      client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(custEmail, "CustPass123!")))
      }.bodyAsText(),
    ).accessToken

    val cat = json.decodeFromString<CategoryDto>(
      client.post("/api/v1/owner/categories") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCategoryRequest("P17 Artisanal ${UUID.randomUUID()}", 1, true)))
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
              name = "Truffle Funghi ${UUID.randomUUID().toString().take(4)}",
              description = "Wild mushroom & truffle oil",
              isVeg = true,
              variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, productPricePaise, true)),
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
        setBody(json.encodeToString(CreateCrustRequest("P17 Neapolitan ${UUID.randomUUID()}", 0L, true)))
      }.bodyAsText(),
    )

    val topping = json.decodeFromString<ToppingDto>(
      client.post("/api/v1/owner/toppings") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateToppingRequest("P17 Rocket ${UUID.randomUUID()}", true, 5_000L, true)))
      }.bodyAsText(),
    )

    val dough = json.decodeFromString<InventoryItemDto>(
      client.post("/api/v1/owner/inventory") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateIngredientRequest(
              name = "P17 Dough ${UUID.randomUUID()}",
              unit = IngredientUnit.GRAMS,
              lowStockThreshold = 300,
              initialStockQty = doughGrams,
            ),
          ),
        )
      }.bodyAsText(),
    )

    client.put("/api/v1/owner/recipes") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          PutRecipeRequest(
            targetType = RecipeTargetType.VARIANT,
            targetId = variantId,
            ingredients = listOf(RecipeIngredientLineRequest(dough.ingredientId, 200)),
          ),
        ),
      )
    }

    val pincode = "56${(1000..9999).random()}"
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        """
        INSERT INTO delivery_zones (id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active)
        VALUES (?, ?, 'Koramangala', 0, 2000, 25, TRUE)
        ON CONFLICT (pincode) DO NOTHING
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setString(2, pincode)
        ps.executeUpdate()
      }
    }

    val address = json.decodeFromString<AddressDto>(
      client.post("/api/v1/me/addresses") {
        header(HttpHeaders.Authorization, "Bearer $customerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(UpsertAddressRequest("Home", "80 Feet Rd", pincode = pincode, city = "Bengaluru", isDefault = true)))
      }.bodyAsText(),
    )

    return SeedFixtures(
      ownerToken = ownerToken,
      kitchenToken = kitchenToken,
      customerToken = customerToken,
      productId = prod.id,
      variantId = variantId,
      crustId = crust.id,
      toppingId = topping.id,
      doughId = dough.ingredientId,
      addressId = address.id,
      gateway = fakeGateway,
      outboxWorker = OutboxDispatcherWorker(pg.postgresDatabase, fakeGateway, maxAttempts = 3),
    )
  }

  private fun signCheckout(orderId: String, paymentId: String): String =
    RazorpaySignatureVerifier.computeHmacSha256Hex(
      "$orderId|$paymentId".toByteArray(StandardCharsets.UTF_8),
      testKeySecret,
    )

  private fun signWebhook(rawBody: ByteArray): String =
    RazorpaySignatureVerifier.computeHmacSha256Hex(rawBody, testWebhookSecret)

  @Test
  fun `audit logging is transactional on rollback and records owner mutations with cursor pagination`() = testApplication {
    val fx = setupFixtures()

    // 1. Rolled-back transaction MUST leave zero audit_logs rows
    val rolledBackEntityId = "rollback-${UUID.randomUUID()}"
    pg.postgresDatabase.connection.use { conn ->
      conn.autoCommit = false
      AuditLogger.record(
        conn = conn,
        context = AuditContext(null, "OWNER", "corr-rollback-${UUID.randomUUID()}"),
        action = "SHOULD_NOT_EXIST",
        entityType = "rollback_probe",
        entityId = rolledBackEntityId,
        afterJson = """{"temp":true}""",
      )
      conn.rollback()
    }
    val rollbackSearch = json.decodeFromString<AuditLogsResponse>(
      client.get("/api/v1/owner/audit-logs?entity_id=$rolledBackEntityId") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      }.bodyAsText(),
    )
    assertTrue(rollbackSearch.entries.isEmpty())

    // 2. Execute multiple owner mutations: variant price change, staff update, inventory adjust, settings update
    val variantPatchResp = client.patch("/api/v1/owner/variants/${fx.variantId}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateVariantRequest(pricePaise = 48_000L)))
    }
    assertEquals(HttpStatusCode.OK, variantPatchResp.status)
    val updatedVariant = json.decodeFromString<ProductVariantDto>(variantPatchResp.bodyAsText())
    assertEquals(48_000L, updatedVariant.pricePaise)

    val staffList = json.decodeFromString<List<StaffMemberDto>>(
      client.get("/api/v1/owner/staff") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      }.bodyAsText(),
    )
    val targetStaff = staffList.first()
    val staffPatchResp = client.patch("/api/v1/owner/staff/${targetStaff.userId}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateStaffRequest(fullName = "Chef P17 Renamed")))
    }
    assertEquals(HttpStatusCode.OK, staffPatchResp.status)

    val adjustResp = client.post("/api/v1/owner/inventory/adjust") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          AdjustInventoryRequest(
            ingredientId = fx.doughId,
            operationId = "p17-adj-${UUID.randomUUID()}",
            deltaQty = -150L,
            reason = InventoryLedgerReason.MANUAL_ADJUST,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.OK, adjustResp.status)

    // Verify filter by entity_type=product_variant & action=VARIANT_UPDATED captures before_json/after_json
    val variantAudit = json.decodeFromString<AuditLogsResponse>(
      client.get("/api/v1/owner/audit-logs?entity_type=product_variant&action=VARIANT_UPDATED&entity_id=${fx.variantId}") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      }.bodyAsText(),
    )
    assertEquals(1, variantAudit.entries.size)
    val vEntry = variantAudit.entries.first()
    assertEquals("OWNER", vEntry.actorRole)
    assertEquals(ownerEmail, vEntry.actorEmail)
    assertTrue(vEntry.beforeJson!!.contains("40000"))
    assertTrue(vEntry.afterJson!!.contains("48000"))

    // Verify staff update audit entry exists
    val staffAudit = json.decodeFromString<AuditLogsResponse>(
      client.get("/api/v1/owner/audit-logs?entity_type=staff&action=STAFF_UPDATED&entity_id=${targetStaff.userId}") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      }.bodyAsText(),
    )
    assertEquals(1, staffAudit.entries.size)
    assertTrue(staffAudit.entries.first().afterJson!!.contains("Chef P17 Renamed"))

    // Verify cursor pagination across audit_logs without duplicates
    val page1 = json.decodeFromString<AuditLogsResponse>(
      client.get("/api/v1/owner/audit-logs?limit=2") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      }.bodyAsText(),
    )
    assertEquals(2, page1.entries.size)
    assertNotNull(page1.nextCursor)

    val page2 = json.decodeFromString<AuditLogsResponse>(
      client.get("/api/v1/owner/audit-logs?limit=2&cursor=${page1.nextCursor}") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      }.bodyAsText(),
    )
    assertEquals(2, page2.entries.size)
    val page1Ids = page1.entries.map { it.id }.toSet()
    val page2Ids = page2.entries.map { it.id }.toSet()
    assertTrue(page1Ids.intersect(page2Ids).isEmpty())
  }

  @Test
  fun `customer and kitchen staff receive 403 on owner reports and audit logs`() = testApplication {
    val fx = setupFixtures()

    for (token in listOf(fx.customerToken, fx.kitchenToken)) {
      assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/owner/reports/sales") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
      )
      assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/owner/reports/operations") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
      )
      assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/owner/audit-logs") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
      )
    }
  }

  @Test
  fun `sales report handles partial refund netting, GST split, coupon ROI, and Asia Kolkata midnight boundaries`() =
    testApplication {
      val fx = setupFixtures(productPricePaise = 50_000L)

      val couponCode = "P17SAVE${UUID.randomUUID().toString().take(4).uppercase()}"
      val couponResp = client.post("/api/v1/owner/coupons") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateCouponRequest(
              code = couponCode,
              discountType = DiscountType.FLAT_PAISE,
              discountValue = 10_000L,
              minOrderPaise = 20_000L,
              maxUses = 10,
              maxUsesPerUser = 5,
              startsAt = Instant.now().minus(1, ChronoUnit.DAYS).toString(),
              endsAt = Instant.now().plus(2, ChronoUnit.DAYS).toString(),
              isActive = true,
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.Created, couponResp.status)

      // Update variant price from 50000 -> 55000 before checkout
      client.patch("/api/v1/owner/variants/${fx.variantId}") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(UpdateVariantRequest(pricePaise = 55_000L)))
      }

      // Customer checks out with Razorpay & coupon
      val checkoutResp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${fx.customerToken}")
        header("Idempotency-Key", "p17-sale-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CheckoutRequest(
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
              paymentMethod = PaymentMethod.RAZORPAY,
              couponCode = couponCode,
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.Created, checkoutResp.status)
      val order = json.decodeFromString<CheckoutResponse>(checkoutResp.bodyAsText()).order
      assertEquals(1, fx.outboxWorker.runOnce())

      val payStatus = json.decodeFromString<OrderPaymentStatusResponse>(
        client.get("/api/v1/orders/${order.id}/payment") {
          header(HttpHeaders.Authorization, "Bearer ${fx.customerToken}")
        }.bodyAsText(),
      )
      val gwOrderId = payStatus.payment.latestAttempt!!.gatewayOrderId!!
      val gwPayId = "pay_p17_${UUID.randomUUID().toString().take(8)}"

      val verifyResp = client.post("/api/v1/payments/razorpay/verify") {
        header(HttpHeaders.Authorization, "Bearer ${fx.customerToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            VerifyRazorpayPaymentRequest(
              orderId = order.id,
              razorpayOrderId = gwOrderId,
              razorpayPaymentId = gwPayId,
              razorpaySignature = signCheckout(gwOrderId, gwPayId),
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, verifyResp.status)
      val capturedOrder = json.decodeFromString<VerifyRazorpayPaymentResponse>(verifyResp.bodyAsText()).order
      assertEquals(PaymentStatus.CAPTURED, capturedOrder.payment.status)

      // Owner issues partial refund (15_000 paise) — initially PENDING before webhook settles
      val partialRefundPaise = 15_000L
      val refundResp = client.post("/api/v1/owner/orders/${order.id}/refund") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        header("Idempotency-Key", "p17-ref-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(OwnerRefundRequest(amountPaise = partialRefundPaise, reason = "Missing dip")))
      }
      assertEquals(HttpStatusCode.Accepted, refundResp.status)
      val pendingRefund = json.decodeFromString<RefundDto>(refundResp.bodyAsText())
      assertEquals(RefundStatus.PENDING, pendingRefund.status)

      val todayIst = LocalDate.now(istZone).toString()
      val reportBeforeWebhook = json.decodeFromString<SalesReportResponse>(
        client.get("/api/v1/owner/reports/sales?start_date=$todayIst&end_date=$todayIst") {
          header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        }.bodyAsText(),
      )
      assertTrue(reportBeforeWebhook.pendingRefundsPaise >= partialRefundPaise)

      // Deliver signed refund.processed webhook referencing refund idempotency_key note
      val webhookBody = """
        {
          "id": "evt_rfnd_${UUID.randomUUID().toString().take(8)}",
          "event": "refund.processed",
          "created_at": 1763100000,
          "payload": {
            "refund": {
              "entity": {
                "id": "rfnd_wh_${UUID.randomUUID().toString().take(8)}",
                "payment_id": "$gwPayId",
                "amount": $partialRefundPaise,
                "currency": "INR",
                "status": "processed",
                "notes": {
                  "idempotency_key": "${pendingRefund.idempotencyKey}"
                }
              }
            }
          }
        }
      """.trimIndent().toByteArray(StandardCharsets.UTF_8)

      val whResp = client.post("/api/v1/webhooks/razorpay") {
        header("X-Razorpay-Signature", signWebhook(webhookBody))
        contentType(ContentType.Application.Json)
        setBody(webhookBody)
      }
      assertEquals(HttpStatusCode.OK, whResp.status)

      val reportAfterWebhook = json.decodeFromString<SalesReportResponse>(
        client.get("/api/v1/owner/reports/sales?start_date=$todayIst&end_date=$todayIst") {
          header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        }.bodyAsText(),
      )
      assertEquals(0L, reportAfterWebhook.pendingRefundsPaise)
      assertEquals(reportBeforeWebhook.grossSalesPaise, reportAfterWebhook.grossSalesPaise)
      assertEquals(
        reportAfterWebhook.grossSalesPaise - reportAfterWebhook.refundedPaise,
        reportAfterWebhook.netSalesPaise,
      )
      assertEquals(
        reportAfterWebhook.taxPaise,
        reportAfterWebhook.cgstPaise + reportAfterWebhook.sgstPaise,
      )
      assertTrue(reportAfterWebhook.refundedPaise >= partialRefundPaise)
      assertTrue(reportAfterWebhook.topProducts.any { it.productId == fx.productId && it.quantitySold >= 1 })
      val couponRoi = reportAfterWebhook.couponPerformance.firstOrNull { it.code == couponCode }
      assertNotNull(couponRoi)
      assertEquals(1, couponRoi!!.redemptionCount)
      assertEquals(10_000L, couponRoi.totalDiscountPaise)
      assertEquals(order.totalPaise - partialRefundPaise, couponRoi.netOrderRevenuePaise)

      // Test Asia/Kolkata midnight boundary isolation:
      // 2026-11-10T18:29:59Z == 2026-11-10 23:59:59 IST (Day 1)
      // 2026-11-10T18:30:01Z == 2026-11-11 00:00:01 IST (Day 2)
      val day1Instant = Instant.parse("2026-11-10T18:29:59Z")
      val day2Instant = Instant.parse("2026-11-10T18:30:01Z")
      insertHistoricalPlacedOrder(createdAt = day1Instant, totalPaise = 31_500L, taxPaise = 1_500L)
      insertHistoricalPlacedOrder(createdAt = day2Instant, totalPaise = 63_000L, taxPaise = 3_000L)

      val day1Report = json.decodeFromString<SalesReportResponse>(
        client.get("/api/v1/owner/reports/sales?start_date=2026-11-10&end_date=2026-11-10") {
          header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        }.bodyAsText(),
      )
      assertEquals(1, day1Report.orderCount)
      assertEquals(31_500L, day1Report.grossSalesPaise)
      assertEquals(31_500L, day1Report.netSalesPaise)
      assertEquals(750L, day1Report.cgstPaise)
      assertEquals(750L, day1Report.sgstPaise)
      assertEquals(listOf("2026-11-10"), day1Report.dailyBreakdown.map { it.date })

      val day2Report = json.decodeFromString<SalesReportResponse>(
        client.get("/api/v1/owner/reports/sales?start_date=2026-11-11&end_date=2026-11-11") {
          header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        }.bodyAsText(),
      )
      assertEquals(1, day2Report.orderCount)
      assertEquals(63_000L, day2Report.grossSalesPaise)
      assertEquals(listOf("2026-11-11"), day2Report.dailyBreakdown.map { it.date })
    }

  @Test
  fun `operations report computes prep SLA compliance, order split, consumption, and wastage accurately`() =
    testApplication {
      val fx = setupFixtures(doughGrams = 3000L)

      // Set default prep SLA to 20 mins
      client.put("/api/v1/owner/settings") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(UpdateRestaurantSettingsRequest(defaultPrepMins = 20)))
      }

      // Place 2 COD orders -> consumes 2 * 200 = 400g dough
      val order1 = placeCodOrder(fx)
      val order2 = placeCodOrder(fx)

      // Transition order1 through KDS to READY + COMPLETED and backdate prep timestamps cleanly (10 mins <= 20 SLA)
      client.post("/api/v1/kds/orders/${order1.id}/ack") { header(HttpHeaders.Authorization, "Bearer ${fx.kitchenToken}") }
      client.post("/api/v1/kds/orders/${order1.id}/start-prep") { header(HttpHeaders.Authorization, "Bearer ${fx.kitchenToken}") }
      client.post("/api/v1/kds/orders/${order1.id}/ready") { header(HttpHeaders.Authorization, "Bearer ${fx.kitchenToken}") }

      // Transition order2 through KDS to READY (30 mins > 20 SLA -> breached)
      client.post("/api/v1/kds/orders/${order2.id}/ack") { header(HttpHeaders.Authorization, "Bearer ${fx.kitchenToken}") }
      client.post("/api/v1/kds/orders/${order2.id}/start-prep") { header(HttpHeaders.Authorization, "Bearer ${fx.kitchenToken}") }
      client.post("/api/v1/kds/orders/${order2.id}/ready") { header(HttpHeaders.Authorization, "Bearer ${fx.kitchenToken}") }

      // Restock +500g and record wastage -120g via MANUAL_ADJUST
      client.post("/api/v1/owner/inventory/adjust") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            AdjustInventoryRequest(
              ingredientId = fx.doughId,
              operationId = "p17-restock-${UUID.randomUUID()}",
              deltaQty = 500L,
              reason = InventoryLedgerReason.RESTOCK,
            ),
          ),
        )
      }
      client.post("/api/v1/owner/inventory/adjust") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            AdjustInventoryRequest(
              ingredientId = fx.doughId,
              operationId = "p17-waste-${UUID.randomUUID()}",
              deltaQty = -120L,
              reason = InventoryLedgerReason.MANUAL_ADJUST,
            ),
          ),
        )
      }

      // Put all test measurements on an isolated future IST date so concurrent tests don't skew SLA averages
      val targetIstDate = "2027-01-15"
      val baseInstant = LocalDate.parse(targetIstDate).atTime(14, 0).atZone(istZone).toInstant()
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement(
          """
          UPDATE orders
          SET created_at = ?, prep_started_at = ?, ready_at = ?, completed_at = ?, status = 'COMPLETED'
          WHERE id = ?::uuid
          """.trimIndent(),
        ).use { ps ->
          // Order 1: 10 mins prep (compliant), 18 mins completion
          ps.setTimestamp(1, Timestamp.from(baseInstant))
          ps.setTimestamp(2, Timestamp.from(baseInstant.plus(2, ChronoUnit.MINUTES)))
          ps.setTimestamp(3, Timestamp.from(baseInstant.plus(12, ChronoUnit.MINUTES)))
          ps.setTimestamp(4, Timestamp.from(baseInstant.plus(18, ChronoUnit.MINUTES)))
          ps.setString(5, order1.id)
          ps.executeUpdate()

          // Order 2: 30 mins prep (breached), 35 mins completion
          ps.setTimestamp(1, Timestamp.from(baseInstant.plus(5, ChronoUnit.MINUTES)))
          ps.setTimestamp(2, Timestamp.from(baseInstant.plus(7, ChronoUnit.MINUTES)))
          ps.setTimestamp(3, Timestamp.from(baseInstant.plus(37, ChronoUnit.MINUTES)))
          ps.setTimestamp(4, Timestamp.from(baseInstant.plus(40, ChronoUnit.MINUTES)))
          ps.setString(5, order2.id)
          ps.executeUpdate()
        }

        // Move dough ledger movements onto the same isolated IST date window
        conn.prepareStatement("UPDATE inventory_ledger SET created_at = ? WHERE ingredient_id = ?::uuid").use { ps ->
          ps.setTimestamp(1, Timestamp.from(baseInstant.plus(1, ChronoUnit.MINUTES)))
          ps.setString(2, fx.doughId)
          ps.executeUpdate()
        }
      }

      val ops = json.decodeFromString<OperationsReportResponse>(
        client.get("/api/v1/owner/reports/operations?start_date=$targetIstDate&end_date=$targetIstDate") {
          header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        }.bodyAsText(),
      )
      assertEquals(20, ops.prepSlaMinutes)
      assertEquals(2, ops.totalOrders)
      assertEquals(2, ops.completedOrders)
      assertEquals(2, ops.measuredPrepOrders)
      assertEquals(1, ops.slaBreachedCount)
      assertEquals(50.0, ops.slaCompliancePercent, 0.01)
      assertEquals(20.0, ops.avgPrepMinutes, 0.01)
      assertEquals(30.0, ops.maxPrepMinutes, 0.01)
      assertEquals(26.5, ops.avgCompletionMinutes, 0.01)
      assertEquals(3500L, ops.restockedUnits)
      assertEquals(400L, ops.consumedUnits)
      assertEquals(120L, ops.wastageUnits)
      val doughMove = ops.stockMovements.firstOrNull { it.ingredientId == fx.doughId }
      assertNotNull(doughMove)
      assertEquals(3500L, doughMove!!.restockedQty)
      assertEquals(400L, doughMove.consumedQty)
      assertEquals(120L, doughMove.wastageQty)
    }

  private suspend fun ApplicationTestBuilder.placeCodOrder(fx: SeedFixtures): OrderDetailDto {
    val resp = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerToken}")
      header("Idempotency-Key", "p17-cod-${UUID.randomUUID()}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CheckoutRequest(
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
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, resp.status)
    return json.decodeFromString<CheckoutResponse>(resp.bodyAsText()).order
  }

  private fun insertHistoricalPlacedOrder(
    createdAt: Instant,
    totalPaise: Long,
    taxPaise: Long,
  ) {
    pg.postgresDatabase.connection.use { conn ->
      val customerId = conn.prepareStatement("SELECT id FROM users ORDER BY created_at ASC LIMIT 1").use { ps ->
        ps.executeQuery().use { rs ->
          rs.next()
          rs.getObject("id", UUID::class.java)
        }
      }
      val orderId = UUID.randomUUID()
      val subtotal = totalPaise - taxPaise
      conn.prepareStatement(
        """
        INSERT INTO orders (
          id, order_number, customer_id, idempotency_key, request_hash,
          fulfillment_type, schedule_type, status,
          subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise,
          gst_bps_snapshot, version, created_at
        ) VALUES (?, ?, ?, ?, ?, 'PICKUP', 'ASAP', 'COMPLETED', ?, 0, 0, ?, ?, 500, 1, ?)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, orderId)
        ps.setString(2, "PZ-HIST-${UUID.randomUUID().toString().take(8).uppercase()}")
        ps.setObject(3, customerId)
        ps.setString(4, "hist-idem-${orderId}")
        ps.setString(5, "hist-hash-${orderId}")
        ps.setLong(6, subtotal)
        ps.setLong(7, taxPaise)
        ps.setLong(8, totalPaise)
        ps.setTimestamp(9, Timestamp.from(createdAt))
        ps.executeUpdate()
      }
    }
  }
}
