package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.config.AuthConfig
import com.pizzora.backend.core.config.RazorpayConfig
import com.pizzora.backend.core.config.SecurityConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.error.ValidationException
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.JwtTokenProvider
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.core.security.StorageKeyValidator
import com.pizzora.backend.coupons.CouponService
import com.pizzora.backend.images.InspectedObject
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.notifications.NotificationService
import com.pizzora.backend.orders.OrderService
import com.pizzora.backend.razorpay.GatewayOrderResult
import com.pizzora.backend.razorpay.GatewayPaymentResult
import com.pizzora.backend.razorpay.GatewayRefundResult
import com.pizzora.backend.razorpay.RazorpayGateway
import com.pizzora.backend.razorpay.RazorpaySignatureVerifier
import com.pizzora.backend.worker.OutboxDispatcherWorker
import com.pizzora.contracts.AddressDto
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuditLogsResponse
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CheckoutRequest
import com.pizzora.contracts.CheckoutResponse
import com.pizzora.contracts.ConfirmProductImageRequest
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateDeliveryZoneRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.FailPaymentAttemptRequest
import com.pizzora.contracts.ForgotPasswordRequest
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.NotificationDeliveryStatus
import com.pizzora.contracts.NotificationInboxResponse
import com.pizzora.contracts.OrderDetailDto
import com.pizzora.contracts.PaymentMethod
import com.pizzora.contracts.PaymentStatus
import com.pizzora.contracts.PresignProductImageRequest
import com.pizzora.contracts.PresignProductImageResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.RefreshTokenRequest
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ResetPasswordRequest
import com.pizzora.contracts.ScheduleType
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.UpdateStaffRequest
import com.pizzora.contracts.UpsertAddressRequest
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyEmailRequest
import com.pizzora.contracts.VerifyRazorpayPaymentRequest
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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase18SecurityHardeningTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-p18-${UUID.randomUUID()}@pizzora.test"
    private val ownerPass = "OwnerPass123!"

    private val testKeyId = "rzp_test_pizzora_18"
    private val testKeySecret = "rzp_secret_pizzora_super_secret_18"
    private val testWebhookSecret = "rzp_webhook_pizzora_secret_18"

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
          fullName = "Owner P18",
          phone = "9899990018",
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

  private class StubStorageClient : ObjectStorageClient {
    val objects = ConcurrentHashMap<String, InspectedObject>()
    val deletedKeys = CopyOnWriteArrayList<String>()

    override fun presignPut(objectKey: String, contentType: String, expiresInSeconds: Long): String {
      StorageKeyValidator.validateObjectKey(objectKey)
      return "https://storage.pizzora.test/pizzora/$objectKey"
    }

    override fun publicUrlFor(objectKey: String): String {
      StorageKeyValidator.validateObjectKey(objectKey)
      return "https://storage.pizzora.test/pizzora/$objectKey"
    }

    override suspend fun inspectObject(objectKey: String): InspectedObject? {
      StorageKeyValidator.validateObjectKey(objectKey)
      return objects[objectKey]
    }

    override suspend fun deleteObject(objectKey: String): Boolean {
      StorageKeyValidator.validateObjectKey(objectKey)
      deletedKeys += objectKey
      objects.remove(objectKey)
      return true
    }
  }

  private class StubRazorpayGateway(
    override val publicKeyId: String = testKeyId,
  ) : RazorpayGateway {
    private val seq = AtomicInteger(1)
    override suspend fun createOrder(
      amountPaise: Long,
      currency: String,
      receipt: String,
      notes: Map<String, String>,
    ): GatewayOrderResult = GatewayOrderResult(
      gatewayOrderId = "order_gw18_${seq.getAndIncrement()}",
      receipt = receipt,
      amountPaise = amountPaise,
      currency = currency,
      status = "created",
    )

    override suspend fun createRefund(
      gatewayPaymentId: String,
      amountPaise: Long,
      idempotencyKey: String,
      notes: Map<String, String>,
    ): GatewayRefundResult = GatewayRefundResult(
      gatewayRefundId = "rfnd_gw18_${seq.getAndIncrement()}",
      gatewayPaymentId = gatewayPaymentId,
      amountPaise = amountPaise,
      status = "processed",
      notes = notes,
    )

    override suspend fun fetchPaymentsForOrder(gatewayOrderId: String): List<GatewayPaymentResult> = emptyList()
    override suspend fun fetchRefundsForPayment(gatewayPaymentId: String): List<GatewayRefundResult> = emptyList()
  }

  private fun baseConfig(
    environment: String = "development",
    security: SecurityConfig = SecurityConfig(),
    authOverride: AuthConfig? = null,
  ): AppConfig {
    val base = AppConfig.fromEnv()
    return base.copy(
      environment = environment,
      auth = authOverride ?: base.auth.copy(jwtSecret = "pizzora-phase18-test-hmac-secret-32-bytes-min"),
      razorpay = RazorpayConfig(
        keyId = testKeyId,
        keySecret = testKeySecret,
        webhookSecret = testWebhookSecret,
      ),
      security = security,
    )
  }

  private suspend fun ApplicationTestBuilder.registerAndLoginCustomer(
    emailProvider: InMemoryEmailDeliveryProvider,
    prefix: String,
  ): AuthTokensResponse {
    val email = "$prefix-${UUID.randomUUID()}@pizzora.test"
    val pass = "PizzaSec123!"
    val reg = client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(email, pass, "User $prefix", "9876543210")))
    }
    assertEquals(HttpStatusCode.Created, reg.status)
    val token = emailProvider.outbox.last { it.toEmail == email }.token
    val verify = client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest(token)))
    }
    assertEquals(HttpStatusCode.OK, verify.status)
    val login = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(email, pass, "PixelTest")))
    }
    assertEquals(HttpStatusCode.OK, login.status)
    return json.decodeFromString<AuthTokensResponse>(login.bodyAsText())
  }

  private suspend fun ApplicationTestBuilder.loginOwner(): String {
    val login = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass, "OwnerDevice")))
    }
    assertEquals(HttpStatusCode.OK, login.status)
    return json.decodeFromString<AuthTokensResponse>(login.bodyAsText()).accessToken
  }

  @Test
  fun `production environment guard rejects default or weak secrets and allows hardened config`() {
    val validProdEnv = mapOf(
      "PIZZORA_ENV" to "production",
      "JWT_SECRET" to "prod-strong-hmac-secret-with-more-than-32-chars",
      "DATABASE_PASSWORD" to "prod-db-secret-9981",
    )
    val validConfig = AppConfig.fromEnv(validProdEnv)
    assertEquals("production", validConfig.environment)
    assertFalse(validConfig.storage.enabled)

    val validProdS3Env = validProdEnv + mapOf(
      "S3_ENABLED" to "true",
      "S3_ENDPOINT" to "https://s3.ap-south-1.amazonaws.com",
      "S3_BUCKET" to "pizzora-prod-media",
      "S3_REGION" to "ap-south-1",
      "S3_ACCESS_KEY" to "AKIA_PROD_ACCESS_KEY",
      "S3_SECRET_KEY" to "prod-s3-secret-7741",
    )
    val validS3Config = AppConfig.fromEnv(validProdS3Env)
    assertTrue(validS3Config.storage.enabled)

    val checks = listOf(
      validProdEnv - "JWT_SECRET",
      validProdEnv + ("JWT_SECRET" to "too-short"),
      validProdEnv + ("JWT_SECRET" to "pizzora-dev-only-hmac-secret-minimum-32-bytes"),
      validProdEnv + ("DATABASE_PASSWORD" to "pizzora_dev_password"),
      validProdS3Env + ("S3_SECRET_KEY" to "pizzora_minio_dev_secret"),
      validProdS3Env + ("S3_ACCESS_KEY" to "pizzora_minio_admin"),
      validProdS3Env - "S3_SECRET_KEY",
    )
    for (badEnv in checks) {
      val failed = runCatching { AppConfig.fromEnv(badEnv) }.isFailure
      assertTrue("Expected production guard to fail for $badEnv", failed)
    }
  }

  @Test
  fun `security headers include hardening directives and HSTS strictly in production`() {
    testApplication {
      application {
        pizzoraModule(
          config = baseConfig(environment = "development"),
          probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
          dataSource = pg.postgresDatabase,
          cacheStore = InMemoryCacheStore(),
          passwordHasher = fastHasher,
        )
      }
      val devRes = client.get("/api/v1/health/live")
      assertEquals(HttpStatusCode.OK, devRes.status)
      assertEquals("nosniff", devRes.headers["X-Content-Type-Options"])
      assertEquals("DENY", devRes.headers["X-Frame-Options"])
      assertEquals("no-referrer", devRes.headers["Referrer-Policy"])
      assertEquals("default-src 'none'; frame-ancestors 'none'", devRes.headers["Content-Security-Policy"])
      assertEquals("geolocation=(), microphone=(), camera=()", devRes.headers["Permissions-Policy"])
      assertEquals("same-origin", devRes.headers["Cross-Origin-Resource-Policy"])
      assertTrue(devRes.headers[HttpHeaders.CacheControl]?.contains("no-store") == true)
      assertNull(devRes.headers["Strict-Transport-Security"])
    }

    testApplication {
      application {
        pizzoraModule(
          config = baseConfig(environment = "production"),
          probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
          dataSource = pg.postgresDatabase,
          cacheStore = InMemoryCacheStore(),
          passwordHasher = fastHasher,
        )
      }
      val prodRes = client.get("/api/v1/config/public")
      assertEquals(HttpStatusCode.OK, prodRes.status)
      assertEquals("nosniff", prodRes.headers["X-Content-Type-Options"])
      assertEquals("max-age=31536000; includeSubDomains", prodRes.headers["Strict-Transport-Security"])
    }
  }

  @Test
  fun `body size limits reject oversized Content-Length and chunked payloads with 413`() = testApplication {
    val emailProvider = InMemoryEmailDeliveryProvider()
    val cfg = baseConfig(
      security = SecurityConfig(
        maxJsonBodyBytes = 256L,
        maxWebhookBodyBytes = 1024L,
        rateLimitEnabled = false,
      ),
    )
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

    // 1. Standard JSON request exceeding limit (with Content-Length)
    val hugePayload = "{\"email\":\"a@b.test\",\"password\":\"${"x".repeat(400)}\"}"
    val overJson = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(hugePayload)
    }
    assertEquals(HttpStatusCode.PayloadTooLarge, overJson.status)
    assertEquals("PAYLOAD_TOO_LARGE", json.decodeFromString<ApiErrorEnvelope>(overJson.bodyAsText()).error.code)

    // 2. Chunked streaming JSON payload without Content-Length exceeding limit
    val streamedOver = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(
        object : OutgoingContent.WriteChannelContent() {
          override val contentType: ContentType = ContentType.Application.Json
          override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully(hugePayload.toByteArray(StandardCharsets.UTF_8))
          }
        },
      )
    }
    assertEquals(HttpStatusCode.PayloadTooLarge, streamedOver.status)

    // 3. Optional-body authenticated endpoint still surfaces 413 rather than swallowing payload error
    val cust = registerAndLoginCustomer(emailProvider, "sizecust")
    val optionalRouteOver = client.post("/api/v1/orders/${UUID.randomUUID()}/cancel") {
      header(HttpHeaders.Authorization, "Bearer ${cust.accessToken}")
      contentType(ContentType.Application.Json)
      setBody("{\"reason\":\"${"r".repeat(400)}\"}")
    }
    assertEquals(HttpStatusCode.PayloadTooLarge, optionalRouteOver.status)

    // 4. Webhook endpoint allows payload above JSON cap (500B <= 1024B) but blocks above 1024B
    val underWebhook = client.post("/api/v1/webhooks/razorpay") {
      contentType(ContentType.Application.Json)
      header("X-Razorpay-Signature", "invalid")
      setBody("{\"pad\":\"${"w".repeat(500)}\"}")
    }
    assertEquals(HttpStatusCode.Unauthorized, underWebhook.status)

    val overWebhook = client.post("/api/v1/webhooks/razorpay") {
      contentType(ContentType.Application.Json)
      header("X-Razorpay-Signature", "invalid")
      setBody("{\"pad\":\"${"w".repeat(1200)}\"}")
    }
    assertEquals(HttpStatusCode.PayloadTooLarge, overWebhook.status)

    val streamedOverWebhook = client.post("/api/v1/webhooks/razorpay") {
      contentType(ContentType.Application.Json)
      header("X-Razorpay-Signature", "invalid")
      setBody(
        object : OutgoingContent.WriteChannelContent() {
          override val contentType: ContentType = ContentType.Application.Json
          override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully("{\"pad\":\"${"w".repeat(1200)}\"}".toByteArray(StandardCharsets.UTF_8))
          }
        },
      )
    }
    assertEquals(HttpStatusCode.PayloadTooLarge, streamedOverWebhook.status)
  }

  @Test
  fun `brute-force rate limiting returns 429 with Retry-After while health probes remain unthrottled`() = testApplication {
    val cfg = baseConfig(
      security = SecurityConfig(
        rateLimitEnabled = true,
        authVerifyPerMinute = 2,
        authResetPerMinute = 2,
        publicPerMinute = 3,
      ),
    )
    application {
      pizzoraModule(
        config = cfg,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = InMemoryCacheStore(),
        passwordHasher = fastHasher,
      )
    }

    // auth_verify group (limit 2/min)
    repeat(2) {
      val res = client.post("/api/v1/auth/verify-email") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(VerifyEmailRequest("bad-token")))
      }
      assertEquals(HttpStatusCode.BadRequest, res.status)
    }
    val verifyBlocked = client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest("bad-token")))
    }
    assertEquals(HttpStatusCode.TooManyRequests, verifyBlocked.status)
    assertEquals("60", verifyBlocked.headers[HttpHeaders.RetryAfter])
    assertEquals("RATE_LIMITED", json.decodeFromString<ApiErrorEnvelope>(verifyBlocked.bodyAsText()).error.code)

    // auth_reset group (limit 2/min)
    repeat(2) {
      val res = client.post("/api/v1/auth/reset-password") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(ResetPasswordRequest("bad-reset-token", "NewPass123!")))
      }
      assertEquals(HttpStatusCode.BadRequest, res.status)
    }
    val resetBlocked = client.post("/api/v1/auth/reset-password") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ResetPasswordRequest("bad-reset-token", "NewPass123!")))
    }
    assertEquals(HttpStatusCode.TooManyRequests, resetBlocked.status)

    // auth/login route-level limiter (20/min)
    repeat(20) {
      val res = client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest("nonexistent-${it}@pizzora.test", "WrongPass123!")))
      }
      assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
    val loginBlocked = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest("nonexistent-21@pizzora.test", "WrongPass123!")))
    }
    assertEquals(HttpStatusCode.TooManyRequests, loginBlocked.status)
    assertEquals("60", loginBlocked.headers[HttpHeaders.RetryAfter])

    // Public endpoints throttled cleanly after 3 requests, health probe unthrottled
    repeat(3) {
      assertEquals(HttpStatusCode.OK, client.get("/api/v1/config/public").status)
    }
    assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/v1/config/public").status)
    assertEquals(HttpStatusCode.OK, client.get("/api/v1/health/live").status)
  }

  @Test
  fun `JWT tampering, alg-none, expired tokens, refresh reuse, reset replay, and session revocation are blocked`() = testApplication {
    val emailProvider = InMemoryEmailDeliveryProvider()
    val cfg = baseConfig(security = SecurityConfig(rateLimitEnabled = false))
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

    val customer = registerAndLoginCustomer(emailProvider, "jwtcust")
    val ownerToken = loginOwner()

    // 1. Tampered JWT payload (changing role to OWNER on valid signature)
    val parts = customer.accessToken.split('.')
    assertEquals(3, parts.size)
    val decodedPayload = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
    val tamperedPayloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
      decodedPayload.replace("\"CUSTOMER\"", "\"OWNER\"").toByteArray(StandardCharsets.UTF_8),
    )
    val tamperedJwt = "${parts[0]}.$tamperedPayloadB64.${parts[2]}"
    assertEquals(
      HttpStatusCode.Unauthorized,
      client.get("/api/v1/owner/dashboard") {
        header(HttpHeaders.Authorization, "Bearer $tamperedJwt")
      }.status,
    )

    // 2. alg=none unsigned JWT rejected
    val noneHeaderB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
      "{\"alg\":\"none\",\"typ\":\"JWT\"}".toByteArray(StandardCharsets.UTF_8),
    )
    val algNoneJwt = "$noneHeaderB64.$tamperedPayloadB64."
    assertEquals(
      HttpStatusCode.Unauthorized,
      client.get("/api/v1/me") {
        header(HttpHeaders.Authorization, "Bearer $algNoneJwt")
      }.status,
    )

    // 3. JWT signed with wrong secret rejected
    val wrongSecretProvider = JwtTokenProvider(cfg.auth.copy(jwtSecret = "completely-wrong-attacker-signing-secret-32b"))
    val forgedJwt = wrongSecretProvider.createAccessToken(
      userId = UUID.fromString(customer.user.id),
      sessionId = UUID.randomUUID(),
      role = UserRole.OWNER,
    )
    assertEquals(
      HttpStatusCode.Unauthorized,
      client.get("/api/v1/owner/dashboard") {
        header(HttpHeaders.Authorization, "Bearer $forgedJwt")
      }.status,
    )

    // 4. Expired JWT rejected
    val expiredJwt = JwtTokenProvider(cfg.auth.copy(accessTokenTtlSeconds = -60L)).createAccessToken(
      userId = UUID.fromString(customer.user.id),
      sessionId = UUID.randomUUID(),
      role = UserRole.CUSTOMER,
    )
    assertEquals(
      HttpStatusCode.Unauthorized,
      client.get("/api/v1/me") {
        header(HttpHeaders.Authorization, "Bearer $expiredJwt")
      }.status,
    )

    // 5. Refresh token rotation and reuse detection revokes entire token family & active session
    val refresh1 = client.post("/api/v1/auth/refresh") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RefreshTokenRequest(customer.refreshToken)))
    }
    assertEquals(HttpStatusCode.OK, refresh1.status)
    val rotatedTokens = json.decodeFromString<AuthTokensResponse>(refresh1.bodyAsText())

    // Replaying already-rotated refresh token must fail AND revoke the family
    val replayOldRefresh = client.post("/api/v1/auth/refresh") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RefreshTokenRequest(customer.refreshToken)))
    }
    assertEquals(HttpStatusCode.Unauthorized, replayOldRefresh.status)
    assertEquals("REFRESH_TOKEN_REUSED", json.decodeFromString<ApiErrorEnvelope>(replayOldRefresh.bodyAsText()).error.code)

    // Even the newer valid refresh token and access token from that compromised session are now invalid
    val useRotatedAfterBreach = client.post("/api/v1/auth/refresh") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RefreshTokenRequest(rotatedTokens.refreshToken)))
    }
    assertEquals(HttpStatusCode.Unauthorized, useRotatedAfterBreach.status)
    assertEquals(
      HttpStatusCode.Unauthorized,
      client.get("/api/v1/me") {
        header(HttpHeaders.Authorization, "Bearer ${rotatedTokens.accessToken}")
      }.status,
    )

    // 6. Password reset token: expired token blocked, valid token single-use, replay blocked
    val forgot = client.post("/api/v1/auth/forgot-password") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ForgotPasswordRequest(customer.user.email)))
    }
    assertEquals(HttpStatusCode.Accepted, forgot.status)
    val resetToken = emailProvider.outbox.last { it.toEmail == customer.user.email }.token

    // Force-expire reset token check using an explicit expired row, then insert/keep valid one or test valid + replay:
    // First insert an artificially expired reset token via SQL and verify it's blocked
    val expiredRawToken = "expired-reset-${UUID.randomUUID()}"
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement(
        """
        INSERT INTO password_reset_tokens (id, user_id, token_hash, expires_at)
        VALUES (?, ?, ?, NOW() - INTERVAL '5 minutes')
        """.trimIndent(),
      ).use { ps ->
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, UUID.fromString(customer.user.id))
        ps.setString(3, com.pizzora.backend.core.security.CryptoTokens.sha256Hex(expiredRawToken))
        ps.executeUpdate()
      }
    }
    val expiredResetRes = client.post("/api/v1/auth/reset-password") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ResetPasswordRequest(expiredRawToken, "FreshPass123!")))
    }
    assertEquals(HttpStatusCode.BadRequest, expiredResetRes.status)
    assertEquals("INVALID_OR_EXPIRED_TOKEN", json.decodeFromString<ApiErrorEnvelope>(expiredResetRes.bodyAsText()).error.code)

    // Consume valid reset token once -> succeeds
    val validResetRes = client.post("/api/v1/auth/reset-password") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ResetPasswordRequest(resetToken, "FreshPass123!")))
    }
    assertEquals(HttpStatusCode.OK, validResetRes.status)

    // Replaying consumed reset token -> blocked
    val replayResetRes = client.post("/api/v1/auth/reset-password") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ResetPasswordRequest(resetToken, "AnotherPass123!")))
    }
    assertEquals(HttpStatusCode.BadRequest, replayResetRes.status)

    // 7. Deactivating a staff account immediately invalidates active JWT sessions
    val staffEmail = "kds-p18-${UUID.randomUUID()}@pizzora.test"
    val createStaff = client.post("/api/v1/owner/staff") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateStaffRequest(
            email = staffEmail,
            password = "StaffPass123!",
            fullName = "Chef P18",
            phone = "9877770018",
            role = UserRole.KITCHEN_STAFF,
            employeeCode = "EMP-${UUID.randomUUID().toString().take(6)}",
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, createStaff.status)
    val staffLogin = client.post("/api/v1/auth/login") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(LoginRequest(staffEmail, "StaffPass123!", "KdsTab")))
    }
    assertEquals(HttpStatusCode.OK, staffLogin.status)
    val staffTokens = json.decodeFromString<AuthTokensResponse>(staffLogin.bodyAsText())
    assertEquals(
      HttpStatusCode.OK,
      client.get("/api/v1/kds/tickets") {
        header(HttpHeaders.Authorization, "Bearer ${staffTokens.accessToken}")
      }.status,
    )

    val deactivate = client.patch("/api/v1/owner/staff/${staffTokens.user.id}") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateStaffRequest(isActive = false)))
    }
    assertEquals(HttpStatusCode.OK, deactivate.status)

    assertEquals(
      HttpStatusCode.Unauthorized,
      client.get("/api/v1/kds/tickets") {
        header(HttpHeaders.Authorization, "Bearer ${staffTokens.accessToken}")
      }.status,
    )
  }

  @Test
  fun `cross-user IDOR, RBAC, invalid payment signatures, webhook replay, path traversal, and malformed uploads are blocked and logged`() = testApplication {
    val emailProvider = InMemoryEmailDeliveryProvider()
    val storageStub = StubStorageClient()
    val gatewayStub = StubRazorpayGateway()
    val cfg = baseConfig(security = SecurityConfig(rateLimitEnabled = false))

    application {
      pizzoraModule(
        config = cfg,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = InMemoryCacheStore(),
        emailDeliveryProvider = emailProvider,
        objectStorageClient = storageStub,
        razorpayGateway = gatewayStub,
        passwordHasher = fastHasher,
      )
    }

    val ownerToken = loginOwner()
    val victim = registerAndLoginCustomer(emailProvider, "victim")
    val attacker = registerAndLoginCustomer(emailProvider, "attacker")

    // Seed category, pizza product, crust, delivery zone, and kitchen staff
    val cat = json.decodeFromString<CategoryDto>(
      client.post("/api/v1/owner/categories") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCategoryRequest("Sec Pizza ${UUID.randomUUID().toString().take(4)}")))
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )
    val product = json.decodeFromString<ProductDto>(
      client.post("/api/v1/owner/products") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateProductRequest(
              categoryId = cat.id,
              name = "Security Margherita",
              description = "Classic",
              variants = listOf(CreateVariantRequest(SizeCode.REGULAR, 29900L)),
            ),
          ),
        )
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )
    val crust = json.decodeFromString<CrustOptionDto>(
      client.post("/api/v1/owner/crusts") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCrustRequest("Hand Tossed ${UUID.randomUUID().toString().take(4)}", 0L)))
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )
    assertEquals(
      HttpStatusCode.Created,
      client.post("/api/v1/owner/delivery-zones") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateDeliveryZoneRequest(
              pincode = "560038",
              areaName = "Indiranagar",
              minOrderPaise = 10000L,
              deliveryFeePaise = 3000L,
              estimatedMins = 30,
            ),
          ),
        )
      }.status,
    )

    // Victim saves an address and places a Razorpay order
    val victimAddr = json.decodeFromString<AddressDto>(
      client.post("/api/v1/me/addresses") {
        header(HttpHeaders.Authorization, "Bearer ${victim.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(UpsertAddressRequest("Home", "12th Main", null, null, "560038", "Bengaluru", true)))
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )

    val checkout = json.decodeFromString<CheckoutResponse>(
      client.post("/api/v1/checkout") {
        header(HttpHeaders.Authorization, "Bearer ${victim.accessToken}")
        header("Idempotency-Key", "p18-${UUID.randomUUID()}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CheckoutRequest(
              items = listOf(
                CartItemInputDto(
                  productId = product.id,
                  variantId = product.variants.first().id,
                  crustId = crust.id,
                  quantity = 1,
                ),
              ),
              fulfillmentType = FulfillmentType.DELIVERY,
              scheduleType = ScheduleType.ASAP,
              addressId = victimAddr.id,
              paymentMethod = PaymentMethod.RAZORPAY,
            ),
          ),
        )
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )
    val victimOrderId = checkout.order.id

    // Dispatch gateway order outbox so gateway_order_id exists
    val dispatcher = OutboxDispatcherWorker(
      dataSource = pg.postgresDatabase,
      razorpayGateway = gatewayStub,
    )
    repeat(3) { dispatcher.runOnce() }

    val refreshedVictimOrder = json.decodeFromString<OrderDetailDto>(
      client.get("/api/v1/orders/$victimOrderId") {
        header(HttpHeaders.Authorization, "Bearer ${victim.accessToken}")
      }.also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText(),
    )
    val gatewayOrderId = refreshedVictimOrder.payment.latestAttempt?.gatewayOrderId
    assertNotNull(gatewayOrderId)

    // 1. Cross-user IDOR blocking across order, payment, address, and notification routes
    assertEquals(
      HttpStatusCode.Forbidden,
      client.get("/api/v1/orders/$victimOrderId") {
        header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
      }.status,
    )
    assertEquals(
      HttpStatusCode.Forbidden,
      client.post("/api/v1/orders/$victimOrderId/cancel") {
        header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
        contentType(ContentType.Application.Json)
        setBody("{}")
      }.status,
    )
    assertEquals(
      HttpStatusCode.Forbidden,
      client.get("/api/v1/orders/$victimOrderId/payment") {
        header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
      }.status,
    )
    assertEquals(
      HttpStatusCode.Forbidden,
      client.post("/api/v1/orders/$victimOrderId/payments/retry") {
        header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
        header("Idempotency-Key", "idor-retry-${UUID.randomUUID()}")
      }.status,
    )
    assertEquals(
      HttpStatusCode.Forbidden,
      client.post("/api/v1/orders/$victimOrderId/payments/fail") {
        header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(FailPaymentAttemptRequest("BAD", "IDOR")))
      }.status,
    )
    val resolvedGatewayOrderId = gatewayOrderId!!
    val validSigByGateway = RazorpaySignatureVerifier.computeHmacSha256Hex(
      payload = "$resolvedGatewayOrderId|pay_idor_1".toByteArray(StandardCharsets.UTF_8),
      secret = testKeySecret,
    )
    assertEquals(
      HttpStatusCode.Forbidden,
      client.post("/api/v1/payments/razorpay/verify") {
        header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            VerifyRazorpayPaymentRequest(
              orderId = victimOrderId,
              razorpayOrderId = resolvedGatewayOrderId,
              razorpayPaymentId = "pay_idor_1",
              razorpaySignature = validSigByGateway,
            ),
          ),
        )
      }.status,
    )

    // Victim's address cannot be updated/deleted by Attacker
    assertEquals(
      HttpStatusCode.NotFound,
      client.patch("/api/v1/me/addresses/${victimAddr.id}") {
        header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(UpsertAddressRequest("Hacked", "99 Street", null, null, "560038", "Bengaluru")))
      }.status,
    )
    assertEquals(
      HttpStatusCode.NotFound,
      client.delete("/api/v1/me/addresses/${victimAddr.id}") {
        header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
      }.status,
    )

    // Victim notification cannot be marked read by Attacker
    val victimInboxId = UUID.randomUUID()
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      NotificationService.insertInboxNotificationIfAllowed(
        conn = conn,
        userId = UUID.fromString(victim.user.id),
        eventId = victimInboxId,
        title = "Payment Ready",
        body = "Your order is awaiting payment",
        category = "ORDER_UPDATE",
        referenceId = UUID.fromString(victimOrderId),
        deliveryStatus = NotificationDeliveryStatus.SYNCED,
      )
    }
    val victimInbox = json.decodeFromString<NotificationInboxResponse>(
      client.get("/api/v1/notifications") {
        header(HttpHeaders.Authorization, "Bearer ${victim.accessToken}")
      }.bodyAsText(),
    )
    val targetNotifId = victimInbox.notifications.first().id
    assertEquals(
      HttpStatusCode.NotFound,
      client.post("/api/v1/notifications/$targetNotifId/read") {
        header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
      }.status,
    )

    // 2. RBAC enforcement: CUSTOMER blocked on Owner & KDS routes
    for (ownerRoute in listOf("/api/v1/owner/dashboard", "/api/v1/owner/settings", "/api/v1/owner/staff", "/api/v1/owner/audit-logs", "/api/v1/kds/tickets")) {
      assertEquals(
        "Expected 403 for customer on $ownerRoute",
        HttpStatusCode.Forbidden,
        client.get(ownerRoute) {
          header(HttpHeaders.Authorization, "Bearer ${attacker.accessToken}")
        }.status,
      )
    }

    // 3. Invalid client Razorpay verify signature blocked & logged to audit_logs
    val invalidClientVerify = client.post("/api/v1/payments/razorpay/verify") {
      header(HttpHeaders.Authorization, "Bearer ${victim.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          VerifyRazorpayPaymentRequest(
            orderId = victimOrderId,
            razorpayOrderId = resolvedGatewayOrderId,
            razorpayPaymentId = "pay_bad_sig",
            razorpaySignature = "0000deadbeef",
          ),
        ),
      )
    }
    assertEquals(HttpStatusCode.Unauthorized, invalidClientVerify.status)
    assertEquals("INVALID_PAYMENT_SIGNATURE", json.decodeFromString<ApiErrorEnvelope>(invalidClientVerify.bodyAsText()).error.code)

    // 4. Invalid webhook signature blocked & logged to audit_logs
    val webhookBody = """
      {"event":"payment.captured","created_at":1731500000,"payload":{"payment":{"entity":{"id":"pay_wh_18","order_id":"$resolvedGatewayOrderId","amount":${refreshedVictimOrder.totalPaise}}}}}
    """.trimIndent()
    val eventId = "evt_p18_${UUID.randomUUID().toString().take(8)}"
    val badWebhook = client.post("/api/v1/webhooks/razorpay") {
      contentType(ContentType.Application.Json)
      header("X-Razorpay-Signature", "tampered_hmac_hex")
      header("X-Razorpay-Event-Id", eventId)
      setBody(webhookBody)
    }
    assertEquals(HttpStatusCode.Unauthorized, badWebhook.status)

    // Valid webhook succeeds, and replay returns 200 idempotently without duplicate payment_events
    val validWebhookSig = RazorpaySignatureVerifier.computeHmacSha256Hex(
      webhookBody.toByteArray(StandardCharsets.UTF_8),
      testWebhookSecret,
    )
    repeat(2) {
      val okWebhook = client.post("/api/v1/webhooks/razorpay") {
        contentType(ContentType.Application.Json)
        header("X-Razorpay-Signature", validWebhookSig)
        header("X-Razorpay-Event-Id", eventId)
        setBody(webhookBody)
      }
      assertEquals(HttpStatusCode.OK, okWebhook.status)
    }
    val afterWebhookOrder = json.decodeFromString<OrderDetailDto>(
      client.get("/api/v1/orders/$victimOrderId") {
        header(HttpHeaders.Authorization, "Bearer ${victim.accessToken}")
      }.bodyAsText(),
    )
    assertEquals(PaymentStatus.CAPTURED, afterWebhookOrder.payment.status)

    val eventCount = DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement("SELECT COUNT(*) FROM payment_events WHERE gateway_event_id = ?").use { ps ->
        ps.setString(1, eventId)
        ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
      }
    }
    assertEquals(1, eventCount)

    // 5. Path traversal object keys rejected cleanly
    val traversalKeys = listOf(
      "../etc/passwd",
      "/etc/passwd",
      "products/../secrets.txt",
      "products\\evil.jpg",
      "products//evil.jpg",
      "products/%2e%2e/evil.jpg",
      "products/%2f/evil.jpg",
      "products/null\u0000.jpg",
      "   ",
    )
    for (badKey in traversalKeys) {
      val rejected = runCatching { StorageKeyValidator.validateObjectKey(badKey) }.exceptionOrNull()
      assertTrue("Expected ValidationException for key='$badKey'", rejected is ValidationException)
    }

    // 6. Spoofed / malformed image upload (HTML script masquerading as image/jpeg) rejected & deleted + audited
    val presignRes = json.decodeFromString<PresignProductImageResponse>(
      client.post("/api/v1/owner/products/${product.id}/images/presign") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(PresignProductImageRequest("image/jpeg", 2048L, 0)))
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )
    storageStub.objects[presignRes.objectKey] = InspectedObject(
      sizeBytes = 64L,
      contentType = "image/jpeg",
      headerBytes = "<html><script>alert(1)</script></html>".toByteArray(StandardCharsets.UTF_8),
    )
    val confirmRes = client.post("/api/v1/owner/products/${product.id}/images/confirm") {
      header(HttpHeaders.Authorization, "Bearer $ownerToken")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ConfirmProductImageRequest(presignRes.imageId)))
    }
    assertEquals(HttpStatusCode.UnprocessableEntity, confirmRes.status)
    assertEquals("INVALID_IMAGE_SIGNATURE", json.decodeFromString<ApiErrorEnvelope>(confirmRes.bodyAsText()).error.code)
    assertTrue(storageStub.deletedKeys.contains(presignRes.objectKey))

    // Verify all security rejections are recorded in owner audit logs
    val auditLogs = json.decodeFromString<AuditLogsResponse>(
      client.get("/api/v1/owner/audit-logs?limit=100") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
      }.also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText(),
    )
    val actions = auditLogs.entries.map { it.action }.toSet()
    assertTrue("Expected PAYMENT_SIGNATURE_INVALID in audit logs, found $actions", "PAYMENT_SIGNATURE_INVALID" in actions)
    assertTrue("Expected RAZORPAY_WEBHOOK_INVALID_SIGNATURE in audit logs, found $actions", "RAZORPAY_WEBHOOK_INVALID_SIGNATURE" in actions)
    assertTrue("Expected PRODUCT_IMAGE_REJECTED in audit logs, found $actions", "PRODUCT_IMAGE_REJECTED" in actions)
  }
}
