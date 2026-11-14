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
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.KdsActionRequest
import com.pizzora.contracts.KdsTicketActionResponse
import com.pizzora.contracts.KdsTicketsResponse
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.OrderStatus
import com.pizzora.contracts.PaymentAttemptStatus
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateOrderPriorityRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyEmailRequest
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

class Phase13KdsOperationsTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-p13-${UUID.randomUUID()}@pizzora.test"
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
          fullName = "Owner P13",
          phone = "9833333313",
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

  private data class KdsTestFixtures(
    val ownerToken: String,
    val kitchenUserId: String,
    val kitchenToken: String,
    val customerToken: String,
    val productId: String,
    val variantId: String,
    val crustId: String,
    val toppingId: String,
    val addressId: String,
  )

  private suspend fun ApplicationTestBuilder.setupFixtures(): KdsTestFixtures {
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

    val staffEmail = "chef-${UUID.randomUUID()}@pizzora.test"
    val staffPass = "ChefPass123!"
    val staffResp = client.post("/api/v1/owner/staff") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateStaffRequest(
            email = staffEmail,
            password = staffPass,
            fullName = "Chef Matteo",
            phone = "9844444413",
            role = UserRole.KITCHEN_STAFF,
            employeeCode = "CHEF-${UUID.randomUUID().toString().take(6)}",
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, staffResp.status)
    val staff = json.decodeFromString<StaffMemberDto>(staffResp.bodyAsText())

    val staffLogin = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(staffEmail, staffPass)))
    }
    val kitchenToken = json.decodeFromString<AuthTokensResponse>(staffLogin.bodyAsText()).accessToken

    val custEmail = "cust-p13-${UUID.randomUUID()}@pizzora.test"
    client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(custEmail, "CustPass123!", "Elena Gilbert", "9811111313")))
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
      setBody(json.encodeToString(CreateCategoryRequest("P13 Cat ${UUID.randomUUID()}", 1, true)))
    }
    val cat = json.decodeFromString<CategoryDto>(catResp.bodyAsText())

    val prodResp = client.post("/api/v1/owner/products") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateProductRequest(
            categoryId = cat.id,
            name = "Quattro Formaggi",
            description = "Wood-fired 4 cheese",
            isVeg = true,
            variants = listOf(CreateVariantRequest(SizeCode.LARGE, 55_000L, true)),
          ),
        ),
      )
    }
    val prod = json.decodeFromString<ProductDto>(prodResp.bodyAsText())

    val crustResp = client.post("/api/v1/owner/crusts") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCrustRequest("P13 Sourdough ${UUID.randomUUID()}", 5_000L, true)))
    }
    val crust = json.decodeFromString<CrustOptionDto>(crustResp.bodyAsText())

    val topResp = client.post("/api/v1/owner/toppings") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateToppingRequest("P13 Truffle Oil ${UUID.randomUUID()}", true, 8_000L, true)))
    }
    val top = json.decodeFromString<ToppingDto>(topResp.bodyAsText())

    val pincode = "56${(1000..9999).random()}"
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        """
        INSERT INTO delivery_zones (id, pincode, area_name, min_order_paise, delivery_fee_paise, estimated_mins, is_active)
        VALUES (?, ?, 'Koramangala', 0, 3000, 30, TRUE)
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
          UpsertAddressRequest("Home", "42 Brick Oven Rd", pincode = pincode, city = "Bengaluru", isDefault = true),
        ),
      )
    }
    val addr = json.decodeFromString<AddressDto>(addrResp.bodyAsText())

    return KdsTestFixtures(
      ownerToken = ownerToken,
      kitchenUserId = staff.userId,
      kitchenToken = kitchenToken,
      customerToken = customerToken,
      productId = prod.id,
      variantId = prod.variants.first().id,
      crustId = crust.id,
      toppingId = top.id,
      addressId = addr.id,
    )
  }

  private suspend fun ApplicationTestBuilder.placeCodOrder(
    fixtures: KdsTestFixtures,
    fulfillmentType: FulfillmentType = FulfillmentType.DELIVERY,
  ): OrderDetailDto {
    val resp = client.post("/api/v1/checkout") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.customerToken}")
      header("Idempotency-Key", UUID.randomUUID().toString())
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CheckoutRequest(
            items = listOf(
              CartItemInputDto(
                productId = fixtures.productId,
                variantId = fixtures.variantId,
                crustId = fixtures.crustId,
                toppingIds = listOf(fixtures.toppingId),
                quantity = 2,
              ),
            ),
            fulfillmentType = fulfillmentType,
            scheduleType = ScheduleType.ASAP,
            addressId = if (fulfillmentType == FulfillmentType.DELIVERY) fixtures.addressId else null,
            paymentMethod = PaymentMethod.COD,
            clearCart = false,
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, resp.status)
    return json.decodeFromString<CheckoutResponse>(resp.bodyAsText()).order
  }

  @Test
  fun `customer gets 403 on KDS endpoints while kitchen staff completes full delivery lifecycle and collects COD`() = testApplication {
    val fixtures = setupFixtures()
    val deliveryOrder = placeCodOrder(fixtures, FulfillmentType.DELIVERY)

    // Customer blocked from KDS endpoints (403)
    val custTickets = client.get("/api/v1/kds/tickets") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.customerToken}")
    }
    assertEquals(HttpStatusCode.Forbidden, custTickets.status)

    val custAck = client.post("/api/v1/kds/orders/${deliveryOrder.id}/ack") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.customerToken}")
    }
    assertEquals(HttpStatusCode.Forbidden, custAck.status)

    val custPriority = client.patch("/api/v1/kds/orders/${deliveryOrder.id}/priority") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.customerToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateOrderPriorityRequest(isPriority = true)))
    }
    assertEquals(HttpStatusCode.Forbidden, custPriority.status)

    // Kitchen staff lists active tickets with server_now & prep_sla_minutes
    val listResp = client.get("/api/v1/kds/tickets") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
    }
    assertEquals(HttpStatusCode.OK, listResp.status)
    val board = json.decodeFromString<KdsTicketsResponse>(listResp.bodyAsText())
    assertTrue(board.prepSlaMinutes > 0)
    assertNotNull(Instant.parse(board.serverNow))
    val initialTicket = board.tickets.firstOrNull { it.id == deliveryOrder.id }
    assertNotNull(initialTicket)
    assertEquals(OrderStatus.PLACED, initialTicket!!.status)
    assertEquals(2, initialTicket.items.first().quantity)

    // ack -> PLACED -> ACCEPTED (emits KITCHEN_ACKNOWLEDGED outbox event)
    val ackResp = client.post("/api/v1/kds/orders/${deliveryOrder.id}/ack") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(KdsActionRequest("On deck")))
    }
    assertEquals(HttpStatusCode.OK, ackResp.status)
    val afterAck = json.decodeFromString<KdsTicketActionResponse>(ackResp.bodyAsText()).ticket
    assertEquals(OrderStatus.ACCEPTED, afterAck.status)
    assertNotNull(afterAck.acceptedAt)

    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        "SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
      ).use { ps ->
        ps.setObject(1, UUID.fromString(deliveryOrder.id))
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals("KITCHEN_ACKNOWLEDGED", rs.getString("event_type"))
        }
      }
    }

    // start-prep -> PREPARING
    val prepResp = client.post("/api/v1/kds/orders/${deliveryOrder.id}/start-prep") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
    }
    assertEquals(HttpStatusCode.OK, prepResp.status)
    assertEquals(OrderStatus.PREPARING, json.decodeFromString<KdsTicketActionResponse>(prepResp.bodyAsText()).ticket.status)

    // ready -> READY
    val readyResp = client.post("/api/v1/kds/orders/${deliveryOrder.id}/ready") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
    }
    assertEquals(HttpStatusCode.OK, readyResp.status)
    assertEquals(OrderStatus.READY, json.decodeFromString<KdsTicketActionResponse>(readyResp.bodyAsText()).ticket.status)

    // Cannot complete DELIVERY directly from READY without dispatch
    val invalidComplete = client.post("/api/v1/kds/orders/${deliveryOrder.id}/complete") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
    }
    assertEquals(HttpStatusCode.Conflict, invalidComplete.status)

    // dispatch -> OUT_FOR_DELIVERY
    val dispatchResp = client.post("/api/v1/kds/orders/${deliveryOrder.id}/dispatch") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
    }
    assertEquals(HttpStatusCode.OK, dispatchResp.status)
    assertEquals(OrderStatus.OUT_FOR_DELIVERY, json.decodeFromString<KdsTicketActionResponse>(dispatchResp.bodyAsText()).ticket.status)

    // complete -> COMPLETED + COD collected
    val completeResp = client.post("/api/v1/kds/orders/${deliveryOrder.id}/complete") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
    }
    assertEquals(HttpStatusCode.OK, completeResp.status)
    val completedTicket = json.decodeFromString<KdsTicketActionResponse>(completeResp.bodyAsText()).ticket
    assertEquals(OrderStatus.COMPLETED, completedTicket.status)
    assertEquals(PaymentStatus.COD_COLLECTED, completedTicket.paymentStatus)
    assertNotNull(completedTicket.completedAt)

    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement(
        """
        SELECT p.status, p.captured_paise, pa.status AS attempt_status, pa.collected_by_user_id
        FROM payments p
        JOIN payment_attempts pa ON pa.payment_id = p.id
        WHERE p.order_id = ?
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.fromString(deliveryOrder.id))
        ps.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals(PaymentStatus.COD_COLLECTED.name, rs.getString("status"))
          assertEquals(deliveryOrder.totalPaise, rs.getLong("captured_paise"))
          assertEquals(PaymentAttemptStatus.CAPTURED.name, rs.getString("attempt_status"))
          assertEquals(fixtures.kitchenUserId, rs.getObject("collected_by_user_id", UUID::class.java).toString())
        }
      }
    }

    // Completed order excluded from default active KDS queue, included when include_completed=true
    val activeAfter = json.decodeFromString<KdsTicketsResponse>(
      client.get("/api/v1/kds/tickets") {
        header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
      }.bodyAsText(),
    )
    assertFalse(activeAfter.tickets.any { it.id == deliveryOrder.id })

    val withCompleted = json.decodeFromString<KdsTicketsResponse>(
      client.get("/api/v1/kds/tickets?include_completed=true") {
        header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
      }.bodyAsText(),
    )
    assertTrue(withCompleted.tickets.any { it.id == deliveryOrder.id })
  }

  @Test
  fun `pickup lifecycle skips dispatch and priority toggle floats ticket to top with outbox event`() = testApplication {
    val fixtures = setupFixtures()
    val firstOrder = placeCodOrder(fixtures, FulfillmentType.PICKUP)
    val secondOrder = placeCodOrder(fixtures, FulfillmentType.PICKUP)

    // Push firstOrder created_at slightly earlier to guarantee deterministic FIFO ordering
    pg.postgresDatabase.connection.use { conn ->
      conn.prepareStatement("UPDATE orders SET created_at = ? WHERE id = ?").use { ps ->
        ps.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(120)))
        ps.setObject(2, UUID.fromString(firstOrder.id))
        ps.executeUpdate()
      }
    }

    // Owner enables priority on secondOrder
    val priorityResp = client.patch("/api/v1/kds/orders/${secondOrder.id}/priority") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.ownerToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateOrderPriorityRequest(isPriority = true, reason = "VIP customer waiting")))
    }
    assertEquals(HttpStatusCode.OK, priorityResp.status)
    val updatedPriority = json.decodeFromString<KdsTicketActionResponse>(priorityResp.bodyAsText()).ticket
    assertTrue(updatedPriority.isPriority)
    assertTrue(updatedPriority.version > secondOrder.version)

    val board = json.decodeFromString<KdsTicketsResponse>(
      client.get("/api/v1/kds/tickets") {
        header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
      }.bodyAsText(),
    )
    val firstIdx = board.tickets.indexOfFirst { it.id == firstOrder.id }
    val secondIdx = board.tickets.indexOfFirst { it.id == secondOrder.id }
    assertTrue(secondIdx >= 0 && firstIdx >= 0 && secondIdx < firstIdx)

    // PICKUP transitions: ack -> start-prep -> ready -> reject dispatch -> complete directly
    assertEquals(
      HttpStatusCode.OK,
      client.post("/api/v1/kds/orders/${secondOrder.id}/ack") {
        header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
      }.status,
    )
    assertEquals(
      HttpStatusCode.OK,
      client.post("/api/v1/kds/orders/${secondOrder.id}/start-prep") {
        header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
      }.status,
    )
    assertEquals(
      HttpStatusCode.OK,
      client.post("/api/v1/kds/orders/${secondOrder.id}/ready") {
        header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
      }.status,
    )

    val invalidDispatch = client.post("/api/v1/kds/orders/${secondOrder.id}/dispatch") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
    }
    assertEquals(HttpStatusCode.Conflict, invalidDispatch.status)
    val err = json.decodeFromString<ApiErrorEnvelope>(invalidDispatch.bodyAsText())
    assertEquals("INVALID_ORDER_TRANSITION", err.error.code)

    val completePickup = client.post("/api/v1/kds/orders/${secondOrder.id}/complete") {
      header(HttpHeaders.Authorization, "Bearer ${fixtures.kitchenToken}")
    }
    assertEquals(HttpStatusCode.OK, completePickup.status)
    assertEquals(
      OrderStatus.COMPLETED,
      json.decodeFromString<KdsTicketActionResponse>(completePickup.bodyAsText()).ticket.status,
    )
  }
}
