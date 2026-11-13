package com.pizzora.backend

import com.pizzora.backend.auth.BootstrapOwner
import com.pizzora.backend.core.config.AppConfig
import com.pizzora.backend.core.db.DatabaseFactory
import com.pizzora.backend.core.db.PostgresJdbcProbe
import com.pizzora.backend.core.email.InMemoryEmailDeliveryProvider
import com.pizzora.backend.core.error.AppException
import com.pizzora.backend.core.redis.EphemeralCacheStore
import com.pizzora.backend.core.security.PasswordHasher
import com.pizzora.backend.images.InspectedObject
import com.pizzora.backend.images.ObjectStorageClient
import com.pizzora.backend.inventory.InventoryService
import com.pizzora.backend.recipes.RecipeDemandItem
import com.pizzora.backend.recipes.RecipeService
import com.pizzora.contracts.AdjustInventoryRequest
import com.pizzora.contracts.ApiErrorEnvelope
import com.pizzora.contracts.AuthTokensResponse
import com.pizzora.contracts.CategoryDto
import com.pizzora.contracts.CreateCategoryRequest
import com.pizzora.contracts.CreateCrustRequest
import com.pizzora.contracts.CreateIngredientRequest
import com.pizzora.contracts.CreateProductRequest
import com.pizzora.contracts.CreateStaffRequest
import com.pizzora.contracts.CreateToppingRequest
import com.pizzora.contracts.CreateVariantRequest
import com.pizzora.contracts.CrustOptionDto
import com.pizzora.contracts.IngredientUnit
import com.pizzora.contracts.InventoryItemDto
import com.pizzora.contracts.InventoryLedgerEntryDto
import com.pizzora.contracts.InventoryLedgerReason
import com.pizzora.contracts.LoginRequest
import com.pizzora.contracts.ProductDto
import com.pizzora.contracts.PutRecipeRequest
import com.pizzora.contracts.RecipeBindingDto
import com.pizzora.contracts.RecipeIngredientLineRequest
import com.pizzora.contracts.RecipeTargetType
import com.pizzora.contracts.RecipesOverviewResponse
import com.pizzora.contracts.RegisterRequest
import com.pizzora.contracts.SizeCode
import com.pizzora.contracts.ToppingDto
import com.pizzora.contracts.UserRole
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
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.SQLException
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
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Phase9InventoryAndRecipesTest {
  companion object {
    private lateinit var pg: EmbeddedPostgres
    private val fastHasher = PasswordHasher(memoryKb = 4096, iterations = 1, parallelism = 1)
    private val json = Json { ignoreUnknownKeys = true }

    private val ownerEmail = "owner-p9-${UUID.randomUUID()}@pizzora.test"
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
          fullName = "Owner P9",
          phone = "9822222229",
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
  fun `owner inventory CRUD, idempotent operation_id ledger, RBAC, recipe aggregation, and FK cascade-restrict`() =
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

      val ownerToken = json.decodeFromString<AuthTokensResponse>(
        client.post("/api/v1/auth/login") {
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(LoginRequest(ownerEmail, ownerPass)))
        }.bodyAsText(),
      ).accessToken

      // Provision kitchen staff + verify customer & KDS get 403 on inventory & recipes
      val kdsEmail = "kds-p9-${UUID.randomUUID()}@pizzora.test"
      client.post("/api/v1/owner/staff") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            CreateStaffRequest(
              email = kdsEmail,
              password = "StaffPassword123!",
              fullName = "Chef Pino",
              phone = "9866666601",
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

      val custEmail = "cust-p9-${UUID.randomUUID()}@pizzora.test"
      client.post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RegisterRequest(custEmail, "CustPass123!", "Sofia", "9866666602")))
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

      assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/owner/inventory") { header(HttpHeaders.Authorization, "Bearer $custToken") }.status,
      )
      assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/owner/recipes") { header(HttpHeaders.Authorization, "Bearer $kdsToken") }.status,
      )

      // Owner creates ingredients (Mozzarella, Dough, Fresh Basil)
      val mozzarella = json.decodeFromString<InventoryItemDto>(
        client.post("/api/v1/owner/inventory") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateIngredientRequest("Fior di Latte P9", IngredientUnit.GRAMS, lowStockThreshold = 300L, initialStockQty = 1_000L),
            ),
          )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText(),
      )
      val dough = json.decodeFromString<InventoryItemDto>(
        client.post("/api/v1/owner/inventory") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateIngredientRequest("Sourdough Ball P9", IngredientUnit.GRAMS, lowStockThreshold = 500L, initialStockQty = 400L),
            ),
          )
        }.bodyAsText(),
      )
      assertTrue(dough.isLowStock)
      val basil = json.decodeFromString<InventoryItemDto>(
        client.post("/api/v1/owner/inventory") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateIngredientRequest("Sweet Basil P9", IngredientUnit.GRAMS, lowStockThreshold = 50L, initialStockQty = 200L),
            ),
          )
        }.bodyAsText(),
      )

      // Idempotent operation_id restock: replay with same key applies delta once!
      val opId = "restock-${UUID.randomUUID()}"
      val adjustReq = AdjustInventoryRequest(
        ingredientId = mozzarella.ingredientId,
        operationId = opId,
        deltaQty = 500L,
        reason = InventoryLedgerReason.RESTOCK,
      )
      val firstAdjust = json.decodeFromString<InventoryItemDto>(
        client.post("/api/v1/owner/inventory/adjust") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(adjustReq))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText(),
      )
      assertEquals(1_500L, firstAdjust.onHandQty)

      val replayAdjust = json.decodeFromString<InventoryItemDto>(
        client.post("/api/v1/owner/inventory/adjust") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(adjustReq))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText(),
      )
      assertEquals(1_500L, replayAdjust.onHandQty)

      // Same operation_id with different payload -> 409 OPERATION_ID_CONFLICT
      val conflictResp = client.post("/api/v1/owner/inventory/adjust") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            AdjustInventoryRequest(
              ingredientId = mozzarella.ingredientId,
              operationId = opId,
              deltaQty = 900L,
              reason = InventoryLedgerReason.RESTOCK,
            ),
          ),
        )
      }
      assertEquals(HttpStatusCode.Conflict, conflictResp.status)
      assertEquals("OPERATION_ID_CONFLICT", json.decodeFromString<ApiErrorEnvelope>(conflictResp.bodyAsText()).error.code)

      val ledger = json.decodeFromString<List<InventoryLedgerEntryDto>>(
        client.get("/api/v1/owner/inventory/ledger?ingredient_id=${mozzarella.ingredientId}") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }.bodyAsText(),
      )
      assertEquals(2, ledger.size) // 1 initial + 1 restock (not duplicated!)

      // Seed category, pizza, crust, topping & bind relational recipes
      val cat = json.decodeFromString<CategoryDto>(
        client.post("/api/v1/owner/categories") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCategoryRequest("Recipe Pies P9", 1)))
        }.bodyAsText(),
      )
      val prod = json.decodeFromString<ProductDto>(
        client.post("/api/v1/owner/products") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              CreateProductRequest(
                categoryId = cat.id,
                name = "Marinara P9",
                description = "Artisanal",
                isVeg = true,
                variants = listOf(CreateVariantRequest(SizeCode.MEDIUM, 39_900L)),
              ),
            ),
          )
        }.bodyAsText(),
      )
      val variant = prod.variants.first()
      val crust = json.decodeFromString<CrustOptionDto>(
        client.post("/api/v1/owner/crusts") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateCrustRequest("Thin Woodfire P9", 0L)))
        }.bodyAsText(),
      )
      val topping = json.decodeFromString<ToppingDto>(
        client.post("/api/v1/owner/toppings") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(CreateToppingRequest("Basil Leaf P9", true, 2_000L)))
        }.bodyAsText(),
      )

      val variantBinding = json.decodeFromString<RecipeBindingDto>(
        client.put("/api/v1/owner/recipes") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
          contentType(ContentType.Application.Json)
          setBody(
            json.encodeToString(
              PutRecipeRequest(
                targetType = RecipeTargetType.VARIANT,
                targetId = variant.id,
                ingredients = listOf(
                  RecipeIngredientLineRequest(mozzarella.ingredientId, 120),
                  RecipeIngredientLineRequest(dough.ingredientId, 200),
                ),
              ),
            ),
          )
        }.also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText(),
      )
      assertEquals(2, variantBinding.ingredients.size)

      client.put("/api/v1/owner/recipes") {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            PutRecipeRequest(
              targetType = RecipeTargetType.CRUST,
              targetId = crust.id,
              ingredients = listOf(RecipeIngredientLineRequest(dough.ingredientId, 80)),
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
              targetType = RecipeTargetType.TOPPING,
              targetId = topping.id,
              ingredients = listOf(RecipeIngredientLineRequest(basil.ingredientId, 15)),
            ),
          ),
        )
      }

      val overview = json.decodeFromString<RecipesOverviewResponse>(
        client.get("/api/v1/owner/recipes") {
          header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }.bodyAsText(),
      )
      assertTrue(overview.variants.any { it.targetId == variant.id && it.ingredients.size == 2 })

      // Verify multi-modifier aggregation: 2 pizzas => Mozz 240, Dough (200+80)*2 = 560, Basil 30
      val recipeService = RecipeService(pg.postgresDatabase)
      val demand = DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
        recipeService.computeRequiredIngredientsForItems(
          conn = conn,
          items = listOf(
            RecipeDemandItem(
              variantId = UUID.fromString(variant.id),
              crustId = UUID.fromString(crust.id),
              toppingIds = listOf(UUID.fromString(topping.id)),
              quantity = 2,
            ),
          ),
        )
      }
      assertEquals(240L, demand[UUID.fromString(mozzarella.ingredientId)])
      assertEquals(560L, demand[UUID.fromString(dough.ingredientId)])
      assertEquals(30L, demand[UUID.fromString(basil.ingredientId)])

      // Verify FK ON DELETE RESTRICT blocks deleting referenced ingredient
      val deleteRestricted = runCatching {
        pg.postgresDatabase.connection.use { conn ->
          conn.prepareStatement("DELETE FROM ingredients WHERE id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(mozzarella.ingredientId))
            ps.executeUpdate()
          }
        }
      }.exceptionOrNull()
      assertTrue(deleteRestricted is SQLException)

      // Verify FK ON DELETE CASCADE removes variant_recipes cleanly when variant is deleted
      pg.postgresDatabase.connection.use { conn ->
        conn.prepareStatement("DELETE FROM product_variants WHERE id = ?").use { ps ->
          ps.setObject(1, UUID.fromString(variant.id))
          ps.executeUpdate()
        }
        conn.prepareStatement("SELECT COUNT(*) FROM variant_recipes WHERE variant_id = ?").use { ps ->
          ps.setObject(1, UUID.fromString(variant.id))
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(0, rs.getInt(1))
          }
        }
      }
    }

  @Test
  fun `20-thread concurrent oversell race prevents reserved_qty greater than on_hand_qty`() = runBlocking {
    val inventoryService = InventoryService(pg.postgresDatabase)
    val ingredientId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    val threadCount = 20
    val orderIds = List(threadCount) { UUID.randomUUID() }

    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement(
        "INSERT INTO ingredients (id, name, unit, low_stock_threshold, is_active) VALUES (?, ?, 'GRAMS', 50, TRUE)",
      ).use { ps ->
        ps.setObject(1, ingredientId)
        ps.setString(2, "Limited Truffle-$ingredientId")
        ps.executeUpdate()
      }
      conn.prepareStatement(
        "INSERT INTO inventory (ingredient_id, on_hand_qty, reserved_qty) VALUES (?, 500, 0)",
      ).use { ps ->
        ps.setObject(1, ingredientId)
        ps.executeUpdate()
      }
      conn.prepareStatement(
        "INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active) VALUES (?, ?, NOW(), 'h', 'Stock Racer', '9877770001', TRUE)",
      ).use { ps ->
        ps.setObject(1, userId)
        ps.setString(2, "stock-racer-$userId@pizzora.test")
        ps.executeUpdate()
      }
      orderIds.forEachIndexed { idx, oid ->
        conn.prepareStatement(
          """
          INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash,
            fulfillment_type, schedule_type, status,
            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot
          ) VALUES (?, ?, ?, ?, 'h', 'PICKUP', 'ASAP', 'PENDING_PAYMENT', 30000, 0, 0, 1500, 31500, 500)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, oid)
          ps.setString(2, "PZ-INV-$idx-$oid")
          ps.setObject(3, userId)
          ps.setString(4, "idem-$oid")
          ps.executeUpdate()
        }
      }
    }

    val startGate = CountDownLatch(1)
    val doneGate = CountDownLatch(threadCount)
    val wins = AtomicInteger(0)
    val insufficientErrors = AtomicInteger(0)
    val unexpected = Collections.synchronizedList(mutableListOf<Throwable>())
    val executor = Executors.newFixedThreadPool(threadCount)

    try {
      repeat(threadCount) { idx ->
        executor.submit {
          try {
            startGate.await()
            runBlocking {
              DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
                inventoryService.reserveForOrder(
                  conn = conn,
                  orderId = orderIds[idx],
                  requiredByIngredient = mapOf(ingredientId to 100L),
                  expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES),
                  immediateCommit = false,
                )
              }
            }
            wins.incrementAndGet()
          } catch (ex: AppException) {
            if (ex.code == "INSUFFICIENT_INVENTORY") {
              insufficientErrors.incrementAndGet()
            } else {
              unexpected += ex
            }
          } catch (t: Throwable) {
            unexpected += t
          } finally {
            doneGate.countDown()
          }
        }
      }
      startGate.countDown()
      assertTrue("Timed out waiting for oversell race", doneGate.await(20, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }

    assertTrue("Unexpected exceptions: $unexpected", unexpected.isEmpty())
    assertEquals(5, wins.get())
    assertEquals(threadCount - 5, insufficientErrors.get())

    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement(
        "SELECT on_hand_qty, reserved_qty, available_qty FROM inventory WHERE ingredient_id = ?",
      ).use { ps ->
        ps.setObject(1, ingredientId)
        ps.executeQuery().use { rs ->
          rs.next()
          assertEquals(500L, rs.getLong("on_hand_qty"))
          assertEquals(500L, rs.getLong("reserved_qty"))
          assertEquals(0L, rs.getLong("available_qty"))
        }
      }
    }
  }

  @Test
  fun `deadlock stress test with reversed multi-ingredient lock sets completes cleanly without 40P01`() = runBlocking {
    val inventoryService = InventoryService(pg.postgresDatabase)
    val ingIds = List(3) { UUID.randomUUID() }.sortedBy { it.toString() }
    val userId = UUID.randomUUID()
    val threads = 16
    val rounds = 3
    val totalOrders = threads * rounds
    val orderIds = List(totalOrders) { UUID.randomUUID() }

    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      ingIds.forEachIndexed { i, id ->
        conn.prepareStatement(
          "INSERT INTO ingredients (id, name, unit, low_stock_threshold, is_active) VALUES (?, ?, 'GRAMS', 100, TRUE)",
        ).use { ps ->
          ps.setObject(1, id)
          ps.setString(2, "DeadlockStress-$i-$id")
          ps.executeUpdate()
        }
        conn.prepareStatement(
          "INSERT INTO inventory (ingredient_id, on_hand_qty, reserved_qty) VALUES (?, 50000, 0)",
        ).use { ps ->
          ps.setObject(1, id)
          ps.executeUpdate()
        }
      }
      conn.prepareStatement(
        "INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active) VALUES (?, ?, NOW(), 'h', 'Stress User', '9877770002', TRUE)",
      ).use { ps ->
        ps.setObject(1, userId)
        ps.setString(2, "stress-$userId@pizzora.test")
        ps.executeUpdate()
      }
      orderIds.forEachIndexed { idx, oid ->
        conn.prepareStatement(
          """
          INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash,
            fulfillment_type, schedule_type, status,
            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot
          ) VALUES (?, ?, ?, ?, 'h', 'PICKUP', 'ASAP', 'PENDING_PAYMENT', 30000, 0, 0, 1500, 31500, 500)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, oid)
          ps.setString(2, "PZ-DL-$idx-$oid")
          ps.setObject(3, userId)
          ps.setString(4, "idem-$oid")
          ps.executeUpdate()
        }
      }
    }

    val startGate = CountDownLatch(1)
    val doneGate = CountDownLatch(threads)
    val failures = Collections.synchronizedList(mutableListOf<Throwable>())
    val executor = Executors.newFixedThreadPool(threads)

    try {
      repeat(threads) { tIdx ->
        executor.submit {
          try {
            startGate.await()
            repeat(rounds) { rIdx ->
              val oid = orderIds[tIdx * rounds + rIdx]
              // Alternate map insertion order intentionally to verify sorted locking prevents deadlocks
              val reqMap = linkedMapOf<UUID, Long>()
              if (tIdx % 2 == 0) {
                reqMap[ingIds[0]] = 10L
                reqMap[ingIds[1]] = 20L
                reqMap[ingIds[2]] = 30L
              } else {
                reqMap[ingIds[2]] = 30L
                reqMap[ingIds[1]] = 20L
                reqMap[ingIds[0]] = 10L
              }
              runBlocking {
                DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
                  inventoryService.reserveForOrder(
                    conn = conn,
                    orderId = oid,
                    requiredByIngredient = reqMap,
                    expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES),
                    immediateCommit = false,
                  )
                }
              }
            }
          } catch (t: Throwable) {
            failures += t
          } finally {
            doneGate.countDown()
          }
        }
      }
      startGate.countDown()
      assertTrue("Timed out in deadlock stress test", doneGate.await(25, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }

    assertTrue("Deadlock/failure occurred: $failures", failures.isEmpty())

    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      val expected = listOf(10L * totalOrders, 20L * totalOrders, 30L * totalOrders)
      ingIds.forEachIndexed { idx, id ->
        conn.prepareStatement("SELECT reserved_qty FROM inventory WHERE ingredient_id = ?").use { ps ->
          ps.setObject(1, id)
          ps.executeQuery().use { rs ->
            rs.next()
            assertEquals(expected[idx], rs.getLong("reserved_qty"))
          }
        }
      }
    }
  }

  @Test
  fun `inventory lifecycle reserve, commit, cancel-restore, and expire-release are atomic and idempotent`() = runBlocking {
    val inventoryService = InventoryService(pg.postgresDatabase)
    val ingId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    val orderPlacedAndCancelled = UUID.randomUUID()
    val orderExpired = UUID.randomUUID()

    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement(
        "INSERT INTO ingredients (id, name, unit, low_stock_threshold, is_active) VALUES (?, ?, 'UNITS', 5, TRUE)",
      ).use { ps ->
        ps.setObject(1, ingId)
        ps.setString(2, "Burrata Ball-$ingId")
        ps.executeUpdate()
      }
      conn.prepareStatement(
        "INSERT INTO inventory (ingredient_id, on_hand_qty, reserved_qty) VALUES (?, 10, 0)",
      ).use { ps ->
        ps.setObject(1, ingId)
        ps.executeUpdate()
      }
      conn.prepareStatement(
        "INSERT INTO users (id, email, email_verified_at, password_hash, full_name, phone, is_active) VALUES (?, ?, NOW(), 'h', 'Lifecycle User', '9877770003', TRUE)",
      ).use { ps ->
        ps.setObject(1, userId)
        ps.setString(2, "life-$userId@pizzora.test")
        ps.executeUpdate()
      }
      listOf(orderPlacedAndCancelled, orderExpired).forEach { oid ->
        conn.prepareStatement(
          """
          INSERT INTO orders (
            id, order_number, customer_id, idempotency_key, request_hash,
            fulfillment_type, schedule_type, status,
            subtotal_paise, discount_paise, delivery_fee_paise, tax_paise, total_paise, gst_bps_snapshot
          ) VALUES (?, ?, ?, ?, 'h', 'PICKUP', 'ASAP', 'PENDING_PAYMENT', 30000, 0, 0, 1500, 31500, 500)
          """.trimIndent(),
        ).use { ps ->
          ps.setObject(1, oid)
          ps.setString(2, "PZ-LC-$oid")
          ps.setObject(3, userId)
          ps.setString(4, "idem-$oid")
          ps.executeUpdate()
        }
      }
    }

    // Reserve 3 for orderPlacedAndCancelled, 2 for orderExpired -> available = 5 (10 on hand - 5 reserved)
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      inventoryService.reserveForOrder(conn, orderPlacedAndCancelled, mapOf(ingId to 3L), Instant.now().plusSeconds(900), immediateCommit = false)
      inventoryService.reserveForOrder(conn, orderExpired, mapOf(ingId to 2L), Instant.now().plusSeconds(900), immediateCommit = false)
    }

    // Commit orderPlacedAndCancelled twice (idempotent!) -> on_hand = 7, reserved = 2
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      inventoryService.commitReservedInventory(conn, orderPlacedAndCancelled, userId)
      inventoryService.commitReservedInventory(conn, orderPlacedAndCancelled, userId)
    }
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement("SELECT on_hand_qty, reserved_qty FROM inventory WHERE ingredient_id = ?").use { ps ->
        ps.setObject(1, ingId)
        ps.executeQuery().use { rs ->
          rs.next()
          assertEquals(7L, rs.getLong("on_hand_qty"))
          assertEquals(2L, rs.getLong("reserved_qty"))
        }
      }
    }

    // Expire-release orderExpired twice -> on_hand = 7, reserved = 0
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      inventoryService.releaseReservedInventory(conn, orderExpired, expired = true)
      inventoryService.releaseReservedInventory(conn, orderExpired, expired = true)
    }

    // Cancel-restore orderPlacedAndCancelled twice -> on_hand = 10, reserved = 0, available = 10
    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      inventoryService.restoreCommittedInventory(conn, orderPlacedAndCancelled, userId)
      inventoryService.restoreCommittedInventory(conn, orderPlacedAndCancelled, userId)
    }

    DatabaseFactory.withJdbcTransaction(pg.postgresDatabase) { conn ->
      conn.prepareStatement("SELECT on_hand_qty, reserved_qty, available_qty FROM inventory WHERE ingredient_id = ?").use { ps ->
        ps.setObject(1, ingId)
        ps.executeQuery().use { rs ->
          rs.next()
          assertEquals(10L, rs.getLong("on_hand_qty"))
          assertEquals(0L, rs.getLong("reserved_qty"))
          assertEquals(10L, rs.getLong("available_qty"))
        }
      }
    }

    val ledger = inventoryService.listLedger(ingId, limit = 50)
    // reserve x2 + commit + expire_release + cancel_restore = 5 rows (no duplicates from idempotent replays!)
    assertEquals(5, ledger.size)
    assertFalse(ledger.groupBy { it.operationId }.any { it.value.size > 1 })
  }
}
