package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.auth.WsTicketService
import com.pizzora.backend.authorization.PizzoraPrincipal
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.ForgotPasswordRequest
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.RefreshTokenRequest
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ResetPasswordRequest
import com.pizzora.contracts.StaffMemberDto
import com.pizzora.contracts.UpdateStaffRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyEmailRequest
import com.pizzora.contracts.WsTicketResponse
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase4AuthRbacTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    @BeforeClass
    fun startPostgres() {
      pg = EmbeddedPostgres.builder().start()
      DatabaseFactory.migrateAndConnect(pg.postgresDatabase)
    }

    @JvmStatic
    @AfterClass
    fun stopPostgres() {
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

  private object FailingRedisStore : EphemeralCacheStore {
    override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean = false
    override suspend fun get(key: String): String? = null
    override suspend fun getDel(key: String): String? = null
    override suspend fun del(key: String): Boolean = false
    override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? = null
  }

  @Test
  fun `registration requires email verification and password reset purges active sessions`() = testApplication {
    val emailProvider = InMemoryEmailDeliveryProvider()
    val cacheStore = InMemoryCacheStore()
    val cfg = AppConfig.fromEnv()
    application {
      pizzoraModule(
        config = cfg,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = cacheStore,
        emailDeliveryProvider = emailProvider,
        passwordHasher = fastHasher,
      )
    }

    val email = "customer-${UUID.randomUUID()}@pizzora.test"
    val regRes = client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(email, "PizzaPass123!", "Asha Rao", "9876543210")))
    }
    assertEquals(HttpStatusCode.Created, regRes.status)

    // Unverified login blocked
    val blockedLogin = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(email, "PizzaPass123!")))
    }
    assertEquals(HttpStatusCode.Forbidden, blockedLogin.status)

    // Verify email via single-use token
    val verifyToken = emailProvider.outbox.last { it.toEmail == email && it.type == "VERIFICATION" }.token
    val verifyRes = client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest(verifyToken)))
    }
    assertEquals(HttpStatusCode.OK, verifyRes.status)

    // Replay verification token rejected
    val replayVerify = client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest(verifyToken)))
    }
    assertEquals(HttpStatusCode.BadRequest, replayVerify.status)

    // Login succeeds
    val loginRes = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(email, "PizzaPass123!")))
    }
    assertEquals(HttpStatusCode.OK, loginRes.status)
    val tokens = json.decodeFromString<AuthTokensResponse>(loginRes.bodyAsText())
    assertEquals(UserRole.CUSTOMER, tokens.user.role)

    // Active access token works
    val meRes = client.get("/api/v1/me") {
      header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
    }
    assertEquals(HttpStatusCode.OK, meRes.status)

    // Forgot & reset password revokes all active sessions + refresh tokens
    val forgotRes = client.post("/api/v1/auth/forgot-password") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ForgotPasswordRequest(email)))
    }
    assertEquals(HttpStatusCode.Accepted, forgotRes.status)
    val resetToken = emailProvider.outbox.last { it.toEmail == email && it.type == "PASSWORD_RESET" }.token

    val resetRes = client.post("/api/v1/auth/reset-password") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ResetPasswordRequest(resetToken, "NewPizzaPass456!")))
    }
    assertEquals(HttpStatusCode.OK, resetRes.status)

    val oldAccessCheck = client.get("/api/v1/me") {
      header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
    }
    assertEquals(HttpStatusCode.Unauthorized, oldAccessCheck.status)

    val oldRefreshCheck = client.post("/api/v1/auth/refresh") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RefreshTokenRequest(tokens.refreshToken)))
    }
    assertEquals(HttpStatusCode.Unauthorized, oldRefreshCheck.status)

    // Login with new password succeeds
    val newLoginRes = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(email, "NewPizzaPass456!")))
    }
    assertEquals(HttpStatusCode.OK, newLoginRes.status)
  }

  @Test
  fun `refresh token rotation detects reuse and revokes entire family`() = testApplication {
    val emailProvider = InMemoryEmailDeliveryProvider()
    val cfg = AppConfig.fromEnv()
    application {
      pizzoraModule(
        config = cfg,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = InMemoryCacheStore(),
        emailDeliveryProvider = emailProvider,
        passwordHasher = fastHasher,
      )
    }

    val email = "rotate-${UUID.randomUUID()}@pizzora.test"
    client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(email, "RotatePass123!", "Vikram", "9876501234")))
    }
    val verifyToken = emailProvider.outbox.last { it.toEmail == email }.token
    client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest(verifyToken)))
    }
    val t1 = json.decodeFromString<AuthTokensResponse>(
      client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(email, "RotatePass123!")))
      }.bodyAsText(),
    )

    // Valid rotation t1 -> t2
    val refreshRes = client.post("/api/v1/auth/refresh") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RefreshTokenRequest(t1.refreshToken)))
    }
    assertEquals(HttpStatusCode.OK, refreshRes.status)
    val t2 = json.decodeFromString<AuthTokensResponse>(refreshRes.bodyAsText())

    // Replay old t1 -> detects reuse & revokes entire family including t2!
    val replayRes = client.post("/api/v1/auth/refresh") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RefreshTokenRequest(t1.refreshToken)))
    }
    assertEquals(HttpStatusCode.Unauthorized, replayRes.status)
    assertTrue(replayRes.bodyAsText().contains("REFRESH_TOKEN_REUSED"))

    val compromisedFamilyRes = client.post("/api/v1/auth/refresh") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RefreshTokenRequest(t2.refreshToken)))
    }
    assertEquals(HttpStatusCode.Unauthorized, compromisedFamilyRes.status)
  }

  @Test
  fun `owner bootstrap, 3-role RBAC matrix, staff deactivation purge, IDOR address protection, and PG ws-ticket fallback`() = testApplication {
    val emailProvider = InMemoryEmailDeliveryProvider()
    val cfg = AppConfig.fromEnv()
    val ownerEmail = "owner-${UUID.randomUUID()}@pizzora.test"
    runBlocking {
      BootstrapOwner.bootstrap(
        dataSource = pg.postgresDatabase,
        passwordHasher = fastHasher,
        email = ownerEmail,
        password = "OwnerPassword123!",
        fullName = "Chef Owner",
        phone = "9988776655",
        employeeCode = "OWN-${UUID.randomUUID().toString().take(6)}",
      )
    }

    application {
      pizzoraModule(
        config = cfg,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        // Simulate Redis OUTAGE so ws-ticket uses PostgreSQL ws_tickets fallback and RBAC uses PG fallback
        cacheStore = FailingRedisStore,
        emailDeliveryProvider = emailProvider,
        passwordHasher = fastHasher,
      )
    }

    // Owner login
    val ownerTokens = json.decodeFromString<AuthTokensResponse>(
      client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(ownerEmail, "OwnerPassword123!")))
      }.bodyAsText(),
    )
    assertEquals(UserRole.OWNER, ownerTokens.user.role)

    // Owner creates KITCHEN_STAFF
    val kitchenEmail = "kitchen-${UUID.randomUUID()}@pizzora.test"
    val createStaffRes = client.post("/api/v1/owner/staff") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateStaffRequest(
            email = kitchenEmail,
            password = "KitchenPass123!",
            fullName = "Pizzaiolo Sam",
            phone = "9811122233",
            role = UserRole.KITCHEN_STAFF,
            employeeCode = "KIT-${UUID.randomUUID().toString().take(6)}",
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, createStaffRes.status)
    val staffMember = json.decodeFromString<StaffMemberDto>(createStaffRes.bodyAsText())

    val kitchenTokens = json.decodeFromString<AuthTokensResponse>(
      client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(kitchenEmail, "KitchenPass123!")))
      }.bodyAsText(),
    )
    assertEquals(UserRole.KITCHEN_STAFF, kitchenTokens.user.role)

    // KITCHEN_STAFF denied owner:staff_manage (403)
    val kitchenDenied = client.get("/api/v1/owner/staff") {
      header(HttpHeaders.Authorization, "Bearer ${kitchenTokens.accessToken}")
    }
    assertEquals(HttpStatusCode.Forbidden, kitchenDenied.status)

    // Register & verify 2 customers for RBAC + IDOR tests
    suspend fun verifiedCustomer(prefix: String): AuthTokensResponse {
      val email = "$prefix-${UUID.randomUUID()}@pizzora.test"
      client.post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RegisterRequest(email, "CustomerPass123!", "Cust $prefix", "9800000001")))
      }
      val tok = emailProvider.outbox.last { it.toEmail == email }.token
      client.post("/api/v1/auth/verify-email") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(VerifyEmailRequest(tok)))
      }
      return json.decodeFromString(
        client.post("/api/v1/auth/login") {
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(LoginRequest(email, "CustomerPass123!")))
        }.bodyAsText(),
      )
    }

    val custA = verifiedCustomer("a")
    val custB = verifiedCustomer("b")

    // CUSTOMER denied /owner/staff (403)
    assertEquals(
      HttpStatusCode.Forbidden,
      client.get("/api/v1/owner/staff") {
        header(HttpHeaders.Authorization, "Bearer ${custA.accessToken}")
      }.status,
    )

    // IDOR address protection: Customer B cannot patch/delete Customer A's address
    val addrRes = client.post("/api/v1/me/addresses") {
      header(HttpHeaders.Authorization, "Bearer ${custA.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          UpsertAddressRequest("Home", "12 Wood Oven Lane", null, "Near Park", "560001", "Bengaluru", true),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, addrRes.status)
    val addr = json.decodeFromString<AddressDto>(addrRes.bodyAsText())

    val idorPatch = client.patch("/api/v1/me/addresses/${addr.id}") {
      header(HttpHeaders.Authorization, "Bearer ${custB.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpsertAddressRequest("Hijack", "99 Other St", null, null, "560001", "Bengaluru", true)))
    }
    assertEquals(HttpStatusCode.NotFound, idorPatch.status)

    val idorDelete = client.delete("/api/v1/me/addresses/${addr.id}") {
      header(HttpHeaders.Authorization, "Bearer ${custB.accessToken}")
    }
    assertEquals(HttpStatusCode.NotFound, idorDelete.status)

    // Single-use ws-ticket under Redis OUTAGE (PostgreSQL ws_tickets fallback)
    val wsTicketRes = client.post("/api/v1/auth/ws-ticket") {
      header(HttpHeaders.Authorization, "Bearer ${custA.accessToken}")
    }
    assertEquals(HttpStatusCode.OK, wsTicketRes.status)
    val ticketBody = json.decodeFromString<WsTicketResponse>(wsTicketRes.bodyAsText())

    val wsService = WsTicketService(pg.postgresDatabase, FailingRedisStore, cfg.auth)
    val firstConsume: PizzoraPrincipal? = wsService.consumeTicket(ticketBody.ticket)
    assertNotNull(firstConsume)
    assertEquals(custA.user.id, firstConsume!!.userId.toString())
    // Replay rejected!
    val secondConsume: PizzoraPrincipal? = wsService.consumeTicket(ticketBody.ticket)
    assertNull(secondConsume)

    // Deactivating staff immediately purges their session & tokens
    val deactivateRes = client.patch("/api/v1/owner/staff/${staffMember.userId}") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateStaffRequest(isActive = false)))
    }
    assertEquals(HttpStatusCode.OK, deactivateRes.status)

    val deactivatedCheck = client.get("/api/v1/me") {
      header(HttpHeaders.Authorization, "Bearer ${kitchenTokens.accessToken}")
    }
    assertEquals(HttpStatusCode.Unauthorized, deactivatedCheck.status)
  }
}
