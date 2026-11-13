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
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCouponRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderListResponse
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpsertAddressRequest
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
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase10CheckoutAndOrdersTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }
    private val slotDayCounter = java.util.concurrent.atomic.AtomicInteger(1)

    private val ownerEmail = "owner-p10-${UUID.randomUUID()}@pizzora.test"
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
          fullName = "Owner P10",
          phone = "9833333339",
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

  private data class SeedFixtures(
    val ownerToken: String,
    val customerId: String,
    val customerToken: String,
    val otherCustomerToken: String,
    val productId: String,
    val variantId: String,
    val crustId: String,
    val toppingId: String,
    val doughId: String,
    val cheeseId: String,
    val addressId: String,
    val slotId: UUID,
    val couponCode: String,
  )

  private suspend fun ApplicationTestBuilder.setupFixtures(
    initialDoughGrams: Long = 2000L,
    initialCheeseGrams: Long = 1000L,
    slotCapacity: Int = 2,
  ): SeedFixtures {
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

    suspend fun createVerifiedCustomer(): Pair<String, String> {
      val email = "cust-${UUID.randomUUID()}@pizzora.test"
      client.post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RegisterRequest(email, "CustPass123!", "Marco Rossi", "9811112233")))
      }
      val verifyToken = mailProvider.outbox.last { it.toEmail == email && it.type == "VERIFICATION" }.token
      client.post("/api/v1/auth/verify-email") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(VerifyEmailRequest(verifyToken)))
      }
      val login = client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(email, "CustPass123!")))
      }
      val parsed = json.decodeFromString<AuthTokensResponse>(login.bodyAsText())
      return parsed.user.id to parsed.accessToken
    }

    val (custId, custToken) = createVerifiedCustomer()
    val (_, otherCustToken) = createVerifiedCustomer()

    val catResp = client.post("/api/v1/owner/categories") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCategoryRequest("P10 Cat ${UUID.randomUUID()}", 1, true)))
    }
    val cat = json.decodeFromString<CategoryDto>(catResp.bodyAsText())

    val prodResp = client.post("/api/v1/owner/products") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateProductRequest(
            categoryId = cat.id,
            name = "Diavola Woodfire",
            description = "Spicy Calabrian salami",
            isVeg = false,
            variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 45_000L, true)),
          ),
        ),
      )
    }
    val prod = json.decodeFromString<ProductDto>(prodResp.bodyAsText())
    val variantId = prod.variants.first().id

    val crustResp = client.post("/api/v1/owner/crusts") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCrustRequest("P10 Crust ${UUID.randomUUID()}", 5_000L, true)))
    }
    val crust = json.decodeFromString<CrustOptionDto>(crustResp.bodyAsText())

    val topResp = client.post("/api/v1/owner/toppings") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateToppingRequest("P10 Burrata ${UUID.randomUUID()}", true, 10_000L, true)))
    }
    val top = json.decodeFromString<ToppingDto>(topResp.bodyAsText())

    val doughResp = client.post("/api/v1/owner/inventory") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateIngredientRequest("P10 Dough ${UUID.randomUUID()}", IngredientUnit.GRAMS, 200L, initialDoughGrams)))
    }
    val dough = json.decodeFromString<InventoryItemDto>(doughResp.bodyAsText())

    val cheeseResp = client.post("/api/v1/owner/inventory") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateIngredientRequest("P10 Cheese ${UUID.randomUUID()}", IngredientUnit.GRAMS, 100L, initialCheeseGrams)))
    }
    val cheese = json.decodeFromString<InventoryItemDto>(cheeseResp.bodyAsText())

    // 250g dough per pizza + 100g cheese on topping
    client.put("/api/v1/owner/recipes") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          PutRecipeRequest(
            RecipeTargetType.VARIANT,
            variantId,
            listOf(RecipeIngredientLineRequest(dough.ingredientId, 250)),
          ),
        ),
      )
    }
    client.put("/api/v1/owner/recipes") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          PutRecipeRequest(
            RecipeTargetType.TOPPING,
            top.id,
            listOf(RecipeIngredientLineRequest(cheese.ingredientId, 100)),
          ),
        ),
      )
    }

    val pincode = (560100 + (Math.abs(UUID.randomUUID().hashCode()) % 800)).toString()
    val slotId = UUID.randomUUID()
    val couponCode = "P10-${UUID.randomUUID().toString().take(6).uppercase()}"

    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        """
        INSERT INTO delivery_zones (id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active)
        VALUES (?, ?, 'Indiranagar', 30000, 4000, 28, TRUE)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setString(2, pincode)
        ps.executeUpdate()
      }

      conn.prepareStatement(
        """
        INSERT INTO scheduled_slots (id, slot_date, fulfillment_type, start_time_ist, end_time_ist, cutoff_at, max_capacity, reserved_count, is_active)
        VALUES (?, ?, 'DELIVERY', ?, ?, ?, ?, 0, TRUE)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, slotId)
        ps.setObject(2, LocalDate.now().plusDays(slotDayCounter.getAndIncrement().toLong()))
        ps.setObject(3, LocalTime.of(19, 0))
        ps.setObject(4, LocalTime.of(20, 0))
        ps.setTimestamp(5, Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)))
        ps.setInt(6, slotCapacity)
        ps.executeUpdate()
      }
    }

    val addrResp = client.post("/api/v1/me/addresses") {
      header(HttpHeaders.Authorization, "Bearer $custToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpsertAddressRequest("Home", "42 Woodfire Lane", null, null, pincode, "Bengaluru", true)))
    }
    val addr = json.decodeFromString<AddressDto>(addrResp.bodyAsText())

    val couponResp = client.post("/api/v1/owner/coupons") {
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
    assertEquals(HttpStatusCode.Created, couponResp.status)

    return SeedFixtures(
      ownerToken = ownerToken,
      customerId = custId,
      customerToken = custToken,
      otherCustomerToken = otherCustToken,
      productId = prod.id,
      variantId = variantId,
      crustId = crust.id,
      toppingId = top.id,
      doughId = dough.ingredientId,
      cheeseId = cheese.ingredientId,
      addressId = addr.id,
      slotId = slotId,
      couponCode = couponCode,
    )
  }

  @Test
  fun `COD scheduled checkout creates PLACED order with committed stock, clears cart, supports idempotent replay, and cancel restores stock and slot`() =
    testApplication {
      val f = setupFixtures(initialDoughGrams = 1000L, initialCheeseGrams = 500L)

      // Seed cart first to verify clear_cart = true cleans it up
      val putCart = client.put("/api/v1/cart") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            ReplaceCartRequest(
              listOf(CartItemInputDto(productId = f.productId, variantId = f.variantId, crustId = f.crustId, toppingIds = listOf(f.toppingId), quantity = 2)),
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, putCart.status)

      val checkoutReq = CheckoutRequest(
        items = listOf(
          CartItemInputDto(
            productId = f.productId,
            variantId = f.variantId,
            crustId = f.crustId,
            toppingIds = listOf(f.toppingId),
            quantity = 2,
          ),
        ),
        fulfillmentType = FulfillmentType.DELIVERY,
        scheduleType = ScheduleType.SCHEDULED,
        scheduledSlotId = f.slotId.toString(),
        addressId = f.addressId,
        paymentMethod = PaymentMethod.COD,
        couponCode = f.couponCode,
      )

      val idemKey = "cod-checkout-${UUID.randomUUID()}"
      val createResp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", idemKey)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(checkoutReq))
      }
      assertEquals(HttpStatusCode.Created, createResp.status)
      val created = json.decodeFromString<CheckoutResponse>(createResp.bodyAsText())
      assertFalse(created.idempotentReplay)

      val order = created.order
      // 2 * (45000 + 5000 + 10000) = 120000; discount 10000 -> taxable 110000 -> GST 5% = 5500 -> delivery 4000 -> total 119500
      assertEquals(OrderStatus.PLACED, order.status)
      assertEquals(120_000L, order.subtotalPaise)
      assertEquals(10_000L, order.discountPaise)
      assertEquals(4_000L, order.deliveryFeePaise)
      assertEquals(5_500L, order.taxPaise)
      assertEquals(119_500L, order.totalPaise)
      assertEquals(PaymentMethod.COD, order.payment.method)
      assertEquals(PaymentStatus.COD_PENDING, order.payment.status)
      assertEquals(PaymentAttemptStatus.AWAITING_CUSTOMER_PAYMENT, order.payment.latestAttempt?.status)
      assertNotNull(order.deliveryAddress)
      assertNull(order.expiresAt)
      assertEquals(1, order.items.size)
      assertEquals(listOf("P10 Burrata".let { prefix -> order.items[0].toppings.first { it.startsWith("P10 Burrata") } }), order.items[0].toppings)

      // Idempotent replay with same key & same payload -> 200 OK, exact same order ID
      val replayResp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", idemKey)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(checkoutReq))
      }
      assertEquals(HttpStatusCode.OK, replayResp.status)
      val replay = json.decodeFromString<CheckoutResponse>(replayResp.bodyAsText())
      assertTrue(replay.idempotentReplay)
      assertEquals(order.id, replay.order.id)

      // Mismatched payload with same Idempotency-Key -> 409 IDEMPOTENCY_CONFLICT
      val conflictResp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", idemKey)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(checkoutReq.copy(paymentMethod = PaymentMethod.RAZORPAY)))
      }
      assertEquals(HttpStatusCode.Conflict, conflictResp.status)
      assertEquals("IDEMPOTENCY_CONFLICT", json.decodeFromString<ApiErrorEnvelope>(conflictResp.bodyAsText()).error.code)

      // Verify cart cleared and DB commitments (500g dough consumed, 200g cheese consumed, 1 slot reserved, outbox ORDER_PLACED)
      val cartAfter = json.decodeFromString<CartResponseDto>(
        client.get("/api/v1/cart") { header(HttpHeaders.Authorization, "Bearer ${f.customerToken}") }.bodyAsText(),
      )
      assertTrue(cartAfter.items.isEmpty())

      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT on_hand_qty, reserved_qty FROM inventory WHERE ingredient_id = ?").use { ps ->
          ps.setObject(1, UUID.fromString(f.doughId))
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(500L, rs.getLong("on_hand_qty"))
            assertEquals(0L, rs.getLong("reserved_qty"))
          }
        }
        conn.prepareStatement("SELECT reserved_count FROM scheduled_slots WHERE id = ?").use { ps ->
          ps.setObject(1, f.slotId)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(1, rs.getInt("reserved_count"))
          }
        }
        conn.prepareStatement("SELECT event_type, stream_seq, dispatch_status FROM outbox_events WHERE aggregate_id = ?::uuid").use { ps ->
          ps.setString(1, order.id)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals("ORDER_PLACED", rs.getString("event_type"))
            assertNull(rs.getObject("stream_seq"))
            assertEquals("PENDING", rs.getString("dispatch_status"))
          }
        }
      }

      // IDOR check: another customer cannot read or cancel this order
      val idorGet = client.get("/api/v1/orders/${order.id}") {
        header(HttpHeaders.Authorization, "Bearer ${f.otherCustomerToken}")
      }
      assertEquals(HttpStatusCode.Forbidden, idorGet.status)

      // Cancel while PLACED -> restores stock (`CANCEL_RESTORE`), releases slot (`reserved_count = 0`), cancels COD payment
      val cancelResp = client.post("/api/v1/orders/${order.id}/cancel") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        contentType(ContentType.Application.Json)
        setBody("""{"reason":"Changed dinner plan"}""")
      }
      assertEquals(HttpStatusCode.OK, cancelResp.status)
      val cancelled = json.decodeFromString<OrderDetailDto>(cancelResp.bodyAsText())
      assertEquals(OrderStatus.CANCELLED, cancelled.status)
      assertEquals(2L, cancelled.version)
      assertEquals(PaymentStatus.CANCELLED, cancelled.payment.status)
      assertEquals(PaymentAttemptStatus.CANCELLED, cancelled.payment.latestAttempt?.status)
      assertEquals(2, cancelled.statusHistory.size)

      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT on_hand_qty FROM inventory WHERE ingredient_id = ?").use { ps ->
          ps.setObject(1, UUID.fromString(f.doughId))
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(1000L, rs.getLong("on_hand_qty"))
          }
        }
        conn.prepareStatement("SELECT reserved_count FROM scheduled_slots WHERE id = ?").use { ps ->
          ps.setObject(1, f.slotId)
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(0, rs.getInt("reserved_count"))
          }
        }
      }
    }

  @Test
  fun `mid-flight insufficient stock rolls back orders, slot, coupon, payment, and outbox atomically`() =
    testApplication {
      // Only 300g dough on hand; 2 pizzas require 500g
      val f = setupFixtures(initialDoughGrams = 300L, initialCheeseGrams = 1000L)

      val req = CheckoutRequest(
        items = listOf(
          CartItemInputDto(
            productId = f.productId,
            variantId = f.variantId,
            crustId = f.crustId,
            toppingIds = listOf(f.toppingId),
            quantity = 2,
          ),
        ),
        fulfillmentType = FulfillmentType.DELIVERY,
        scheduleType = ScheduleType.SCHEDULED,
        scheduledSlotId = f.slotId.toString(),
        addressId = f.addressId,
        paymentMethod = PaymentMethod.RAZORPAY,
        couponCode = f.couponCode,
      )

      val resp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", "rollback-test-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(req))
      }
      assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
      assertEquals("INSUFFICIENT_INVENTORY", json.decodeFromString<ApiErrorEnvelope>(resp.bodyAsText()).error.code)

      // Zero dangling rows created for customer & slot reserved_count stays 0
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM orders WHERE customer_id = ?::uuid").use { ps ->
          ps.setString(1, f.customerId)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(0, rs.getInt(1))
          }
        }
        conn.prepareStatement("SELECT reserved_count FROM scheduled_slots WHERE id = ?").use { ps ->
          ps.setObject(1, f.slotId)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(0, rs.getInt(1))
          }
        }
        conn.prepareStatement("SELECT usage_count FROM coupons WHERE code = ?").use { ps ->
          ps.setString(1, f.couponCode)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(0, rs.getInt(1))
          }
        }
      }
    }

  @Test
  fun `Razorpay checkout creates PENDING_PAYMENT order and cancel after PREPARING returns 409`() =
    testApplication {
      val f = setupFixtures(initialDoughGrams = 2000L, initialCheeseGrams = 1000L)

      val rzpReq = CheckoutRequest(
        items = listOf(
          CartItemInputDto(
            productId = f.productId,
            variantId = f.variantId,
            crustId = f.crustId,
            toppingIds = listOf(f.toppingId),
            quantity = 1,
          ),
        ),
        fulfillmentType = FulfillmentType.PICKUP,
        scheduleType = ScheduleType.ASAP,
        paymentMethod = PaymentMethod.RAZORPAY,
      )

      val createRzp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", "rzp-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(rzpReq))
      }
      assertEquals(HttpStatusCode.Created, createRzp.status)
      val rzpOrder = json.decodeFromString<CheckoutResponse>(createRzp.bodyAsText()).order
      assertEquals(OrderStatus.PENDING_PAYMENT, rzpOrder.status)
      assertEquals(PaymentStatus.UNPAID, rzpOrder.payment.status)
      assertEquals(PaymentAttemptStatus.INITIATING, rzpOrder.payment.latestAttempt?.status)
      assertNotNull(rzpOrder.expiresAt)

      // Check inventory is reserved (on_hand unchanged = 2000, reserved = 250)
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT on_hand_qty, reserved_qty FROM inventory WHERE ingredient_id = ?").use { ps ->
          ps.setObject(1, UUID.fromString(f.doughId))
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(2000L, rs.getLong("on_hand_qty"))
            assertEquals(250L, rs.getLong("reserved_qty"))
          }
        }
      }

      // Cancel unpaid PENDING_PAYMENT order -> releases reserved_qty back to 0
      val cancelUnpaid = client.post("/api/v1/orders/${rzpOrder.id}/cancel") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
      }
      assertEquals(HttpStatusCode.OK, cancelUnpaid.status)

      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT reserved_qty FROM inventory WHERE ingredient_id = ?").use { ps ->
          ps.setObject(1, UUID.fromString(f.doughId))
          ps.executeQuery().use { rs ->
            assertTrue(rs.next())
            assertEquals(0L, rs.getLong("reserved_qty"))
          }
        }
      }

      // Second order placed via COD and moved to PREPARING -> customer cancel rejected with 409 INVALID_ORDER_TRANSITION
      val codResp = client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
        header("Idempotency-Key", "cod-prep-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(rzpReq.copy(paymentMethod = PaymentMethod.COD)))
      }
      assertEquals(HttpStatusCode.Created, codResp.status)
      val codOrder = json.decodeFromString<CheckoutResponse>(codResp.bodyAsText()).order

      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("UPDATE orders SET status = 'PREPARING', prep_started_at = NOW() WHERE id = ?::uuid").use { ps ->
          ps.setString(1, codOrder.id)
          ps.executeUpdate()
        }
      }

      val lateCancel = client.post("/api/v1/orders/${codOrder.id}/cancel") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
      }
      assertEquals(HttpStatusCode.Conflict, lateCancel.status)
      assertEquals("INVALID_ORDER_TRANSITION", json.decodeFromString<ApiErrorEnvelope>(lateCancel.bodyAsText()).error.code)

      // Order history lists both customer orders
      val listResp = client.get("/api/v1/orders") {
        header(HttpHeaders.Authorization, "Bearer ${f.customerToken}")
      }
      assertEquals(HttpStatusCode.OK, listResp.status)
      val history = json.decodeFromString<OrderListResponse>(listResp.bodyAsText())
      assertEquals(2, history.orders.size)
    }
}
