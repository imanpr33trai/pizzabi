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
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CartResponseDto
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.ReorderResponseDto
import com.pizzora.contracts.VerifyEmailRequest
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
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** HTTP-level coverage for the approved reorder endpoint: 200 owner, 403 other customer, 404 missing. */
class Phase8ReorderApiTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }
    private val ownerEmail = "owner-p8reorder-${UUID.randomUUID()}@pizzora.test"
    private val ownerPass = "OwnerPass123!"

    @JvmStatic
    @BeforeClass
    fun startInfra() {
      pg = EmbeddedPostgres.builder().start()
      DatabaseFactory.migrateAndConnect(pg.postgresDatabase)
      Phase8TestSupport.applyPhase8Schema(pg.postgresDatabase)
      runBlocking {
        BootstrapOwner.bootstrap(
          dataSource = pg.postgresDatabase,
          passwordHasher = fastHasher,
          email = ownerEmail,
          password = ownerPass,
          fullName = "Owner P8 Reorder",
          phone = "9888888881",
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
    override fun publicUrlFor(objectKey: String): String = "http://localhost:9000/pizzora/$objectKey"
    override suspend fun inspectObject(objectKey: String): InspectedObject? = null
    override suspend fun deleteObject(objectKey: String): Boolean = true
  }

  @Test
  fun `reorder endpoint returns 200 for owner, 403 for other customer, 404 for unknown order, and never mutates the cart`() =
    testApplication {
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

      suspend fun createVerifiedCustomer(): Pair<UUID, String> {
        val email = "p8-reorder-${UUID.randomUUID()}@pizzora.test"
        client.post("/api/v1/auth/register") {
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(RegisterRequest(email, "CustPass123!", "P8 Reorder", "9811118881")))
        }
        val token = mailProvider.outbox.last { it.toEmail == email && it.type == "VERIFICATION" }.token
        client.post("/api/v1/auth/verify-email") {
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(VerifyEmailRequest(token)))
        }
        val login = client.post("/api/v1/auth/login") {
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(LoginRequest(email, "CustPass123!")))
        }
        val parsed = json.decodeFromString<AuthTokensResponse>(login.bodyAsText())
        return UUID.fromString(parsed.user.id) to parsed.accessToken
      }

      val (ownerId, ownerToken) = createVerifiedCustomer()
      val (_, otherToken) = createVerifiedCustomer()
      val fx = Phase8TestSupport.seedCatalog(pg.postgresDatabase, "reorder")
      val order = Phase8TestSupport.seedOrder(
        dataSource = pg.postgresDatabase,
        customerId = ownerId,
        productId = fx.productId,
        productName = "Phase8 Pizza reorder",
        variantId = fx.variantId,
        crustId = fx.classicCrustId,
        cheeseId = fx.cheeseId,
        sauceId = fx.sauceId,
        toppingIds = listOf(fx.toppingLeftId),
        toppingNames = listOf("Left reorder"),
        standardToppingIds = listOf(fx.defaultStandardToppingId, fx.extraStandardToppingId),
        standardToppingRemovedIds = listOf(fx.defaultStandardToppingId),
        halfAndHalfConfigId = fx.halfAndHalfConfigId,
        halfAndHalfLeftToppingIds = listOf(fx.toppingLeftId),
        halfAndHalfRightToppingIds = listOf(fx.toppingRightId),
      )

      val ownResponse = client.post("/api/v1/orders/${order.orderId}/reorder") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
      }
      assertEquals(HttpStatusCode.OK, ownResponse.status)
      val body = json.decodeFromString<ReorderResponseDto>(ownResponse.bodyAsText())
      assertEquals(order.orderId.toString(), body.orderId)
      assertTrue(body.canReorder)
      assertTrue(body.unavailable.isEmpty())
      val item = body.items.single()
      assertEquals(order.itemId.toString(), item.orderItemId)
      assertEquals(fx.productId.toString(), item.productId)
      assertEquals(fx.variantId.toString(), item.variantId)
      assertEquals(fx.classicCrustId.toString(), item.crustId)
      assertEquals(fx.cheeseId.toString(), item.cheeseId)
      assertEquals(fx.sauceId.toString(), item.sauceId)
      assertEquals(listOf(fx.toppingLeftId.toString()), item.toppingIds)
      assertTrue(item.standardToppingIds.contains(fx.extraStandardToppingId.toString()))
      assertTrue(item.standardToppingRemovedIds.contains(fx.defaultStandardToppingId.toString()))
      assertEquals(fx.halfAndHalfConfigId.toString(), item.halfAndHalfConfigId)
      assertEquals(listOf(fx.toppingLeftId.toString()), item.halfAndHalfLeftToppingIds)
      assertEquals(listOf(fx.toppingRightId.toString()), item.halfAndHalfRightToppingIds)
      assertTrue(item.isAvailable)

      val forbidden = client.post("/api/v1/orders/${order.orderId}/reorder") {
        header(HttpHeaders.Authorization, "Bearer $otherToken")
      }
      assertEquals(HttpStatusCode.Forbidden, forbidden.status)

      val missing = client.post("/api/v1/orders/${UUID.randomUUID()}/reorder") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
      }
      assertEquals(HttpStatusCode.NotFound, missing.status)

      val cart = client.get("/api/v1/cart") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
      }
      assertEquals(HttpStatusCode.OK, cart.status)
      assertTrue(json.decodeFromString<CartResponseDto>(cart.bodyAsText()).items.isEmpty())
    }
}
