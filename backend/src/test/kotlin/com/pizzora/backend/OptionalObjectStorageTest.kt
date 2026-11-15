package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.health.DependencyProbe
import com.pizzora.backend.core.health.HealthService
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.ConfirmProductImageRequest
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.DependencyHealthStatus
import com.pizzora.contracts.FulfillmentType
import com.pizzora.contracts.HealthReadinessResponse
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.PresignProductImageRequest
import com.pizzora.contracts.PricingQuoteRequest
import com.pizzora.contracts.PricingQuoteResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.ReadinessState
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ValidateCartRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class OptionalObjectStorageTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-s3opt-${UUID.randomUUID()}@pizzora.test"
    private val ownerPass = "OwnerS3OptPass123!"

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
          fullName = "Optional S3 Owner",
          phone = "9811112233",
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

  private class UpProbe(override val name: String, override val required: Boolean = false) : DependencyProbe {
    override suspend fun check(): DependencyHealthStatus = DependencyHealthStatus.UP
  }

  private fun baseProdEnv(): Map<String, String> = mapOf(
    "PIZZORA_ENV" to "production",
    "JWT_SECRET" to "prod-strong-hmac-secret-with-more-than-32-chars",
    "DATABASE_PASSWORD" to "prod-db-secret-9981",
  )

  @Test
  fun `production configuration defaults to S3 disabled and allows explicit S3_ENABLED=false`() {
    val defaultProdConfig = AppConfig.fromEnv(baseProdEnv())
    assertFalse(defaultProdConfig.storage.enabled)

    val explicitDisabledConfig = AppConfig.fromEnv(baseProdEnv() + ("S3_ENABLED" to "false"))
    assertFalse(explicitDisabledConfig.storage.enabled)
  }

  @Test
  fun `S3 disabled ignores unconfigured placeholder endpoint without URISyntaxException`() {
    val prodPlaceholderEnv = baseProdEnv() + mapOf(
      "S3_ENABLED" to "false",
      "S3_ENDPOINT" to "https://<your-object-store>",
      "S3_PUBLIC_URL" to "https://<your-cdn>",
      "S3_BUCKET" to "",
      "S3_ACCESS_KEY" to "",
      "S3_SECRET_KEY" to "",
    )
    val prodCfg = AppConfig.fromEnv(prodPlaceholderEnv)
    assertFalse(prodCfg.storage.enabled)

    val prodOmittedPlaceholder = AppConfig.fromEnv(
      baseProdEnv() + ("S3_ENDPOINT" to "https://<your-object-store>"),
    )
    assertFalse(prodOmittedPlaceholder.storage.enabled)

    val devOmittedPlaceholder = AppConfig.fromEnv(
      mapOf(
        "PIZZORA_ENV" to "development",
        "S3_ENDPOINT" to "https://<your-object-store>",
      ),
    )
    assertFalse(devOmittedPlaceholder.storage.enabled)
  }

  @Test
  fun `production configuration with S3_ENABLED=true accepts valid S3-compatible providers`() {
    val r2Config = AppConfig.fromEnv(
      baseProdEnv() + mapOf(
        "S3_ENABLED" to "true",
        "S3_ENDPOINT" to "https://acct123.r2.cloudflarestorage.com/",
        "S3_BUCKET" to "pizzora-media",
        "S3_REGION" to "auto",
        "S3_ACCESS_KEY" to "r2_access_key_123",
        "S3_SECRET_KEY" to "r2_secret_key_456",
        "S3_PUBLIC_URL" to "https://cdn.pizzora.app/",
      ),
    )
    assertTrue(r2Config.storage.enabled)
    assertEquals("https://acct123.r2.cloudflarestorage.com", r2Config.storage.endpoint)
    assertEquals(443, r2Config.storage.port)
    assertEquals("https://cdn.pizzora.app", r2Config.storage.publicBaseUrl)

    val b2Config = AppConfig.fromEnv(
      baseProdEnv() + mapOf(
        "S3_ENABLED" to "true",
        "S3_ENDPOINT" to "https://s3.us-west-004.backblazeb2.com",
        "S3_BUCKET" to "pizzora-b2",
        "S3_REGION" to "us-west-004",
        "S3_ACCESS_KEY" to "b2_key_id",
        "S3_SECRET_KEY" to "b2_app_key",
      ),
    )
    assertTrue(b2Config.storage.enabled)
    assertEquals("https://s3.us-west-004.backblazeb2.com/pizzora-b2", b2Config.storage.publicBaseUrl)
  }

  @Test
  fun `S3_ENABLED=true fails clearly with missing credentials or invalid endpoint`() {
    val invalidCases = listOf(
      baseProdEnv() + ("S3_ENABLED" to "true"),
      baseProdEnv() + mapOf(
        "S3_ENABLED" to "true",
        "S3_ENDPOINT" to "https://<your-object-store>",
        "S3_BUCKET" to "bucket",
        "S3_REGION" to "us-east-1",
        "S3_ACCESS_KEY" to "key",
        "S3_SECRET_KEY" to "secret",
      ),
      baseProdEnv() + mapOf(
        "S3_ENABLED" to "true",
        "S3_ENDPOINT" to "https://s3.ap-south-1.amazonaws.com",
        "S3_BUCKET" to "bucket",
        "S3_REGION" to "ap-south-1",
        "S3_ACCESS_KEY" to "key",
      ),
      baseProdEnv() + mapOf(
        "S3_ENABLED" to "true",
        "S3_ENDPOINT" to "ftp://s3.example.com",
        "S3_BUCKET" to "bucket",
        "S3_REGION" to "ap-south-1",
        "S3_ACCESS_KEY" to "key",
        "S3_SECRET_KEY" to "secret",
      ),
      mapOf(
        "PIZZORA_ENV" to "development",
        "S3_ENABLED" to "true",
      ),
      baseProdEnv() + ("S3_ENABLED" to "not-a-boolean"),
    )

    for (env in invalidCases) {
      val err = runCatching { AppConfig.fromEnv(env) }.exceptionOrNull()
      assertTrue("Expected IllegalArgumentException for $env, got $err", err is IllegalArgumentException)
    }
  }

  @Test
  fun `backend starts cleanly without S3, serves catalog and cart without invalid image URLs, preserves DB rows, and returns 503 on image endpoints`() {
    val disabledCfg = AppConfig.fromEnv(
      baseProdEnv() + mapOf(
        "S3_ENABLED" to "false",
        "S3_ENDPOINT" to "https://<your-object-store>",
      ),
    )
    assertFalse(disabledCfg.storage.enabled)
    assertFalse(HealthService.defaultProbes(disabledCfg).any { it.name == "minio" })

    var createdProductId = ""
    var createdVariantId = ""
    var createdCrustId = ""
    val seededImageId = UUID.randomUUID()
    val seededObjectKey = "products/${UUID.randomUUID()}/$seededImageId.jpg"

    testApplication {
      application {
        pizzoraModule(
          config = disabledCfg,
          probes = listOf(PostgresJdbcProbe(pg.postgresDatabase), UpProbe("redis")),
          dataSource = pg.postgresDatabase,
          cacheStore = InMemoryCacheStore(),
          emailDeliveryProvider = InMemoryEmailDeliveryProvider(),
          passwordHasher = fastHasher,
        )
      }

      // Health readiness stays HEALTHY without minio/S3 probe
      val readyRes = client.get("/api/v1/health/ready")
      assertEquals(HttpStatusCode.OK, readyRes.status)
      val readyBody = json.decodeFromString<HealthReadinessResponse>(readyRes.bodyAsText())
      assertEquals(ReadinessState.HEALTHY, readyBody.status)
      assertFalse(readyBody.dependencies.containsKey("minio"))

      val ownerTokens = json.decodeFromString<AuthTokensResponse>(
        client.post("/api/v1/auth/login") {
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText(),
      )

      val category = json.decodeFromString<CategoryDto>(
        client.post("/api/v1/owner/categories") {
          header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCategoryRequest("S3Opt-${UUID.randomUUID().toString().take(5)}", 1, true)))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
      )

      val product = json.decodeFromString<ProductDto>(
        client.post("/api/v1/owner/products") {
          header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateProductRequest(
                categoryId = category.id,
                name = "No-S3 Margherita",
                description = "Works without object storage",
                variants = listOf(CreateVariantRequest(SizeCode.REGULAR, 29_900L)),
              ),
            ),
          )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
      )
      createdProductId = product.id
      createdVariantId = product.variants.first().id

      val crust = json.decodeFromString<CrustOptionDto>(
        client.post("/api/v1/owner/crusts") {
          header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCrustRequest("HandTossed-${UUID.randomUUID().toString().take(5)}", 0L)))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
      )
      createdCrustId = crust.id

      // Seed an existing ACTIVE image row in PostgreSQL (e.g. created earlier when S3 was enabled)
      DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
        val ownerUuid = conn.createStatement().use { st ->
          st.executeQuery("SELECT id FROM users WHERE email = '$ownerEmail'").use { rs ->
            rs.next()
            rs.getObject("id", UUID::class.java)
          }
        }
        conn.prepareStatement(
          """
          INSERT INTO product_images (
            id, product_id, object_key, mime_type, size_bytes, status, sort_order, uploaded_by
          ) VALUES (?, ?, ?, 'image/jpeg', 1024, 'ACTIVE', 0, ?)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, seededImageId)
          ps.setObject(2, UUID.fromString(createdProductId))
          ps.setString(3, seededObjectKey)
          ps.setObject(4, ownerUuid)
          ps.executeUpdate()
        }
      }

      // Public menu & owner product list work and do NOT expose empty or invalid image URLs
      val menuRes = client.get("/api/v1/catalog/menu")
      assertEquals(HttpStatusCode.OK, menuRes.status)
      val menu = json.decodeFromString<PublicMenuResponse>(menuRes.bodyAsText())
      val menuProd = menu.products.first { it.id == createdProductId }
      assertTrue("Products without accessible URLs must not expose invalid/empty image URLs", menuProd.images.isEmpty())

      val ownerProducts = json.decodeFromString<List<ProductDto>>(
        client.get("/api/v1/owner/products") {
          header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText(),
      )
      assertTrue(ownerProducts.first { it.id == createdProductId }.images.isEmpty())

      // Cart validation & pricing quote work without S3 and keep imageUrl null
      val cartRes = client.post("/api/v1/cart/validate") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            ValidateCartRequest(
              items = listOf(CartItemInputDto(productId = createdProductId, variantId = createdVariantId, crustId = createdCrustId, quantity = 1)),
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, cartRes.status)
      val cartBody = json.decodeFromString<CartResponseDto>(cartRes.bodyAsText())
      assertNull(cartBody.items.first().imageUrl)

      val quoteRes = client.post("/api/v1/pricing/quote") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            PricingQuoteRequest(
              items = listOf(CartItemInputDto(productId = createdProductId, variantId = createdVariantId, crustId = createdCrustId, quantity = 1)),
              fulfillmentType = FulfillmentType.PICKUP,
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, quoteRes.status)
      val quoteBody = json.decodeFromString<PricingQuoteResponse>(quoteRes.bodyAsText())
      assertNull(quoteBody.items.first().imageUrl)

      // Image upload presign & confirm return controlled 503 OBJECT_STORAGE_UNAVAILABLE
      val presignRes = client.post("/api/v1/owner/products/$createdProductId/images/presign") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(PresignProductImageRequest("image/jpeg", 2048L)))
      }
      assertEquals(HttpStatusCode.ServiceUnavailable, presignRes.status)
      assertEquals(
        "OBJECT_STORAGE_UNAVAILABLE",
        json.decodeFromString<ApiErrorEnvelope>(presignRes.bodyAsText()).error.code,
      )

      val confirmRes = client.post("/api/v1/owner/products/$createdProductId/images/confirm") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(ConfirmProductImageRequest(seededImageId.toString())))
      }
      assertEquals(HttpStatusCode.ServiceUnavailable, confirmRes.status)
      assertEquals(
        "OBJECT_STORAGE_UNAVAILABLE",
        json.decodeFromString<ApiErrorEnvelope>(confirmRes.bodyAsText()).error.code,
      )
    }

    // Existing DB image record was NOT deleted while S3 was disabled, and resolves again when S3 is enabled!
    val enabledCfg = AppConfig.fromEnv(
      baseProdEnv() + mapOf(
        "S3_ENABLED" to "true",
        "S3_ENDPOINT" to "https://s3.ap-south-1.amazonaws.com",
        "S3_BUCKET" to "pizzora-prod-media",
        "S3_REGION" to "ap-south-1",
        "S3_ACCESS_KEY" to "AKIA_PROD_KEY",
        "S3_SECRET_KEY" to "prod-s3-secret-9999",
        "S3_PUBLIC_URL" to "https://cdn.pizzora.app",
      ),
    )
    assertTrue(enabledCfg.storage.enabled)
    assertTrue(HealthService.defaultProbes(enabledCfg).any { it.name == "minio" })

    testApplication {
      application {
        pizzoraModule(
          config = enabledCfg,
          probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
          dataSource = pg.postgresDatabase,
          cacheStore = InMemoryCacheStore(),
          emailDeliveryProvider = InMemoryEmailDeliveryProvider(),
          passwordHasher = fastHasher,
        )
      }

      val menuWithS3 = json.decodeFromString<PublicMenuResponse>(client.get("/api/v1/catalog/menu").bodyAsText())
      val prodWithImg = menuWithS3.products.first { it.id == createdProductId }
      assertEquals(1, prodWithImg.images.size)
      assertEquals("https://cdn.pizzora.app/$seededObjectKey", prodWithImg.images.first().publicUrl)
    }
  }
}
