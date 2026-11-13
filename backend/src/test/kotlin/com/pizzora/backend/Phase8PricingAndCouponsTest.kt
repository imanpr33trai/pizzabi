package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.error.AppException
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.images.InspectedObject
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.AvailableCouponsResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CouponDto
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCouponRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DiscountType
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateCouponRequest
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyEmailRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase8PricingAndCouponsTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-p8-${UUID.randomUUID()}@pizzora.test"
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
          fullName = "Owner P8",
          phone = "9822222228",
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
      "http://localhost:9000/test/$objectKey"
    override suspend fun inspectObject(objectKey: String): InspectedObject? = null
    override suspend fun deleteObject(objectKey: String): Boolean = true
    override fun publicUrlFor(objectKey: String): String = "http://localhost:9000/test/$objectKey"
  }

  @Test
  fun `owner coupon CRUD, audit log, RBAC, customer coupon discovery, and quote calculation`() =
    testApplication {
      val emailProvider = InMemoryEmailDeliveryProvider()
      val config = AppConfig.fromEnv()
      application {
        pizzoraModule(
          config = config,
          probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
          dataSource = pg.postgresDatabase,
          cacheStore = InMemoryCacheStore(),
          emailDeliveryProvider = emailProvider,
          objectStorageClient = DummyStorageClient,
          passwordHasher = fastHasher,
        )
      }

      val ownerLogin = client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
      }
      val ownerToken = json.decodeFromString<AuthTokensResponse>(ownerLogin.bodyAsText()).accessToken

      // Provision kitchen staff + verify 403 on both customer quote and owner coupons
      val kdsEmail = "kds-p8-${UUID.randomUUID()}@pizzora.test"
      client.post("/api/v1/owner/staff") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateStaffRequest(
              email = kdsEmail,
              password = "StaffPassword123!",
              fullName = "Chef Franco",
              phone = "9855555551",
              role = UserRole.KITCHEN_STAFF,
              employeeCode = "KDS-${UUID.randomUUID().toString().take(6)}",
            ),
          ),
        )
      }
      val kdsToken = json.decodeFromString<AuthTokensResponse>(
        client.post("/api/v1/auth/login") {
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(LoginRequest(kdsEmail, "StaffPassword123!")))
        }.bodyAsText(),
      ).accessToken

      assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/coupons/available") { header(HttpHeaders.Authorization, "Bearer $kdsToken") }.status,
      )
      assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/owner/coupons") { header(HttpHeaders.Authorization, "Bearer $kdsToken") }.status,
      )

      // Seed catalog & delivery zone
      val category = json.decodeFromString<CategoryDto>(
        client.post("/api/v1/owner/categories") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCategoryRequest("Neapolitan P8", 1)))
        }.bodyAsText(),
      )
      val product = json.decodeFromString<ProductDto>(
        client.post("/api/v1/owner/products") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateProductRequest(
                categoryId = category.id,
                name = "Quattro Formaggi",
                description = "Four artisanal Italian cheeses",
                isVeg = true,
                variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 50_000L)),
              ),
            ),
          )
        }.bodyAsText(),
      )
      val variant = product.variants.first()
      val crust = json.decodeFromString<CrustOptionDto>(
        client.post("/api/v1/owner/crusts") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCrustRequest("Woodfire Thin P8", 5_000L)))
        }.bodyAsText(),
      )
      val topping = json.decodeFromString<ToppingDto>(
        client.post("/api/v1/owner/toppings") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateToppingRequest("Roasted Garlic P8", true, 5_000L)))
        }.bodyAsText(),
      )

      // Seed active delivery zone 560001
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement(
          """
          INSERT INTO delivery_zones (id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active)
          VALUES (?, '560001', 'MG Road Bengaluru', 40000, 4000, 30, TRUE)
          ON CONFLICT (pincode) DO UPDATE SET min_order_paise = 40000, delivery_fee_paise = 4000, is_active = TRUE
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, UUID.randomUUID())
          ps.executeUpdate()
        }
      }

      // Register & verify customer
      val custEmail = "cust-p8-${UUID.randomUUID()}@pizzora.test"
      client.post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RegisterRequest(custEmail, "CustPass123!", "Elena Rossi", "9855555552")))
      }
      client.post("/api/v1/auth/verify-email") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(VerifyEmailRequest(emailProvider.outbox.last().token)))
      }
      val custToken = json.decodeFromString<AuthTokensResponse>(
        client.post("/api/v1/auth/login") {
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(LoginRequest(custEmail, "CustPass123!")))
        }.bodyAsText(),
      ).accessToken

      // Customer blocked from owner coupon CRUD
      assertEquals(
        HttpStatusCode.Forbidden,
        client.post("/api/v1/owner/coupons") {
          header(HttpHeaders.Authorization, "Bearer $custToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateCouponRequest(
                code = "HACK20",
                discountType = DiscountType.FLAT_PAISE,
                discountValue = 10_000L,
                startsAt = Instant.now().minus(1, ChronoUnit.HOURS).toString(),
                endsAt = Instant.now().plus(2, ChronoUnit.DAYS).toString(),
              ),
            ),
          )
        }.status,
      )

      // Owner creates active capped percentage coupon, high-min coupon, and expired coupon
      val now = Instant.now()
      val activeResp = client.post("/api/v1/owner/coupons") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateCouponRequest(
              code = "pizza20", // normalized uppercase
              discountType = DiscountType.PERCENT_BPS,
              discountValue = 2000L, // 20%
              maxDiscountPaise = 10_000L, // cap Rs 100
              minOrderPaise = 50_000L,
              maxUses = 100,
              maxUsesPerUser = 2,
              startsAt = now.minus(1, ChronoUnit.HOURS).toString(),
              endsAt = now.plus(7, ChronoUnit.DAYS).toString(),
              isActive = true,
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.Created, activeResp.status)
      val pizza20 = json.decodeFromString<CouponDto>(activeResp.bodyAsText())
      assertEquals("PIZZA20", pizza20.code)

      // Duplicate code returns 409 CONFLICT
      val dupResp = client.post("/api/v1/owner/coupons") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateCouponRequest(
              code = "PIZZA20",
              discountType = DiscountType.FLAT_PAISE,
              discountValue = 5_000L,
              startsAt = now.minus(1, ChronoUnit.HOURS).toString(),
              endsAt = now.plus(1, ChronoUnit.DAYS).toString(),
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.Conflict, dupResp.status)

      // High-minimum-order coupon + expired coupon + temp coupon to delete
      val feastCode = "FEAST1000"
      client.post("/api/v1/owner/coupons") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateCouponRequest(
              code = feastCode,
              discountType = DiscountType.FLAT_PAISE,
              discountValue = 20_000L,
              minOrderPaise = 100_000L,
              startsAt = now.minus(1, ChronoUnit.HOURS).toString(),
              endsAt = now.plus(7, ChronoUnit.DAYS).toString(),
            ),
          ),
        )
      }
      client.post("/api/v1/owner/coupons") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateCouponRequest(
              code = "OLDDAYS",
              discountType = DiscountType.FLAT_PAISE,
              discountValue = 5_000L,
              startsAt = now.minus(5, ChronoUnit.DAYS).toString(),
              endsAt = now.minus(1, ChronoUnit.HOURS).toString(),
            ),
          ),
        )
      }
      val deletable = json.decodeFromString<CouponDto>(
        client.post("/api/v1/owner/coupons") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateCouponRequest(
                code = "TEMPDELETE",
                discountType = DiscountType.FLAT_PAISE,
                discountValue = 1_000L,
                startsAt = now.minus(1, ChronoUnit.HOURS).toString(),
                endsAt = now.plus(1, ChronoUnit.DAYS).toString(),
              ),
            ),
          )
        }.bodyAsText(),
      )
      assertEquals(
        HttpStatusCode.NoContent,
        client.delete("/api/v1/owner/coupons/${deletable.id}") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }.status,
      )

      // Verify audit logs written for coupon create/delete
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'coupon'").use { ps ->
          ps.executeQuery().use { rs ->
            rs.next()
            assertTrue(rs.getInt(1) >= 4)
          }
        }
      }

      // Customer checks available coupons with subtotal = 60,000 paise (50k + 5k + 5k)
      val availResp = client.get("/api/v1/coupons/available?subtotal_paise=60000") {
        header(HttpHeaders.Authorization, "Bearer $custToken")
      }
      assertEquals(HttpStatusCode.OK, availResp.status)
      val available = json.decodeFromString<AvailableCouponsResponse>(availResp.bodyAsText())
      // Expired & deleted excluded
      assertFalse(available.coupons.any { it.code == "OLDDAYS" || it.code == "TEMPDELETE" })
      val pizza20View = available.coupons.first { it.code == "PIZZA20" }
      assertTrue(pizza20View.isEligible)
      // 20% of 60,000 = 12,000 -> capped at 10,000
      assertEquals(10_000L, pizza20View.estimatedDiscountPaise)
      val feastView = available.coupons.first { it.code == feastCode }
      assertFalse(feastView.isEligible)
      assertNotNull(feastView.ineligibleReason)

      // Quote with tampered client price (1 paise), delivery pincode 560001, and lowercase "pizza20"
      val quoteResp = client.post("/api/v1/pricing/quote") {
        header(HttpHeaders.Authorization, "Bearer $custToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            PricingQuoteRequest(
              items = listOf(
                CartItemInputDto(
                  productId = product.id,
                  variantId = variant.id,
                  crustId = crust.id,
                  toppingIds = listOf(topping.id),
                  quantity = 1,
                  clientUnitPricePaise = 1L,
                ),
              ),
              fulfillmentType = FulfillmentType.DELIVERY,
              pincode = "560001",
              couponCode = "pizza20",
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, quoteResp.status)
      val quote = json.decodeFromString<PricingQuoteResponse>(quoteResp.bodyAsText())
      // Authoritative math: subtotal=60,000, discount=10,000 -> taxable=50,000
      // GST 5% of 50,000 = 2,500 -> CGST=1,250, SGST=1,250
      // Delivery=4,000 -> Total = (60,000 - 10,000) + 4,000 + 2,500 = 56,500
      assertEquals(60_000L, quote.breakdown.subtotalPaise)
      assertEquals(10_000L, quote.breakdown.discountPaise)
      assertEquals(50_000L, quote.breakdown.taxableAmountPaise)
      assertEquals(4_000L, quote.breakdown.deliveryFeePaise)
      assertEquals(2_500L, quote.breakdown.taxPaise)
      assertEquals(1_250L, quote.breakdown.cgstPaise)
      assertEquals(1_250L, quote.breakdown.sgstPaise)
      assertEquals(56_500L, quote.breakdown.totalPaise)
      assertEquals("PIZZA20", quote.appliedCoupon?.code)
      assertEquals("560001", quote.deliveryZone?.pincode)

      // Quote with expired coupon -> 422 COUPON_EXPIRED
      val expiredQuote = client.post("/api/v1/pricing/quote") {
        header(HttpHeaders.Authorization, "Bearer $custToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            PricingQuoteRequest(
              items = listOf(
                CartItemInputDto(
                  productId = product.id,
                  variantId = variant.id,
                  crustId = crust.id,
                  toppingIds = emptyList(),
                  quantity = 1,
                ),
              ),
              fulfillmentType = FulfillmentType.PICKUP,
              couponCode = "OLDDAYS",
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.UnprocessableEntity, expiredQuote.status)
      assertEquals("COUPON_EXPIRED", json.decodeFromString<ApiErrorEnvelope>(expiredQuote.bodyAsText()).error.code)

      // Quote with unmet min order -> 422 COUPON_MIN_ORDER_NOT_MET
      val minOrderQuote = client.post("/api/v1/pricing/quote") {
        header(HttpHeaders.Authorization, "Bearer $custToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            PricingQuoteRequest(
              items = listOf(
                CartItemInputDto(
                  productId = product.id,
                  variantId = variant.id,
                  crustId = crust.id,
                  toppingIds = emptyList(),
                  quantity = 1,
                ),
              ),
              fulfillmentType = FulfillmentType.PICKUP,
              couponCode = feastCode,
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.UnprocessableEntity, minOrderQuote.status)
      assertEquals("COUPON_MIN_ORDER_NOT_MET", json.decodeFromString<ApiErrorEnvelope>(minOrderQuote.bodyAsText()).error.code)

      // Quote with unserviceable pincode -> 422 ZONE_UNSERVICEABLE
      val badPincodeQuote = client.post("/api/v1/pricing/quote") {
        header(HttpHeaders.Authorization, "Bearer $custToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            PricingQuoteRequest(
              items = listOf(
                CartItemInputDto(
                  productId = product.id,
                  variantId = variant.id,
                  crustId = crust.id,
                  toppingIds = emptyList(),
                  quantity = 1,
                ),
              ),
              fulfillmentType = FulfillmentType.DELIVERY,
              pincode = "999999",
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.UnprocessableEntity, badPincodeQuote.status)
      assertEquals("ZONE_UNSERVICEABLE", json.decodeFromString<ApiErrorEnvelope>(badPincodeQuote.bodyAsText()).error.code)

      // Owner deactivates PIZZA20 -> PATCH returns isActive = false
      val patched = json.decodeFromString<CouponDto>(
        client.patch("/api/v1/owner/coupons/${pizza20.id}") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(UpdateCouponRequest(isActive = false)))
        }.bodyAsText(),
      )
      assertFalse(patched.isActive)
    }

  @Test
  fun `20-thread concurrent last-use coupon race allows exactly one winner under PostgreSQL FOR UPDATE`() = runBlocking {
    val couponService = CouponService(pg.postgresDatabase)
    val couponId = UUID.randomUUID()
    val couponCode = "LAST1_${couponId.toString().take(8).uppercase()}"
    val threadCount = 20

    // Seed single-use coupon + 20 distinct customer users + 20 parent orders rows
    val userIds = mutableListOf<UUID>()
    val orderIds = mutableListOf<UUID>()

    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement(
        """
        INSERT INTO coupons (
          id, code, discount_type, discount_value, max_discount_paise,
          min_order_paise, max_uses, max_uses_per_user, usage_count,
          starts_at, ends_at, is_active
        ) VALUES (?, ?, 'FLAT_PAISE', 10000, NULL, 20000, 1, 1, 0, NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 day', TRUE)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, couponId)
        ps.setString(2, couponCode)
        ps.executeUpdate()
      }

      repeat(threadCount) { idx ->
        val uid = UUID.randomUUID()
        val oid = UUID.randomUUID()
        userIds += uid
        orderIds += oid

        conn.prepareStatement(
          """
          INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active)
          VALUES (?, ?, NOW(), 'hash', ?, ?, TRUE)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, uid)
          ps.setString(2, "race-$idx-$uid@pizzora.test")
          ps.setString(3, "Racer $idx")
          ps.setString(4, "98000000${idx.toString().padStart(2, '0')}")
          ps.executeUpdate()
        }

        conn.prepareStatement(
          """
          INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash,
            fulfillment_type, schedule_type, status,
            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot
          ) VALUES (?, ?, ?, ?, 'hash', 'PICKUP', 'ASAP', 'PLACED', 50000, 10000, 0, 2000, 42000, 500)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, oid)
          ps.setString(2, "PZ-RACE-$oid")
          ps.setObject(3, uid)
          ps.setString(4, "idem-$oid")
          ps.executeUpdate()
        }
      }
    }

    val startGate = CountDownLatch(1)
    val doneGate = CountDownLatch(threadCount)
    val wins = AtomicInteger(0)
    val limitErrors = AtomicInteger(0)
    val unexpectedErrors = Collections.synchronizedList(mutableListOf<Throwable>())
    val executor = Executors.newFixedThreadPool(threadCount)

    try {
      repeat(threadCount) { idx ->
        executor.submit {
          try {
            startGate.await()
            runBlocking {
              DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
                couponService.reserveCouponForOrder(
                  conn = conn,
                  rawCode = couponCode,
                  userId = userIds[idx],
                  orderId = orderIds[idx],
                  subtotalPaise = 50_000L,
                  expiresAt = null,
                  immediateCommit = true,
                )
              }
            }
            wins.incrementAndGet()
          } catch (ex: AppException) {
            if (ex.code == "COUPON_USAGE_LIMIT_REACHED") {
              limitErrors.incrementAndGet()
            } else {
              unexpectedErrors += ex
            }
          } catch (t: Throwable) {
            unexpectedErrors += t
          } finally {
            doneGate.countDown()
          }
        }
      }

      startGate.countDown()
      assertTrue("Timed out waiting for concurrent threads", doneGate.await(20, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }

    assertTrue("Unexpected exceptions: $unexpectedErrors", unexpectedErrors.isEmpty())
    assertEquals(1, wins.get())
    assertEquals(threadCount - 1, limitErrors.get())

    // Verify DB consistency
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement("SELECT usage_count FROM coupons WHERE id = ?").use { ps ->
        ps.setObject(1, couponId)
        ps.executeQuery().use { rs ->
          rs.next()
          assertEquals(1, rs.getInt(1))
        }
      }
      conn.prepareStatement("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = ?").use { ps ->
        ps.setObject(1, couponId)
        ps.executeQuery().use { rs ->
          rs.next()
          assertEquals(1, rs.getInt(1))
        }
      }
    }
  }

  @Test
  fun `active coupon reservation blocks per-user limit until released and commit increments usage_count once`() = runBlocking {
    val couponService = CouponService(pg.postgresDatabase)
    val couponId = UUID.randomUUID()
    val couponCode = "HOLD_${couponId.toString().take(8).uppercase()}"
    val userId = UUID.randomUUID()
    val order1 = UUID.randomUUID()
    val order2 = UUID.randomUUID()

    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement(
        """
        INSERT INTO coupons (
          id, code, discount_type, discount_value, max_discount_paise,
          min_order_paise, max_uses, max_uses_per_user, usage_count,
          starts_at, ends_at, is_active
        ) VALUES (?, ?, 'FLAT_PAISE', 5000, NULL, 10000, 10, 1, 0, NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 day', TRUE)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, couponId)
        ps.setString(2, couponCode)
        ps.executeUpdate()
      }

      conn.prepareStatement(
        """
        INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active)
        VALUES (?, ?, NOW(), 'hash', 'Hold Tester', '9800009999', TRUE)
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, userId)
        ps.setString(2, "hold-$userId@pizzora.test")
        ps.executeUpdate()
      }

      listOf(order1, order2).forEach { oid ->
        conn.prepareStatement(
          """
          INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash,
            fulfillment_type, schedule_type, status,
            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot
          ) VALUES (?, ?, ?, ?, 'hash', 'PICKUP', 'ASAP', 'PENDING_PAYMENT', 40000, 5000, 0, 1750, 36750, 500)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, oid)
          ps.setString(2, "PZ-HOLD-$oid")
          ps.setObject(3, userId)
          ps.setString(4, "idem-$oid")
          ps.executeUpdate()
        }
      }
    }

    // Reserve ACTIVE hold for order1
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      couponService.reserveCouponForOrder(
        conn = conn,
        rawCode = couponCode,
        userId = userId,
        orderId = order1,
        subtotalPaise = 40_000L,
        expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES),
        immediateCommit = false,
      )
    }

    // Same user attempting order2 while order1 is ACTIVE fails with COUPON_USER_LIMIT_REACHED
    val blocked = runCatching {
      DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
        couponService.reserveCouponForOrder(
          conn = conn,
          rawCode = couponCode,
          userId = userId,
          orderId = order2,
          subtotalPaise = 40_000L,
          expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES),
          immediateCommit = false,
        )
      }
    }.exceptionOrNull() as? AppException
    assertNotNull(blocked)
    assertEquals("COUPON_USER_LIMIT_REACHED", blocked?.code)

    // Release hold on order1 -> order2 can now reserve, and committing twice only increments usage_count once
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      couponService.releaseReservedCoupon(conn, order1, expired = true)
      couponService.reserveCouponForOrder(
        conn = conn,
        rawCode = couponCode,
        userId = userId,
        orderId = order2,
        subtotalPaise = 40_000L,
        expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES),
        immediateCommit = false,
      )
      couponService.commitReservedCoupon(conn, order2)
      couponService.commitReservedCoupon(conn, order2)
    }

    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement("SELECT usage_count FROM coupons WHERE id = ?").use { ps ->
        ps.setObject(1, couponId)
        ps.executeQuery().use { rs ->
          rs.next()
          assertEquals(1, rs.getInt(1))
        }
      }
    }
  }
}
