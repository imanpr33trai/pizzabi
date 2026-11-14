package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
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
import com.pizzora.backend.slots.ScheduledSlotService
import com.pizzora.backend.worker.ExpirySweepWorker
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.AvailableSlotsResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateDeliveryZoneRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateScheduledSlotRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DeliveryZoneCheckResponse
import com.pizzora.contracts.DeliveryZoneDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.ScheduledSlotDto
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateDeliveryZoneRequest
import com.pizzora.contracts.UpdateScheduledSlotRequest
import com.pizzora.contracts.UpsertAddressRequest
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase15ZonesAndSlotsTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }
    private val slotDayOffset = AtomicInteger(10)

    private val ownerEmail = "owner-p15-${UUID.randomUUID()}@pizzora.test"
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
          fullName = "Owner P15",
          phone = "9833333315",
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
    val customerAToken: String,
    val customerBToken: String,
    val productId: String,
    val variantId: String,
    val crustId: String,
    val toppingId: String,
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

    suspend fun registerAndLogin(prefix: String): String {
      val email = "$prefix-${UUID.randomUUID()}@pizzora.test"
      client.post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RegisterRequest(email, "CustPass123!", "$prefix User", "9811111515")))
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
      return json.decodeFromString<AuthTokensResponse>(login.bodyAsText()).accessToken
    }

    val customerAToken = registerAndLogin("cust-a-p15")
    val customerBToken = registerAndLogin("cust-b-p15")

    val catResp = client.post("/api/v1/owner/categories") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCategoryRequest("P15 Artisanal ${UUID.randomUUID()}", 1, true)))
    }
    val cat = json.decodeFromString<CategoryDto>(catResp.bodyAsText())

    val prodResp = client.post("/api/v1/owner/products") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateProductRequest(
            categoryId = cat.id,
            name = "Diavola P15",
            description = "Spicy salami + honey",
            isVeg = false,
            variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 40_000L, true)),
          ),
        ),
      )
    }
    val prod = json.decodeFromString<ProductDto>(prodResp.bodyAsText())

    val crustResp = client.post("/api/v1/owner/crusts") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCrustRequest("P15 Sourdough ${UUID.randomUUID()}", 0L, true)))
    }
    val crust = json.decodeFromString<CrustOptionDto>(crustResp.bodyAsText())

    val topResp = client.post("/api/v1/owner/toppings") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateToppingRequest("P15 Chili Oil ${UUID.randomUUID()}", true, 2_000L, true)))
    }
    val top = json.decodeFromString<ToppingDto>(topResp.bodyAsText())

    return Fixtures(
      ownerToken = ownerToken,
      customerAToken = customerAToken,
      customerBToken = customerBToken,
      productId = prod.id,
      variantId = prod.variants.first().id,
      crustId = crust.id,
      toppingId = top.id,
    )
  }

  private fun nextUniquePincode(): String = "${(560100..569999).random()}"

  private fun nextUniqueSlotDate(): LocalDate =
    LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(slotDayOffset.getAndIncrement().toLong())

  @Test
  fun `delivery zone CRUD, public pincode check, RBAC, and unserviceable or min-order checkout rejection`() = testApplication {
    val fx = setupFixtures()
    val pinServiceable = nextUniquePincode()
    val pinUnserviceable = nextUniquePincode()

    // Customer cannot access /owner/delivery-zones
    val forbiddenList = client.get("/api/v1/owner/delivery-zones") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
    }
    assertEquals(HttpStatusCode.Forbidden, forbiddenList.status)

    // Owner creates active zone with minOrder = ₹600 (60_000 paise)
    val createResp = client.post("/api/v1/owner/delivery-zones") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateDeliveryZoneRequest(
            pincode = pinServiceable,
            areaName = "Koramangala 4th Block",
            minOrderPaise = 60_000L,
            deliveryFeePaise = 3_500L,
            estimatedMins = 28,
            isActive = true,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, createResp.status)
    val createdZone = json.decodeFromString<DeliveryZoneDto>(createResp.bodyAsText())
    assertEquals(pinServiceable, createdZone.pincode)

    // Public pincode check returns serviceable
    val checkOk = client.get("/api/v1/delivery-zones/check?pincode=$pinServiceable")
    assertEquals(HttpStatusCode.OK, checkOk.status)
    val okDto = json.decodeFromString<DeliveryZoneCheckResponse>(checkOk.bodyAsText())
    assertTrue(okDto.isServiceable)
    assertEquals("Koramangala 4th Block", okDto.zone?.areaName)

    // Public check on unknown pincode returns isServiceable = false
    val checkMissing = client.get("/api/v1/delivery-zones/check?pincode=$pinUnserviceable")
    assertEquals(HttpStatusCode.OK, checkMissing.status)
    val missingDto = json.decodeFromString<DeliveryZoneCheckResponse>(checkMissing.bodyAsText())
    assertFalse(missingDto.isServiceable)
    assertNull(missingDto.zone)

    // Customer saves unserviceable address + serviceable address
    val unservAddrResp = client.post("/api/v1/me/addresses") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpsertAddressRequest("Outskirts", "Far Rd", pincode = pinUnserviceable, city = "Bengaluru")))
    }
    val unservAddr = json.decodeFromString<AddressDto>(unservAddrResp.bodyAsText())

    val servAddrResp = client.post("/api/v1/me/addresses") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpsertAddressRequest("Home", "4th Block", pincode = pinServiceable, city = "Bengaluru", isDefault = true)))
    }
    val servAddr = json.decodeFromString<AddressDto>(servAddrResp.bodyAsText())

    val item = listOf(CartItemInputDto(productId = fx.productId, variantId = fx.variantId, crustId = fx.crustId, toppingIds = listOf(fx.toppingId), quantity = 1))

    // Unserviceable pincode blocks delivery checkout
    val unservCheckout = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CheckoutRequest(item, FulfillmentType.DELIVERY, ScheduleType.ASAP, addressId = unservAddr.id, paymentMethod = PaymentMethod.COD)))
    }
    assertEquals(HttpStatusCode.UnprocessableEntity, unservCheckout.status)
    assertEquals("ZONE_UNSERVICEABLE", json.decodeFromString<ApiErrorEnvelope>(unservCheckout.bodyAsText()).error.code)

    // Below min order (42_000 < 60_000) blocks delivery checkout
    val belowMinCheckout = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CheckoutRequest(item, FulfillmentType.DELIVERY, ScheduleType.ASAP, addressId = servAddr.id, paymentMethod = PaymentMethod.COD)))
    }
    assertEquals(HttpStatusCode.UnprocessableEntity, belowMinCheckout.status)
    assertEquals("DELIVERY_MIN_ORDER_NOT_MET", json.decodeFromString<ApiErrorEnvelope>(belowMinCheckout.bodyAsText()).error.code)

    // Owner lowers min order & toggles zone inactive -> check returns false, checkout blocked; reactivate -> succeeds
    val deactivateResp = client.patch("/api/v1/owner/delivery-zones/${createdZone.id}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateDeliveryZoneRequest(minOrderPaise = 20_000L, isActive = false)))
    }
    assertEquals(HttpStatusCode.OK, deactivateResp.status)
    assertFalse(json.decodeFromString<DeliveryZoneCheckResponse>(client.get("/api/v1/delivery-zones/check?pincode=$pinServiceable").bodyAsText()).isServiceable)

    client.patch("/api/v1/owner/delivery-zones/${createdZone.id}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateDeliveryZoneRequest(isActive = true)))
    }

    val okCheckout = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CheckoutRequest(item, FulfillmentType.DELIVERY, ScheduleType.ASAP, addressId = servAddr.id, paymentMethod = PaymentMethod.COD)))
    }
    assertEquals(HttpStatusCode.Created, okCheckout.status)
    assertEquals(3_500L, json.decodeFromString<CheckoutResponse>(okCheckout.bodyAsText()).order.deliveryFeePaise)

    // Owner deletes a temporary zone
    val tempPin = nextUniquePincode()
    val tempCreated = json.decodeFromString<DeliveryZoneDto>(
      client.post("/api/v1/owner/delivery-zones") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateDeliveryZoneRequest(tempPin, "Temp Area", 0L, 1_500L, 20)))
      }.bodyAsText(),
    )
    val delResp = client.delete("/api/v1/owner/delivery-zones/${tempCreated.id}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
    }
    assertEquals(HttpStatusCode.NoContent, delResp.status)
  }

  @Test
  fun `Asia Kolkata IST timezone cutoff computation and past-cutoff slot rejection`() = testApplication {
    val fx = setupFixtures()
    val istZone = ZoneId.of("Asia/Kolkata")
    val slotDate = nextUniqueSlotDate()

    // 19:00 - 20:00 IST with 30-min cutoff => cutoff_at must equal 18:30 IST (13:00 UTC same day)
    val createResp = client.post("/api/v1/owner/slots") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateScheduledSlotRequest(
            slotDate = slotDate.toString(),
            fulfillmentType = FulfillmentType.PICKUP,
            startTimeIst = "19:00",
            endTimeIst = "20:00",
            cutoffMinutesBeforeStart = 30,
            maxCapacity = 5,
            isActive = true,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, createResp.status)
    val created = json.decodeFromString<ScheduledSlotDto>(createResp.bodyAsText())
    val expectedCutoffInstant = ZonedDateTime.of(slotDate, LocalTime.of(18, 30), istZone).toInstant()
    assertEquals(expectedCutoffInstant, Instant.parse(created.cutoffAt))

    // Verify ScheduledSlotService IST cutoff evaluation right before and right after 18:30 IST
    val slotService = ScheduledSlotService(pg.postgresDatabase)
    val beforeCutoff = slotService.listAvailableSlots("PICKUP", slotDate.toString(), now = expectedCutoffInstant.minusSeconds(30))
    assertEquals("Asia/Kolkata", beforeCutoff.timezone)
    val slotBefore = beforeCutoff.slots.first { it.id == created.id }
    assertTrue(slotBefore.isBookable)
    assertNull(slotBefore.unavailableReason)

    val afterCutoff = slotService.listAvailableSlots("PICKUP", slotDate.toString(), now = expectedCutoffInstant.plusSeconds(30))
    val slotAfter = afterCutoff.slots.first { it.id == created.id }
    assertFalse(slotAfter.isBookable)
    assertEquals("Booking cutoff passed", slotAfter.unavailableReason)

    // Create an already-past-cutoff slot and attempt HTTP checkout -> rejected with SLOT_CUTOFF_PASSED
    val pastDate = LocalDate.now(istZone).minusDays(1)
    val pastSlotResp = client.post("/api/v1/owner/slots") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateScheduledSlotRequest(
            slotDate = pastDate.toString(),
            fulfillmentType = FulfillmentType.PICKUP,
            startTimeIst = "20:00",
            endTimeIst = "21:00",
            cutoffMinutesBeforeStart = 30,
            maxCapacity = 5,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, pastSlotResp.status)
    val pastSlot = json.decodeFromString<ScheduledSlotDto>(pastSlotResp.bodyAsText())
    assertFalse(pastSlot.isBookable)

    val publicSlots = json.decodeFromString<AvailableSlotsResponse>(
      client.get("/api/v1/slots?type=PICKUP&date=$pastDate").bodyAsText(),
    )
    assertFalse(publicSlots.slots.first { it.id == pastSlot.id }.isBookable)

    val item = listOf(CartItemInputDto(productId = fx.productId, variantId = fx.variantId, crustId = fx.crustId, toppingIds = listOf(fx.toppingId), quantity = 1))
    val pastCheckout = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CheckoutRequest(
            items = item,
            fulfillmentType = FulfillmentType.PICKUP,
            scheduleType = ScheduleType.SCHEDULED,
            scheduledSlotId = pastSlot.id,
            paymentMethod = PaymentMethod.COD,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.UnprocessableEntity, pastCheckout.status)
    assertEquals("SLOT_CUTOFF_PASSED", json.decodeFromString<ApiErrorEnvelope>(pastCheckout.bodyAsText()).error.code)
  }

  @Test
  fun `concurrent last-slot booking prevents overbooking and keeps reserved_count in sync`() = testApplication {
    val fx = setupFixtures()
    val slotDate = nextUniqueSlotDate()

    val slotResp = client.post("/api/v1/owner/slots") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateScheduledSlotRequest(
            slotDate = slotDate.toString(),
            fulfillmentType = FulfillmentType.PICKUP,
            startTimeIst = "13:00",
            endTimeIst = "14:00",
            cutoffMinutesBeforeStart = 30,
            maxCapacity = 1,
            isActive = true,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, slotResp.status)
    val slot = json.decodeFromString<ScheduledSlotDto>(slotResp.bodyAsText())

    val item = listOf(CartItemInputDto(productId = fx.productId, variantId = fx.variantId, crustId = fx.crustId, toppingIds = listOf(fx.toppingId), quantity = 1))
    val results = coroutineScope {
      (1..6).map { idx ->
        async {
          val token = if (idx % 2 == 0) fx.customerAToken else fx.customerBToken
          client.post("/api/v1/checkout") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Idempotency-Key", "race-$idx-${UUID.randomUUID()}")
            contentType(ContentType.Application.Json)
            setBody(
              json.encodeToString(
                CheckoutRequest(
                  items = item,
                  fulfillmentType = FulfillmentType.PICKUP,
                  scheduleType = ScheduleType.SCHEDULED,
                  scheduledSlotId = slot.id,
                  paymentMethod = PaymentMethod.COD,
                ),
              ),
            )
          }
        }
      }.awaitAll()
    }

    val createdCount = results.count { it.status == HttpStatusCode.Created }
    val conflictCount = results.count { it.status == HttpStatusCode.Conflict }
    assertEquals(1, createdCount)
    assertEquals(5, conflictCount)

    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        """
        SELECT s.reserved_count,
               (SELECT COUNT(*) FROM scheduled_slot_reservations r
                WHERE r.slot_id = s.id AND r.status IN ('ACTIVE', 'COMMITTED')) AS active_or_committed
        FROM scheduled_slots s
        WHERE s.id = ?::uuid
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, slot.id)
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals(1, rs.getInt("reserved_count"))
          assertEquals(1, rs.getInt("active_or_committed"))
        }
      }
    }
  }

  @Test
  fun `expiry sweep and cancellation release scheduled slot with double-decrement guard`() = testApplication {
    val fx = setupFixtures()
    val slotDate = nextUniqueSlotDate()

    val slot = json.decodeFromString<ScheduledSlotDto>(
      client.post("/api/v1/owner/slots") {
        header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateScheduledSlotRequest(
              slotDate = slotDate.toString(),
              fulfillmentType = FulfillmentType.PICKUP,
              startTimeIst = "20:00",
              endTimeIst = "21:00",
              cutoffMinutesBeforeStart = 30,
              maxCapacity = 2,
              isActive = true,
            ),
          ),
        )
      }.bodyAsText(),
    )

    val item = listOf(CartItemInputDto(productId = fx.productId, variantId = fx.variantId, crustId = fx.crustId, toppingIds = listOf(fx.toppingId), quantity = 1))

    // 1. Place Razorpay scheduled order -> ACTIVE reservation, reserved_count = 1
    val rzpCheckout = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CheckoutRequest(
            items = item,
            fulfillmentType = FulfillmentType.PICKUP,
            scheduleType = ScheduleType.SCHEDULED,
            scheduledSlotId = slot.id,
            paymentMethod = PaymentMethod.RAZORPAY,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, rzpCheckout.status)
    val rzpOrder = json.decodeFromString<CheckoutResponse>(rzpCheckout.bodyAsText()).order
    assertEquals(OrderStatus.PENDING_PAYMENT, rzpOrder.status)

    // Force payment window expiry and run ExpirySweepWorker TWICE -> double-decrement guard keeps reserved_count at 0
    val past = Timestamp.from(Instant.now().minus(30, ChronoUnit.MINUTES))
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement("UPDATE orders SET expires_at = ? WHERE id = ?::uuid").use { ps ->
        ps.setTimestamp(1, past)
        ps.setString(2, rzpOrder.id)
        ps.executeUpdate()
      }
      conn.prepareStatement("UPDATE scheduled_slot_reservations SET expires_at = ? WHERE order_id = ?::uuid").use { ps ->
        ps.setTimestamp(1, past)
        ps.setString(2, rzpOrder.id)
        ps.executeUpdate()
      }
    }

    val inventoryService = InventoryService(pg.postgresDatabase)
    val couponService = CouponService(pg.postgresDatabase)
    val orderService = OrderService(pg.postgresDatabase, inventoryService, couponService)
    val expiryWorker = ExpirySweepWorker(pg.postgresDatabase, inventoryService, couponService, orderService)
    assertEquals(1, expiryWorker.runOnce())
    assertEquals(0, expiryWorker.runOnce())

    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement("SELECT reserved_count FROM scheduled_slots WHERE id = ?::uuid").use { ps ->
        ps.setString(1, slot.id)
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals(0, rs.getInt("reserved_count"))
        }
      }
      conn.prepareStatement("SELECT status FROM scheduled_slot_reservations WHERE order_id = ?::uuid").use { ps ->
        ps.setString(1, rzpOrder.id)
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals("EXPIRED", rs.getString("status"))
        }
      }
    }

    // 2. Place COD scheduled order -> COMMITTED reservation, reserved_count = 1; then cancel -> RELEASED & reserved_count = 0
    val codCheckout = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerBToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CheckoutRequest(
            items = item,
            fulfillmentType = FulfillmentType.PICKUP,
            scheduleType = ScheduleType.SCHEDULED,
            scheduledSlotId = slot.id,
            paymentMethod = PaymentMethod.COD,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, codCheckout.status)
    val codOrder = json.decodeFromString<CheckoutResponse>(codCheckout.bodyAsText()).order

    val cancelResp = client.post("/api/v1/orders/${codOrder.id}/cancel") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerBToken}")
    }
    assertEquals(HttpStatusCode.OK, cancelResp.status)
    assertEquals(OrderStatus.CANCELLED, json.decodeFromString<OrderDetailDto>(cancelResp.bodyAsText()).status)

    // Verify reserved_count == COUNT(ACTIVE + COMMITTED) == 0 and slot update/delete guards behave properly
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        """
        SELECT s.reserved_count,
               (SELECT COUNT(*) FROM scheduled_slot_reservations r
                WHERE r.slot_id = s.id AND r.status IN ('ACTIVE', 'COMMITTED')) AS active_or_committed
        FROM scheduled_slots s
        WHERE s.id = ?::uuid
        """.trimIndent(),
      ).use { ps ->
        ps.setString(1, slot.id)
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals(0, rs.getInt("reserved_count"))
          assertEquals(0, rs.getInt("active_or_committed"))
        }
      }
    }

    // Deleting a slot that already has historical order references returns 409 SLOT_IN_USE; owner deactivates instead
    val deleteInUse = client.delete("/api/v1/owner/slots/${slot.id}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
    }
    assertEquals(HttpStatusCode.Conflict, deleteInUse.status)

    val patchSlot = client.patch("/api/v1/owner/slots/${slot.id}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateScheduledSlotRequest(maxCapacity = 4, isActive = false)))
    }
    assertEquals(HttpStatusCode.OK, patchSlot.status)
    val patched = json.decodeFromString<ScheduledSlotDto>(patchSlot.bodyAsText())
    assertFalse(patched.isActive)
    assertEquals(4, patched.maxCapacity)
  }

  @Test
  fun `customer address book CRUD promotes default and blocks cross-user IDOR`() = testApplication {
    val fx = setupFixtures()
    val pin = nextUniquePincode()

    // Customer A creates two addresses
    val a1 = json.decodeFromString<AddressDto>(
      client.post("/api/v1/me/addresses") {
        header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            UpsertAddressRequest("Home", "12 MG Road", line2 = "Apt 4B", landmark = "Near Metro", pincode = pin, city = "Bengaluru", isDefault = true),
          ),
        )
      }.bodyAsText(),
    )
    assertTrue(a1.isDefault)
    assertEquals("Apt 4B", a1.line2)
    assertEquals("Near Metro", a1.landmark)

    val a2 = json.decodeFromString<AddressDto>(
      client.post("/api/v1/me/addresses") {
        header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            UpsertAddressRequest("Work", "88 Church St", pincode = pin, city = "Bengaluru", isDefault = false),
          ),
        )
      }.bodyAsText(),
    )
    assertFalse(a2.isDefault)

    // Customer B cannot see A's addresses
    val listB = json.decodeFromString<List<AddressDto>>(
      client.get("/api/v1/me/addresses") {
        header(HttpHeaders.Authorization, "Bearer ${fx.customerBToken}")
      }.bodyAsText(),
    )
    assertTrue(listB.isEmpty())

    // Customer B IDOR PATCH/DELETE on A's address rejected with 404 ADDRESS_NOT_FOUND
    val idorPatch = client.patch("/api/v1/me/addresses/${a1.id}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerBToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpsertAddressRequest("Hijack", "99 Evil St", pincode = pin, city = "Bengaluru")))
    }
    assertEquals(HttpStatusCode.NotFound, idorPatch.status)

    val idorDelete = client.delete("/api/v1/me/addresses/${a1.id}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerBToken}")
    }
    assertEquals(HttpStatusCode.NotFound, idorDelete.status)

    // Customer B IDOR checkout using A's addressId rejected with 403 FORBIDDEN
    val zoneCreate = client.post("/api/v1/owner/delivery-zones") {
      header(HttpHeaders.Authorization, "Bearer ${fx.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateDeliveryZoneRequest(pin, "Central", 0L, 2_000L, 20)))
    }
    assertEquals(HttpStatusCode.Created, zoneCreate.status)

    val item = listOf(CartItemInputDto(productId = fx.productId, variantId = fx.variantId, crustId = fx.crustId, toppingIds = listOf(fx.toppingId), quantity = 1))
    val idorCheckout = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerBToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CheckoutRequest(item, FulfillmentType.DELIVERY, ScheduleType.ASAP, addressId = a1.id, paymentMethod = PaymentMethod.COD)))
    }
    assertEquals(HttpStatusCode.Forbidden, idorCheckout.status)

    // Customer A deletes default address -> remaining address a2 auto-promoted to default
    val delA1 = client.delete("/api/v1/me/addresses/${a1.id}") {
      header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
    }
    assertEquals(HttpStatusCode.NoContent, delA1.status)

    val listA = json.decodeFromString<List<AddressDto>>(
      client.get("/api/v1/me/addresses") {
        header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
      }.bodyAsText(),
    )
    assertEquals(1, listA.size)
    assertEquals(a2.id, listA.first().id)
    assertTrue(listA.first().isDefault)

    // Customer A updates a2 label & landmark
    val patchedA2 = json.decodeFromString<AddressDto>(
      client.patch("/api/v1/me/addresses/${a2.id}") {
        header(HttpHeaders.Authorization, "Bearer ${fx.customerAToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            UpsertAddressRequest("Office HQ", "88 Church St", line2 = "Floor 3", landmark = "Opp Park", pincode = pin, city = "Bengaluru", isDefault = true),
          ),
        )
      }.bodyAsText(),
    )
    assertEquals("Office HQ", patchedA2.label)
    assertEquals("Floor 3", patchedA2.line2)
    assertNotNull(patchedA2.landmark)
  }
}
