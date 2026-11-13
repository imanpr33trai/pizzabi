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
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartItemInputDto
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.CartValidationIssueCode
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ReplaceCartRequest
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UpdateToppingRequest
import com.pizzora.contracts.UserRole
import com.pizzora.contracts.ValidateCartRequest
import com.pizzora.contracts.VerifyEmailRequest
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
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase7CartValidationTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-p7-${UUID.randomUUID()}@pizzora.test"
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
          fullName = "Owner P7",
          phone = "9822222222",
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
  fun `cart PUT ignores client price, persists relational toppings, flags stale modifiers, and enforces RBAC`() =
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
      assertEquals(HttpStatusCode.OK, ownerLogin.status)
      val ownerToken = json.decodeFromString<AuthTokensResponse>(ownerLogin.bodyAsText()).accessToken

      // Create kitchen staff and verify RBAC denial on /cart
      val kitchenEmail = "kds-${UUID.randomUUID()}@pizzora.test"
      val staffResp = client.post("/api/v1/owner/staff") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateStaffRequest(
              email = kitchenEmail,
              password = "StaffPassword123!",
              fullName = "Chef Mario",
              phone = "9833333333",
              role = UserRole.KITCHEN_STAFF,
              employeeCode = "KDS-${UUID.randomUUID().toString().take(6)}",
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.Created, staffResp.status)
      val kdsLogin = client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(kitchenEmail, "StaffPassword123!")))
      }
      val kdsToken = json.decodeFromString<AuthTokensResponse>(kdsLogin.bodyAsText()).accessToken

      val kdsCartResp = client.get("/api/v1/cart") {
        header(HttpHeaders.Authorization, "Bearer $kdsToken")
      }
      assertEquals(HttpStatusCode.Forbidden, kdsCartResp.status)

      // Seed category, pizza, crust, toppings
      val catResp = client.post("/api/v1/owner/categories") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCategoryRequest("Artisanal Pies", 1)))
      }
      val category = json.decodeFromString<CategoryDto>(catResp.bodyAsText())

      val prodResp = client.post("/api/v1/owner/products") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateProductRequest(
              categoryId = category.id,
              name = "Margherita Verace",
              description = "San Marzano & Fior di Latte",
              isVeg = true,
              variants = listOf(
                CreateVariantRequest(SizeCode.REGULAR, 29_900L),
                CreateVariantRequest(SizeCode.MEDIUM, 44_900L),
              ),
            ),
          ),
        )
      }
      val product = json.decodeFromString<ProductDto>(prodResp.bodyAsText())
      val mediumVariant = product.variants.first { it.sizeCode == SizeCode.MEDIUM }

      val crustResp = client.post("/api/v1/owner/crusts") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateCrustRequest("Sourdough Pan", 5_000L)))
      }
      val crust = json.decodeFromString<CrustOptionDto>(crustResp.bodyAsText())

      val basilResp = client.post("/api/v1/owner/toppings") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateToppingRequest("Fresh Basil", isVeg = true, extraPricePaise = 2_500L)))
      }
      val basil = json.decodeFromString<ToppingDto>(basilResp.bodyAsText())

      val truffleResp = client.post("/api/v1/owner/toppings") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CreateToppingRequest("Black Truffle", isVeg = true, extraPricePaise = 9_500L)))
      }
      val truffle = json.decodeFromString<ToppingDto>(truffleResp.bodyAsText())

      // Register & verify customer
      val custEmail = "cust-${UUID.randomUUID()}@pizzora.test"
      val regResp = client.post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RegisterRequest(custEmail, "CustPass123!", "Luigi", "9844444444")))
      }
      assertEquals(HttpStatusCode.Created, regResp.status)
      val verifyToken = emailProvider.outbox.last().token
      client.post("/api/v1/auth/verify-email") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(VerifyEmailRequest(verifyToken)))
      }
      val custLogin = client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LoginRequest(custEmail, "CustPass123!")))
      }
      val custToken = json.decodeFromString<AuthTokensResponse>(custLogin.bodyAsText()).accessToken

      // Empty cart check
      val emptyCartResp = client.get("/api/v1/cart") {
        header(HttpHeaders.Authorization, "Bearer $custToken")
      }
      assertEquals(HttpStatusCode.OK, emptyCartResp.status)
      val emptyCart = json.decodeFromString<CartResponseDto>(emptyCartResp.bodyAsText())
      assertTrue(emptyCart.items.isEmpty())
      assertEquals(0L, emptyCart.subtotalPaise)

      // Replace cart with tampered client price (1 paise) and duplicate config lines -> merged, server price computed!
      val putResp = client.put("/api/v1/cart") {
        header(HttpHeaders.Authorization, "Bearer $custToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            ReplaceCartRequest(
              items = listOf(
                CartItemInputDto(
                  productId = product.id,
                  variantId = mediumVariant.id,
                  crustId = crust.id,
                  toppingIds = listOf(basil.id, truffle.id, basil.id),
                  quantity = 1,
                  clientUnitPricePaise = 1L,
                ),
                CartItemInputDto(
                  productId = product.id,
                  variantId = mediumVariant.id,
                  crustId = crust.id,
                  toppingIds = listOf(truffle.id, basil.id),
                  quantity = 2,
                  clientUnitPricePaise = 1L,
                ),
              ),
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, putResp.status)
      val savedCart = json.decodeFromString<CartResponseDto>(putResp.bodyAsText())
      assertEquals(1, savedCart.items.size)
      val savedItem = savedCart.items.first()
      assertEquals(3, savedItem.quantity)
      assertEquals(2, savedItem.toppings.size)
      // 44900 + 5000 + 2500 + 9500 = 61900 paise/unit
      assertEquals(61_900L, savedItem.unitPricePaise)
      assertEquals(185_700L, savedItem.lineTotalPaise)
      assertEquals(185_700L, savedCart.subtotalPaise)
      assertTrue(savedCart.allAvailable)

      // Owner deactivates Black Truffle -> subsequent GET /cart & POST /cart/validate flag unavailable modifier
      val deactivateResp = client.patch("/api/v1/owner/toppings/${truffle.id}") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(UpdateToppingRequest(isActive = false)))
      }
      assertEquals(HttpStatusCode.OK, deactivateResp.status)

      val staleCartResp = client.get("/api/v1/cart") {
        header(HttpHeaders.Authorization, "Bearer $custToken")
      }
      assertEquals(HttpStatusCode.OK, staleCartResp.status)
      val staleCart = json.decodeFromString<CartResponseDto>(staleCartResp.bodyAsText())
      assertFalse(staleCart.allAvailable)
      assertEquals(1, staleCart.unavailableCount)
      assertTrue(staleCart.items.first().issues.any { it.code == CartValidationIssueCode.TOPPING_UNAVAILABLE })

      // Validate local offline payload referencing unknown product UUID cleanly returns validation issue
      val validateResp = client.post("/api/v1/cart/validate") {
        header(HttpHeaders.Authorization, "Bearer $custToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            ValidateCartRequest(
              items = listOf(
                CartItemInputDto(
                  id = "offline-item-1",
                  productId = UUID.randomUUID().toString(),
                  variantId = mediumVariant.id,
                  crustId = crust.id,
                  toppingIds = listOf(basil.id),
                  quantity = 1,
                ),
              ),
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, validateResp.status)
      val validated = json.decodeFromString<CartResponseDto>(validateResp.bodyAsText())
      assertFalse(validated.allAvailable)
      assertTrue(validated.items.first().issues.any { it.code == CartValidationIssueCode.PRODUCT_UNAVAILABLE })
    }
}
