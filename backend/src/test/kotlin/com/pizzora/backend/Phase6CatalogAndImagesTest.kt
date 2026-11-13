package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.config.StorageConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.backend.images.ObjectStorageUnavailableException
import com.pizzora.backend.images.S3ObjectStorageClient
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.ConfirmProductImageRequest
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.PresignProductImageRequest
import com.pizzora.contracts.PresignProductImageResponse
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.ProductImageDto
import com.pizzora.contracts.ProductImageStatus
import com.pizzora.contracts.PublicMenuResponse
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateProductRequest
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.VerifyEmailRequest
import com.sun.net.httpserver.HttpServer
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
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase6CatalogAndImagesTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private lateinit var s3Server: LocalS3StubServer
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-${UUID.randomUUID()}@pizzora.test"
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
          fullName = "Marco Rossi",
          phone = "9811111111",
        )
      }
      s3Server = LocalS3StubServer().also { it.start() }
    }

    @JvmStatic
    @AfterClass
    fun stopInfra() {
      s3Server.stop()
      pg.close()
    }
  }

  private class InMemoryCacheStore : EphemeralCacheStore {
    val map = ConcurrentHashMap<String, String>()
    override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean {
      map[key] = value
      return true
    }
    override suspend fun get(key: String): String? = map[key]
    override suspend fun getDel(key: String): String? = map.remove(key)
    override suspend fun del(key: String): Boolean = map.remove(key) != null
    override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? = null
  }

  private object DownRedisStore : EphemeralCacheStore {
    override suspend fun setEx(key: String, ttlSeconds: Long, value: String): Boolean = false
    override suspend fun get(key: String): String? = null
    override suspend fun getDel(key: String): String? = null
    override suspend fun del(key: String): Boolean = false
    override suspend fun incrementWithinWindow(key: String, windowSeconds: Long): Long? = null
  }

  /**
   * Minimal HTTP S3/MinIO server verifying SigV4 presigned query presence + Range GET/HEAD/DELETE.
   */
  private class LocalS3StubServer {
    data class StoredObject(val bytes: ByteArray, val contentType: String)

    val objects = ConcurrentHashMap<String, StoredObject>()
    @Volatile
    var simulateOutage: Boolean = false
    private lateinit var server: HttpServer

    val port: Int
      get() = server.address.port

    fun start() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        if (simulateOutage) {
          exchange.sendResponseHeaders(503, -1)
          exchange.close()
          return@createContext
        }

        val path = exchange.requestURI.path.trimStart('/')
        when (exchange.requestMethod.uppercase()) {
          "PUT" -> {
            val query = exchange.requestURI.rawQuery.orEmpty()
            if (!query.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256") || !query.contains("X-Amz-Signature=")) {
              exchange.sendResponseHeaders(403, -1)
              exchange.close()
              return@createContext
            }
            val body = exchange.requestBody.readAllBytes()
            val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: "application/octet-stream"
            objects[path] = StoredObject(body, contentType)
            exchange.sendResponseHeaders(200, -1)
          }
          "HEAD" -> {
            val obj = objects[path]
            if (obj == null) {
              exchange.sendResponseHeaders(404, -1)
            } else {
              exchange.responseHeaders.add("Content-Type", obj.contentType)
              exchange.responseHeaders.add("Content-Length", obj.bytes.size.toString())
              exchange.sendResponseHeaders(200, -1)
            }
          }
          "GET" -> {
            val obj = objects[path]
            if (obj == null) {
              exchange.sendResponseHeaders(404, -1)
            } else {
              val range = exchange.requestHeaders.getFirst("Range")
              val slice = if (range == "bytes=0-15") obj.bytes.take(16).toByteArray() else obj.bytes
              val status = if (range != null) 206 else 200
              exchange.responseHeaders.add("Content-Type", obj.contentType)
              exchange.sendResponseHeaders(status, slice.size.toLong())
              exchange.responseBody.write(slice)
            }
          }
          "DELETE" -> {
            objects.remove(path)
            exchange.sendResponseHeaders(204, -1)
          }
          else -> exchange.sendResponseHeaders(405, -1)
        }
        exchange.close()
      }
      server.start()
    }

    fun stop() {
      server.stop(0)
    }
  }

  private fun storageConfig(): StorageConfig = StorageConfig(
    endpoint = "http://127.0.0.1:${s3Server.port}",
    host = "127.0.0.1",
    port = s3Server.port,
    bucket = "pizzora-product-images",
    region = "ap-south-1",
    accessKey = "test-access",
    secretKey = "test-secret",
    publicBaseUrl = "http://127.0.0.1:${s3Server.port}/pizzora-product-images",
  )

  @Test
  fun `owner catalog CRUD, category FK delete protection, Redis caching and outage fallback`() = testApplication {
    val cache = InMemoryCacheStore()
    val emailProvider = InMemoryEmailDeliveryProvider()
    val cfg = AppConfig.fromEnv().copy(storage = storageConfig())

    application {
      pizzoraModule(
        config = cfg,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = cache,
        emailDeliveryProvider = emailProvider,
        objectStorageClient = S3ObjectStorageClient(cfg.storage),
        passwordHasher = fastHasher,
      )
    }

    val ownerTokens = json.decodeFromString<AuthTokensResponse>(
      client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
      }.bodyAsText(),
    )

    // Register & verify a customer to test RBAC 403
    val custEmail = "cust-${UUID.randomUUID()}@pizzora.test"
    client.post("/api/v1/auth/register") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RegisterRequest(custEmail, "CustPass123!", "Asha", "9800000001")))
    }
    val verifyToken = emailProvider.outbox.last().token
    client.post("/api/v1/auth/verify-email") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(VerifyEmailRequest(verifyToken)))
    }
    val custTokens = json.decodeFromString<AuthTokensResponse>(
      client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(custEmail, "CustPass123!")))
      }.bodyAsText(),
    )

    // Customer cannot create category
    val forbidden = client.post("/api/v1/owner/categories") {
      header(HttpHeaders.Authorization, "Bearer ${custTokens.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateCategoryRequest("Artisanal", 1)))
    }
    assertEquals(HttpStatusCode.Forbidden, forbidden.status)

    // Owner creates active category and inactive category
    val cat1 = json.decodeFromString<CategoryDto>(
      client.post("/api/v1/owner/categories") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCategoryRequest("Wood-Fired Classics-${UUID.randomUUID().toString().take(5)}", 1, true)))
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )
    val hiddenCat = json.decodeFromString<CategoryDto>(
      client.post("/api/v1/owner/categories") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCategoryRequest("Hidden-${UUID.randomUUID().toString().take(5)}", 2, false)))
      }.bodyAsText(),
    )

    // Owner creates active product + variants, crust, topping
    val product = json.decodeFromString<ProductDto>(
      client.post("/api/v1/owner/products") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateProductRequest(
              categoryId = cat1.id,
              name = "Margherita Napoletana",
              description = "San Marzano DOP tomato, fior di latte, basil",
              isVeg = true,
              variants = listOf(
                CreateVariantRequest(SizeCode.REGULAR, 29_900L),
                CreateVariantRequest(SizeCode.MEDIUM, 44_900L),
                CreateVariantRequest(SizeCode.LARGE, 59_900L),
              ),
            ),
          ),
        )
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )
    assertEquals(3, product.variants.size)

    // Inactive product hidden from public menu
    val inactiveProd = json.decodeFromString<ProductDto>(
      client.post("/api/v1/owner/products") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateProductRequest(
              categoryId = cat1.id,
              name = "Secret Test Pie",
              isActive = false,
              variants = listOf(CreateVariantRequest(SizeCode.REGULAR, 19_900L)),
            ),
          ),
        )
      }.bodyAsText(),
    )

    val crust = json.decodeFromString<CrustOptionDto>(
      client.post("/api/v1/owner/crusts") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCrustRequest("Sourdough-${UUID.randomUUID().toString().take(5)}", 4_000L)))
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )

    val topping = json.decodeFromString<ToppingDto>(
      client.post("/api/v1/owner/toppings") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateToppingRequest("Burrata-${UUID.randomUUID().toString().take(5)}", true, 9_500L)))
      }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
    )

    // Category referenced by product cannot be deleted (409 CATEGORY_IN_USE)
    val delInUse = client.delete("/api/v1/owner/categories/${cat1.id}") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
    }
    assertEquals(HttpStatusCode.Conflict, delInUse.status)
    assertEquals("CATEGORY_IN_USE", json.decodeFromString<ApiErrorEnvelope>(delInUse.bodyAsText()).error.code)

    // Unused hidden category deletes cleanly
    val delUnused = client.delete("/api/v1/owner/categories/${hiddenCat.id}") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
    }
    assertEquals(HttpStatusCode.NoContent, delUnused.status)

    // Public menu caches in Redis and excludes inactive product & category
    val menuRes = client.get("/api/v1/catalog/menu")
    assertEquals(HttpStatusCode.OK, menuRes.status)
    val menu = json.decodeFromString<PublicMenuResponse>(menuRes.bodyAsText())
    assertTrue(menu.categories.any { it.id == cat1.id })
    assertFalse(menu.categories.any { it.id == hiddenCat.id })
    assertTrue(menu.products.any { it.id == product.id })
    assertFalse(menu.products.any { it.id == inactiveProd.id })
    assertTrue(menu.crusts.any { it.id == crust.id })
    assertTrue(menu.toppings.any { it.id == topping.id })
    assertTrue(cache.map.containsKey("catalog:menu:v1"))

    // Owner updates product -> Redis cache invalidated and new description served
    client.patch("/api/v1/owner/products/${product.id}") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(UpdateProductRequest(description = "Wood-fired 48h fermented dough")))
    }.also { assertEquals(HttpStatusCode.OK, it.status) }
    assertFalse(cache.map.containsKey("catalog:menu:v1"))

    val refreshedMenu = json.decodeFromString<PublicMenuResponse>(client.get("/api/v1/catalog/menu").bodyAsText())
    assertEquals("Wood-fired 48h fermented dough", refreshedMenu.products.first { it.id == product.id }.description)
  }

  @Test
  fun `public catalog seamlessly falls back to PostgreSQL when Redis is down`() = testApplication {
    val cfg = AppConfig.fromEnv().copy(storage = storageConfig())
    application {
      pizzoraModule(
        config = cfg,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = DownRedisStore,
        emailDeliveryProvider = InMemoryEmailDeliveryProvider(),
        objectStorageClient = S3ObjectStorageClient(cfg.storage),
        passwordHasher = fastHasher,
      )
    }

    val menuRes = client.get("/api/v1/catalog/menu")
    assertEquals(HttpStatusCode.OK, menuRes.status)
    val catsRes = client.get("/api/v1/catalog/categories")
    assertEquals(HttpStatusCode.OK, catsRes.status)
  }

  @Test
  fun `S3 presign upload, magic-byte validation, spoofed MIME rejection, oversize rejection and MinIO outage 503`() = testApplication {
    s3Server.simulateOutage = false
    val cache = InMemoryCacheStore()
    val cfg = AppConfig.fromEnv().copy(storage = storageConfig())

    application {
      pizzoraModule(
        config = cfg,
        probes = listOf(PostgresJdbcProbe(pg.postgresDatabase)),
        dataSource = pg.postgresDatabase,
        cacheStore = cache,
        emailDeliveryProvider = InMemoryEmailDeliveryProvider(),
        objectStorageClient = S3ObjectStorageClient(cfg.storage),
        passwordHasher = fastHasher,
      )
    }

    val ownerTokens = json.decodeFromString<AuthTokensResponse>(
      client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
      }.bodyAsText(),
    )

    val cat = json.decodeFromString<CategoryDto>(
      client.post("/api/v1/owner/categories") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCategoryRequest("Gourmet-${UUID.randomUUID().toString().take(5)}", 1)))
      }.bodyAsText(),
    )
    val product = json.decodeFromString<ProductDto>(
      client.post("/api/v1/owner/products") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateProductRequest(
              categoryId = cat.id,
              name = "Truffle Funghi",
              variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 49_900L)),
            ),
          ),
        )
      }.bodyAsText(),
    )

    // 1. Valid JPEG upload via presigned PUT -> confirm passes & image appears in public menu
    val presignRes = client.post("/api/v1/owner/products/${product.id}/images/presign") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(PresignProductImageRequest("image/jpeg", 128L, sortOrder = 0)))
    }
    assertEquals(HttpStatusCode.Created, presignRes.status)
    val presign = json.decodeFromString<PresignProductImageResponse>(presignRes.bodyAsText())
    assertTrue(presign.uploadUrl.contains("X-Amz-Signature="))

    val validJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) + ByteArray(64)
    val jdkHttp = HttpClient.newHttpClient()
    val putResp = jdkHttp.send(
      HttpRequest.newBuilder()
        .uri(URI(presign.uploadUrl))
        .header("Content-Type", "image/jpeg")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(validJpegBytes))
        .build(),
      HttpResponse.BodyHandlers.discarding(),
    )
    assertEquals(200, putResp.statusCode())

    val confirmRes = client.post("/api/v1/owner/products/${product.id}/images/confirm") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ConfirmProductImageRequest(presign.imageId)))
    }
    assertEquals(HttpStatusCode.OK, confirmRes.status)
    val confirmedImage = json.decodeFromString<ProductImageDto>(confirmRes.bodyAsText())
    assertEquals(ProductImageStatus.ACTIVE, confirmedImage.status)
    assertEquals(validJpegBytes.size.toLong(), confirmedImage.sizeBytes)

    val menuAfterImg = json.decodeFromString<PublicMenuResponse>(client.get("/api/v1/catalog/menu").bodyAsText())
    val loadedProd = menuAfterImg.products.first { it.id == product.id }
    assertEquals(1, loadedProd.images.size)
    assertEquals(presign.publicUrl, loadedProd.images.first().publicUrl)

    // 2. Spoofed MIME upload (PNG header uploaded under image/jpeg claim) -> rejected 422
    val spoofPresign = json.decodeFromString<PresignProductImageResponse>(
      client.post("/api/v1/owner/products/${product.id}/images/presign") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(PresignProductImageRequest("image/jpeg", 100L)))
      }.bodyAsText(),
    )
    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(20)
    jdkHttp.send(
      HttpRequest.newBuilder()
        .uri(URI(spoofPresign.uploadUrl))
        .header("Content-Type", "image/jpeg")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(pngBytes))
        .build(),
      HttpResponse.BodyHandlers.discarding(),
    )
    val spoofConfirm = client.post("/api/v1/owner/products/${product.id}/images/confirm") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ConfirmProductImageRequest(spoofPresign.imageId)))
    }
    assertEquals(HttpStatusCode.UnprocessableEntity, spoofConfirm.status)
    assertEquals(
      "INVALID_IMAGE_SIGNATURE",
      json.decodeFromString<ApiErrorEnvelope>(spoofConfirm.bodyAsText()).error.code,
    )

    // 3. Oversized declared upload rejected at presign (400 IMAGE_TOO_LARGE)
    val tooLargePresign = client.post("/api/v1/owner/products/${product.id}/images/presign") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(PresignProductImageRequest("image/png", 6L * 1024L * 1024L)))
    }
    assertEquals(HttpStatusCode.BadRequest, tooLargePresign.status)

    // 4. MinIO outage during confirm returns 503 OBJECT_STORAGE_UNAVAILABLE while /catalog/menu stays 200 OK
    val outagePresign = json.decodeFromString<PresignProductImageResponse>(
      client.post("/api/v1/owner/products/${product.id}/images/presign") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(PresignProductImageRequest("image/webp", 64L)))
      }.bodyAsText(),
    )
    s3Server.simulateOutage = true
    try {
      val confirmDuringOutage = client.post("/api/v1/owner/products/${product.id}/images/confirm") {
        header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(ConfirmProductImageRequest(outagePresign.imageId)))
      }
      assertEquals(HttpStatusCode.ServiceUnavailable, confirmDuringOutage.status)
      assertEquals(
        "OBJECT_STORAGE_UNAVAILABLE",
        json.decodeFromString<ApiErrorEnvelope>(confirmDuringOutage.bodyAsText()).error.code,
      )

      // Public menu still succeeds 200 OK even while MinIO is down
      assertEquals(HttpStatusCode.OK, client.get("/api/v1/catalog/menu").status)
    } finally {
      s3Server.simulateOutage = false
    }

    // 5. Delete active image removes it from public menu
    val delImg = client.delete("/api/v1/owner/products/${product.id}/images/${confirmedImage.id}") {
      header(HttpHeaders.Authorization, "Bearer ${ownerTokens.accessToken}")
    }
    assertEquals(HttpStatusCode.NoContent, delImg.status)
    val menuAfterDel = json.decodeFromString<PublicMenuResponse>(client.get("/api/v1/catalog/menu").bodyAsText())
    assertTrue(menuAfterDel.products.first { it.id == product.id }.images.isEmpty())
  }
}
